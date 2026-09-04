import type { PoolClient } from "pg";
import type { AuthenticatedUser } from "./auth.js";

interface UserRow {
  id: string;
}

export async function resolveUser(
  client: PoolClient,
  identity: AuthenticatedUser
): Promise<string> {
  await client.query(
    "select pg_advisory_xact_lock(hashtextextended(jsonb_build_array($1::text, $2::text)::text, 0))",
    [identity.issuer, identity.subject]
  );

  const existingPrincipal = await client.query<UserRow>(
    `select user_id as id
       from auth_principal
      where issuer = $1 and subject = $2`,
    [identity.issuer, identity.subject]
  );
  if (existingPrincipal.rowCount) {
    return existingPrincipal.rows[0].id;
  }

  const user = await client.query<UserRow>(
    `insert into app_user (normalized_email)
     values ($1)
     returning id`,
    [identity.email]
  );
  const userId = user.rows[0].id;

  const principal = await client.query<UserRow>(
    `insert into auth_principal (issuer, subject, user_id)
     values ($1, $2, $3)
     returning user_id as id`,
    [identity.issuer, identity.subject, userId]
  );
  return principal.rows[0].id;
}
