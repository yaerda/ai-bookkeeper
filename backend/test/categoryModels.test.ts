import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { describe, it } from "node:test";
import {
  CATEGORY_WHITESPACE,
  createCategorySchema,
  DEFAULT_CATEGORIES,
  importCategoriesSchema,
  legacyCategoryInput
} from "../src/shared/categoryModels.js";

const custom = {
  name: "宠物",
  type: "EXPENSE",
  icon: "🐈‍⬛",
  color: "#aB12cD"
};

describe("ledger category models", () => {
  it("exactly matches all sixteen Android defaults without reusing local IDs", async () => {
    const android = await readFile(
      new URL(
        "../../core-data/src/main/java/com/aibookkeeper/core/data/local/PrepopulateCallback.kt",
        import.meta.url
      ),
      "utf8"
    );
    const defaults = [...android.matchAll(
      /\(\d+, '([^']+)', '([^']+)', '(#[0-9A-Fa-f]{6})', '(EXPENSE|INCOME)', NULL, 1, (\d+)\)/g
    )].map(([, name, icon, color, type, sortOrder]) => ({
      name, type, icon, color, sortOrder: Number(sortOrder), isSystem: true
    }));

    assert.equal(defaults.length, 16);
    assert.deepEqual(DEFAULT_CATEGORIES, defaults);
    assert.equal(DEFAULT_CATEGORIES.filter((item) => item.type === "EXPENSE").length, 10);
    assert.equal(DEFAULT_CATEGORIES.filter((item) => item.type === "INCOME").length, 6);
    assert.deepEqual(
      DEFAULT_CATEGORIES.filter((item) => item.name === "其他").map((item) => item.type),
      ["EXPENSE", "INCOME"]
    );
    assert.ok(DEFAULT_CATEGORIES.every((item) => !("id" in item) && !("parentId" in item)));
  });

  it("normalizes Unicode whitespace and preserves trimmed custom emoji", () => {
    assert.deepEqual(
      createCategorySchema.parse({
        ...custom,
        name: " \t宠物\u00a0 \u3000用品\n",
        icon: " \t🐈‍⬛ \n"
      }),
      { ...custom, name: "宠物 用品", sortOrder: 1000 }
    );
    for (const whitespace of CATEGORY_WHITESPACE) {
      assert.equal(
        createCategorySchema.parse({ ...custom, name: `${whitespace}a${whitespace}b${whitespace}` }).name,
        "a b"
      );
    }
  });

  it("accepts boundary lengths, both types and explicit sort-order boundaries", () => {
    for (const sortOrder of [0, 1_000_000]) {
      const value = createCategorySchema.parse({
        name: "n".repeat(100),
        type: "INCOME",
        icon: "i".repeat(64),
        color: "#123AbC",
        sortOrder
      });
      assert.equal(value.name.length, 100);
      assert.equal(value.icon.length, 64);
      assert.equal(value.sortOrder, sortOrder);
    }
  });

  it("rejects invalid names, styles, types and sort orders", () => {
    const invalid = [
      { name: "" }, { name: " \n\t" }, { name: "n".repeat(101) },
      { name: null }, { name: "a\u0000b" }, { name: "\ud800" },
      { icon: "" }, { icon: " \t" }, { icon: "i".repeat(65) },
      { icon: null }, { icon: "\udc00" }, { icon: "\u0000" },
      { color: "#FFF" }, { color: "123456" }, { color: "#GG1234" },
      { color: "#12345678" }, { color: null },
      { type: "expense" }, { type: "TRANSFER" },
      { sortOrder: -1 }, { sortOrder: 1_000_001 }, { sortOrder: 0.5 },
      { sortOrder: "1" }, { sortOrder: null }, { sortOrder: NaN }
    ];
    for (const override of invalid) {
      assert.equal(createCategorySchema.safeParse({ ...custom, ...override }).success, false);
    }
  });

  it("rejects all client-supplied catalog identity and server metadata", () => {
    for (const key of ["id", "isSystem", "ledger_id", "ledgerId", "parentId"]) {
      const input = { ...custom, [key]: "unexpected" };
      assert.equal(createCategorySchema.safeParse(input).success, false, key);
      assert.equal(importCategoriesSchema.safeParse({ categories: [input] }).success, false, key);
    }
  });

  it("accepts only strict import batches of zero through two hundred entries", () => {
    assert.deepEqual(importCategoriesSchema.parse({ categories: [] }), { categories: [] });
    assert.equal(
      importCategoriesSchema.parse({ categories: Array(200).fill(custom) }).categories.length,
      200
    );
    for (const input of [
      { categories: Array(201).fill(custom) },
      { categories: [custom], ledgerId: "injected" },
      { categories: [custom, { ...custom, name: "" }] },
      { categories: null }, { categories: {} }, {}, []
    ]) {
      assert.equal(importCategoriesSchema.safeParse(input).success, false);
    }
  });
});

describe("legacy category sanitization", () => {
  it("retains valid custom names and styles, including compound emoji", () => {
    assert.deepEqual(
      legacyCategoryInput({
        name: "\u3000宠物 \n用品 ",
        type: "EXPENSE",
        icon: " 🐈‍⬛ ",
        color: " #aB12cD "
      }),
      { name: "宠物 用品", type: "EXPENSE", icon: "🐈‍⬛", color: "#aB12cD", sortOrder: 1000 }
    );
  });

  it("falls back on absent, invalid or old Web placeholder styles without throwing", () => {
    for (const icon of [
      null, undefined, "", "   ", "x".repeat(4000),
      "●", "↑", "•", "·", "...", "…", "● ●", "\u0000", "\ud800", 42, {}
    ]) {
      const category = legacyCategoryInput({ ...custom, icon, color: "not-a-color" });
      assert.equal(category?.name, custom.name);
      assert.equal(category?.icon, "tag");
      assert.equal(category?.color, "#607D8B");
    }
    assert.equal(legacyCategoryInput({ ...custom, icon: null })?.color, custom.color);
  });

  it("skips invalid legacy identities rather than truncating or failing a whole catalog", () => {
    for (const override of [
      { name: null }, { name: "" }, { name: "\u00a0\n" },
      { name: "x".repeat(4000) }, { name: "🐈".repeat(100) },
      { name: "\u0000" }, { name: 42 }, { type: "TRANSFER" }, { type: null }
    ]) {
      assert.equal(legacyCategoryInput({ ...custom, ...override }), undefined);
    }
  });
});
