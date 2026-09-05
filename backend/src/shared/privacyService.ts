import type { PoolClient } from "pg";
import type { AuthenticatedUser } from "./auth.js";
import {
  createPrivacyCredential,
  LEGACY_PRIVACY_ITERATIONS,
  PRIVACY_ITERATIONS,
  type PrivacyCredential,
  verifyPrivacyPasscode
} from "./privacyCrypto.js";
import { resolveUser } from "./users.js";

export interface PrivacySettings {
  initialized: boolean;
  hasPasscode: boolean;
  requireOnLogin: boolean;
  requireForIncome: boolean;
  version: number;
}

export interface PrivacySettingsInput {
  version: number;
  currentPasscode?: string;
  newPasscode?: string;
  clearPasscode?: boolean;
  requireOnLogin: boolean;
  requireForIncome: boolean;
}

export interface PrivacyVerifyInput {
  passcode: string;
  version: number;
}

export interface PrivacyMigrationInput {
  passcodeHash: string;
  salt: string;
  requireOnLogin: boolean;
  requireForIncome: boolean;
}

export type PrivacyFailure =
  | {
      ok: false;
      error: "invalid_request" | "settings_changed" | "invalid_passcode";
      message: string;
    }
  | {
      ok: false;
      error: "locked";
      message: string;
      retryAfterSeconds: number;
    };

export type PrivacyResult<T> = { ok: true; value: T } | PrivacyFailure;

export interface PrivacyRuntime {
  now: () => number;
  createCredential: typeof createPrivacyCredential;
  verifyPasscode: typeof verifyPrivacyPasscode;
}

interface PrivacyRow {
  salt: string | null;
  passcode_hash: string | null;
  iterations: number | null;
  require_on_login: boolean;
  require_for_income: boolean;
  version: number;
  failed_attempts: number;
  locked_until: Date | null;
}

const runtimeDefaults: PrivacyRuntime = {
  now: Date.now,
  createCredential: createPrivacyCredential,
  verifyPasscode: verifyPrivacyPasscode
};
const maxStoredVersion = 2_147_483_647;
const attemptLimit = 5;
const lockDurationMs = 5 * 60 * 1_000;
const privacyColumns = `salt, passcode_hash, iterations, require_on_login,
  require_for_income, version, failed_attempts, locked_until`;

function credentialFor(row: PrivacyRow | undefined): PrivacyCredential | undefined {
  if (!row || row.salt === null || row.passcode_hash === null || row.iterations === null) {
    return undefined;
  }
  return {
    salt: row.salt,
    passcodeHash: row.passcode_hash,
    iterations: row.iterations
  };
}

function publicSettings(row: PrivacyRow | undefined): PrivacySettings {
  return {
    initialized: row !== undefined,
    hasPasscode: credentialFor(row) !== undefined,
    requireOnLogin: row?.require_on_login ?? false,
    requireForIncome: row?.require_for_income ?? false,
    version: row?.version ?? 0
  };
}

function settingsChanged(): PrivacyFailure {
  return {
    ok: false,
    error: "settings_changed",
    message: "Your privacy settings have changed. Refresh them and try again."
  };
}

function locked(until: Date, now: number): PrivacyFailure {
  return {
    ok: false,
    error: "locked",
    message: "Too many incorrect passcodes. Please wait a few minutes and try again.",
    retryAfterSeconds: Math.max(1, Math.ceil((until.getTime() - now) / 1_000))
  };
}

async function loadPrivacy(
  client: PoolClient,
  identity: AuthenticatedUser,
  forUpdate = false
): Promise<{ userId: string; row: PrivacyRow | undefined }> {
  const userId = await resolveUser(client, identity);
  if (forUpdate) {
    // A row lock alone cannot serialize setup/migration while no row exists.
    await client.query(
      `select pg_advisory_xact_lock(
         hashtextextended(jsonb_build_array('user_privacy', $1::text)::text, 0)
       )`,
      [userId]
    );
  }
  const result = await client.query<PrivacyRow>(
    `select ${privacyColumns}
       from user_privacy
      where user_id = $1${forUpdate ? " for update" : ""}`,
    [userId]
  );
  return { userId, row: result.rows[0] };
}

async function saveSettings(
  client: PoolClient,
  userId: string,
  existing: PrivacyRow | undefined,
  credential: PrivacyCredential | undefined,
  flags: Pick<PrivacySettings, "requireOnLogin" | "requireForIncome">
): Promise<PrivacySettings> {
  const values = [
    userId,
    credential?.salt ?? null,
    credential?.passcodeHash ?? null,
    credential?.iterations ?? null,
    flags.requireOnLogin,
    flags.requireForIncome,
    (existing?.version ?? 0) + 1
  ];
  const result = await client.query<PrivacyRow>(
    existing
      ? `update user_privacy
            set salt = $2, passcode_hash = $3, iterations = $4,
                require_on_login = $5, require_for_income = $6, version = $7,
                failed_attempts = 0, locked_until = null, updated_at = now()
          where user_id = $1
          returning ${privacyColumns}`
      : `insert into user_privacy (
           user_id, salt, passcode_hash, iterations, require_on_login,
           require_for_income, version
         ) values ($1, $2, $3, $4, $5, $6, $7)
         returning ${privacyColumns}`,
    values
  );
  const saved = result.rows[0];
  if (!saved) throw new Error("Privacy settings were not stored");
  return publicSettings(saved);
}

async function checkPasscode(
  client: PoolClient,
  userId: string,
  row: PrivacyRow,
  credential: PrivacyCredential,
  passcode: string | undefined,
  runtime: PrivacyRuntime
): Promise<PrivacyResult<true>> {
  const now = runtime.now();
  if (row.locked_until && row.locked_until.getTime() > now) {
    return locked(row.locked_until, now);
  }
  if (passcode !== undefined && await runtime.verifyPasscode(passcode, credential)) {
    return { ok: true, value: true };
  }

  const failures = Math.min(attemptLimit, (row.locked_until ? 0 : row.failed_attempts) + 1);
  const lockedUntil = failures === attemptLimit
    ? new Date(runtime.now() + lockDurationMs)
    : null;
  await client.query(
    `update user_privacy
        set failed_attempts = $2, locked_until = $3, updated_at = now()
      where user_id = $1`,
    [userId, failures, lockedUntil]
  );
  // Return denials instead of throwing: transaction() must commit the throttle.
  return lockedUntil
    ? locked(lockedUntil, runtime.now())
    : {
        ok: false,
        error: "invalid_passcode",
        message: "The privacy passcode is incorrect."
      };
}

export async function getPrivacySettings(
  client: PoolClient,
  identity: AuthenticatedUser
): Promise<PrivacySettings> {
  const { row } = await loadPrivacy(client, identity);
  return publicSettings(row);
}

export async function updatePrivacySettings(
  client: PoolClient,
  identity: AuthenticatedUser,
  input: PrivacySettingsInput,
  runtime: PrivacyRuntime = runtimeDefaults
): Promise<PrivacyResult<PrivacySettings>> {
  const { userId, row } = await loadPrivacy(client, identity, true);
  if (input.version !== (row?.version ?? 0) || row?.version === maxStoredVersion) {
    return settingsChanged();
  }

  let credential = credentialFor(row);
  if (
    (input.clearPasscode &&
      (input.newPasscode !== undefined || input.requireOnLogin || input.requireForIncome)) ||
    (!credential && input.newPasscode === undefined &&
      (input.requireOnLogin || input.requireForIncome))
  ) {
    return {
      ok: false,
      error: "invalid_request",
      message: "Set a passcode before enabling privacy options, or disable both options to clear it."
    };
  }

  if (row && credential) {
    const checked = await checkPasscode(
      client, userId, row, credential, input.currentPasscode, runtime
    );
    if (!checked.ok) return checked;
  }

  if (input.clearPasscode) {
    credential = undefined;
  } else if (input.newPasscode !== undefined) {
    credential = await runtime.createCredential(input.newPasscode);
  } else if (credential && credential.iterations < PRIVACY_ITERATIONS) {
    credential = await runtime.createCredential(input.currentPasscode!);
  }

  return {
    ok: true,
    value: await saveSettings(client, userId, row, credential, input)
  };
}

export async function verifyPrivacy(
  client: PoolClient,
  identity: AuthenticatedUser,
  input: PrivacyVerifyInput,
  runtime: PrivacyRuntime = runtimeDefaults
): Promise<PrivacyResult<{ verified: true; version: number }>> {
  const { userId, row } = await loadPrivacy(client, identity, true);
  const credential = credentialFor(row);
  if (!row || !credential || row.version !== input.version) {
    return settingsChanged();
  }
  const checked = await checkPasscode(
    client, userId, row, credential, input.passcode, runtime
  );
  if (!checked.ok) return checked;

  const current = credential.iterations < PRIVACY_ITERATIONS
    ? await runtime.createCredential(input.passcode)
    : credential;
  await client.query(
    `update user_privacy
        set salt = $2, passcode_hash = $3, iterations = $4,
            failed_attempts = 0, locked_until = null, updated_at = now()
      where user_id = $1`,
    [userId, current.salt, current.passcodeHash, current.iterations]
  );
  return { ok: true, value: { verified: true, version: row.version } };
}

export async function migratePrivacy(
  client: PoolClient,
  identity: AuthenticatedUser,
  input: PrivacyMigrationInput
): Promise<PrivacySettings> {
  const { userId, row } = await loadPrivacy(client, identity, true);
  if (row) return publicSettings(row);
  return saveSettings(client, userId, undefined, {
    salt: input.salt,
    passcodeHash: input.passcodeHash.toLowerCase(),
    iterations: LEGACY_PRIVACY_ITERATIONS
  }, input);
}
