import type { LedgerCategory, Transaction, TransactionDraft, TransactionType } from './types'

export const CATEGORY_ICONS: Record<string, string> = {
  ic_food: '🍚',
  ic_transport: '🚗',
  ic_shopping: '🛒',
  ic_entertainment: '🎮',
  ic_housing: '🏠',
  ic_medical: '💊',
  ic_education: '📚',
  ic_communication: '📱',
  ic_clothing: '👔',
  ic_other: '📦',
  ic_salary: '💰',
  ic_bonus: '🎁',
  ic_parttime: '💼',
  ic_investment: '📈',
  ic_redpacket: '🧧',
  ic_other_income: '💵',
  ic_fruit: '🍎',
  ic_drink: '🥤',
  ic_pet: '🐱',
  ic_travel: '✈️',
  ic_sport: '⚽',
  ic_beauty: '💄',
  ic_baby: '🍼',
  ic_digital: '💻',
  ic_gift: '🎀',
  ic_repair: '🔧',
  tag: '🏷️',
}

export function categoryIcon(icon?: string | null, name?: string | null) {
  const value = icon?.trim()
  if (value && CATEGORY_ICONS[value]) return CATEGORY_ICONS[value]
  if (value && !value.startsWith('ic_')) return value
  return name?.trim().slice(0, 1) || CATEGORY_ICONS.tag
}

export function categoriesForType(categories: LedgerCategory[], type: TransactionType) {
  return categories.filter((category) => category.type === type)
    .sort((left, right) => left.sortOrder - right.sortOrder || left.id - right.id)
}

export function mergeCategories(previous: LedgerCategory[], incoming: LedgerCategory[]) {
  const categories = new Map(previous.map((category) => [category.id, category]))
  for (const category of incoming) categories.set(category.id, category)
  return [...categories.values()]
}

export function transactionCategoryFields(
  draft: Pick<TransactionDraft, 'type' | 'categoryName'>,
  categories: LedgerCategory[],
  existing?: Transaction,
): Pick<Transaction, 'categoryId' | 'categoryName' | 'categoryIcon' | 'categoryColor'> {
  const selected = categories.find((category) => category.type === draft.type && category.name === draft.categoryName)
  if (selected) {
    return {
      categoryId: selected.id,
      categoryName: selected.name,
      categoryIcon: selected.icon,
      categoryColor: selected.color,
    }
  }
  if (existing && existing.type === draft.type && (existing.categoryName ?? '') === draft.categoryName) {
    return {
      categoryId: existing.categoryId,
      categoryName: existing.categoryName,
      categoryIcon: existing.categoryIcon,
      categoryColor: existing.categoryColor,
    }
  }
  throw new Error('请选择当前账本中的分类')
}
