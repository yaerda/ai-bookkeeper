import { readdir, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { AzureCliCredential } from "@azure/identity";
import pg from "pg";

function required(name: string): string {
  const value = process.env[name]?.trim();
  if (!value) {
    throw new Error(`Missing required setting: ${name}`);
  }
  return value;
}

const credential = new AzureCliCredential();
const token = await credential.getToken(
  "https://ossrdbms-aad.database.windows.net/.default"
);
if (!token) {
  throw new Error("Could not obtain a PostgreSQL Entra token");
}

const client = new pg.Client({
  host: required("PG_HOST"),
  port: Number(process.env.PG_PORT ?? "5432"),
  database: process.env.PG_DATABASE?.trim() || "aibookkeeper",
  user: required("PG_USER"),
  password: token.token,
  ssl: { rejectUnauthorized: true }
});
const migrationsDirectory = resolve("migrations");

try {
  await client.connect();
  const migrations = (await readdir(migrationsDirectory))
    .filter((name) => name.endsWith(".sql"))
    .sort();
  for (const migration of migrations) {
    await client.query(
      await readFile(resolve(migrationsDirectory, migration), "utf8")
    );
    console.log(`Applied ${migration}`);
  }
  const runtimeUser = process.env.PG_RUNTIME_USER?.trim();
  if (runtimeUser) {
    if (!/^[a-zA-Z0-9._-]+$/.test(runtimeUser)) {
      throw new Error("PG_RUNTIME_USER contains unsupported characters");
    }
    const quotedRuntimeUser = `"${runtimeUser.replaceAll('"', '""')}"`;
    await client.query(
      `grant select, insert, update, delete
         on family_ledger, ledger_transaction, ledger_member, ledger_invitation
         to ${quotedRuntimeUser}`
    );
    await client.query(
      `grant usage, select on sequence sync_version_seq
         to ${quotedRuntimeUser}`
    );
    console.log(`Granted family ledger permissions to ${runtimeUser}`);
  }
} finally {
  await client.end();
}
