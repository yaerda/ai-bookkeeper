import {
  app,
  type HttpRequest,
  type HttpResponseInit,
  type InvocationContext
} from "@azure/functions";
import { z, ZodError } from "zod";
import { authenticate, type AuthenticatedUser } from "../shared/auth.js";
import { getConfig, type Config } from "../shared/config.js";
import { transaction } from "../shared/db.js";
import {
  getPrivacySettings,
  migratePrivacy,
  type PrivacyResult,
  updatePrivacySettings,
  verifyPrivacy
} from "../shared/privacyService.js";

const versionSchema = z.number().int().min(0).max(Number.MAX_SAFE_INTEGER);
export const privacySettingsSchema = z.object({
  version: versionSchema,
  currentPasscode: z.string().max(64).optional(),
  newPasscode: z.string().min(4).max(64).optional(),
  clearPasscode: z.boolean().optional(),
  requireOnLogin: z.boolean(),
  requireForIncome: z.boolean()
}).strict().refine(
  (input) => !input.clearPasscode ||
    (input.newPasscode === undefined && !input.requireOnLogin && !input.requireForIncome),
  { message: "Clearing a passcode requires both privacy options off and no new passcode." }
);

export const privacyVerifySchema = z.object({
  passcode: z.string().min(1).max(64),
  version: versionSchema
}).strict();

export const privacyMigrationSchema = z.object({
  passcodeHash: z.string().regex(/^[0-9a-f]{64}$/i),
  salt: z.string().regex(/^[0-9a-f]{32}$/i),
  requireOnLogin: z.boolean(),
  requireForIncome: z.boolean()
}).strict();

export interface PrivacyEndpointDependencies {
  authenticate: typeof authenticate;
  getConfig: typeof getConfig;
  transaction: typeof transaction;
}

const defaults: PrivacyEndpointDependencies = { authenticate, getConfig, transaction };
const noStore = { "Cache-Control": "no-store" };

export function privacyResultResponse<T>(result: PrivacyResult<T>): HttpResponseInit {
  if (result.ok) {
    return { status: 200, headers: noStore, jsonBody: result.value };
  }
  const status = {
    invalid_request: 400,
    settings_changed: 409,
    invalid_passcode: 403,
    locked: 429
  }[result.error];
  return {
    status,
    headers: result.error === "locked"
      ? { ...noStore, "Retry-After": String(result.retryAfterSeconds) }
      : noStore,
    jsonBody: { error: result.error, message: result.message }
  };
}

function failure(error: unknown, context: InvocationContext): HttpResponseInit {
  if (error instanceof ZodError || error instanceof SyntaxError) {
    return {
      status: 400,
      headers: noStore,
      jsonBody: {
        error: "invalid_request",
        message: "The privacy request is invalid. Check your settings and try again."
      }
    };
  }
  // Database/validation errors can contain verifier values; never log them.
  context.error("Privacy request failed");
  return {
    status: 500,
    headers: noStore,
    jsonBody: {
      error: "internal_error",
      message: "Privacy settings are temporarily unavailable. Please try again."
    }
  };
}

async function authenticated<T>(
  request: HttpRequest,
  context: InvocationContext,
  dependencies: PrivacyEndpointDependencies,
  operation: (identity: AuthenticatedUser, config: Config) => Promise<PrivacyResult<T>>
): Promise<HttpResponseInit> {
  let identity: AuthenticatedUser;
  let config: Config;
  try {
    config = dependencies.getConfig();
    identity = await dependencies.authenticate(request.headers.get("authorization"), config);
  } catch {
    context.warn("Privacy authentication rejected");
    return {
      status: 401,
      headers: noStore,
      jsonBody: {
        error: "unauthorized",
        message: "Please sign in to manage your privacy settings."
      }
    };
  }
  try {
    return privacyResultResponse(await operation(identity, config));
  } catch (error) {
    return failure(error, context);
  }
}

export function privacySettings(
  request: HttpRequest,
  context: InvocationContext,
  dependencies: PrivacyEndpointDependencies = defaults
): Promise<HttpResponseInit> {
  return authenticated(request, context, dependencies, async (identity, config) => {
    if (request.method === "PATCH") {
      const input = privacySettingsSchema.parse(await request.json());
      return dependencies.transaction(config, (client) =>
        updatePrivacySettings(client, identity, input)
      );
    }
    return {
      ok: true,
      value: await dependencies.transaction(config, (client) =>
        getPrivacySettings(client, identity)
      )
    };
  });
}

export function privacyVerify(
  request: HttpRequest,
  context: InvocationContext,
  dependencies: PrivacyEndpointDependencies = defaults
): Promise<HttpResponseInit> {
  return authenticated(request, context, dependencies, async (identity, config) => {
    const input = privacyVerifySchema.parse(await request.json());
    return dependencies.transaction(config, (client) =>
      verifyPrivacy(client, identity, input)
    );
  });
}

export function privacyMigrate(
  request: HttpRequest,
  context: InvocationContext,
  dependencies: PrivacyEndpointDependencies = defaults
): Promise<HttpResponseInit> {
  return authenticated(request, context, dependencies, async (identity, config) => {
    const input = privacyMigrationSchema.parse(await request.json());
    return {
      ok: true,
      value: await dependencies.transaction(config, (client) =>
        migratePrivacy(client, identity, input)
      )
    };
  });
}

app.http("privacySettings", {
  methods: ["GET", "PATCH"],
  authLevel: "anonymous",
  route: "privacy/settings",
  handler: privacySettings
});
app.http("privacyVerify", {
  methods: ["POST"],
  authLevel: "anonymous",
  route: "privacy/verify",
  handler: privacyVerify
});
app.http("privacyMigrate", {
  methods: ["POST"],
  authLevel: "anonymous",
  route: "privacy/migrate",
  handler: privacyMigrate
});
