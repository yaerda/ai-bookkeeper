import { z } from "zod";

const nullableText = z.string().max(4000).nullable();
const epochMillis = z.number().int().nonnegative();

export const transactionSchema = z.object({
  syncId: z.string().uuid(),
  serverVersion: z.number().int().nonnegative(),
  amount: z.number().finite().nonnegative(),
  type: z.enum(["INCOME", "EXPENSE"]),
  categoryId: z.number().int().nullable(),
  categoryName: nullableText,
  categoryIcon: nullableText,
  categoryColor: nullableText,
  merchantName: nullableText,
  note: nullableText,
  originalInput: nullableText,
  date: epochMillis,
  createdAt: epochMillis,
  updatedAt: epochMillis,
  source: z.enum([
    "MANUAL",
    "TEXT_AI",
    "VOICE_AI",
    "PHOTO_AI",
    "AUTO_CAPTURE",
    "NOTIFICATION_QUICK"
  ]),
  status: z.enum(["CONFIRMED", "PENDING"]),
  aiConfidence: z.number().min(0).max(1).nullable(),
  deletedAt: epochMillis.nullable()
}).strict();

export const pushRequestSchema = z.object({
  transactions: z.array(transactionSchema).min(1).max(200)
}).strict();

export type ClientTransaction = z.infer<typeof transactionSchema>;

export interface ServerTransaction extends ClientTransaction {
  serverVersion: number;
}

export interface StoredTransaction {
  syncId: string;
  serverVersion: number;
  payload: Omit<ClientTransaction, "syncId" | "serverVersion" | "deletedAt">;
  deletedAt: number | null;
}

export type SyncDecision = "insert" | "update" | "idempotent" | "conflict";

function canonical(value: unknown): string {
  if (Array.isArray(value)) {
    return `[${value.map(canonical).join(",")}]`;
  }
  if (value !== null && typeof value === "object") {
    const entries = Object.entries(value as Record<string, unknown>)
      .sort(([left], [right]) => left.localeCompare(right));
    return `{${entries.map(([key, item]) => `${key}:${canonical(item)}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

export function clientPayload(
  transaction: ClientTransaction
): StoredTransaction["payload"] {
  const {
    syncId: _syncId,
    serverVersion: _serverVersion,
    deletedAt: _deletedAt,
    ...payload
  } = transaction;
  return payload;
}

export function decideSync(
  existing: StoredTransaction | undefined,
  incoming: ClientTransaction
): SyncDecision {
  if (!existing) {
    return "insert";
  }

  const samePayload =
    canonical(existing.payload) === canonical(clientPayload(incoming)) &&
    existing.deletedAt === incoming.deletedAt;
  if (samePayload) {
    return "idempotent";
  }
  return existing.serverVersion === incoming.serverVersion
    ? "update"
    : "conflict";
}

export function toServerTransaction(
  stored: StoredTransaction
): ServerTransaction {
  return {
    ...stored.payload,
    syncId: stored.syncId,
    serverVersion: stored.serverVersion,
    deletedAt: stored.deletedAt
  };
}
