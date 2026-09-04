import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  decideSync,
  pushRequestSchema,
  type ClientTransaction,
  type StoredTransaction
} from "../src/shared/models.js";

const incoming: ClientTransaction = {
  syncId: "0ec11d58-589d-40c5-bc30-e4524b539a2c",
  serverVersion: 0,
  amount: 12.5,
  type: "EXPENSE",
  categoryId: 1,
  categoryName: "餐饮",
  categoryIcon: null,
  categoryColor: null,
  merchantName: "商店",
  note: null,
  originalInput: null,
  date: 1_700_000_000_000,
  createdAt: 1_700_000_000_000,
  updatedAt: 1_700_000_000_000,
  source: "MANUAL",
  status: "CONFIRMED",
  aiConfidence: null,
  deletedAt: null
};

const stored: StoredTransaction = {
  syncId: incoming.syncId,
  serverVersion: 7,
  payload: {
    amount: incoming.amount,
    type: incoming.type,
    categoryId: incoming.categoryId,
    categoryName: incoming.categoryName,
    categoryIcon: incoming.categoryIcon,
    categoryColor: incoming.categoryColor,
    merchantName: incoming.merchantName,
    note: incoming.note,
    originalInput: incoming.originalInput,
    date: incoming.date,
    createdAt: incoming.createdAt,
    updatedAt: incoming.updatedAt,
    source: incoming.source,
    status: incoming.status,
    aiConfidence: incoming.aiConfidence
  },
  deletedAt: null
};

describe("pushRequestSchema", () => {
  it("accepts positive amounts and explicit transaction direction", () => {
    assert.equal(
      pushRequestSchema.parse({ transactions: [incoming] })
        .transactions[0].type,
      "EXPENSE"
    );
  });

  it("rejects negative amounts", () => {
    assert.throws(() => pushRequestSchema.parse({
      transactions: [{ ...incoming, amount: -1 }]
    }));
  });
});

describe("decideSync", () => {
  it("accepts a retried identical create idempotently", () => {
    assert.equal(decideSync(stored, incoming), "idempotent");
  });

  it("rejects a stale conflicting update", () => {
    assert.equal(
      decideSync(stored, { ...incoming, amount: 99 }),
      "conflict"
    );
  });

  it("allows an update based on the current server version", () => {
    assert.equal(
      decideSync(stored, { ...incoming, serverVersion: 7, amount: 99 }),
      "update"
    );
  });
});
