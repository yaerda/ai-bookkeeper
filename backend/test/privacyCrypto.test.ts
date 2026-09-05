import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  createPrivacyCredential,
  LEGACY_PRIVACY_ITERATIONS,
  PRIVACY_ITERATIONS,
  verifyPrivacyPasscode
} from "../src/shared/privacyCrypto.js";
import { browserCredential } from "./privacyTestSupport.js";

describe("privacy passcode cryptography", () => {
  it("matches legacy WebCrypto with the original UTF-8 salt text and exact passcode", async () => {
    const passcode = "  paß🔒  ";
    const legacy = await browserCredential(passcode);
    assert.equal(legacy.iterations, LEGACY_PRIVACY_ITERATIONS);
    assert.equal(await verifyPrivacyPasscode(passcode, legacy), true);
    assert.equal(await verifyPrivacyPasscode(passcode.trim(), legacy), false);
    assert.equal(await verifyPrivacyPasscode(passcode, {
      ...legacy, salt: legacy.salt.toLowerCase()
    }), false);
    assert.equal(await verifyPrivacyPasscode(passcode, {
      ...legacy, passcodeHash: legacy.passcodeHash.toUpperCase()
    }), true);
  });

  it("creates fresh 600,000-iteration credentials without trimming or returning plaintext", async () => {
    const passcode = " 2468 ";
    const first = await createPrivacyCredential(passcode);
    const second = await createPrivacyCredential(passcode);
    assert.deepEqual(Object.keys(first).sort(), ["iterations", "passcodeHash", "salt"]);
    assert.match(first.salt, /^[0-9a-f]{32}$/);
    assert.match(first.passcodeHash, /^[0-9a-f]{64}$/);
    assert.equal(first.iterations, PRIVACY_ITERATIONS);
    assert.notEqual(first.salt, second.salt);
    assert.notEqual(first.passcodeHash, second.passcodeHash);
    assert.equal(await verifyPrivacyPasscode(passcode, first), true);
    assert.equal(await verifyPrivacyPasscode(passcode.trim(), first), false);
    const browser = await browserCredential(passcode, first.salt, PRIVACY_ITERATIONS);
    assert.equal(browser.passcodeHash, first.passcodeHash);
    assert.equal(JSON.stringify(first).includes(passcode), false);
  });

  it("rejects malformed stored verifiers and unsupported work factors safely", async () => {
    const credential = {
      salt: "a".repeat(32),
      passcodeHash: "b".repeat(64),
      iterations: PRIVACY_ITERATIONS
    };
    for (const invalid of [
      { ...credential, salt: "00" },
      { ...credential, salt: "g".repeat(32) },
      { ...credential, passcodeHash: "00" },
      { ...credential, passcodeHash: "z".repeat(64) },
      { ...credential, iterations: 1 },
      { ...credential, iterations: Number.MAX_SAFE_INTEGER }
    ]) {
      assert.equal(await verifyPrivacyPasscode("2468", invalid), false);
    }
  });
});
