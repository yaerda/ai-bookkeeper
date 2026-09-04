import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { PoolClient, QueryResult } from "pg";
import {
  LedgerAccessDeniedError,
  resolveLedgerAccess
} from "../src/shared/ledgerAccess.js";

function result<T>(rows: T[]): QueryResult<T> {
  return {
    command: "SELECT",
    rowCount: rows.length,
    oid: 0,
    fields: [],
    rows
  };
}

const identity = {
  issuer: "issuer",
  subject: "subject",
  email: "member@example.com"
};

function clientWithRole(role?: "OWNER" | "VIEWER" | "EDITOR"): PoolClient {
  return {
    query: async (text: string) => {
      if (/pg_advisory_xact_lock/i.test(text)) {
        return result([{ pg_advisory_xact_lock: null }]);
      }
      if (/from auth_principal/i.test(text)) {
        return result([{ id: "member-id" }]);
      }
      if (/insert into family_ledger/i.test(text)) {
        return result([]);
      }
      if (/where owner_id = \$1 and is_default/i.test(text)) {
        return result([{
          ledger_id: "member-id",
          owner_id: "member-id"
        }]);
      }
      return result(role ? [{
        ledger_id: role === "OWNER" ? "member-id" : "owner-ledger-id",
        owner_id: role === "OWNER" ? "member-id" : "owner-id",
        role
      }] : []);
    }
  } as unknown as PoolClient;
}

describe("resolveLedgerAccess", () => {
  it("allows an owner to read and write the personal ledger", async () => {
    const access = await resolveLedgerAccess(
      clientWithRole(),
      identity,
      undefined,
      true
    );
    assert.equal(access.role, "OWNER");
    assert.equal(access.ledgerId, "member-id");
    assert.equal(access.ownerId, "member-id");
  });

  it("allows an owner to select an additional owned ledger", async () => {
    const access = await resolveLedgerAccess(
      clientWithRole("OWNER"),
      identity,
      "member-id",
      true
    );
    assert.equal(access.role, "OWNER");
    assert.equal(access.ledgerId, "member-id");
  });

  it("allows a viewer to read a shared ledger", async () => {
    const access = await resolveLedgerAccess(
      clientWithRole("VIEWER"),
      identity,
      "owner-id",
      false
    );
    assert.equal(access.role, "VIEWER");
  });

  it("rejects a viewer attempting to write", async () => {
    await assert.rejects(
      resolveLedgerAccess(
        clientWithRole("VIEWER"),
        identity,
        "owner-id",
        true
      ),
      LedgerAccessDeniedError
    );
  });

  it("allows an editor to write a shared ledger", async () => {
    const access = await resolveLedgerAccess(
      clientWithRole("EDITOR"),
      identity,
      "owner-id",
      true
    );
    assert.equal(access.role, "EDITOR");
  });
});
