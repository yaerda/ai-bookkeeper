import { describe, expect, it } from 'vitest'
import { categoriesForType, categoryIcon, mergeCategories, transactionCategoryFields } from './categories'
import type { LedgerCategory, Transaction } from './types'

const catalog: LedgerCategory[] = [
  { id: 3, name: '其他', type: 'INCOME', icon: 'ic_other_income', color: '#607D8B', sortOrder: 6, isSystem: true },
  { id: 2, name: '宠物', type: 'EXPENSE', icon: '🐶', color: '#2196F3', sortOrder: 1000, isSystem: false },
  { id: 1, name: '其他', type: 'EXPENSE', icon: 'ic_other', color: '#607D8B', sortOrder: 10, isSystem: true },
]
const existing: Transaction = {
  syncId: 'sync-1', serverVersion: 1, type: 'EXPENSE', amount: 12,
  categoryId: 101, categoryName: '旧分类', categoryIcon: '🍉', categoryColor: '#FF5722',
  merchantName: null, note: null, originalInput: null, date: 1, createdAt: 1, updatedAt: 1,
  source: 'MANUAL', status: 'CONFIRMED', aiConfidence: null, deletedAt: null,
}

describe('ledger category choices', () => {
  it('uses only the supplied ledger catalog, in Android sort order', () => {
    expect(categoriesForType(catalog, 'EXPENSE').map((item) => item.name)).toEqual(['其他', '宠物'])
    expect(categoriesForType([], 'EXPENSE')).toEqual([])
    expect(catalog.map((item) => item.id)).toEqual([3, 2, 1])
  })

  it('renders Android icon keys and custom emoji consistently', () => {
    expect(categoryIcon('ic_education', '教育')).toBe('📚')
    expect(categoryIcon('ic_redpacket', '红包')).toBe('🧧')
    expect(categoryIcon('🐶', '宠物')).toBe('🐶')
    expect(categoryIcon('ic_unknown', '自定义')).toBe('自')
  })

  it('keeps a newly created category when an earlier catalog read finishes late', () => {
    expect(mergeCategories(catalog, [catalog[0]])).toEqual(catalog)
    expect(mergeCategories([catalog[0]], catalog)).toEqual(catalog)
  })

  it('updates the category ID, icon and color when editing the selection', () => {
    expect(transactionCategoryFields({ type: 'EXPENSE', categoryName: '宠物' }, catalog, existing)).toEqual({
      categoryId: 2, categoryName: '宠物', categoryIcon: '🐶', categoryColor: '#2196F3',
    })
  })

  it('distinguishes identically named income and expense categories', () => {
    expect(transactionCategoryFields({ type: 'INCOME', categoryName: '其他' }, catalog, existing)).toMatchObject({
      categoryId: 3, categoryIcon: 'ic_other_income',
    })
  })

  it('preserves an existing historical category absent from the catalog', () => {
    expect(transactionCategoryFields({ type: 'EXPENSE', categoryName: '旧分类' }, catalog, existing)).toEqual({
      categoryId: 101, categoryName: '旧分类', categoryIcon: '🍉', categoryColor: '#FF5722',
    })
  })

  it('never accepts a missing category from a different ledger or transaction type', () => {
    expect(() => transactionCategoryFields({ type: 'EXPENSE', categoryName: '宠物' }, [])).toThrow('当前账本')
    expect(() => transactionCategoryFields({ type: 'INCOME', categoryName: '旧分类' }, catalog, existing)).toThrow('当前账本')
  })
})
