import {
  app,
  type HttpRequest,
  type HttpResponseInit,
  type InvocationContext
} from "@azure/functions";
import type { PoolClient } from "pg";
import { z, ZodError } from "zod";
import { authenticate, type AuthenticatedUser } from "../shared/auth.js";
import {
  createCategorySchema,
  importCategoriesSchema
} from "../shared/categoryModels.js";
import {
  createCategory,
  importCategories,
  listCategories
} from "../shared/categoryService.js";
import { getConfig } from "../shared/config.js";
import { transaction } from "../shared/db.js";
import { LedgerAccessDeniedError } from "../shared/ledgerAccess.js";

const uuidSchema = z.uuid();

function requestedLedgerId(request: HttpRequest): string | undefined {
  const value = request.query.get("ledgerId");
  return value === null ? undefined : uuidSchema.parse(value);
}

interface CategoryDependencies {
  authenticate(request: HttpRequest): Promise<AuthenticatedUser>;
  transaction<T>(operation: (client: PoolClient) => Promise<T>): Promise<T>;
}

export function createCategoryHandlers(dependencies: CategoryDependencies) {
  async function authenticated<T>(
    request: HttpRequest,
    context: InvocationContext,
    operation: (identity: AuthenticatedUser) => Promise<T>
  ): Promise<HttpResponseInit> {
    let identity: AuthenticatedUser;
    try {
      identity = await dependencies.authenticate(request);
    } catch (error) {
      context.warn("Authentication rejected", error);
      return { status: 401, jsonBody: { error: "unauthorized" } };
    }
    try {
      return { status: 200, jsonBody: await operation(identity) };
    } catch (error) {
      if (error instanceof ZodError || error instanceof SyntaxError) {
        return { status: 400, jsonBody: { error: "invalid_request" } };
      }
      if (error instanceof LedgerAccessDeniedError) {
        return { status: 403, jsonBody: { error: "forbidden" } };
      }
      context.error("Category request failed", error);
      return { status: 500, jsonBody: { error: "internal_error" } };
    }
  }

  return {
    categories(request: HttpRequest, context: InvocationContext) {
      return authenticated(request, context, async (identity) => {
        const ledgerId = requestedLedgerId(request);
        if (request.method === "POST") {
          const input = createCategorySchema.parse(await request.json());
          return dependencies.transaction((client) =>
            createCategory(client, identity, input, ledgerId)
          );
        }
        return dependencies.transaction((client) =>
          listCategories(client, identity, ledgerId)
        );
      });
    },
    categoryImport(request: HttpRequest, context: InvocationContext) {
      return authenticated(request, context, async (identity) => {
        const ledgerId = requestedLedgerId(request);
        const input = importCategoriesSchema.parse(await request.json());
        return dependencies.transaction((client) =>
          importCategories(client, identity, input.categories, ledgerId)
        );
      });
    }
  };
}

export const { categories, categoryImport } = createCategoryHandlers({
  authenticate: (request) =>
    authenticate(request.headers.get("authorization"), getConfig()),
  transaction: (operation) => transaction(getConfig(), operation)
});

app.http("categories", {
  methods: ["GET", "POST"],
  authLevel: "anonymous",
  route: "categories",
  handler: categories
});
app.http("categoryImport", {
  methods: ["POST"],
  authLevel: "anonymous",
  route: "categories/import",
  handler: categoryImport
});
