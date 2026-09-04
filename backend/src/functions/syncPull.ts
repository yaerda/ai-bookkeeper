import {
  app,
  type HttpRequest,
  type HttpResponseInit,
  type InvocationContext
} from "@azure/functions";
import { authenticate } from "../shared/auth.js";
import { getConfig } from "../shared/config.js";
import { pullTransactions } from "../shared/syncService.js";
import { LedgerAccessDeniedError } from "../shared/ledgerAccess.js";
import { z } from "zod";

function parseNonNegativeInteger(
  value: string | null,
  fallback: number,
  maximum: number
): number | undefined {
  if (value === null) {
    return fallback;
  }
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed >= 0 && parsed <= maximum
    ? parsed
    : undefined;
}

export async function syncPull(
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

  const cursor = parseNonNegativeInteger(
    request.query.get("cursor"),
    0,
    Number.MAX_SAFE_INTEGER
  );
  const limit = parseNonNegativeInteger(request.query.get("limit"), 200, 500);
  const ledgerIdValue = request.query.get("ledgerId");
  const ledgerId = ledgerIdValue
    ? z.uuid().safeParse(ledgerIdValue)
    : undefined;
  if (ledgerId && !ledgerId.success) {
    return { status: 400, jsonBody: { error: "invalid_ledger_id" } };
  }
  if (cursor === undefined || limit === undefined || limit === 0) {
    return { status: 400, jsonBody: { error: "invalid_cursor_or_limit" } };
  }

  try {
    return {
      status: 200,
      jsonBody: await pullTransactions(
        config,
        identity,
        cursor,
        limit,
        ledgerId?.data
      )
    };
  } catch (error) {
    if (error instanceof LedgerAccessDeniedError) {
      return { status: 403, jsonBody: { error: "forbidden" } };
    }
    context.error("Pull failed", error);
    return { status: 500, jsonBody: { error: "internal_error" } };
  }
}

app.http("syncPull", {
  methods: ["GET"],
  authLevel: "anonymous",
  route: "sync/pull",
  handler: syncPull
});
