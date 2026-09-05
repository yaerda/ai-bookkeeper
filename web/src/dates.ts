import type { Transaction } from './types'

export function localDateValue(date: Date) {
  if (!Number.isFinite(date.getTime())) throw new Error('交易日期无效')
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

export function transactionDateMillis(day: string, now: Date, existing?: Date) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) throw new Error('交易日期无效')
  if (existing && localDateValue(existing) === day) return existing.getTime()
  const [year, month, date] = day.split('-').map(Number)
  const timestamp = new Date(existing ?? now)
  timestamp.setFullYear(year, month - 1, date)
  if (localDateValue(timestamp) !== day) throw new Error('交易日期无效')
  return timestamp.getTime()
}

export function compareTransactionChronology(
  left: Pick<Transaction, 'date' | 'createdAt' | 'syncId'>,
  right: Pick<Transaction, 'date' | 'createdAt' | 'syncId'>,
) {
  return right.date - left.date || right.createdAt - left.createdAt || right.syncId.localeCompare(left.syncId)
}
