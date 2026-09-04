import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  createLedgerSchema,
  settingsSchema
} from "../src/functions/family.js";

describe("family settings request", () => {
  it("accepts the Android conversion payload with an explicit null name", () => {
    assert.deepEqual(
      settingsSchema.parse({ name: null, mode: "FAMILY" }),
      { name: undefined, mode: "FAMILY" }
    );
  });

  describe("create family ledger request", () => {
    it("accepts a strict named ledger with an optional mode", () => {
      assert.deepEqual(
        createLedgerSchema.parse({ name: "Travel", mode: "FAMILY" }),
        { name: "Travel", mode: "FAMILY" }
      );
    });

    it("defaults mode in the service and rejects unknown fields", () => {
      assert.deepEqual(createLedgerSchema.parse({ name: "Personal" }), {
        name: "Personal"
      });
      assert.throws(() =>
        createLedgerSchema.parse({ name: "Personal", isDefault: true })
      );
    });
  });

  it("rejects a request without a setting to update", () => {
    assert.throws(() => settingsSchema.parse({ name: null }));
  });
});
