import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { describe, it } from "node:test";
import azureFunctions, { type InvocationContext } from "@azure/functions";
import type { PoolClient, QueryResult } from "pg";
import { createCategoryHandlers } from "../src/functions/categories.js";
import type { AuthenticatedUser } from "../src/shared/auth.js";
import {
  CATEGORY_WHITESPACE,
  createCategorySchema,
  DEFAULT_CATEGORIES,
  importCategoriesSchema,
  LEGACY_PLACEHOLDER_ICON_PATTERN,
  type LedgerCategory
} from "../src/shared/categoryModels.js";
import {
  createCategory,
  importCategories,
  listCategories
} from "../src/shared/categoryService.js";
import { LedgerAccessDeniedError, type LedgerRole } from "../src/shared/ledgerAccess.js";

const { HttpRequest } = azureFunctions;

const ownerId = "11111111-1111-4111-8111-111111111111";
const editorId = "22222222-2222-4222-8222-222222222222";
const viewerId = "33333333-3333-4333-8333-333333333333";
const guestId = "44444444-4444-4444-8444-444444444444";
const sharedId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const otherId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
const custom = createCategorySchema.parse({
  name: "宠物", type: "EXPENSE", icon: "🐈‍⬛", color: "#673AB7"
});

function identity(userId = ownerId): AuthenticatedUser {
  return { issuer: "issuer", subject: userId, email: `${userId}@example.com` };
}

function result<T>(rows: T[]): QueryResult<T> {
  return { command: "SELECT", rowCount: rows.length, oid: 0, fields: [], rows };
}

interface TestLedger {
  ownerId: string;
  isDefault: boolean;
  deleted: boolean;
  members: Map<string, LedgerRole>;
}

interface TestCategory {
  id: number;
  ledger_id: string;
  name: string;
  type: LedgerCategory["type"];
  icon: string;
  color: string;
  sort_order: number;
  is_system: boolean;
}

function catalogDatabase() {
  const ledgers = new Map<string, TestLedger>();
  for (const ledgerId of [ownerId, sharedId, otherId]) {
    ledgers.set(ledgerId, {
      ownerId, isDefault: ledgerId === ownerId, deleted: false,
      members: new Map(ledgerId === sharedId ? [[editorId, "EDITOR"], [viewerId, "VIEWER"]] : [])
    });
  }
  const rows: TestCategory[] = [];
  const calls: Array<{ text: string; values: unknown[] }> = [];
  const legacy = new Map<string, Array<{
    name: string; type: string; icon: string | null; color: string | null;
  }>>();
  let nextId = 101;

  const client = {
    query: async (text: string, values: unknown[] = []) => {
      calls.push({ text, values });
      if (/pg_advisory_xact_lock/i.test(text)) return result([]);
      if (/from auth_principal/i.test(text)) return result([{ id: values[1] }]);
      if (/insert into family_ledger/i.test(text)) {
        const userId = String(values[0]);
        if (!ledgers.has(userId)) {
          ledgers.set(userId, {
            ownerId: userId, isDefault: true, deleted: false, members: new Map()
          });
        }
        return result([]);
      }
      if (/where owner_id = \$1 and is_default/i.test(text)) {
        assert.match(text, /deleted_at is null/i);
        return result([...ledgers].filter(([, ledger]) =>
          ledger.ownerId === values[0] && ledger.isDefault && !ledger.deleted
        ).map(([ledgerId, ledger]) => ({ ledger_id: ledgerId, owner_id: ledger.ownerId })));
      }
      if (/from family_ledger fl/i.test(text)) {
        assert.match(text, /fl\.deleted_at is null/i);
        assert.match(text, /left join ledger_member lm/i);
        assert.match(text, /lm\.ledger_id = fl\.id and lm\.member_id = \$2/i);
        assert.match(text, /fl\.owner_id = \$2 or lm\.member_id is not null/i);
        const ledger = ledgers.get(String(values[0]));
        if (!ledger || ledger.deleted) return result([]);
        const role = ledger.ownerId === values[1]
          ? "OWNER" : ledger.members.get(String(values[1]));
        return result(role ? [{
          ledger_id: values[0], owner_id: ledger.ownerId, role
        }] : []);
      }
      if (/insert into ledger_category/i.test(text)) {
        assert.match(text, /on conflict \(ledger_id, type, name\) do nothing/i);
        assert.doesNotMatch(text, /do update/i);
        assert.match(text, /existing\.ledger_id = \$1/i);
        assert.match(text, /order by incoming\.type, incoming\.name/i);
        const candidates = JSON.parse(String(values[1])) as Array<Omit<LedgerCategory, "id">>;
        for (const candidate of candidates) {
          if (!rows.some((row) => row.ledger_id === values[0] &&
            row.type === candidate.type && row.name === candidate.name)) {
            rows.push({
              id: nextId++, ledger_id: String(values[0]), name: candidate.name,
              type: candidate.type, icon: candidate.icon, color: candidate.color,
              sort_order: candidate.sortOrder, is_system: candidate.isSystem
            });
          }
        }
        return result([]);
      }
      if (/from ledger_transaction/i.test(text)) {
        assert.match(text, /where ledger_id = \$1 and deleted_at is null/i);
        assert.match(text, /select distinct on \(legacy\.type, legacy\.name\)/i);
        assert.match(text, /existing\.ledger_id = \$1/i);
        assert.doesNotMatch(text, /categoryId/);
        return result(legacy.get(String(values[0])) ?? []);
      }
      if (/from ledger_category/i.test(text)) {
        assert.match(text, /where ledger_id = \$1/i);
        return result(rows.filter((row) => row.ledger_id === values[0] &&
          (values.length === 1 || (row.type === values[1] && row.name === values[2]))
        ).sort((a, b) => a.type.localeCompare(b.type) ||
          a.sort_order - b.sort_order || a.name.localeCompare(b.name) || a.id - b.id)
          .map((row) => ({ ...row })));
      }
      throw new Error(`Unexpected query: ${text}`);
    }
  } as unknown as PoolClient;
  return { client, rows, calls, legacy, ledgers };
}

type TestDatabase = ReturnType<typeof catalogDatabase>;

function categoryCalls(database: TestDatabase) {
  return database.calls.filter(({ text }) => /ledger_category|ledger_transaction/i.test(text));
}

describe("ledger category service", () => {
  it("seeds independent per-ledger catalogs idempotently with safe cloud IDs", async () => {
    const database = catalogDatabase();
    const first = await listCategories(database.client, identity());
    const repeated = await listCategories(database.client, identity());
    const second = await listCategories(database.client, identity(), otherId);

    assert.equal(first.categories.length, 16);
    assert.deepEqual(repeated, first);
    assert.equal(second.categories.length, 16);
    assert.equal(database.rows.length, 32);
    const firstIds = new Set(first.categories.map((category) => category.id));
    assert.ok(second.categories.every((category) => !firstIds.has(category.id)));
    for (const category of [...first.categories, ...second.categories]) {
      assert.ok(Number.isSafeInteger(category.id) && category.id > 0);
      assert.deepEqual(Object.keys(category).sort(), [
        "color", "icon", "id", "isSystem", "name", "sortOrder", "type"
      ]);
      assert.deepEqual(
        { ...category, id: undefined },
        { ...DEFAULT_CATEGORIES.find((item) => item.type === category.type && item.name === category.name), id: undefined }
      );
    }
  });

  it("lets owners and editors add shared categories visible to accepted viewers", async () => {
    const database = catalogDatabase();
    const created = await createCategory(database.client, identity(editorId), custom, sharedId);
    const same = await createCategory(
      database.client, identity(), { ...custom, icon: "changed", color: "#FFFFFF", sortOrder: 0 }, sharedId
    );
    const viewer = await listCategories(database.client, identity(viewerId), sharedId);

    assert.equal(created.category.isSystem, false);
    assert.deepEqual(same, created);
    assert.ok(viewer.categories.some((item) =>
      item.id === created.category.id && item.icon === "🐈‍⬛"
    ));
    const independent = await createCategory(database.client, identity(), custom, otherId);
    assert.notEqual(independent.category.id, created.category.id);
  });

  it("imports normalized duplicates only once and never overwrites default or custom metadata", async () => {
    const database = catalogDatabase();
    const input = importCategoriesSchema.parse({ categories: [
      { ...custom, name: "\t宠物\u3000用品 ", sortOrder: 12 },
      { ...custom, name: "宠物  用品", icon: "overwritten", sortOrder: 0 },
      { ...custom, name: "宠物 用品", type: "INCOME" },
      { ...custom, name: " 餐饮 ", icon: "●", color: "#000000" }
    ] }).categories;
    const imported = await importCategories(database.client, identity(editorId), input, sharedId);
    const repeated = await importCategories(database.client, identity(editorId), input, sharedId);
    const empty = await importCategories(database.client, identity(), [], sharedId);

    assert.deepEqual(repeated, imported);
    assert.deepEqual(empty, imported);
    assert.equal(imported.categories.length, 18);
    const expense = imported.categories.find((item) => item.name === "宠物 用品" && item.type === "EXPENSE");
    assert.equal(expense?.icon, custom.icon);
    assert.equal(expense?.sortOrder, 12);
    assert.equal(expense?.isSystem, false);
    const food = imported.categories.find((item) => item.name === "餐饮");
    assert.equal(food?.icon, "ic_food");
    assert.equal(food?.color, "#FF5722");
    assert.equal(food?.isSystem, true);
    const customInsert = database.calls.find(({ text, values }) =>
      /insert into ledger_category/i.test(text) && String(values[1]).includes("宠物 用品")
    );
    assert.equal(JSON.parse(String(customInsert?.values[1])).length, 3);
  });

  it("resolves omitted ledger IDs to the caller's default, not a shared ledger", async () => {
    const database = catalogDatabase();
    const imported = await importCategories(database.client, identity(editorId), [custom]);
    const scopedCalls = categoryCalls(database);
    assert.ok(scopedCalls.length > 0);
    assert.ok(scopedCalls.every(({ values }) => values[0] === editorId));
    assert.equal(imported.categories.length, 17);

    const shared = await listCategories(database.client, identity(editorId), sharedId);
    assert.equal(shared.categories.length, 16);
    assert.ok(shared.categories.every((item) => item.name !== custom.name));
  });

  it("authorizes explicit ledger access before any category reads or writes", async () => {
    const database = catalogDatabase();
    await importCategories(database.client, identity(editorId), [custom], sharedId);
    const authorization = database.calls.findIndex(({ text }) => /from family_ledger fl/i.test(text));
    const firstCategory = database.calls.findIndex(({ text }) => /ledger_category|ledger_transaction/i.test(text));
    assert.ok(authorization >= 0 && firstCategory > authorization);
    assert.ok(database.calls.every(({ text }) => !/insert into family_ledger/i.test(text)));
    assert.ok(categoryCalls(database).every(({ values }) => values[0] === sharedId));
  });

  it("backfills valid legacy names safely and keeps catalog entries after history disappears", async () => {
    const database = catalogDatabase();
    database.legacy.set(sharedId, [
      { name: " \t宠物\u3000用品 ", type: "EXPENSE", icon: " 🐈‍⬛ ", color: "#673AB7" },
      { name: "旧分类", type: "INCOME", icon: "x".repeat(4000), color: "#bad" },
      { name: "餐饮", type: "EXPENSE", icon: "●", color: "#000000" },
      { name: "工资", type: "INCOME", icon: "↑", color: "#000000" },
      { name: "x".repeat(4000), type: "EXPENSE", icon: null, color: null }
    ]);
    const catalog = await listCategories(database.client, identity(viewerId), sharedId);
    assert.equal(catalog.categories.length, 18);
    assert.equal(catalog.categories.find((item) => item.name === "宠物 用品")?.icon, "🐈‍⬛");
    assert.equal(catalog.categories.find((item) => item.name === "旧分类")?.icon, "tag");
    assert.equal(catalog.categories.find((item) => item.name === "旧分类")?.color, "#607D8B");
    assert.equal(catalog.categories.find((item) => item.name === "餐饮")?.icon, "ic_food");
    assert.equal(catalog.categories.find((item) => item.name === "工资")?.icon, "ic_salary");

    database.legacy.clear();
    assert.deepEqual(
      await listCategories(database.client, identity(viewerId), sharedId),
      catalog
    );
    const other = await listCategories(database.client, identity(), otherId);
    assert.equal(other.categories.length, 16);
    assert.ok(database.calls.every(({ text }) =>
      !/(insert into|update|delete from) ledger_transaction/i.test(text)
    ));
  });

  it("queries only distinct missing active same-ledger legacy names, preferring useful styles", async () => {
    const database = catalogDatabase();
    await listCategories(database.client, identity(), sharedId);
    const call = database.calls.find(({ text }) => /from ledger_transaction/i.test(text))!;
    assert.match(call.text, /where ledger_id = \$1 and deleted_at is null/i);
    assert.match(call.text, /select distinct on \(legacy\.type, legacy\.name\)/i);
    assert.match(call.text, /jsonb_typeof\(payload->'categoryName'\) = 'string'/);
    assert.match(call.text, /payload->>'type' in \('EXPENSE', 'INCOME'\)/);
    assert.match(call.text, /char_length\(legacy\.name\) between 1 and 100/i);
    assert.match(call.text, /not exists \([\s\S]*existing\.ledger_id = \$1/);
    assert.match(call.text, /existing\.type = legacy\.type and existing\.name = legacy\.name/);
    assert.match(call.text, /legacy\.icon !~ \$4, false\) desc/i);
    assert.match(call.text, /coalesce\(legacy\.color ~ '[^']+', false\) desc/i);
    assert.match(call.text, /legacy\.server_version desc, legacy\.sync_id/i);
    assert.doesNotMatch(call.text, /categoryId|select \*|update ledger_transaction/i);
    assert.deepEqual(call.values, [
      sharedId, `[${CATEGORY_WHITESPACE}]+`, CATEGORY_WHITESPACE, LEGACY_PLACEHOLDER_ICON_PATTERN
    ]);
  });

  it("rejects an unsafe or non-positive database category ID instead of rounding it", async () => {
    for (const id of [0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1]) {
      const database = catalogDatabase();
      await listCategories(database.client, identity(), sharedId);
      database.rows[0].id = id;
      await assert.rejects(
        listCategories(database.client, identity(), sharedId),
        /Invalid ledger category ID/
      );
    }
  });
});

describe("ledger category authorization", () => {
  for (const operation of ["create", "import", "empty import"] as const) {
    it(`denies a viewer's ${operation} before seeding or backfilling`, async () => {
      const database = catalogDatabase();
      const request = operation === "create"
        ? createCategory(database.client, identity(viewerId), custom, sharedId)
        : importCategories(
          database.client, identity(viewerId), operation === "import" ? [custom] : [], sharedId
        );
      await assert.rejects(request, LedgerAccessDeniedError);
      assert.equal(categoryCalls(database).length, 0);
      assert.equal(database.rows.length, 0);
    });
  }

  for (const reason of ["revoked", "pending invitation", "deleted ledger", "missing ledger", "no access"] as const) {
    for (const operation of ["read", "create", "import"] as const) {
      it(`denies ${operation} for ${reason} without touching any catalog`, async () => {
        const database = catalogDatabase();
        const userId = reason === "pending invitation" || reason === "no access" ? guestId : editorId;
        if (reason === "revoked") database.ledgers.get(sharedId)!.members.delete(editorId);
        if (reason === "deleted ledger") database.ledgers.get(sharedId)!.deleted = true;
        if (reason === "missing ledger") database.ledgers.delete(sharedId);
        const request = operation === "read"
          ? listCategories(database.client, identity(userId), sharedId)
          : operation === "create"
            ? createCategory(database.client, identity(userId), custom, sharedId)
            : importCategories(database.client, identity(userId), [], sharedId);
        await assert.rejects(request, LedgerAccessDeniedError);
        assert.equal(categoryCalls(database).length, 0);
        assert.equal(database.rows.length, 0);
      });
    }
  }

  it("also denies the owner access to a soft-deleted ledger with retained categories", async () => {
    const database = catalogDatabase();
    await createCategory(database.client, identity(), custom, sharedId);
    database.ledgers.get(sharedId)!.deleted = true;
    database.calls.length = 0;
    await assert.rejects(listCategories(database.client, identity(), sharedId), LedgerAccessDeniedError);
    assert.equal(categoryCalls(database).length, 0);
    assert.equal(database.rows.length, 17);
  });
});

const context = { warn: () => {}, error: () => {} } as unknown as InvocationContext;

function request(method: "GET" | "POST", query = "", body?: unknown) {
  return new HttpRequest({
    method,
    url: `https://example.com/api/categories${query}`,
    body: body === undefined ? undefined : { string: JSON.stringify(body) }
  });
}

function handlersFor(database: TestDatabase, userId = ownerId) {
  const metrics = { transactions: 0, authentications: 0 };
  const handlers = createCategoryHandlers({
    authenticate: async () => {
      metrics.authentications++;
      return identity(userId);
    },
    transaction: async (operation) => {
      metrics.transactions++;
      return operation(database.client);
    }
  });
  return { ...handlers, metrics };
}

describe("category HTTP handlers", () => {
  it("returns the exact GET, POST and import response envelopes", async () => {
    const database = catalogDatabase();
    const handlers = handlersFor(database, editorId);
    const get = await handlers.categories(request("GET", `?ledgerId=${sharedId}`), context);
    assert.equal(get.status, 200);
    assert.equal((get.jsonBody as { categories: LedgerCategory[] }).categories.length, 16);
    const post = await handlers.categories(
      request("POST", `?ledgerId=${sharedId}`, { ...custom, name: "  宠物 \t用品  " }), context
    );
    assert.equal(post.status, 200);
    const category = (post.jsonBody as { category: LedgerCategory }).category;
    assert.equal(category.name, "宠物 用品");
    assert.equal(category.isSystem, false);
    assert.deepEqual(Object.keys(post.jsonBody as object), ["category"]);
    const duplicate = await handlers.categories(
      request("POST", `?ledgerId=${sharedId}`, {
        ...custom, name: "宠物\u00a0用品", icon: "changed", sortOrder: 0
      }),
      context
    );
    assert.deepEqual(duplicate, post);
    const imported = await handlers.categoryImport(
      request("POST", `?ledgerId=${sharedId}`, { categories: [custom] }), context
    );
    assert.equal(imported.status, 200);
    assert.equal((imported.jsonBody as { categories: LedgerCategory[] }).categories.length, 18);
    assert.deepEqual(Object.keys(imported.jsonBody as object), ["categories"]);
  });

  it("allows omitted ledgerId but rejects empty or malformed UUIDs before a transaction", async () => {
    const database = catalogDatabase();
    const handlers = handlersFor(database);
    assert.equal((await handlers.categories(request("GET"), context)).status, 200);
    const count = handlers.metrics.transactions;
    for (const ledgerId of ["", "not-a-uuid", `${sharedId}x`, "' or true --"]) {
      for (const operation of ["get", "post", "import"] as const) {
        const query = `?ledgerId=${encodeURIComponent(ledgerId)}`;
        const response = operation === "import"
          ? await handlers.categoryImport(request("POST", query, { categories: [] }), context)
          : await handlers.categories(
            request(operation === "get" ? "GET" : "POST", query, operation === "post" ? custom : undefined),
            context
          );
        assert.deepEqual(response, { status: 400, jsonBody: { error: "invalid_request" } });
      }
    }
    assert.equal(handlers.metrics.transactions, count);
  });

  it("rejects malformed JSON and unknown fields before creating or importing data", async () => {
    const database = catalogDatabase();
    const handlers = handlersFor(database);
    const malformed = new HttpRequest({
      method: "POST", url: "https://example.com/api/categories", body: { string: "{" }
    });
    assert.deepEqual(await handlers.categories(malformed, context), {
      status: 400, jsonBody: { error: "invalid_request" }
    });
    for (const body of [{ ...custom, id: 1 }, { ...custom, isSystem: true }, { ...custom, ledger_id: otherId }]) {
      assert.equal((await handlers.categories(request("POST", "", body), context)).status, 400);
    }
    for (const categories of [[{ ...custom, parentId: 1 }], Array(201).fill(custom)]) {
      assert.equal((await handlers.categoryImport(request("POST", "", { categories }), context)).status, 400);
    }
    assert.equal(handlers.metrics.transactions, 0);
    assert.equal(database.calls.length, 0);
  });

  it("returns 401 before validation or database access when authentication fails", async () => {
    const handlers = createCategoryHandlers({
      authenticate: async () => { throw new Error("Authentication rejected"); },
      transaction: async () => { assert.fail("Unauthorized transaction"); }
    });
    for (const handler of [handlers.categories, handlers.categoryImport]) {
      assert.deepEqual(await handler(request("POST", "?ledgerId=invalid", {}), context), {
        status: 401, jsonBody: { error: "unauthorized" }
      });
    }
  });

  it("returns 403 for viewer additions, including an empty import, but permits GET", async () => {
    const database = catalogDatabase();
    const handlers = handlersFor(database, viewerId);
    const query = `?ledgerId=${sharedId}`;
    assert.deepEqual(await handlers.categories(request("POST", query, custom), context), {
      status: 403, jsonBody: { error: "forbidden" }
    });
    assert.deepEqual(await handlers.categoryImport(request("POST", query, { categories: [] }), context), {
      status: 403, jsonBody: { error: "forbidden" }
    });
    assert.equal(categoryCalls(database).length, 0);
    assert.equal((await handlers.categories(request("GET", query), context)).status, 200);
  });

  it("does not disclose database failures in the HTTP response", async () => {
    const handlers = createCategoryHandlers({
      authenticate: async () => identity(),
      transaction: async () => { throw new Error("Internal database details"); }
    });
    assert.deepEqual(await handlers.categories(request("GET"), context), {
      status: 500, jsonBody: { error: "internal_error" }
    });
  });
});

describe("ledger category migration", () => {
  it("is non-destructive and rerunnable, using scoped uniqueness and safe generated IDs", async () => {
    const sql = await readFile(new URL("../migrations/007_ledger_categories.sql", import.meta.url), "utf8");
    assert.match(sql, /create table if not exists ledger_category/i);
    assert.match(sql, /id integer generated always as identity primary key check \(id > 0\)/i);
    assert.match(sql, /ledger_id uuid not null references family_ledger\(id\) on delete cascade/i);
    assert.match(sql, /unique \(ledger_id, type, name\)/i);
    assert.match(sql, /is_system boolean not null default false/i);
    assert.match(sql, /sort_order between 0 and 1000000/i);
    assert.match(sql, /create index if not exists ledger_transaction_active_categories/i);
    assert.match(sql, /on ledger_transaction \(ledger_id\)\s+where deleted_at is null/i);
    assert.doesNotMatch(sql, /\b(drop|truncate|update|insert)\b|delete from/i);
  });
});
