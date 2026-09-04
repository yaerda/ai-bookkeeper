import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { describe, it } from "node:test";
import type { PoolClient, QueryResult } from "pg";
import {
  createLedger,
  DefaultLedgerDeletionError,
  deleteOrLeaveLedger,
  listLedgers,
  updateLedgerSettings
} from "../src/shared/familyService.js";

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
  email: "owner@example.com"
};

function familyClient(
  onQuery: (text: string, values?: unknown[]) => QueryResult<unknown>
): PoolClient {
  return {
    query: async (text: string, values?: unknown[]) => {
      if (/pg_advisory_xact_lock/i.test(text)) {
        return result([{ pg_advisory_xact_lock: null }]);
      }
      if (/from auth_principal/i.test(text)) {
        return result([{ id: "owner-id" }]);
      }
      if (/insert into family_ledger \(id, owner_id, is_default\)/i.test(text)) {
        return result([]);
      }
      return onQuery(text, values);
    }
  } as unknown as PoolClient;
}

describe("multi-ledger family service", () => {
  it("creates a non-default ledger under the authenticated owner", async () => {
    const calls: Array<{ text: string; values?: unknown[] }> = [];
    const client = familyClient((text, values) => {
      calls.push({ text, values });
      return result([{
        id: "new-ledger-id",
        owner_id: "owner-id",
        name: "Travel",
        mode: "FAMILY",
        is_default: false
      }]);
    });

    const created = await createLedger(client, identity, {
      name: "Travel",
      mode: "FAMILY"
    });

    assert.deepEqual(created, {
      id: "new-ledger-id",
      name: "Travel",
      mode: "FAMILY",
      isDefault: false
    });
    assert.match(calls[0].text, /owner_id, name, mode, is_default/i);
    assert.deepEqual(calls[0].values, ["owner-id", "Travel", "FAMILY"]);
  });

  it("lists owned and accepted shared ledgers with contract fields", async () => {
    let operation = 0;
    const client = familyClient(() => {
      operation++;
      if (operation === 1) {
        return result([
          {
            id: "owner-id",
            name: "Default",
            owner_email: "owner@example.com",
            role: "OWNER",
            mode: "PERSONAL",
            is_default: true
          },
          {
            id: "shared-id",
            name: "Shared",
            owner_email: "other@example.com",
            role: "VIEWER",
            mode: "FAMILY",
            is_default: false
          }
        ]);
      }
      return result([]);
    });

    const response = await listLedgers(client, identity);

    assert.deepEqual(response.ledgers, [
      {
        id: "owner-id",
        name: "Default",
        ownerEmail: "owner@example.com",
        role: "OWNER",
        mode: "PERSONAL",
        isDefault: true
      },
      {
        id: "shared-id",
        name: "Shared",
        ownerEmail: "other@example.com",
        role: "VIEWER",
        mode: "FAMILY",
        isDefault: false
      }
    ]);
  });

  it("converts only the selected ledger to personal", async () => {
    const calls: Array<{ text: string; values?: unknown[] }> = [];
    const client = familyClient((text, values) => {
      calls.push({ text, values });
      if (/select id, owner_id, name, mode, is_default/i.test(text)) {
        return result([{
          id: "selected-ledger",
          owner_id: "owner-id",
          name: "Family",
          mode: "FAMILY",
          is_default: false
        }]);
      }
      if (/update family_ledger/i.test(text)) {
        return result([{
          id: "selected-ledger",
          owner_id: "owner-id",
          name: "Family",
          mode: "PERSONAL",
          is_default: false
        }]);
      }
      return result([]);
    });

    await updateLedgerSettings(
      client,
      identity,
      { mode: "PERSONAL" },
      "selected-ledger"
    );

    const scopedCalls = calls.filter(({ text }) =>
      /delete from ledger_(member|invitation)/i.test(text)
    );
    assert.equal(scopedCalls.length, 2);
    assert.ok(scopedCalls.every(({ text }) => /ledger_id = \$1/i.test(text)));
    assert.ok(scopedCalls.every(({ values }) =>
      values?.[0] === "selected-ledger"
    ));
  });

  it("soft deletes an owned non-default ledger and revokes sharing", async () => {
    const calls: Array<{ text: string; values?: unknown[] }> = [];
    const client = familyClient((text, values) => {
      calls.push({ text, values });
      if (/select fl\.owner_id, fl\.is_default/i.test(text)) {
        return result([{
          owner_id: "owner-id",
          is_default: false,
          member_id: null
        }]);
      }
      return result([]);
    });

    const response = await deleteOrLeaveLedger(
      client,
      identity,
      "owned-ledger"
    );

    assert.deepEqual(response, { action: "DELETED" });
    assert.ok(calls.some(({ text }) =>
      /set deleted_at = now\(\)/i.test(text)
    ));
    assert.equal(calls.filter(({ text }) =>
      /delete from ledger_(member|invitation)/i.test(text)
    ).length, 2);
  });

  it("leaves a shared ledger without deleting the owner's ledger", async () => {
    const calls: Array<{ text: string; values?: unknown[] }> = [];
    const client = familyClient((text, values) => {
      calls.push({ text, values });
      if (/select fl\.owner_id, fl\.is_default/i.test(text)) {
        return result([{
          owner_id: "another-owner",
          is_default: false,
          member_id: "owner-id"
        }]);
      }
      return result([]);
    });

    const response = await deleteOrLeaveLedger(
      client,
      identity,
      "shared-ledger"
    );

    assert.deepEqual(response, { action: "LEFT" });
    assert.ok(calls.some(({ text, values }) =>
      /delete from ledger_member where ledger_id = \$1 and member_id = \$2/i.test(text)
      && values?.[0] === "shared-ledger"
      && values?.[1] === "owner-id"
    ));
    assert.ok(calls.every(({ text }) =>
      !/update family_ledger/i.test(text)
    ));
  });

  it("protects the default ledger from deletion", async () => {
    const client = familyClient((text) => {
      if (/select fl\.owner_id, fl\.is_default/i.test(text)) {
        return result([{
          owner_id: "owner-id",
          is_default: true,
          member_id: null
        }]);
      }
      return result([]);
    });

    await assert.rejects(
      deleteOrLeaveLedger(client, identity, "owner-id"),
      DefaultLedgerDeletionError
    );
  });
});

describe("multi-ledger migration", () => {
  it("preserves default IDs and scopes all ledger data by ledger_id", async () => {
    const sql = await readFile(
      new URL("../migrations/005_multi_ledgers.sql", import.meta.url),
      "utf8"
    );

    assert.match(sql, /set id = owner_id\s+where id is null/i);
    assert.match(sql, /family_ledger_one_default_per_owner/i);
    assert.match(sql, /primary key \(ledger_id, sync_id\)/i);
    assert.match(sql, /ledger_member_ledger_member_key/i);
    assert.match(sql, /ledger_invitation_ledger_email_key/i);
    assert.match(sql, /create constraint trigger family_ledger_default_required/i);
    const softDeleteSql = await readFile(
      new URL("../migrations/006_soft_delete_ledgers.sql", import.meta.url),
      "utf8"
    );
    assert.match(softDeleteSql, /add column if not exists deleted_at/i);
    assert.match(softDeleteSql, /where deleted_at is null/i);
  });
});
