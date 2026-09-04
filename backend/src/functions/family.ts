import {
  app,
  type HttpRequest,
  type HttpResponseInit,
  type InvocationContext
} from "@azure/functions";
import { z, ZodError } from "zod";
import { authenticate, normalizeEmail } from "../shared/auth.js";
import { getConfig } from "../shared/config.js";
import { transaction } from "../shared/db.js";
import {
  acceptInvitation,
  createLedger,
  DefaultLedgerDeletionError,
  deleteOrLeaveLedger,
  inviteMember,
  listLedgers,
  listMembers,
  removeMember,
  updateLedgerSettings,
  updateMember
} from "../shared/familyService.js";
import { LedgerAccessDeniedError } from "../shared/ledgerAccess.js";

const roleSchema = z.enum(["VIEWER", "EDITOR"]);
const invitationSchema = z.object({
  email: z.email().max(320).transform(normalizeEmail),
  role: roleSchema
}).strict();
const roleUpdateSchema = z.object({ role: roleSchema }).strict();
export const settingsSchema = z.object({
  name: z.string().trim().min(1).max(100).nullish()
    .transform((value) => value ?? undefined),
  mode: z.enum(["PERSONAL", "FAMILY"]).optional()
}).strict().refine((value) => value.name || value.mode);
export const createLedgerSchema = z.object({
  name: z.string().trim().min(1).max(100),
  mode: z.enum(["PERSONAL", "FAMILY"]).optional()
}).strict();
const uuidSchema = z.uuid();

function requestedLedgerId(request: HttpRequest): string | undefined {
  const value = request.query.get("ledgerId");
  return value === null ? undefined : uuidSchema.parse(value);
}

async function identityFor(request: HttpRequest) {
  return authenticate(
    request.headers.get("authorization"),
    getConfig()
  );
}

function failure(
  error: unknown,
  context: InvocationContext
): HttpResponseInit {
  if (error instanceof ZodError || error instanceof SyntaxError) {
    return { status: 400, jsonBody: { error: "invalid_request" } };
  }
  if (error instanceof Error && error.message === "cannot_invite_self") {
    return { status: 400, jsonBody: { error: "cannot_invite_self" } };
  }
  if (error instanceof LedgerAccessDeniedError) {
    return { status: 403, jsonBody: { error: "forbidden" } };
  }
  if (error instanceof DefaultLedgerDeletionError) {
    return {
      status: 409,
      jsonBody: { error: "default_ledger_cannot_be_deleted" }
    };
  }
  context.error("Family ledger request failed", error);
  return { status: 500, jsonBody: { error: "internal_error" } };
}

async function authenticated<T>(
  request: HttpRequest,
  context: InvocationContext,
  operation: (identity: Awaited<ReturnType<typeof identityFor>>) => Promise<T>
): Promise<HttpResponseInit> {
  let identity;
  try {
    identity = await identityFor(request);
  } catch (error) {
    context.warn("Authentication rejected", error);
    return { status: 401, jsonBody: { error: "unauthorized" } };
  }
  try {
    return { status: 200, jsonBody: await operation(identity) };
  } catch (error) {
    return failure(error, context);
  }
}

export function familyLedgers(
  request: HttpRequest,
  context: InvocationContext
): Promise<HttpResponseInit> {
  return authenticated(request, context, async (identity) => {
    if (request.method === "POST") {
      const input = createLedgerSchema.parse(await request.json());
      return transaction(getConfig(), (client) =>
        createLedger(client, identity, input)
      );
    }
    return transaction(getConfig(), (client) => listLedgers(client, identity));
  });
}

export function familyMembers(
  request: HttpRequest,
  context: InvocationContext
): Promise<HttpResponseInit> {
  return authenticated(request, context, (identity) =>
    transaction(getConfig(), (client) =>
      listMembers(client, identity, requestedLedgerId(request))
    )
  );
}

export function familyLedgerDelete(
  request: HttpRequest,
  context: InvocationContext
): Promise<HttpResponseInit> {
  return authenticated(request, context, async (identity) => {
    const ledgerId = uuidSchema.parse(request.params.ledgerId);
    const result = await transaction(getConfig(), (client) =>
      deleteOrLeaveLedger(client, identity, ledgerId)
    );
    if (!result) throw new LedgerAccessDeniedError();
    return result;
  });
}

export async function familySettings(
  request: HttpRequest,
  context: InvocationContext
): Promise<HttpResponseInit> {
  return authenticated(request, context, async (identity) => {
    const input = settingsSchema.parse(await request.json());
    return transaction(getConfig(), (client) =>
      updateLedgerSettings(
        client,
        identity,
        input,
        requestedLedgerId(request)
      )
    );
  });
}

export async function familyInvite(
  request: HttpRequest,
  context: InvocationContext
): Promise<HttpResponseInit> {
  return authenticated(request, context, async (identity) => {
    const input = invitationSchema.parse(await request.json());
    return transaction(getConfig(), (client) =>
      inviteMember(
        client,
        identity,
        input.email,
        input.role,
        requestedLedgerId(request)
      )
    );
  });
}

export function familyAccept(
  request: HttpRequest,
  context: InvocationContext
): Promise<HttpResponseInit> {
  return authenticated(request, context, async (identity) => {
    const invitationId = uuidSchema.parse(request.params.invitationId);
    const accepted = await transaction(getConfig(), (client) =>
      acceptInvitation(client, identity, invitationId)
    );
    if (!accepted) {
      throw new ZodError([]);
    }
    return accepted;
  });
}

export async function familyUpdateMember(
  request: HttpRequest,
  context: InvocationContext
): Promise<HttpResponseInit> {
  return authenticated(request, context, async (identity) => {
    const memberId = uuidSchema.parse(request.params.memberId);
    const input = roleUpdateSchema.parse(await request.json());
    const updated = await transaction(getConfig(), (client) =>
      updateMember(
        client,
        identity,
        memberId,
        input.role,
        requestedLedgerId(request)
      )
    );
    if (!updated) {
      throw new ZodError([]);
    }
    return { updated: true };
  });
}

export function familyRemoveMember(
  request: HttpRequest,
  context: InvocationContext
): Promise<HttpResponseInit> {
  return authenticated(request, context, async (identity) => {
    const memberId = uuidSchema.parse(request.params.memberId);
    const removed = await transaction(getConfig(), (client) =>
      removeMember(
        client,
        identity,
        memberId,
        requestedLedgerId(request)
      )
    );
    if (!removed) {
      throw new ZodError([]);
    }
    return { removed: true };
  });
}

app.http("familyLedgers", {
  methods: ["GET", "POST"],
  authLevel: "anonymous",
  route: "family/ledgers",
  handler: familyLedgers
});
app.http("familyMembers", {
  methods: ["GET"],
  authLevel: "anonymous",
  route: "family/members",
  handler: familyMembers
});
app.http("familyLedgerDelete", {
  methods: ["DELETE"],
  authLevel: "anonymous",
  route: "family/ledgers/{ledgerId}",
  handler: familyLedgerDelete
});
app.http("familySettings", {
  methods: ["PATCH"],
  authLevel: "anonymous",
  route: "family/settings",
  handler: familySettings
});
app.http("familyInvite", {
  methods: ["POST"],
  authLevel: "anonymous",
  route: "family/invitations",
  handler: familyInvite
});
app.http("familyAccept", {
  methods: ["POST"],
  authLevel: "anonymous",
  route: "family/invitations/{invitationId}/accept",
  handler: familyAccept
});
app.http("familyUpdateMember", {
  methods: ["PATCH"],
  authLevel: "anonymous",
  route: "family/members/{memberId}",
  handler: familyUpdateMember
});
app.http("familyRemoveMember", {
  methods: ["DELETE"],
  authLevel: "anonymous",
  route: "family/members/{memberId}",
  handler: familyRemoveMember
});
