export interface Config {
  authIssuer: string;
  authAudience: string;
  authScope: string;
  authJwksUri: string;
  pgHost: string;
  pgDatabase: string;
  pgUser: string;
  pgPort: number;
}

function required(name: string): string {
  const value = process.env[name]?.trim();
  if (!value) {
    throw new Error(`Missing required setting: ${name}`);
  }
  return value;
}

export function getConfig(): Config {
  const pgPort = Number(process.env.PG_PORT ?? "5432");
  if (!Number.isInteger(pgPort) || pgPort < 1 || pgPort > 65535) {
    throw new Error("PG_PORT must be a valid TCP port");
  }

  return {
    authIssuer: required("AUTH_ISSUER").replace(/\/$/, ""),
    authAudience: required("AUTH_AUDIENCE"),
    authScope: process.env.AUTH_SCOPE?.trim() || "sync.readwrite",
    authJwksUri: required("AUTH_JWKS_URI"),
    pgHost: required("PG_HOST"),
    pgDatabase: process.env.PG_DATABASE?.trim() || "aibookkeeper",
    pgUser: required("PG_USER"),
    pgPort
  };
}
