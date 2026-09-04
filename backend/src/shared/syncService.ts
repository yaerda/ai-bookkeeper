import type { PoolClient } from "pg";
import type { Config } from "./config.js";
import { transaction } from "./db.js";
import {
  clientPayload,
  decideSync,
  type ClientTransaction,
  type ServerTransaction,
  type StoredTransaction,
  toServerTransaction
} from "./models.js";
import type { AuthenticatedUser } from "./auth.js";
import { resolveLedgerAccess } from "./ledgerAccess.js";

interface StoredRow {
  sync_id: string;
  server_version: string;
  payload: StoredTransaction["payload"];
  deleted_at_ms: string | null;
}

function rowToStored(row: StoredRow): StoredTransaction {
  return {
    syncId: row.sync_id,
    serverVersion: Number(row.server_version),
    payload: row.payload,
    deletedAt: row.deleted_at_ms === null ? null : Number(row.deleted_at_ms)
  };
}

async function findExisting(
  client: PoolClient,
  ledgerId: string,
  syncId: string
): Promise<StoredTransaction | undefined> {
  const result = await client.query<StoredRow>(
    `select sync_id, server_version, payload,
            case when deleted_at is null then null
                 else floor(extract(epoch from deleted_at) * 1000)::bigint
             end as deleted_at_ms
       from ledger_transaction
      where ledger_id = $1 and sync_id = $2
      for update`,
    [ledgerId, syncId]
  );
  return result.rowCount ? rowToStored(result.rows[0]) : undefined;
}

async function writeTransaction(
  client: PoolClient,
  ledgerId: string,
  ownerId: string,
  item: ClientTransaction,
  existing: StoredTransaction | undefined
): Promise<StoredTransaction> {
  const payload = clientPayload(item);
  const deletedAt =
    item.deletedAt === null ? null : new Date(item.deletedAt).toISOString();
  const result = existing
    ? await client.query<StoredRow>(
        `update ledger_transaction
            set payload = $3::jsonb, deleted_at = $4
          where ledger_id = $1 and sync_id = $2
        returning sync_id, server_version, payload,
                  case when deleted_at is null then null
                       else floor(extract(epoch from deleted_at) * 1000)::bigint
                   end as deleted_at_ms`,
        [ledgerId, item.syncId, JSON.stringify(payload), deletedAt]
      )
    : await client.query<StoredRow>(
        `insert into ledger_transaction
          (ledger_id, owner_id, sync_id, payload, deleted_at)
         values ($1, $2, $3, $4::jsonb, $5)
         returning sync_id, server_version, payload,
                   case when deleted_at is null then null
                        else floor(extract(epoch from deleted_at) * 1000)::bigint
                    end as deleted_at_ms`,
        [ledgerId, ownerId, item.syncId, JSON.stringify(payload), deletedAt]
      );
  return rowToStored(result.rows[0]);
}

export interface PushResult {
  accepted: ServerTransaction[];
  conflicts: ServerTransaction[];
}

export async function pushTransactions(
  config: Config,
  identity: AuthenticatedUser,
  items: ClientTransaction[],
  requestedLedgerId?: string
): Promise<PushResult> {
  return transaction(config, async (client) => {
    const { ledgerId, ownerId } = await resolveLedgerAccess(
      client,
      identity,
      requestedLedgerId,
      true
    );
    const accepted: ServerTransaction[] = [];
    const conflicts: ServerTransaction[] = [];

    for (const item of items) {
      const existing = await findExisting(client, ledgerId, item.syncId);
      const decision = decideSync(existing, item);
      if (decision === "conflict") {
        conflicts.push(toServerTransaction(existing!));
      } else if (decision === "idempotent") {
        accepted.push(toServerTransaction(existing!));
      } else {
        accepted.push(toServerTransaction(
          await writeTransaction(client, ledgerId, ownerId, item, existing)
        ));
      }
    }
    return { accepted, conflicts };
  });
}

export interface PullResult {
  transactions: ServerTransaction[];
  nextCursor: number;
  hasMore: boolean;
}

export async function pullTransactions(
  config: Config,
  identity: AuthenticatedUser,
  cursor: number,
  limit: number,
  requestedLedgerId?: string
): Promise<PullResult> {
  return transaction(config, async (client) => {
    const { ledgerId } = await resolveLedgerAccess(
      client,
      identity,
      requestedLedgerId,
      false
    );
    const result = await client.query<StoredRow>(
      `select sync_id, server_version, payload,
              case when deleted_at is null then null
                   else floor(extract(epoch from deleted_at) * 1000)::bigint
               end as deleted_at_ms
         from ledger_transaction
        where ledger_id = $1 and server_version > $2
        order by server_version
        limit $3`,
      [ledgerId, cursor, limit + 1]
    );
    const hasMore = result.rows.length > limit;
    const page = result.rows.slice(0, limit).map(rowToStored);
    return {
      transactions: page.map(toServerTransaction),
      nextCursor: page.length
        ? page[page.length - 1].serverVersion
        : cursor,
      hasMore
    };
  });
}
