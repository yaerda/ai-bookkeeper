import {
  app,
  type HttpRequest,
  type HttpResponseInit,
  type InvocationContext
} from "@azure/functions";
import { getConfig } from "../shared/config.js";
import { query } from "../shared/db.js";

export async function health(
  _request: HttpRequest,
  context: InvocationContext
): Promise<HttpResponseInit> {
  try {
    const config = getConfig();
    await query(config, "select 1");
    return {
      status: 200,
      jsonBody: { status: "healthy", database: "reachable" }
    };
  } catch (error) {
    context.error("Health check failed", error);
    return {
      status: 503,
      jsonBody: { status: "unhealthy", database: "unreachable" }
    };
  }
}

app.http("health", {
  methods: ["GET"],
  authLevel: "anonymous",
  route: "health",
  handler: health
});
