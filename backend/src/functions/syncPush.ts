import {
  app,
  type HttpRequest,
  type HttpResponseInit,
  type InvocationContext
} from "@azure/functions";
import { ZodError } from "zod";
import { authenticate } from "../shared/auth.js";
import { getConfig } from "../shared/config.js";
import { pushRequestSchema } from "../shared/models.js";
import { pushTransactions } from "../shared/syncService.js";
import { LedgerAccessDeniedError } from "../shared/ledgerAccess.js";
import { z } from "zod";

export async function syncPush(
  request: HttpRequest,
  context: InvocationContext
): Promise<HttpResponseInit> {
  const config = getConfig();
  let identity;
  try {
    identity = await authenticate(request.headers.get("authorization"), config);
  } catch (error) {
    context.warn("Authentication rejected", error);
    return { status: 401, jsonBody: { error: "unauthorized" } };
  }

  try {
    const body = pushRequestSchema.parse(await request.json());
    const ledgerIdValue = request.query.get("ledgerId");
    const ledgerId = ledgerIdValue
      ? z.uuid().parse(ledgerIdValue)
      : undefined;
    const result = await pushTransactions(
      config,
      identity,
      body.transactions,
      ledgerId
    );
    return {
      status: result.conflicts.length ? 409 : 200,
      jsonBody: result
    };
  } catch (error) {
    if (error instanceof LedgerAccessDeniedError) {
      return { status: 403, jsonBody: { error: "forbidden" } };
    }
    if (error instanceof ZodError || error instanceof SyntaxError) {
      return { status: 400, jsonBody: { error: "invalid_request" } };
    }
    context.error("Push failed", error);
    return { status: 500, jsonBody: { error: "internal_error" } };
  }
}

app.http("syncPush", {
  methods: ["POST"],
  authLevel: "anonymous",
  route: "sync/push",
  handler: syncPush
});
