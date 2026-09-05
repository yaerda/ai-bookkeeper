import type { PoolClient } from "pg";
import type { AuthenticatedUser } from "./auth.js";
import {
  CATEGORY_WHITESPACE,
  DEFAULT_CATEGORIES,
  LEGACY_PLACEHOLDER_ICON_PATTERN,
  legacyCategoryInput,
  type CategoryInput,
  type LedgerCategory
} from "./categoryModels.js";
import { resolveLedgerAccess } from "./ledgerAccess.js";

interface CategoryRow {
  id: number;
  name: string;
  type: LedgerCategory["type"];
  icon: string;
  color: string;
  sort_order: number;
  is_system: boolean;
}

interface LegacyCategoryRow {
  name: string;
  type: string;
  icon: string | null;
  color: string | null;
}

function toCategory(row: CategoryRow): LedgerCategory {
  if (!Number.isSafeInteger(row.id) || row.id <= 0) {
    throw new Error("Invalid ledger category ID");
  }
  return {
    id: row.id,
    name: row.name,
    type: row.type,
    icon: row.icon,
    color: row.color,
    sortOrder: row.sort_order,
    isSystem: row.is_system
  };
}

async function insertMissing(
  client: PoolClient,
  ledgerId: string,
  categories: ReadonlyArray<Omit<LedgerCategory, "id">>
): Promise<void> {
  const unique = new Map<string, Omit<LedgerCategory, "id">>();
  for (const category of categories) {
    const key = JSON.stringify([category.type, category.name]);
    if (!unique.has(key)) unique.set(key, category);
  }
  if (unique.size === 0) return;

  await client.query(
    `insert into ledger_category
       (ledger_id, name, type, icon, color, sort_order, is_system)
     select $1, incoming.name, incoming.type, incoming.icon, incoming.color,
            incoming."sortOrder", incoming."isSystem"
       from jsonb_to_recordset($2::jsonb) as incoming(
         name text, type text, icon text, color text,
         "sortOrder" integer, "isSystem" boolean
       )
      where not exists (
        select 1 from ledger_category existing
         where existing.ledger_id = $1
           and existing.type = incoming.type and existing.name = incoming.name
      )
      order by incoming.type, incoming.name
     on conflict (ledger_id, type, name) do nothing`,
    [ledgerId, JSON.stringify([...unique.values()])]
  );
}

async function prepareCatalog(
  client: PoolClient,
  ledgerId: string
): Promise<void> {
  // Seed first so old Web placeholders cannot displace Android's default metadata.
  await insertMissing(client, ledgerId, DEFAULT_CATEGORIES);
  const legacy = await client.query<LegacyCategoryRow>(
    `with legacy as (
       select btrim(regexp_replace(payload->>'categoryName', $2, ' ', 'g')) as name,
              payload->>'type' as type,
              case when jsonb_typeof(payload->'categoryIcon') = 'string'
                   then btrim(payload->>'categoryIcon', $3) end as icon,
              case when jsonb_typeof(payload->'categoryColor') = 'string'
                   then btrim(payload->>'categoryColor', $3) end as color,
              server_version, sync_id
         from ledger_transaction
        where ledger_id = $1 and deleted_at is null
          and jsonb_typeof(payload->'categoryName') = 'string'
          and payload->>'type' in ('EXPENSE', 'INCOME')
     )
     select distinct on (legacy.type, legacy.name)
            legacy.name, legacy.type, legacy.icon, legacy.color
       from legacy
      where char_length(legacy.name) between 1 and 100
        and not exists (
          select 1 from ledger_category existing
           where existing.ledger_id = $1
             and existing.type = legacy.type and existing.name = legacy.name
        )
      order by legacy.type, legacy.name,
               coalesce(char_length(legacy.icon) between 1 and 64
                        and legacy.icon !~ $4, false) desc,
               coalesce(legacy.color ~ '^#[0-9a-fA-F]{6}$', false) desc,
               legacy.server_version desc, legacy.sync_id`,
    [
      ledgerId,
      `[${CATEGORY_WHITESPACE}]+`,
      CATEGORY_WHITESPACE,
      LEGACY_PLACEHOLDER_ICON_PATTERN
    ]
  );
  const missing = legacy.rows
    .map(legacyCategoryInput)
    .filter((category) => category !== undefined)
    .map((category) => ({ ...category, isSystem: false }));
  await insertMissing(client, ledgerId, missing);
}

async function readCatalog(
  client: PoolClient,
  ledgerId: string
): Promise<LedgerCategory[]> {
  const result = await client.query<CategoryRow>(
    `select id, name, type, icon, color, sort_order, is_system
       from ledger_category
      where ledger_id = $1
      order by type, sort_order, name, id`,
    [ledgerId]
  );
  return result.rows.map(toCategory);
}

export async function listCategories(
  client: PoolClient,
  identity: AuthenticatedUser,
  requestedLedgerId?: string
): Promise<{ categories: LedgerCategory[] }> {
  const { ledgerId } = await resolveLedgerAccess(
    client, identity, requestedLedgerId, false
  );
  await prepareCatalog(client, ledgerId);
  return { categories: await readCatalog(client, ledgerId) };
}

export async function createCategory(
  client: PoolClient,
  identity: AuthenticatedUser,
  input: CategoryInput,
  requestedLedgerId?: string
): Promise<{ category: LedgerCategory }> {
  const { ledgerId } = await resolveLedgerAccess(
    client, identity, requestedLedgerId, true
  );
  await prepareCatalog(client, ledgerId);
  await insertMissing(client, ledgerId, [{ ...input, isSystem: false }]);
  const result = await client.query<CategoryRow>(
    `select id, name, type, icon, color, sort_order, is_system
       from ledger_category
      where ledger_id = $1 and type = $2 and name = $3`,
    [ledgerId, input.type, input.name]
  );
  if (!result.rows[0]) throw new Error("Ledger category was not created");
  return { category: toCategory(result.rows[0]) };
}

export async function importCategories(
  client: PoolClient,
  identity: AuthenticatedUser,
  input: CategoryInput[],
  requestedLedgerId?: string
): Promise<{ categories: LedgerCategory[] }> {
  const { ledgerId } = await resolveLedgerAccess(
    client, identity, requestedLedgerId, true
  );
  await prepareCatalog(client, ledgerId);
  await insertMissing(
    client, ledgerId, input.map((category) => ({ ...category, isSystem: false }))
  );
  return { categories: await readCatalog(client, ledgerId) };
}
