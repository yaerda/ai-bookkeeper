import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { normalizeEmail, validateClaims } from "../src/shared/auth.js";

const config = {
  authIssuer: "https://issuer.example/tenant/v2.0",
  authAudience: "api-client-id",
  authScope: "sync.readwrite"
};

describe("validateClaims", () => {
  it("uses issuer and subject as the immutable identity", () => {
    const result = validateClaims({
      iss: config.authIssuer,
      aud: config.authAudience,
      sub: "subject-1",
      scp: "openid sync.readwrite",
      emails: ["  Person@Example.COM "]
    }, config);

    assert.deepEqual(result, {
      issuer: config.authIssuer,
      subject: "subject-1",
      email: "person@example.com"
    });
  });

  it("rejects tokens without the sync scope", () => {
    assert.throws(() => validateClaims({
      iss: config.authIssuer,
      aud: config.authAudience,
      sub: "subject-1",
      scp: "openid",
      email: "person@example.com"
    }, config), /scope/i);
  });

  it("rejects a token issued for another API", () => {
    assert.throws(() => validateClaims({
      iss: config.authIssuer,
      aud: "another-api",
      sub: "subject-1",
      scp: "sync.readwrite",
      email: "person@example.com"
    }, config), /audience/i);
  });
});

describe("normalizeEmail", () => {
  it("trims and lowercases email addresses", () => {
    assert.equal(normalizeEmail(" User@Example.COM "), "user@example.com");
  });
});
