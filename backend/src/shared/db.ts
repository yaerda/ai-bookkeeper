import { DefaultAzureCredential } from "@azure/identity";
import pg, { type PoolClient, type QueryResultRow } from "pg";
import type { Config } from "./config.js";

const { Pool } = pg;
const credential = new DefaultAzureCredential();
const postgresScope =
  "https://ossrdbms-aad.database.windows.net/.default";

let pool: pg.Pool | undefined;
let poolKey: string | undefined;

function getPool(config: Config): pg.Pool {
  const key = `${config.pgHost}:${config.pgPort}/${config.pgDatabase}/${config.pgUser}`;
  if (!pool || poolKey !== key) {
    poolKey = key;
    pool = new Pool({
      host: config.pgHost,
      port: config.pgPort,
      database: config.pgDatabase,
      user: config.pgUser,
      password: async () => {
        const token = await credential.getToken(postgresScope);
        if (!token) {
          throw new Error("Could not obtain a PostgreSQL Entra token");
        }
        return token.token;
      },
      ssl: { rejectUnauthorized: true },
      max: 10,
      idleTimeoutMillis: 30_000,
      connectionTimeoutMillis: 10_000
    });
  }
  return pool;
}

export async function query<T extends QueryResultRow>(
  config: Config,
  text: string,
  values?: unknown[]
): Promise<pg.QueryResult<T>> {
  return getPool(config).query<T>(text, values);
}

export async function transaction<T>(
  config: Config,
  operation: (client: PoolClient) => Promise<T>
): Promise<T> {
  const client = await getPool(config).connect();
  try {
    await client.query("begin");
    const result = await operation(client);
    await client.query("commit");
    return result;
  } catch (error) {
    await client.query("rollback");
    throw error;
  } finally {
    client.release();
  }
}
