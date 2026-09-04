import type { PoolClient } from "pg";
import type { AuthenticatedUser } from "./auth.js";
import { LedgerAccessDeniedError } from "./ledgerAccess.js";
import { resolveUser } from "./users.js";

export type MemberRole = "VIEWER" | "EDITOR";
export type LedgerMode = "PERSONAL" | "FAMILY";

export class DefaultLedgerDeletionError extends Error {
  constructor() {
    super("The default ledger cannot be deleted");
  }
}

interface LedgerRow {
  id: string;
  name: string;
  owner_email: string;
  role: "OWNER" | MemberRole;
  mode: LedgerMode;
  is_default: boolean;
}

interface OwnedLedgerRow {
  id: string;
  owner_id: string;
  name: string;
  mode: LedgerMode;
  is_default: boolean;
}

interface InvitationRow {
  id: string;
  ledger_id: string;
  ledger_name: string;
  inviter_email: string;
  invited_email?: string;
  role: MemberRole;
  created_at?: Date;
}

interface MemberRow {
  id: string;
  user_id: string;
  email: string;
  role: MemberRole;
}

async function ensureDefaultLedger(
  client: PoolClient,
  ownerId: string
): Promise<void> {
  await client.query(
    `insert into family_ledger (id, owner_id, is_default)
     values ($1, $1, true)
     on conflict (id) do nothing`,
    [ownerId]
  );
}

async function resolveOwnedLedger(
  client: PoolClient,
  identity: AuthenticatedUser,
  requestedLedgerId?: string
): Promise<OwnedLedgerRow> {
  const ownerId = await resolveUser(client, identity);
  await ensureDefaultLedger(client, ownerId);
  const result = await client.query<OwnedLedgerRow>(
    `select id, owner_id, name, mode, is_default
       from family_ledger
      where owner_id = $1
         and deleted_at is null
         and id = coalesce($2::uuid, (
           select id
             from family_ledger
            where owner_id = $1 and is_default and deleted_at is null
         ))`,
    [ownerId, requestedLedgerId ?? null]
  );
  if (!result.rows[0]) {
    throw new LedgerAccessDeniedError();
  }
  return result.rows[0];
}

export async function listLedgers(
  client: PoolClient,
  identity: AuthenticatedUser
) {
  const userId = await resolveUser(client, identity);
  await ensureDefaultLedger(client, userId);

  const ledgers = await client.query<LedgerRow>(
    `select fl.id, fl.name, owner.normalized_email as owner_email,
            'OWNER'::text as role, fl.mode, fl.is_default
       from family_ledger fl
       join app_user owner on owner.id = fl.owner_id
       where fl.owner_id = $1 and fl.deleted_at is null
      union all
     select fl.id, fl.name, owner.normalized_email as owner_email,
            lm.role, fl.mode, false as is_default
       from ledger_member lm
       join family_ledger fl on fl.id = lm.ledger_id
       join app_user owner on owner.id = fl.owner_id
      where lm.member_id = $1 and fl.deleted_at is null
      order by is_default desc, name, owner_email`,
    [userId]
  );
  const invitations = await client.query<InvitationRow>(
    `select li.id, li.ledger_id, fl.name as ledger_name,
            owner.normalized_email as inviter_email, li.role
       from ledger_invitation li
       join family_ledger fl on fl.id = li.ledger_id
       join app_user owner on owner.id = fl.owner_id
      where li.invited_email = $1 and fl.deleted_at is null
      order by li.created_at`,
    [identity.email]
  );

  return {
    ledgers: ledgers.rows.map((row) => ({
      id: row.id,
      name: row.name,
      ownerEmail: row.owner_email,
      role: row.role,
      mode: row.mode,
      isDefault: row.is_default
    })),
    invitations: invitations.rows.map((row) => ({
      id: row.id,
      ledgerId: row.ledger_id,
      ledgerName: row.ledger_name,
      inviterEmail: row.inviter_email,
      role: row.role
    }))
  };
}

export async function createLedger(
  client: PoolClient,
  identity: AuthenticatedUser,
  input: { name: string; mode?: LedgerMode }
) {
  const ownerId = await resolveUser(client, identity);
  await ensureDefaultLedger(client, ownerId);
  const result = await client.query<OwnedLedgerRow>(
    `insert into family_ledger (owner_id, name, mode, is_default)
     values ($1, $2, coalesce($3, 'PERSONAL'), false)
     returning id, owner_id, name, mode, is_default`,
    [ownerId, input.name, input.mode]
  );
  const row = result.rows[0];
  return {
    id: row.id,
    name: row.name,
    mode: row.mode,
    isDefault: row.is_default
  };
}

export async function listMembers(
  client: PoolClient,
  identity: AuthenticatedUser,
  requestedLedgerId?: string
) {
  const ledger = await resolveOwnedLedger(client, identity, requestedLedgerId);
  const members = await client.query<MemberRow>(
    `select lm.id, lm.member_id as user_id,
            member.normalized_email as email, lm.role
       from ledger_member lm
       join app_user member on member.id = lm.member_id
      where lm.ledger_id = $1
      order by member.normalized_email`,
    [ledger.id]
  );
  const invitations = await client.query<InvitationRow>(
    `select id, ledger_id, '' as ledger_name,
            '' as inviter_email, invited_email, role, created_at
       from ledger_invitation
      where ledger_id = $1
      order by created_at`,
    [ledger.id]
  );
  return {
    ledger: {
      id: ledger.id,
      name: ledger.name,
      mode: ledger.mode,
      isDefault: ledger.is_default
    },
    members: members.rows.map((row) => ({
      id: row.id,
      userId: row.user_id,
      email: row.email,
      role: row.role
    })),
    invitations: invitations.rows.map((row) => ({
      id: row.id,
      email: row.invited_email,
      role: row.role,
      createdAt: row.created_at?.toISOString()
    }))
  };
}

export async function inviteMember(
  client: PoolClient,
  identity: AuthenticatedUser,
  email: string,
  role: MemberRole,
  requestedLedgerId?: string
) {
  const ledger = await resolveOwnedLedger(client, identity, requestedLedgerId);
  if (email === identity.email) {
    throw new Error("cannot_invite_self");
  }
  const result = await client.query<{ id: string }>(
    `insert into ledger_invitation
       (ledger_id, owner_id, invited_email, role)
     values ($1, $2, $3, $4)
     on conflict (ledger_id, invited_email)
     do update set role = excluded.role, created_at = now()
     returning id`,
    [ledger.id, ledger.owner_id, email, role]
  );
  await client.query(
    `update family_ledger
        set mode = 'FAMILY', updated_at = now()
      where id = $1`,
    [ledger.id]
  );
  return { id: result.rows[0].id, email, role };
}

export async function updateLedgerSettings(
  client: PoolClient,
  identity: AuthenticatedUser,
  input: { name?: string; mode?: LedgerMode },
  requestedLedgerId?: string
) {
  const ledger = await resolveOwnedLedger(client, identity, requestedLedgerId);
  if (input.mode === "PERSONAL") {
    await client.query("delete from ledger_member where ledger_id = $1", [
      ledger.id
    ]);
    await client.query("delete from ledger_invitation where ledger_id = $1", [
      ledger.id
    ]);
  }
  const result = await client.query<OwnedLedgerRow>(
    `update family_ledger
        set name = coalesce($2, name),
            mode = coalesce($3, mode),
            updated_at = now()
      where id = $1
      returning id, owner_id, name, mode, is_default`,
    [ledger.id, input.name, input.mode]
  );
  const row = result.rows[0];
  return {
    id: row.id,
    name: row.name,
    mode: row.mode,
    isDefault: row.is_default
  };
}

export async function acceptInvitation(
  client: PoolClient,
  identity: AuthenticatedUser,
  invitationId: string
) {
  const memberId = await resolveUser(client, identity);
  const invitation = await client.query<{
    ledger_id: string;
    owner_id: string;
    invited_email: string;
    role: MemberRole;
  }>(
    `select ledger_id, owner_id, invited_email, role
       from ledger_invitation
      where id = $1
      for update`,
    [invitationId]
  );
  const row = invitation.rows[0];
  if (!row || row.invited_email !== identity.email || row.owner_id === memberId) {
    return undefined;
  }
  await client.query(
    `insert into ledger_member (ledger_id, owner_id, member_id, role)
     values ($1, $2, $3, $4)
     on conflict (ledger_id, member_id)
     do update set role = excluded.role, updated_at = now()`,
    [row.ledger_id, row.owner_id, memberId, row.role]
  );
  await client.query("delete from ledger_invitation where id = $1", [
    invitationId
  ]);
  return { ledgerId: row.ledger_id, role: row.role };
}

export async function deleteOrLeaveLedger(
  client: PoolClient,
  identity: AuthenticatedUser,
  ledgerId: string
): Promise<{ action: "DELETED" | "LEFT" } | undefined> {
  const userId = await resolveUser(client, identity);
  const access = await client.query<{
    owner_id: string;
    is_default: boolean;
    member_id: string | null;
  }>(
    `select fl.owner_id, fl.is_default, lm.member_id
       from family_ledger fl
       left join ledger_member lm
         on lm.ledger_id = fl.id and lm.member_id = $2
      where fl.id = $1
        and fl.deleted_at is null
        and (fl.owner_id = $2 or lm.member_id is not null)
      for update of fl`,
    [ledgerId, userId]
  );
  const row = access.rows[0];
  if (!row) return undefined;

  if (row.owner_id === userId) {
    if (row.is_default) throw new DefaultLedgerDeletionError();
    await client.query(
      `update family_ledger
          set deleted_at = now(), updated_at = now()
        where id = $1 and deleted_at is null`,
      [ledgerId]
    );
    await client.query("delete from ledger_invitation where ledger_id = $1", [
      ledgerId
    ]);
    await client.query("delete from ledger_member where ledger_id = $1", [
      ledgerId
    ]);
    return { action: "DELETED" };
  }

  await client.query(
    "delete from ledger_member where ledger_id = $1 and member_id = $2",
    [ledgerId, userId]
  );
  return { action: "LEFT" };
}

export async function updateMember(
  client: PoolClient,
  identity: AuthenticatedUser,
  memberRecordId: string,
  role: MemberRole,
  requestedLedgerId?: string
): Promise<boolean> {
  const ledger = await resolveOwnedLedger(client, identity, requestedLedgerId);
  const result = await client.query(
    `update ledger_member
        set role = $3, updated_at = now()
      where id = $1 and ledger_id = $2`,
    [memberRecordId, ledger.id, role]
  );
  return Boolean(result.rowCount);
}

export async function removeMember(
  client: PoolClient,
  identity: AuthenticatedUser,
  memberRecordId: string,
  requestedLedgerId?: string
): Promise<boolean> {
  const ledger = await resolveOwnedLedger(client, identity, requestedLedgerId);
  const result = await client.query(
    "delete from ledger_member where id = $1 and ledger_id = $2",
    [memberRecordId, ledger.id]
  );
  return Boolean(result.rowCount);
}
