import type { Transaction } from './types'
import { compareTransactionChronology } from './dates'

export interface CategorySummary {
  name: string
  amount: number
  percentage: number
}

export interface MonthlyTrendPoint {
  month: string
  label: string
  income: number
  expense: number
  balance: number
}

export function filterTransactionsByMonth(transactions: Transaction[], month: string): Transaction[] {
  const [year, monthNumber] = month.split('-').map(Number)
  if (!year || !monthNumber) return []
  return transactions.filter((item) => {
    const date = new Date(item.date)
    return item.deletedAt == null && date.getFullYear() === year && date.getMonth() === monthNumber - 1
  })
}

export function calculateMonthSummary(transactions: Transaction[]) {
  let income = 0
  let expense = 0
  const categories = new Map<string, number>()
  for (const item of transactions) {
    if (item.deletedAt != null) continue
    if (item.type === 'INCOME') income += item.amount
    else {
      expense += item.amount
      const name = item.categoryName || '未分类'
      categories.set(name, (categories.get(name) ?? 0) + item.amount)
    }
  }
  const categoryList: CategorySummary[] = [...categories.entries()]
    .map(([name, amount]) => ({ name, amount, percentage: expense ? amount / expense * 100 : 0 }))
    .sort((a, b) => b.amount - a.amount)
  return { income, expense, balance: income - expense, count: transactions.length, categories: categoryList }
}

export function calculateMonthlyExpenses(transactions: Transaction[]): Record<string, number> {
  const expenses: Record<string, number> = {}
  for (const item of transactions) {
    if (item.deletedAt != null || item.type !== 'EXPENSE') continue
    const date = new Date(item.date)
    const key = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`
    expenses[key] = (expenses[key] ?? 0) + item.amount
  }
  return expenses
}

export function calculateMonthlyTrend(
  transactions: Transaction[],
  endMonth: string,
  count = 12,
): MonthlyTrendPoint[] {
  const [year, monthNumber] = endMonth.split('-').map(Number)
  if (!year || !monthNumber || count < 1) return []
  const points = Array.from({ length: count }, (_, index) => {
    const date = new Date(year, monthNumber - count + index, 1)
    return {
      month: `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`,
      label: `${date.getMonth() + 1}月`,
      income: 0,
      expense: 0,
      balance: 0,
    }
  })
  const byMonth = new Map(points.map((point) => [point.month, point]))
  for (const item of transactions) {
    if (item.deletedAt != null) continue
    const date = new Date(item.date)
    const key = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`
    const point = byMonth.get(key)
    if (!point) continue
    if (item.type === 'INCOME') point.income += item.amount
    else point.expense += item.amount
    point.balance = point.income - point.expense
  }
  return points
}

export function formatCompactAmount(amount: number): string {
  if (amount <= 0) return ''
  const compact = (value: number) => Number(value.toPrecision(2)).toString()
  if (amount >= 10_000) return `${compact(amount / 10_000)}w`
  if (amount >= 100) return `${compact(amount / 1_000)}k`
  return compact(amount)
}

export function groupTransactionsByDate(transactions: Transaction[]) {
  const sorted = [...transactions].sort(compareTransactionChronology)
  const groups = new Map<string, { dateKey: string; timestamp: number; expense: number; transactions: Transaction[] }>()
  for (const item of sorted) {
    const date = new Date(item.date)
    const key = `${date.getFullYear()}-${date.getMonth() + 1}-${date.getDate()}`
    const group = groups.get(key) ?? { dateKey: key, timestamp: item.date, expense: 0, transactions: [] }
    group.transactions.push(item)
    if (item.type === 'EXPENSE') group.expense += item.amount
    groups.set(key, group)
  }
  return [...groups.values()]
}
