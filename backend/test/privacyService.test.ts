import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  LEGACY_PRIVACY_ITERATIONS,
  PRIVACY_ITERATIONS,
  verifyPrivacyPasscode
} from "../src/shared/privacyCrypto.js";
import {
  getPrivacySettings,
  migratePrivacy,
  type PrivacyMigrationInput,
  type PrivacyResult,
  type PrivacySettingsInput,
  type PrivacyVerifyInput,
  updatePrivacySettings,
  verifyPrivacy
} from "../src/shared/privacyService.js";
import {
  alice,
  aliceId,
  bob,
  bobId,
  browserCredential,
  fastPrivacyRuntime,
  PrivacyTestDatabase
} from "./privacyTestSupport.js";

const off = { requireOnLogin: false, requireForIncome: false };
const legacyInput: PrivacyMigrationInput = {
  passcodeHash: "a".repeat(64),
  salt: "B1c2".repeat(8),
  requireOnLogin: true,
  requireForIncome: true
};
const publicKeys = ["hasPasscode", "initialized", "requireForIncome", "requireOnLogin", "version"];

function value<T>(result: PrivacyResult<T>): T {
  assert.ok(result.ok, JSON.stringify(result));
  return result.value;
}

function failure<T>(result: PrivacyResult<T>, error: string) {
  assert.equal(result.ok, false);
  if (result.ok) assert.fail("Expected a privacy denial");
  assert.equal(result.error, error);
  assert.ok(result.message.length > 0);
  return result;
}

function fixture() {
  const db = new PrivacyTestDatabase();
  const fast = fastPrivacyRuntime();
  return {
    db,
    ...fast,
    get: (identity = alice) => db.transaction((client) => getPrivacySettings(client, identity)),
    update: (input: PrivacySettingsInput, identity = alice) =>
      db.transaction((client) => updatePrivacySettings(client, identity, input, fast.runtime)),
    verify: (input: PrivacyVerifyInput, identity = alice) =>
      db.transaction((client) => verifyPrivacy(client, identity, input, fast.runtime)),
    migrate: (input = legacyInput, identity = alice) =>
      db.transaction((client) => migratePrivacy(client, identity, input))
  };
}

async function configured() {
  const f = fixture();
  value(await f.update({ ...off, version: 0, newPasscode: "2468" }));
  return f;
}

describe("account-scoped privacy settings", () => {
  it("reads defaults without initializing a row, leaving later legacy migration possible", async () => {
    const f = fixture();
    assert.deepEqual(await f.get(), { ...off, initialized: false, hasPasscode: false, version: 0 });
    assert.equal(f.db.rows.size, 0);
    assert.equal(f.db.calls.some((call) => /insert into user_privacy/.test(call.text)), false);
    const imported = await f.migrate();
    assert.deepEqual(imported, {
      initialized: true, hasPasscode: true,
      requireOnLogin: true, requireForIncome: true, version: 1
    });
  });

  it("sets up a credential and exposes only the public settings projection", async () => {
    const f = fixture();
    const passcode = " 2468 ";
    const settings = value(await f.update({
      version: 0, newPasscode: passcode, requireOnLogin: true, requireForIncome: true
    }));
    assert.deepEqual(settings, {
      initialized: true, hasPasscode: true,
      requireOnLogin: true, requireForIncome: true, version: 1
    });
    assert.deepEqual(Object.keys(settings).sort(), publicKeys);
    const row = f.db.rows.get(aliceId)!;
    assert.equal(row.iterations, PRIVACY_ITERATIONS);
    assert.equal(JSON.stringify(settings).includes(row.passcode_hash!), false);
    assert.equal(JSON.stringify(settings).includes(row.salt!), false);
    assert.equal(JSON.stringify(f.db.calls).includes(passcode), false);
    assert.deepEqual(await f.get(), settings);
    failure(await f.verify({ version: 1, passcode: passcode.trim() }), "invalid_passcode");
    assert.deepEqual(value(await f.verify({ version: 1, passcode })), { verified: true, version: 1 });
  });

  it("requires a new passcode before either privacy flag can be enabled", async () => {
    const f = fixture();
    for (const flags of [
      { requireOnLogin: true, requireForIncome: false },
      { requireOnLogin: false, requireForIncome: true }
    ]) {
      failure(await f.update({ ...flags, version: 0 }), "invalid_request");
      assert.equal(f.db.rows.size, 0);
    }
    failure(await f.update({
      ...off, version: 0, clearPasscode: true, newPasscode: "2468"
    }), "invalid_request");
    failure(await f.update({
      version: 0, clearPasscode: true, requireOnLogin: true, requireForIncome: false
    }), "invalid_request");
    assert.equal(f.db.rows.size, 0);
  });

  it("persists an explicit empty update and never resurrects a browser credential", async () => {
    const f = fixture();
    const empty = value(await f.update({ ...off, version: 0 }));
    assert.deepEqual(empty, { ...off, initialized: true, hasPasscode: false, version: 1 });
    assert.deepEqual(await f.migrate(), empty);
    const next = value(await f.update({ ...off, version: 1, clearPasscode: true }));
    assert.equal(next.version, 2);
    assert.equal(next.initialized, true);
    assert.deepEqual(await f.migrate(), next);
  });

  for (const change of [
    { name: "unchanged flags", input: off },
    { name: "login protection", input: { ...off, requireOnLogin: true } },
    { name: "income protection", input: { ...off, requireForIncome: true } },
    { name: "a replacement passcode", input: { ...off, newPasscode: "1357" } },
    { name: "clearing the passcode", input: { ...off, clearPasscode: true } }
  ]) {
    it(`requires the current passcode for ${change.name}`, async () => {
      const f = await configured();
      const before = structuredClone(f.db.rows.get(aliceId)!);
      failure(await f.update({
        ...change.input, version: 1, currentPasscode: "wrong"
      }), "invalid_passcode");
      const after = f.db.rows.get(aliceId)!;
      assert.deepEqual(after, { ...before, failed_attempts: 1 });
      assert.equal(f.db.outcomes.at(-1), "commit");
    });
  }

  it("counts missing and empty current passcodes as failed modification attempts", async () => {
    const f = await configured();
    failure(await f.update({ ...off, version: 1 }), "invalid_passcode");
    assert.equal(f.calls.verify, 0);
    failure(await f.update({ ...off, version: 1, currentPasscode: "" }), "invalid_passcode");
    assert.equal(f.db.rows.get(aliceId)!.failed_attempts, 2);
    assert.equal(f.db.rows.get(aliceId)!.version, 1);
  });

  it("increments the version for each proven update, including unchanged flags", async () => {
    const f = await configured();
    const row = structuredClone(f.db.rows.get(aliceId)!);
    failure(await f.verify({ version: 1, passcode: "wrong" }), "invalid_passcode");
    const settings = value(await f.update({
      ...off, version: 1, currentPasscode: "2468", requireOnLogin: true
    }));
    assert.equal(settings.version, 2);
    assert.equal(settings.requireOnLogin, true);
    assert.equal(f.db.rows.get(aliceId)!.failed_attempts, 0);
    assert.equal(f.db.rows.get(aliceId)!.passcode_hash, row.passcode_hash);
    const unchanged = value(await f.update({
      version: 2, currentPasscode: "2468", requireOnLogin: true, requireForIncome: false
    }));
    assert.equal(unchanged.version, 3);
    failure(await f.verify({ version: 2, passcode: "2468" }), "settings_changed");
    assert.deepEqual(value(await f.verify({ version: 3, passcode: "2468" })), {
      verified: true, version: 3
    });
  });

  it("replaces the verifier only with current-passcode proof", async () => {
    const f = await configured();
    const previous = f.db.rows.get(aliceId)!.passcode_hash;
    const settings = value(await f.update({
      ...off, version: 1, currentPasscode: "2468", newPasscode: "1357", requireForIncome: true
    }));
    assert.equal(settings.version, 2);
    assert.equal(settings.requireForIncome, true);
    assert.notEqual(f.db.rows.get(aliceId)!.passcode_hash, previous);
    failure(await f.verify({ version: 2, passcode: "2468" }), "invalid_passcode");
    assert.deepEqual(value(await f.verify({ version: 2, passcode: "1357" })), {
      verified: true, version: 2
    });
    assert.equal(f.db.rows.get(aliceId)!.failed_attempts, 0);
  });

  it("clears credentials but retains an authoritative initialized row", async () => {
    const f = await configured();
    const cleared = value(await f.update({
      ...off, version: 1, currentPasscode: "2468", clearPasscode: true
    }));
    assert.deepEqual(cleared, { ...off, initialized: true, hasPasscode: false, version: 2 });
    const row = f.db.rows.get(aliceId)!;
    assert.equal(row.salt, null);
    assert.equal(row.passcode_hash, null);
    assert.equal(row.iterations, null);
    assert.equal(row.failed_attempts, 0);
    assert.equal(row.locked_until, null);
    failure(await f.verify({ version: 2, passcode: "2468" }), "settings_changed");
    failure(await f.update({ ...off, version: 1, newPasscode: "old-browser" }), "settings_changed");
    assert.deepEqual(await f.migrate(), cleared);
    assert.deepEqual(f.db.rows.get(aliceId), row);
    failure(await f.update({ ...off, version: 2, requireForIncome: true }), "invalid_request");
    assert.equal(value(await f.update({
      ...off, version: 2, newPasscode: "1357", requireForIncome: true
    })).version, 3);
  });

  it("isolates family members and issuers, while following a principal across email/browser changes", async () => {
    const f = await configured();
    assert.equal(alice.email, bob.email);
    assert.equal((await f.get(bob)).initialized, false);
    value(await f.update({ ...off, version: 0, newPasscode: "1357" }, bob));
    failure(await f.verify({ version: 1, passcode: "1357" }), "invalid_passcode");
    assert.deepEqual(value(await f.verify({ version: 1, passcode: "1357" }, bob)), {
      verified: true, version: 1
    });
    assert.equal(f.db.rows.get(aliceId)!.failed_attempts, 1);
    assert.equal(f.db.rows.get(bobId)!.failed_attempts, 0);
    const changedEmail = { ...alice, email: "changed@example.test" };
    assert.deepEqual(await f.get(changedEmail), await f.get(alice));
    assert.equal(value(await f.verify({ version: 1, passcode: "2468" }, changedEmail)).verified, true);
    const otherIssuer = { ...alice, issuer: "https://another-issuer.example.test" };
    f.db.bind(otherIssuer, "33333333-3333-4333-8333-333333333333");
    assert.equal((await f.get(otherIssuer)).initialized, false);
    assert.equal(f.db.rows.size, 2);
    assert.equal(f.db.calls.some((call) => /ledger/i.test(call.text)), false);
    assert.ok(f.db.calls.some((call) =>
      call.text.includes("'user_privacy'") && call.values[0] === aliceId
    ));
  });
});

describe("persistent shared privacy throttling", () => {
  it("commits failures from both endpoints, locks on the fifth failure, and skips hashing while locked", async () => {
    const f = await configured();
    for (let attempt = 1; attempt <= 5; attempt++) {
      const result = attempt % 2
        ? await f.verify({ version: 1, passcode: "wrong" })
        : await f.update({ ...off, version: 1, currentPasscode: "wrong", clearPasscode: true });
      failure(result, attempt === 5 ? "locked" : "invalid_passcode");
      assert.equal(f.db.rows.get(aliceId)!.failed_attempts, attempt);
      assert.equal(f.db.rows.get(aliceId)!.version, 1);
      assert.equal(f.db.outcomes.at(-1), "commit");
    }
    const snapshot = structuredClone(f.db.rows.get(aliceId)!);
    assert.equal(snapshot.locked_until!.getTime(), f.clock.now + 300_000);
    const verification = failure(await f.verify({ version: 1, passcode: "2468" }), "locked");
    assert.equal(verification.error === "locked" && verification.retryAfterSeconds, 300);
    failure(await f.update({
      ...off, version: 1, currentPasscode: "2468", newPasscode: "1357"
    }), "locked");
    assert.equal(f.calls.verify, 5);
    assert.equal(f.calls.create, 1);
    await f.get();
    await f.migrate();
    assert.deepEqual(f.db.rows.get(aliceId), snapshot);
  });

  it("resets expired locks, starts a fresh failure count, and rounds Retry-After up", async () => {
    const f = await configured();
    for (let i = 0; i < 5; i++) await f.verify({ version: 1, passcode: "wrong" });
    f.clock.now += 299_001;
    const stillLocked = failure(await f.verify({ version: 1, passcode: "2468" }), "locked");
    assert.equal(stillLocked.error === "locked" && stillLocked.retryAfterSeconds, 1);
    assert.equal(f.calls.verify, 5);
    f.clock.now += 999;
    failure(await f.verify({ version: 1, passcode: "wrong" }), "invalid_passcode");
    assert.equal(f.db.rows.get(aliceId)!.failed_attempts, 1);
    assert.equal(f.db.rows.get(aliceId)!.locked_until, null);
    assert.equal(value(await f.verify({ version: 1, passcode: "2468" })).verified, true);
    assert.equal(f.db.rows.get(aliceId)!.failed_attempts, 0);
    assert.equal(f.db.rows.get(aliceId)!.version, 1);
  });

  it("resets counters on a correct verification without changing flags or version", async () => {
    const f = await configured();
    const before = structuredClone(f.db.rows.get(aliceId)!);
    for (let i = 0; i < 4; i++) await f.verify({ version: 1, passcode: "wrong" });
    const result = value(await f.verify({ version: 1, passcode: "2468" }));
    assert.deepEqual(result, { verified: true, version: 1 });
    assert.deepEqual(f.db.rows.get(aliceId), before);
  });

  it("checks stale versions before hashing, lockout, or any mutation", async () => {
    const f = await configured();
    for (let i = 0; i < 5; i++) await f.verify({ version: 1, passcode: "wrong" });
    const snapshot = structuredClone(f.db.rows.get(aliceId)!);
    const hashCalls = { ...f.calls };
    for (const version of [0, 2, Number.MAX_SAFE_INTEGER]) {
      failure(await f.verify({ version, passcode: "wrong" }), "settings_changed");
      failure(await f.update({
        ...off, version, currentPasscode: "wrong", clearPasscode: true
      }), "settings_changed");
    }
    assert.deepEqual(f.calls, hashCalls);
    assert.deepEqual(f.db.rows.get(aliceId), snapshot);
  });

  it("refuses version overflow rather than wrapping or resetting the stored version", async () => {
    const f = await configured();
    f.db.rows.get(aliceId)!.version = 2_147_483_647;
    failure(await f.update({
      ...off, version: 2_147_483_647, currentPasscode: "2468"
    }), "settings_changed");
    assert.equal(f.db.rows.get(aliceId)!.version, 2_147_483_647);
    assert.equal(value(await f.verify({ version: 2_147_483_647, passcode: "2468" })).version, 2_147_483_647);
  });
});

describe("privacy migration and concurrent requests", () => {
  it("imports the browser algorithm without plaintext and upgrades after proof without a version bump", async () => {
    const db = new PrivacyTestDatabase();
    const passcode = "  legacy🔒  ";
    const browser = await browserCredential(passcode);
    const settings = await db.transaction((client) => migratePrivacy(client, alice, {
      ...off, ...browser, passcodeHash: browser.passcodeHash.toUpperCase(), requireForIncome: true
    }));
    assert.deepEqual(Object.keys(settings).sort(), publicKeys);
    assert.equal(settings.version, 1);
    assert.equal(db.rows.get(aliceId)!.salt, browser.salt);
    assert.equal(db.rows.get(aliceId)!.passcode_hash, browser.passcodeHash);
    assert.equal(db.rows.get(aliceId)!.iterations, LEGACY_PRIVACY_ITERATIONS);
    db.rows.get(aliceId)!.failed_attempts = 4;
    assert.deepEqual(value(await db.transaction((client) =>
      verifyPrivacy(client, alice, { version: 1, passcode })
    )), { verified: true, version: 1 });
    const upgraded = db.rows.get(aliceId)!;
    assert.equal(upgraded.iterations, PRIVACY_ITERATIONS);
    assert.notEqual(upgraded.salt, browser.salt);
    assert.notEqual(upgraded.passcode_hash, browser.passcodeHash);
    assert.equal(upgraded.version, 1);
    assert.equal(upgraded.failed_attempts, 0);
    assert.equal(upgraded.require_for_income, true);
    assert.equal(await verifyPrivacyPasscode(passcode, {
      salt: upgraded.salt!, passcodeHash: upgraded.passcode_hash!, iterations: upgraded.iterations!
    }), true);
    assert.equal(JSON.stringify(db.calls).includes(passcode), false);
    const before = structuredClone(upgraded);
    assert.deepEqual(await db.transaction((client) => migratePrivacy(client, alice, legacyInput)), settings);
    assert.deepEqual(db.rows.get(aliceId), before);
  });

  it("serializes competing first-time setup and clearing before checking versions", { timeout: 5_000 }, async () => {
    const f = fixture();
    let entered!: () => void;
    let release!: () => void;
    const started = new Promise<void>((resolve) => { entered = resolve; });
    const gate = new Promise<void>((resolve) => { release = resolve; });
    const create = f.runtime.createCredential;
    f.runtime.createCredential = async (passcode) => {
      entered();
      await gate;
      return create(passcode);
    };
    const setup = f.update({ ...off, version: 0, newPasscode: "2468" });
    await started;
    let clearFinished = false;
    const clear = f.update({ ...off, version: 0, clearPasscode: true })
      .then((result) => { clearFinished = true; return result; });
    try {
      await new Promise<void>((resolve) => setImmediate(resolve));
      assert.equal(clearFinished, false);
      assert.equal(f.db.rows.size, 0);
    } finally {
      release();
    }
    assert.equal(value(await setup).version, 1);
    failure(await clear, "settings_changed");
    assert.equal(f.db.rows.size, 1);
    assert.equal(f.db.rows.get(aliceId)!.version, 1);
    assert.notEqual(f.db.rows.get(aliceId)!.passcode_hash, null);
  });

  it("does not let a competing creation overwrite an explicitly cleared initial row", async () => {
    const f = fixture();
    const [cleared, created] = await Promise.all([
      f.update({ ...off, version: 0, clearPasscode: true }),
      f.update({ ...off, version: 0, newPasscode: "2468" })
    ]);
    assert.equal(value(cleared).hasPasscode, false);
    failure(created, "settings_changed");
    assert.deepEqual(await f.migrate(), value(cleared));
    assert.equal(f.db.rows.get(aliceId)!.version, 1);
  });

  it("allows only one competing change and cannot reintroduce a passcode after clearing", async () => {
    const f = await configured();
    const [cleared, replaced] = await Promise.all([
      f.update({ ...off, version: 1, currentPasscode: "2468", clearPasscode: true }),
      f.update({ ...off, version: 1, currentPasscode: "2468", newPasscode: "1357" })
    ]);
    assert.equal(value(cleared).version, 2);
    failure(replaced, "settings_changed");
    assert.equal(f.db.rows.get(aliceId)!.passcode_hash, null);
    assert.deepEqual(await f.migrate(), value(cleared));
  });

  it("makes competing migrations idempotent and serializes migration against setup", async () => {
    const f = fixture();
    const [first, second] = await Promise.all([
      f.migrate(),
      f.migrate({ ...legacyInput, ...off, salt: "c".repeat(32) })
    ]);
    assert.deepEqual(first, second);
    assert.equal(f.db.rows.get(aliceId)!.salt, legacyInput.salt);
    assert.equal(f.db.rows.get(aliceId)!.version, 1);
    const g = fixture();
    const [imported, setup] = await Promise.all([
      g.migrate(),
      g.update({ ...off, version: 0, newPasscode: "2468" })
    ]);
    failure(setup, "settings_changed");
    assert.deepEqual(await g.get(), imported);
    const h = fixture();
    const [configuredSettings, ignoredImport] = await Promise.all([
      h.update({ ...off, version: 0, newPasscode: "2468" }),
      h.migrate()
    ]);
    assert.deepEqual(value(configuredSettings), ignoredImport);
  });

  it("does not lose concurrent failure increments or let one account throttle another", async () => {
    const f = await configured();
    value(await f.update({ ...off, version: 0, newPasscode: "1357" }, bob));
    const results = await Promise.all(Array.from({ length: 5 }, (_, i) => i % 2
      ? f.update({ ...off, version: 1, currentPasscode: "wrong" })
      : f.verify({ version: 1, passcode: "wrong" })
    ));
    assert.deepEqual(results.map((result) => result.ok ? "ok" : result.error).sort(), [
      "invalid_passcode", "invalid_passcode", "invalid_passcode", "invalid_passcode", "locked"
    ]);
    assert.equal(f.db.rows.get(aliceId)!.failed_attempts, 5);
    assert.equal(f.db.rows.get(aliceId)!.version, 1);
    assert.equal(f.db.rows.get(bobId)!.failed_attempts, 0);
    assert.equal(value(await f.verify({ version: 1, passcode: "1357" }, bob)).verified, true);
    assert.equal(f.db.outcomes.includes("rollback"), false);
  });
});
