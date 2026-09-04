import type { PoolClient } from "pg";
import type { AuthenticatedUser } from "./auth.js";
import { resolveUser } from "./users.js";

export type LedgerRole = "OWNER" | "EDITOR" | "VIEWER";

export class LedgerAccessDeniedError extends Error {
  constructor() {
    super("Ledger access is denied");
  }
}

interface RoleRow {
  ledger_id: string;
  owner_id: string;
  role: LedgerRole;
}

interface OwnedLedgerRow {
  ledger_id: string;
  owner_id: string;
}

export async function resolveLedgerAccess(
  client: PoolClient,
  identity: AuthenticatedUser,
  requestedLedgerId: string | undefined,
  requireWrite: boolean
): Promise<{
  ledgerId: string;
  ownerId: string;
  userId: string;
  role: LedgerRole;
}> {
  const userId = await resolveUser(client, identity);
  if (!requestedLedgerId) {
    await client.query(
      `insert into family_ledger (id, owner_id, is_default)
       values ($1, $1, true)
       on conflict (id) do nothing`,
      [userId]
    );
    const owned = await client.query<OwnedLedgerRow>(
      `select id as ledger_id, owner_id
         from family_ledger
        where owner_id = $1 and is_default`,
      [userId]
    );
    const ledger = owned.rows[0];
    if (!ledger) {
      throw new LedgerAccessDeniedError();
    }
    return {
      ledgerId: ledger.ledger_id,
      ownerId: ledger.owner_id,
      userId,
      role: "OWNER"
    };
  }

  const access = await client.query<RoleRow>(
    `select fl.id as ledger_id, fl.owner_id,
            case when fl.owner_id = $2 then 'OWNER' else lm.role end as role
       from family_ledger fl
       left join ledger_member lm
         on lm.ledger_id = fl.id and lm.member_id = $2
      where fl.id = $1
        and (fl.owner_id = $2 or lm.member_id is not null)`,
    [requestedLedgerId, userId]
  );
  const row = access.rows[0];
  if (
    !row ||
    (requireWrite && row.role !== "OWNER" && row.role !== "EDITOR")
  ) {
    throw new LedgerAccessDeniedError();
  }
  return {
    ledgerId: row.ledger_id,
    ownerId: row.owner_id,
    userId,
    role: row.role
  };
}
