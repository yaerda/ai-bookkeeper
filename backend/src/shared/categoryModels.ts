import { z } from "zod";

const postgresText = z.string().regex(/^[^\u0000\uD800-\uDFFF]*$/u);
const nameSchema = postgresText
  .transform((value) => value.trim().replace(/\s+/gu, " "))
  .pipe(z.string().min(1).max(100));
const typeSchema = z.enum(["EXPENSE", "INCOME"]);
const iconSchema = postgresText.trim().min(1).max(64);
const colorSchema = z.string().regex(/^#[0-9a-fA-F]{6}$/);

export const createCategorySchema = z.object({
  name: nameSchema,
  type: typeSchema,
  icon: iconSchema,
  color: colorSchema,
  sortOrder: z.number().int().min(0).max(1_000_000).default(1000)
}).strict();

export const importCategoriesSchema = z.object({
  categories: z.array(createCategorySchema).max(200)
}).strict();

export type CategoryInput = z.infer<typeof createCategorySchema>;

export interface LedgerCategory extends CategoryInput {
  id: number;
  isSystem: boolean;
}

export const DEFAULT_CATEGORIES: ReadonlyArray<Omit<LedgerCategory, "id">> = [
  { name: "餐饮", type: "EXPENSE", icon: "ic_food", color: "#FF5722", sortOrder: 1, isSystem: true },
  { name: "交通", type: "EXPENSE", icon: "ic_transport", color: "#2196F3", sortOrder: 2, isSystem: true },
  { name: "购物", type: "EXPENSE", icon: "ic_shopping", color: "#E91E63", sortOrder: 3, isSystem: true },
  { name: "娱乐", type: "EXPENSE", icon: "ic_entertainment", color: "#9C27B0", sortOrder: 4, isSystem: true },
  { name: "居住", type: "EXPENSE", icon: "ic_housing", color: "#795548", sortOrder: 5, isSystem: true },
  { name: "医疗", type: "EXPENSE", icon: "ic_medical", color: "#F44336", sortOrder: 6, isSystem: true },
  { name: "教育", type: "EXPENSE", icon: "ic_education", color: "#3F51B5", sortOrder: 7, isSystem: true },
  { name: "通讯", type: "EXPENSE", icon: "ic_communication", color: "#00BCD4", sortOrder: 8, isSystem: true },
  { name: "服饰", type: "EXPENSE", icon: "ic_clothing", color: "#FF9800", sortOrder: 9, isSystem: true },
  { name: "其他", type: "EXPENSE", icon: "ic_other", color: "#607D8B", sortOrder: 10, isSystem: true },
  { name: "工资", type: "INCOME", icon: "ic_salary", color: "#4CAF50", sortOrder: 1, isSystem: true },
  { name: "奖金", type: "INCOME", icon: "ic_bonus", color: "#8BC34A", sortOrder: 2, isSystem: true },
  { name: "兼职", type: "INCOME", icon: "ic_parttime", color: "#CDDC39", sortOrder: 3, isSystem: true },
  { name: "理财", type: "INCOME", icon: "ic_investment", color: "#009688", sortOrder: 4, isSystem: true },
  { name: "红包", type: "INCOME", icon: "ic_redpacket", color: "#F44336", sortOrder: 5, isSystem: true },
  { name: "其他", type: "INCOME", icon: "ic_other_income", color: "#607D8B", sortOrder: 6, isSystem: true }
];

// Match JavaScript's whitespace normalization when grouping legacy names in SQL.
export const CATEGORY_WHITESPACE =
  "\t\n\v\f\r \u00a0\u1680\u2000\u2001\u2002\u2003\u2004\u2005" +
  "\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000\ufeff";
export const LEGACY_PLACEHOLDER_ICON_PATTERN = "^[.·•●⋅∙… ]+$|^↑$";

const legacyIdentitySchema = z.object({ name: nameSchema, type: typeSchema });
const placeholderIcon = new RegExp(LEGACY_PLACEHOLDER_ICON_PATTERN, "u");

export function legacyCategoryInput(value: {
  name: unknown;
  type: unknown;
  icon: unknown;
  color: unknown;
}): CategoryInput | undefined {
  const identity = legacyIdentitySchema.safeParse(value);
  if (!identity.success) return undefined;

  const icon = iconSchema.safeParse(value.icon);
  const color = colorSchema.safeParse(
    typeof value.color === "string" ? value.color.trim() : value.color
  );
  return {
    ...identity.data,
    icon: icon.success && !placeholderIcon.test(icon.data) ? icon.data : "tag",
    color: color.success ? color.data : "#607D8B",
    sortOrder: 1000
  };
}
