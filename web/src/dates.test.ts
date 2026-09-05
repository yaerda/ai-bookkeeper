import { describe, expect, it } from 'vitest'
import { compareTransactionChronology, localDateValue, transactionDateMillis } from './dates'

describe('transaction chronology', () => {
  it('retains the current time rather than forcing a new entry to noon or midnight', () => {
    const now = new Date(2026, 8, 5, 17, 42, 35, 123)
    expect(transactionDateMillis('2026-09-05', now)).toBe(now.getTime())
    expect(transactionDateMillis('2026-09-05', now)).toBeGreaterThan(new Date(2026, 8, 5, 16).getTime())
  })

  it('uses the local calendar day at month and midnight boundaries', () => {
    expect(localDateValue(new Date(2026, 8, 1, 0, 15))).toBe('2026-09-01')
    expect(localDateValue(new Date(2026, 7, 31, 23, 55))).toBe('2026-08-31')
  })

  it('honors a backdated day without losing the recording time', () => {
    const timestamp = transactionDateMillis('2026-08-31', new Date(2026, 8, 5, 17, 42, 35))
    expect(new Date(timestamp)).toEqual(new Date(2026, 7, 31, 17, 42, 35))
  })

  it('preserves the original time when editing amount or changing only the date', () => {
    const original = new Date(2026, 8, 1, 9, 12, 34, 567)
    const now = new Date(2026, 8, 5, 17)
    expect(transactionDateMillis('2026-09-01', now, original)).toBe(original.getTime())
    expect(new Date(transactionDateMillis('2026-09-02', now, original))).toEqual(new Date(2026, 8, 2, 9, 12, 34, 567))
  })

  it('rejects invalid dates instead of rolling them into another month', () => {
    for (const value of ['2026-02-30', '2026-13-01', '2026-00-01', '2026-09-00', '', 'invalid']) {
      expect(() => transactionDateMillis(value, new Date())).toThrow('交易日期无效')
    }
  })

  it('orders by transaction time, then creation time, with a stable ID tie-breaker', () => {
    const records = [
      { syncId: 'a', date: 100, createdAt: 1 },
      { syncId: 'b', date: 100, createdAt: 2 },
      { syncId: 'c', date: 200, createdAt: 0 },
      { syncId: 'd', date: 100, createdAt: 2 },
    ]
    expect(records.sort(compareTransactionChronology).map((item) => item.syncId)).toEqual(['c', 'd', 'b', 'a'])
  })
})
