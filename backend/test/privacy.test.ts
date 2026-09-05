import assert from "node:assert/strict";
import azureFunctions, {
  type HttpResponseInit,
  type InvocationContext
} from "@azure/functions";
import { describe, it } from "node:test";
import type { Config } from "../src/shared/config.js";
import {
  type PrivacyEndpointDependencies,
  privacyMigrate,
  privacyMigrationSchema,
  privacyResultResponse,
  privacySettings,
  privacySettingsSchema,
  privacyVerify,
  privacyVerifySchema
} from "../src/functions/privacy.js";
import {
  alice,
  aliceId,
  bob,
  browserCredential,
  PrivacyTestDatabase
} from "./privacyTestSupport.js";

const { HttpRequest } = azureFunctions;

const off = { requireOnLogin: false, requireForIncome: false };
const settingsInput = { ...off, version: 0 };
const migrationInput = { ...off, salt: "A1b2".repeat(8), passcodeHash: "a".repeat(64) };
const config: Config = {
  authIssuer: alice.issuer,
  authAudience: "test-audience",
  authScope: "sync.readwrite",
  authJwksUri: "https://issuer.example.test/keys",
  pgHost: "unused.example.test",
  pgDatabase: "unused",
  pgUser: "unused",
  pgPort: 5432
};

function request(method: string, body?: unknown, authorization = "Bearer alice") {
  return new HttpRequest({
    method,
    url: "https://privacy.example.test/api/privacy/settings",
    headers: { authorization, "content-type": "application/json" },
    body: body === undefined ? undefined : { string: JSON.stringify(body) }
  });
}

function noStore(response: HttpResponseInit) {
  assert.equal(new Headers(response.headers).get("cache-control"), "no-store");
}

function publicSettings(response: HttpResponseInit) {
  assert.equal(response.status, 200);
  noStore(response);
  assert.deepEqual(Object.keys(response.jsonBody).sort(), [
    "hasPasscode", "initialized", "requireForIncome", "requireOnLogin", "version"
  ]);
}

function fixture() {
  const db = new PrivacyTestDatabase();
  const logs: unknown[][] = [];
  const authCalls: Array<{ authorization: string | null; config: Config }> = [];
  const context = {
    error: (...args: unknown[]) => { logs.push(args); },
    warn: (...args: unknown[]) => { logs.push(args); }
  } as unknown as InvocationContext;
  const dependencies: PrivacyEndpointDependencies = {
    getConfig: () => config,
    authenticate: async (authorization, receivedConfig) => {
      authCalls.push({ authorization, config: receivedConfig });
      assert.equal(receivedConfig, config);
      if (authorization === "Bearer alice") return alice;
      if (authorization === "Bearer bob") return bob;
      throw new Error("sensitive-authentication-error");
    },
    transaction: (_config, operation) => db.transaction(operation)
  };
  return { db, logs, context, dependencies, authCalls };
}

describe("strict privacy request schemas", () => {
  it("requires booleans and nonnegative safe integer versions, without caller-selected ownership", () => {
    assert.deepEqual(privacySettingsSchema.parse(settingsInput), settingsInput);
    assert.equal(privacySettingsSchema.parse({
      ...settingsInput, version: Number.MAX_SAFE_INTEGER
    }).version, Number.MAX_SAFE_INTEGER);
    for (const version of [-1, 1.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1, "1", null]) {
      assert.equal(privacySettingsSchema.safeParse({ ...settingsInput, version }).success, false);
      assert.equal(privacyVerifySchema.safeParse({ version, passcode: "2468" }).success, false);
    }
    for (const invalid of [
      { version: 0, requireOnLogin: false },
      { ...settingsInput, requireOnLogin: 1 },
      { ...settingsInput, requireForIncome: "false" },
      { ...settingsInput, currentPasscode: null },
      { ...settingsInput, clearPasscode: "true" },
      { ...settingsInput, userId: aliceId },
      { ...settingsInput, ledgerId: aliceId },
      { ...settingsInput, initialized: true },
      { ...settingsInput, iterations: 1 }
    ]) {
      assert.equal(privacySettingsSchema.safeParse(invalid).success, false);
    }
  });

  it("preserves passcodes exactly and uses JavaScript string lengths for new credentials", () => {
    for (const passcode of ["    ", " 2468 ", "🔒🔒", "x".repeat(64), "🔒".repeat(32)]) {
      const parsed = privacySettingsSchema.parse({
        ...settingsInput, currentPasscode: "", newPasscode: passcode
      });
      assert.equal(parsed.newPasscode, passcode);
      assert.equal(parsed.currentPasscode, "");
    }
    for (const passcode of ["abc", "", "x".repeat(65), "🔒".repeat(33), 2468, null]) {
      assert.equal(privacySettingsSchema.safeParse({
        ...settingsInput, newPasscode: passcode
      }).success, false);
    }
  });

  it("permits clearing only without a new passcode and with both flags false", () => {
    assert.equal(privacySettingsSchema.safeParse({
      ...settingsInput, clearPasscode: true, currentPasscode: "2468"
    }).success, true);
    for (const input of [
      { newPasscode: "1357" },
      { requireOnLogin: true },
      { requireForIncome: true }
    ]) {
      assert.equal(privacySettingsSchema.safeParse({
        ...settingsInput, clearPasscode: true, ...input
      }).success, false);
    }
    assert.equal(privacySettingsSchema.safeParse({
      ...settingsInput, clearPasscode: false, newPasscode: "1357"
    }).success, true);
  });

  it("bounds verification input and rejects verifier injection or extra fields", () => {
    assert.deepEqual(privacyVerifySchema.parse({ version: 0, passcode: " " }), {
      version: 0, passcode: " "
    });
    assert.equal(privacyVerifySchema.safeParse({
      version: Number.MAX_SAFE_INTEGER, passcode: "x".repeat(64)
    }).success, true);
    for (const input of [
      { version: 1, passcode: "" },
      { version: 1, passcode: "x".repeat(65) },
      { version: 1, passcode: 2468 },
      { passcode: "2468" },
      { version: 1, passcode: "2468", userId: aliceId },
      { version: 1, passcode: "2468", passcodeHash: "a".repeat(64) }
    ]) {
      assert.equal(privacyVerifySchema.safeParse(input).success, false);
    }
  });

  it("accepts only the exact legacy verifier shape without changing UTF-8 salt casing", () => {
    assert.deepEqual(privacyMigrationSchema.parse(migrationInput), migrationInput);
    assert.equal(privacyMigrationSchema.parse({
      ...migrationInput, passcodeHash: "B".repeat(64)
    }).passcodeHash, "B".repeat(64));
    for (const invalid of [
      { ...migrationInput, salt: "a".repeat(31) },
      { ...migrationInput, salt: "z".repeat(32) },
      { ...migrationInput, passcodeHash: "b".repeat(63) },
      { ...migrationInput, passcodeHash: "z".repeat(64) },
      { ...migrationInput, requireOnLogin: "true" },
      { ...migrationInput, iterations: 120_000 },
      { ...migrationInput, version: 0 },
      { ...migrationInput, userId: aliceId },
      { ...migrationInput, passcode: "2468" }
    ]) {
      assert.equal(privacyMigrationSchema.safeParse(invalid).success, false);
    }
  });
});

describe("authenticated privacy HTTP handlers", () => {
  it("authenticates all operations before reading malformed input or accessing the database", async () => {
    const f = fixture();
    for (const [handler, method] of [
      [privacySettings, "GET"],
      [privacySettings, "PATCH"],
      [privacyVerify, "POST"],
      [privacyMigrate, "POST"]
    ] as const) {
      const malformed = new HttpRequest({
        method,
        url: "https://privacy.example.test/api/privacy/settings",
        body: method === "GET" ? undefined : { string: "{" }
      });
      const response = await handler(malformed, f.context, f.dependencies);
      assert.equal(response.status, 401);
      noStore(response);
      assert.equal(response.jsonBody.error, "unauthorized");
      assert.ok(response.jsonBody.message);
    }
    assert.equal(f.authCalls.length, 4);
    assert.equal(f.db.calls.length, 0);
    assert.equal(JSON.stringify(f.logs).includes("sensitive-authentication-error"), false);
  });

  it("rejects malformed and unknown fields without logging request values or starting transactions", async () => {
    const f = fixture();
    const responses = [
      await privacySettings(request("PATCH", {
        ...settingsInput, ledgerId: aliceId, newPasscode: "private-passcode"
      }), f.context, f.dependencies),
      await privacyVerify(request("POST", {
        version: 1, passcode: "private-passcode", userId: aliceId
      }), f.context, f.dependencies),
      await privacyMigrate(request("POST", {
        ...migrationInput, iterations: 120_000
      }), f.context, f.dependencies),
      await privacySettings(new HttpRequest({
        method: "PATCH",
        url: "https://privacy.example.test/api/privacy/settings",
        headers: { authorization: "Bearer alice" },
        body: { string: "{\"newPasscode\":\"private-passcode\"," }
      }), f.context, f.dependencies)
    ];
    for (const response of responses) {
      assert.equal(response.status, 400);
      assert.equal(response.jsonBody.error, "invalid_request");
      assert.ok(response.jsonBody.message);
      assert.equal(JSON.stringify(response.jsonBody).includes("private-passcode"), false);
      noStore(response);
    }
    assert.equal(f.db.calls.length, 0);
    assert.deepEqual(f.logs, []);
  });

  it("returns uncached defaults without initializing privacy storage", async () => {
    const f = fixture();
    const response = await privacySettings(request("GET"), f.context, f.dependencies);
    publicSettings(response);
    assert.deepEqual(response.jsonBody, {
      ...off, initialized: false, hasPasscode: false, version: 0
    });
    assert.equal(f.db.rows.size, 0);
    assert.equal(f.authCalls[0].authorization, "Bearer alice");
    assert.deepEqual(f.db.outcomes, ["commit"]);
  });

  it("requires a passcode for flags, isolates callers, and never exposes or resurrects a cleared verifier", async () => {
    const f = fixture();
    const invalid = await privacySettings(request("PATCH", {
      ...settingsInput, requireOnLogin: true
    }), f.context, f.dependencies);
    assert.equal(invalid.status, 400);
    assert.equal(f.db.rows.size, 0);
    const created = await privacySettings(request("PATCH", {
      ...settingsInput, newPasscode: "2468", requireOnLogin: true
    }), f.context, f.dependencies);
    publicSettings(created);
    assert.deepEqual(created.jsonBody, {
      ...off, initialized: true, hasPasscode: true, requireOnLogin: true, version: 1
    });
    const stored = structuredClone(f.db.rows.get(aliceId)!);
    assert.equal(JSON.stringify(created.jsonBody).includes(stored.salt!), false);
    assert.equal(JSON.stringify(created.jsonBody).includes(stored.passcode_hash!), false);
    const otherAccount = await privacySettings(request("GET", undefined, "Bearer bob"), f.context, f.dependencies);
    assert.equal(otherAccount.jsonBody.initialized, false);
    const fetched = await privacySettings(request("GET"), f.context, f.dependencies);
    publicSettings(fetched);
    assert.deepEqual(fetched.jsonBody, created.jsonBody);

    const denied = await privacySettings(request("PATCH", {
      ...off, version: 1, currentPasscode: "wrong", clearPasscode: true
    }), f.context, f.dependencies);
    assert.equal(denied.status, 403);
    assert.equal(denied.jsonBody.error, "invalid_passcode");
    assert.equal(f.db.rows.get(aliceId)!.failed_attempts, 1);
    assert.equal(f.db.outcomes.at(-1), "commit");
    noStore(denied);
    const cleared = await privacySettings(request("PATCH", {
      ...off, version: 1, currentPasscode: "2468", clearPasscode: true
    }), f.context, f.dependencies);
    publicSettings(cleared);
    assert.deepEqual(cleared.jsonBody, {
      ...off, initialized: true, hasPasscode: false, version: 2
    });
    const ignored = await privacyMigrate(request("POST", {
      ...off, salt: stored.salt, passcodeHash: stored.passcode_hash, requireOnLogin: true
    }), f.context, f.dependencies);
    publicSettings(ignored);
    assert.deepEqual(ignored.jsonBody, cleared.jsonBody);
    const missing = await privacyVerify(request("POST", {
      version: 2, passcode: "2468"
    }), f.context, f.dependencies);
    assert.equal(missing.status, 409);
    assert.equal(missing.jsonBody.error, "settings_changed");
    assert.equal(f.db.rows.get(aliceId)!.passcode_hash, null);
    assert.deepEqual(f.logs, []);
  });

  it("migrates an exact legacy verifier and verifies only the current version with a minimal response", async () => {
    const f = fixture();
    const browser = await browserCredential("2468");
    const migrated = await privacyMigrate(request("POST", {
      ...off, salt: browser.salt, passcodeHash: browser.passcodeHash
    }), f.context, f.dependencies);
    publicSettings(migrated);
    assert.equal(migrated.jsonBody.version, 1);
    const before = structuredClone(f.db.rows.get(aliceId)!);
    const stale = await privacyVerify(request("POST", {
      version: 0, passcode: "wrong"
    }), f.context, f.dependencies);
    assert.equal(stale.status, 409);
    assert.ok(stale.jsonBody.message);
    noStore(stale);
    assert.deepEqual(f.db.rows.get(aliceId), before);
    const verified = await privacyVerify(request("POST", {
      version: 1, passcode: "2468"
    }), f.context, f.dependencies);
    assert.equal(verified.status, 200);
    noStore(verified);
    assert.deepEqual(verified.jsonBody, { verified: true, version: 1 });
    assert.equal(f.db.rows.get(aliceId)!.version, 1);
  });

  it("commits the shared lockout before returning 403 or 429 with Retry-After", async () => {
    const f = fixture();
    await privacyMigrate(request("POST", migrationInput), f.context, f.dependencies);
    for (let attempt = 1; attempt <= 5; attempt++) {
      const response = attempt % 2
        ? await privacyVerify(request("POST", { version: 1, passcode: "wrong" }), f.context, f.dependencies)
        : await privacySettings(request("PATCH", {
          ...off, version: 1, currentPasscode: "wrong", newPasscode: "1357"
        }), f.context, f.dependencies);
      assert.equal(response.status, attempt === 5 ? 429 : 403);
      assert.equal(response.jsonBody.error, attempt === 5 ? "locked" : "invalid_passcode");
      assert.ok(response.jsonBody.message);
      assert.deepEqual(Object.keys(response.jsonBody).sort(), ["error", "message"]);
      noStore(response);
      assert.equal(f.db.rows.get(aliceId)!.failed_attempts, attempt);
      assert.equal(f.db.outcomes.at(-1), "commit");
      assert.equal(f.db.rows.get(aliceId)!.version, 1);
      if (attempt === 5) {
        const retry = Number(new Headers(response.headers).get("retry-after"));
        assert.ok(Number.isInteger(retry) && retry > 0 && retry <= 300);
      }
    }
    const before = structuredClone(f.db.rows.get(aliceId)!);
    const locked = await privacyVerify(request("POST", {
      version: 1, passcode: "2468"
    }), f.context, f.dependencies);
    assert.equal(locked.status, 429);
    assert.deepEqual(f.db.rows.get(aliceId), before);
    assert.deepEqual(f.logs, []);
  });

  it("waits for transaction completion and sanitizes unexpected failure logs and responses", async () => {
    const f = fixture();
    const sensitive = "private-passcode-and-verifier-must-not-be-logged";
    f.dependencies.transaction = (_config, operation) => f.db.transaction(async (client) => {
      await operation(client);
      throw new Error(sensitive);
    });
    const response = await privacySettings(request("PATCH", settingsInput), f.context, f.dependencies);
    assert.equal(response.status, 500);
    noStore(response);
    assert.equal(response.jsonBody.error, "internal_error");
    assert.ok(response.jsonBody.message);
    assert.equal(JSON.stringify(response).includes(sensitive), false);
    assert.deepEqual(f.logs, [["Privacy request failed"]]);
    assert.equal(f.db.rows.size, 0);
    assert.deepEqual(f.db.outcomes, ["rollback"]);
  });

  it("maps discriminated results only to safe HTTP fields and stable status codes", () => {
    for (const [error, status] of [
      ["invalid_request", 400], ["settings_changed", 409], ["invalid_passcode", 403]
    ] as const) {
      const response = privacyResultResponse({ ok: false, error, message: "Friendly message." });
      assert.equal(response.status, status);
      assert.deepEqual(response.jsonBody, { error, message: "Friendly message." });
      noStore(response);
    }
    const locked = privacyResultResponse({
      ok: false, error: "locked", message: "Please wait.", retryAfterSeconds: 73
    });
    assert.equal(locked.status, 429);
    assert.equal(new Headers(locked.headers).get("retry-after"), "73");
    assert.deepEqual(locked.jsonBody, { error: "locked", message: "Please wait." });
  });
});
