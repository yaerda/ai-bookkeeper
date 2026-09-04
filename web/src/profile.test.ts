import { describe, expect, it } from 'vitest'
import {
  loadDisplayName,
  normalizeDisplayName,
  saveDisplayName,
  validateDisplayName,
} from './profile'

describe('profile display name', () => {
  it('normalizes and validates display names', () => {
    expect(normalizeDisplayName('  不夜侯  ')).toBe('不夜侯')
    expect(normalizeDisplayName('AI   Bookkeeper')).toBe('AI Bookkeeper')
    expect(validateDisplayName('')).toBe(false)
    expect(validateDisplayName('a'.repeat(50))).toBe(true)
    expect(validateDisplayName('a'.repeat(51))).toBe(false)
  })

  it('stores display names separately for each account', () => {
    const values = new Map<string, string>()
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      value: {
        getItem: (key: string) => values.get(key) ?? null,
        setItem: (key: string, value: string) => values.set(key, value),
        removeItem: (key: string) => values.delete(key),
      },
    })

    saveDisplayName('account-a', ' 不夜侯 ')

    expect(loadDisplayName('account-a')).toBe('不夜侯')
    expect(loadDisplayName('account-b')).toBeNull()
    expect(saveDisplayName('account-a', '')).toBeNull()
    expect(loadDisplayName('account-a')).toBeNull()
  })

  it('keeps the session value when browser storage is unavailable', () => {
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      value: {
        getItem: () => { throw new DOMException('blocked', 'SecurityError') },
        setItem: () => { throw new DOMException('blocked', 'SecurityError') },
        removeItem: () => { throw new DOMException('blocked', 'SecurityError') },
      },
    })

    expect(saveDisplayName('account-a', '不夜侯')).toBe('不夜侯')
    expect(saveDisplayName('account-a', '')).toBeNull()
    expect(loadDisplayName('account-a')).toBeNull()
  })
})
