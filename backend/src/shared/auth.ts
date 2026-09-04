import { createRemoteJWKSet, jwtVerify, type JWTPayload } from "jose";
import type { Config } from "./config.js";

export interface AuthenticatedUser {
  issuer: string;
  subject: string;
  email: string;
}

type Jwks = ReturnType<typeof createRemoteJWKSet>;

let cachedJwksUri: string | undefined;
let cachedJwks: Jwks | undefined;

function jwksFor(uri: string): Jwks {
  if (!cachedJwks || cachedJwksUri !== uri) {
    cachedJwksUri = uri;
    cachedJwks = createRemoteJWKSet(new URL(uri));
  }
  return cachedJwks;
}

function hasScope(payload: JWTPayload, requiredScope: string): boolean {
  return typeof payload.scp === "string" &&
    payload.scp.split(/\s+/).includes(requiredScope);
}

function readEmail(payload: JWTPayload): string | undefined {
  if (typeof payload.email === "string") {
    return payload.email;
  }
  if (Array.isArray(payload.emails) && typeof payload.emails[0] === "string") {
    return payload.emails[0];
  }
  if (typeof payload.preferred_username === "string") {
    return payload.preferred_username;
  }
  return undefined;
}

export function normalizeEmail(email: string): string {
  return email.trim().toLocaleLowerCase("en-US");
}

export function validateClaims(
  payload: JWTPayload,
  config: Pick<Config, "authIssuer" | "authAudience" | "authScope">
): AuthenticatedUser {
  if (payload.iss !== config.authIssuer) {
    throw new Error("Token issuer is invalid");
  }
  const audiences = Array.isArray(payload.aud) ? payload.aud : [payload.aud];
  if (!audiences.includes(config.authAudience)) {
    throw new Error("Token audience is invalid");
  }
  if (!hasScope(payload, config.authScope)) {
    throw new Error("Token scope is insufficient");
  }
  if (typeof payload.sub !== "string" || !payload.sub) {
    throw new Error("Token subject is missing");
  }
  const email = readEmail(payload);
  if (!email) {
    throw new Error("Email claim is missing");
  }

  return {
    issuer: payload.iss,
    subject: payload.sub,
    email: normalizeEmail(email)
  };
}

export async function authenticate(
  authorization: string | null,
  config: Config
): Promise<AuthenticatedUser> {
  const match = authorization?.match(/^Bearer\s+(.+)$/i);
  if (!match) {
    throw new Error("Bearer token is missing");
  }

  const result = await jwtVerify(match[1], jwksFor(config.authJwksUri), {
    issuer: config.authIssuer,
    audience: config.authAudience,
    algorithms: ["RS256"]
  });
  return validateClaims(result.payload, config);
}
