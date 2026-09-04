import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { PoolClient, QueryResult } from "pg";
import { resolveUser } from "../src/shared/users.js";

function result<T>(rows: T[]): QueryResult<T> {
  return {
    command: "SELECT",
    rowCount: rows.length,
    oid: 0,
    fields: [],
    rows
  };
}

describe("resolveUser", () => {
  it("creates a distinct owner for a new immutable principal", async () => {
    const calls: Array<{ text: string; values?: unknown[] }> = [];
    const responses = [
      result([{ pg_advisory_xact_lock: null }]),
      result([]),
      result([{ id: "user-a" }]),
      result([{ id: "user-a" }])
    ];
    const client = {
      query: async (text: string, values?: unknown[]) => {
        calls.push({ text, values });
        return responses.shift();
      }
    } as unknown as PoolClient;

    const ownerId = await resolveUser(client, {
      issuer: "issuer",
      subject: "subject-a",
      email: "same@example.com"
    });

    assert.equal(ownerId, "user-a");
    assert.deepEqual(calls[0].values, ["issuer", "subject-a"]);
    assert.match(calls[0].text, /jsonb_build_array/i);
    assert.doesNotMatch(calls[0].text, /chr\(0\)/i);
    assert.match(calls[2].text, /insert into app_user/i);
    assert.doesNotMatch(calls[2].text, /on conflict/i);
    assert.deepEqual(calls[3].values, ["issuer", "subject-a", "user-a"]);
  });

  it("returns the owner already bound to the issuer and subject", async () => {
    const calls: string[] = [];
    const client = {
      query: async (text: string) => {
        calls.push(text);
        return calls.length === 1
          ? result([{ pg_advisory_xact_lock: null }])
          : result([{ id: "existing-user" }]);
      }
    } as unknown as PoolClient;

    const ownerId = await resolveUser(client, {
      issuer: "issuer",
      subject: "subject-a",
      email: "changed@example.com"
    });

    assert.equal(ownerId, "existing-user");
    assert.equal(calls.length, 2);
  });
});
