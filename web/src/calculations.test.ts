import { describe, expect, it } from 'vitest'
import {
  calculateMonthlyExpenses,
  calculateMonthlyTrend,
  calculateMonthSummary,
  filterTransactionsByMonth,
  formatCompactAmount,
  groupTransactionsByDate,
} from './calculations'
import type { Transaction } from './types'

function transaction(overrides: Partial<Transaction> = {}): Transaction {
  return {
    syncId: crypto.randomUUID(),
    serverVersion: 1,
    amount: 20,
    type: 'EXPENSE',
    categoryId: null,
    categoryName: '餐饮',
    categoryIcon: null,
    categoryColor: null,
    merchantName: null,
    note: null,
    originalInput: null,
    date: new Date(2026, 8, 4, 12).getTime(),
    createdAt: 1,
    updatedAt: 1,
    source: 'MANUAL',
    status: 'CONFIRMED',
    aiConfidence: null,
    deletedAt: null,
    ...overrides,
  }
}

describe('bookkeeping calculations', () => {
  it('filters by local calendar month and excludes deleted records', () => {
    const records = [
      transaction(),
      transaction({ date: new Date(2026, 7, 31).getTime() }),
      transaction({ deletedAt: Date.now() }),
    ]
    expect(filterTransactionsByMonth(records, '2026-09')).toHaveLength(1)
  })

  it('calculates income, expense, balance, and ordered category shares', () => {
    const summary = calculateMonthSummary([
      transaction({ amount: 30 }),
      transaction({ amount: 70, categoryName: '交通' }),
      transaction({ amount: 180, type: 'INCOME', categoryName: '工资' }),
    ])
    expect(summary).toMatchObject({ income: 180, expense: 100, balance: 80, count: 3 })
    expect(summary.categories[0]).toMatchObject({ name: '交通', amount: 70, percentage: 70 })
  })

  it('groups newest dates first and totals daily expenses', () => {
    const groups = groupTransactionsByDate([
      transaction({ amount: 12 }),
      transaction({ amount: 8 }),
      transaction({ date: new Date(2026, 8, 5).getTime(), type: 'INCOME' }),
    ])
    expect(groups[0].dateKey).toBe('2026-9-5')
    expect(groups[1].expense).toBe(20)
  })

  it('does not move an edited older entry ahead of a newer entry with the same timestamp', () => {
    const groups = groupTransactionsByDate([
      transaction({ syncId: 'older', createdAt: 10, updatedAt: 1000 }),
      transaction({ syncId: 'newer', createdAt: 20, updatedAt: 20 }),
    ])
    expect(groups[0].transactions.map((item) => item.syncId)).toEqual(['newer', 'older'])
  })

  it('totals expenses by month and formats compact amounts', () => {
    const expenses = calculateMonthlyExpenses([
      transaction({ amount: 300 }),
      transaction({ amount: 23_000, date: new Date(2026, 9, 4).getTime() }),
      transaction({ amount: 10_000, type: 'INCOME' }),
    ])
    expect(expenses).toEqual({ '2026-09': 300, '2026-10': 23_000 })
    expect(formatCompactAmount(expenses['2026-09'])).toBe('0.3k')
    expect(formatCompactAmount(expenses['2026-10'])).toBe('2.3w')
    expect(formatCompactAmount(86)).toBe('86')
    expect(formatCompactAmount(0)).toBe('')
  })

  it('builds an income, expense, and balance trend ending in the selected month', () => {
    const trend = calculateMonthlyTrend([
      transaction({ amount: 300 }),
      transaction({ amount: 500, type: 'INCOME' }),
      transaction({ amount: 90, date: new Date(2026, 7, 4).getTime() }),
    ], '2026-09', 3)
    expect(trend.map((point) => point.month)).toEqual(['2026-07', '2026-08', '2026-09'])
    expect(trend[1]).toMatchObject({ income: 0, expense: 90, balance: -90 })
    expect(trend[2]).toMatchObject({ income: 500, expense: 300, balance: 200 })
  })
})
