import assert from "node:assert/strict";
import { createHash, webcrypto } from "node:crypto";
import type { PoolClient, QueryResult, QueryResultRow } from "pg";
import type { AuthenticatedUser } from "../src/shared/auth.js";
import {
  LEGACY_PRIVACY_ITERATIONS,
  PRIVACY_ITERATIONS,
  type PrivacyCredential
} from "../src/shared/privacyCrypto.js";
import type { PrivacyRuntime } from "../src/shared/privacyService.js";

export const alice: AuthenticatedUser = {
  issuer: "https://issuer.example.test",
  subject: "alice-subject",
  email: "shared@example.test"
};
export const bob: AuthenticatedUser = { ...alice, subject: "bob-subject" };
export const aliceId = "11111111-1111-4111-8111-111111111111";
export const bobId = "22222222-2222-4222-8222-222222222222";

export interface TestPrivacyRow {
  user_id: string;
  salt: string | null;
  passcode_hash: string | null;
  iterations: number | null;
  require_on_login: boolean;
  require_for_income: boolean;
  version: number;
  failed_attempts: number;
  locked_until: Date | null;
}

function result<T extends QueryResultRow>(rows: T[]): QueryResult<T> {
  return { command: "SELECT", rowCount: rows.length, oid: 0, fields: [], rows };
}

// Model committed state and transaction-scoped locks, not just queued SQL results.
export class PrivacyTestDatabase {
  readonly rows = new Map<string, TestPrivacyRow>();
  readonly principals = new Map<string, string>();
  readonly calls: Array<{ transaction: number; text: string; values: unknown[] }> = [];
  readonly outcomes: Array<"commit" | "rollback"> = [];
  private readonly locks = new Map<string, Promise<void>>();
  private nextTransaction = 0;

  constructor() {
    this.bind(alice, aliceId);
    this.bind(bob, bobId);
  }

  bind(identity: AuthenticatedUser, userId: string): void {
    this.principals.set(JSON.stringify([identity.issuer, identity.subject]), userId);
  }

  async transaction<T>(operation: (client: PoolClient) => Promise<T>): Promise<T> {
    const id = ++this.nextTransaction;
    const writes = new Map<string, TestPrivacyRow>();
    const held = new Set<string>();
    const releases: Array<() => void> = [];
    const acquire = async (key: string) => {
      if (held.has(key)) return;
      const previous = this.locks.get(key) ?? Promise.resolve();
      let release!: () => void;
      const current = new Promise<void>((resolve) => { release = resolve; });
      this.locks.set(key, current);
      await previous;
      held.add(key);
      releases.push(() => {
        release();
        if (this.locks.get(key) === current) this.locks.delete(key);
      });
    };
    const read = (userId: string) => writes.get(userId) ?? this.rows.get(userId);
    const client = {
      query: async (text: string, values: unknown[] = []) => {
        const sql = text.replace(/\s+/g, " ").trim();
        this.calls.push({ transaction: id, text: sql, values: structuredClone(values) });
        if (sql.startsWith("select pg_advisory_xact_lock")) {
          const key = sql.includes("'user_privacy'")
            ? `privacy:${values[0]}`
            : `principal:${JSON.stringify(values)}`;
          await acquire(key);
          return result([{ pg_advisory_xact_lock: null }]);
        }
        if (sql.includes("from auth_principal")) {
          assert.match(sql, /where issuer = \$1 and subject = \$2$/);
          const userId = this.principals.get(JSON.stringify(values));
          assert.ok(userId, "Test identities must be bound to an immutable principal");
          return result([{ id: userId }]);
        }
        const userId = String(values[0]);
        if (sql.includes("from user_privacy")) {
          assert.match(sql, /where user_id = \$1(?: for update)?$/);
          if (sql.endsWith("for update") && read(userId)) {
            await acquire(`row:${userId}`);
          }
          const row = read(userId);
          return result(row ? [structuredClone(row)] : []);
        }

        assert.ok(held.has(`privacy:${userId}`), "Writes require the per-account advisory lock");
        assert.ok(this.calls.some((call) =>
          call.transaction === id && call.text.includes("from user_privacy") &&
          call.text.endsWith("for update") && call.values[0] === userId
        ), "Writes must follow SELECT FOR UPDATE");
        let row: TestPrivacyRow;
        if (sql.startsWith("insert into user_privacy")) {
          assert.equal(read(userId), undefined, "Initialization must never overwrite a row");
          row = {
            user_id: userId,
            salt: values[1] as string | null,
            passcode_hash: values[2] as string | null,
            iterations: values[3] as number | null,
            require_on_login: values[4] as boolean,
            require_for_income: values[5] as boolean,
            version: values[6] as number,
            failed_attempts: 0,
            locked_until: null
          };
        } else {
          assert.match(sql, /^update user_privacy /);
          assert.match(sql, /where user_id = \$1(?: returning|$)/);
          const existing = read(userId);
          assert.ok(existing, "Updates require an existing row");
          row = structuredClone(existing);
          if (sql.includes("set failed_attempts = $2")) {
            row.failed_attempts = values[1] as number;
            row.locked_until = values[2] as Date | null;
          } else {
            assert.match(sql, /set salt = \$2, passcode_hash = \$3, iterations = \$4,/);
            row.salt = values[1] as string | null;
            row.passcode_hash = values[2] as string | null;
            row.iterations = values[3] as number | null;
            row.failed_attempts = 0;
            row.locked_until = null;
            if (sql.includes("require_on_login = $5")) {
              row.require_on_login = values[4] as boolean;
              row.require_for_income = values[5] as boolean;
              row.version = values[6] as number;
            } else {
              assert.doesNotMatch(sql, /\bversion\s*=/);
            }
          }
        }
        assert.ok(Number.isInteger(row.version) && row.version >= 1 && row.version <= 2_147_483_647);
        assert.ok(row.failed_attempts >= 0 && row.failed_attempts <= 5);
        assert.equal(row.locked_until !== null, row.failed_attempts === 5);
        if (row.passcode_hash === null) {
          assert.equal(row.salt, null);
          assert.equal(row.iterations, null);
          assert.equal(row.require_on_login, false);
          assert.equal(row.require_for_income, false);
          assert.equal(row.failed_attempts, 0);
        } else {
          assert.match(row.passcode_hash, /^[0-9a-f]{64}$/i);
          assert.match(row.salt!, /^[0-9a-f]{32}$/i);
          assert.ok([LEGACY_PRIVACY_ITERATIONS, PRIVACY_ITERATIONS].includes(row.iterations!));
        }
        writes.set(userId, row);
        return result(sql.includes("returning") ? [structuredClone(row)] : []);
      }
    } as unknown as PoolClient;
    try {
      const value = await operation(client);
      for (const [userId, row] of writes) this.rows.set(userId, structuredClone(row));
      this.outcomes.push("commit");
      return value;
    } catch (error) {
      this.outcomes.push("rollback");
      throw error;
    } finally {
      for (const release of releases.reverse()) release();
    }
  }
}

export function fastPrivacyRuntime() {
  const clock = { now: Date.parse("2026-09-05T05:00:00Z") };
  const calls = { create: 0, verify: 0 };
  const hash = (passcode: string, salt: string) =>
    createHash("sha256").update(`${salt}:${passcode}`).digest("hex");
  const runtime: PrivacyRuntime = {
    now: () => clock.now,
    createCredential: async (passcode) => {
      const salt = (++calls.create).toString(16).padStart(32, "0");
      return { salt, passcodeHash: hash(passcode, salt), iterations: PRIVACY_ITERATIONS };
    },
    verifyPasscode: async (passcode, credential) => {
      calls.verify++;
      return hash(passcode, credential.salt) === credential.passcodeHash;
    }
  };
  return { runtime, clock, calls };
}

export async function browserCredential(
  passcode: string,
  salt = "A1b2".repeat(8),
  iterations = LEGACY_PRIVACY_ITERATIONS
): Promise<PrivacyCredential> {
  const key = await webcrypto.subtle.importKey(
    "raw", new TextEncoder().encode(passcode), "PBKDF2", false, ["deriveBits"]
  );
  const bits = await webcrypto.subtle.deriveBits({
    name: "PBKDF2",
    hash: "SHA-256",
    iterations,
    salt: new TextEncoder().encode(salt)
  }, key, 256);
  return { salt, passcodeHash: Buffer.from(bits).toString("hex"), iterations };
}
