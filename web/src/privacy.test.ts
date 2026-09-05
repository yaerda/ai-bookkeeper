import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  DEFAULT_PRIVACY_SETTINGS,
  loadAccountPrivacy,
  loadLegacyPrivacySettings,
  parsePrivacySettings,
  privacyErrorMessage,
  validatePasscode,
} from './privacy'

const legacy = { passcodeHash: 'a'.repeat(64), salt: 'b'.repeat(32), requireOnLogin: true, requireForIncome: true }
const enabled = { initialized: true, hasPasscode: true, requireOnLogin: true, requireForIncome: true, version: 1 }
let storage: Map<string, string>

beforeEach(() => {
  storage = new Map()
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => storage.set(key, value),
    removeItem: (key: string) => storage.delete(key),
  })
})
afterEach(() => vi.unstubAllGlobals())

describe('account privacy settings', () => {
  it('validates passcode lengths without trimming user input', () => {
    expect(validatePasscode('123')).toBe(false)
    expect(validatePasscode('1234')).toBe(true)
    expect(validatePasscode('a'.repeat(64))).toBe(true)
    expect(validatePasscode('a'.repeat(65))).toBe(false)
  })

  it('shows localized errors for passcode failures, lockouts and remote changes', () => {
    expect(privacyErrorMessage({ status: 403 })).toBe('口令不正确')
    expect(privacyErrorMessage({ status: 429 })).toContain('五分钟')
    expect(privacyErrorMessage({ status: 409 })).toContain('其他设备')
    expect(privacyErrorMessage({ status: 503 })).toContain('暂时不可用')
  })

  it('migrates only the current account legacy verifier and removes it after success', async () => {
    storage.set('ai-bookkeeper:privacy:account-a', JSON.stringify(legacy))
    storage.set('ai-bookkeeper:privacy:account-b', JSON.stringify(legacy))
    const migrate = vi.fn(async () => enabled)
    const api = { getPrivacySettings: async () => DEFAULT_PRIVACY_SETTINGS, migratePrivacySettings: migrate }

    await expect(loadAccountPrivacy(api, 'account-a')).resolves.toEqual(enabled)
    expect(migrate).toHaveBeenCalledWith(legacy)
    expect(loadLegacyPrivacySettings('account-a')).toBeNull()
    expect(loadLegacyPrivacySettings('account-b')).toEqual(legacy)
  })

  it('uses cloud settings on a browser without local data', async () => {
    const migrate = vi.fn()
    const api = { getPrivacySettings: async () => enabled, migratePrivacySettings: migrate }
    await expect(loadAccountPrivacy(api, 'new-browser')).resolves.toEqual(enabled)
    expect(migrate).not.toHaveBeenCalled()
  })

  it('never resurrects a stale local passcode after it was cleared on another device', async () => {
    storage.set('ai-bookkeeper:privacy:account-a', JSON.stringify(legacy))
    const cleared = { ...DEFAULT_PRIVACY_SETTINGS, initialized: true, version: 5 }
    const migrate = vi.fn()
    await expect(loadAccountPrivacy({
      getPrivacySettings: async () => cleared,
      migratePrivacySettings: migrate,
    }, 'account-a')).resolves.toEqual(cleared)
    expect(migrate).not.toHaveBeenCalled()
    expect(storage.has('ai-bookkeeper:privacy:account-a')).toBe(false)
  })

  it('does not initialize an empty account merely by visiting from a new browser', async () => {
    const migrate = vi.fn()
    await expect(loadAccountPrivacy({
      getPrivacySettings: async () => DEFAULT_PRIVACY_SETTINGS,
      migratePrivacySettings: migrate,
    }, 'new-browser')).resolves.toEqual(DEFAULT_PRIVACY_SETTINGS)
    expect(migrate).not.toHaveBeenCalled()
  })

  it('keeps legacy data on failed migration and does not fall back to unlocked defaults', async () => {
    storage.set('ai-bookkeeper:privacy:account-a', JSON.stringify(legacy))
    await expect(loadAccountPrivacy({
      getPrivacySettings: async () => DEFAULT_PRIVACY_SETTINGS,
      migratePrivacySettings: async () => { throw new Error('service unavailable') },
    }, 'account-a')).rejects.toThrow('service unavailable')
    expect(loadLegacyPrivacySettings('account-a')).toEqual(legacy)
  })

  it('propagates cloud failure instead of using a browser-local override', async () => {
    storage.set('ai-bookkeeper:privacy:account-a', JSON.stringify(legacy))
    const migrate = vi.fn()
    await expect(loadAccountPrivacy({
      getPrivacySettings: async () => { throw new Error('offline') },
      migratePrivacySettings: migrate,
    }, 'account-a')).rejects.toThrow('offline')
    expect(migrate).not.toHaveBeenCalled()
  })

  it('rejects malformed or contradictory privacy responses', () => {
    expect(parsePrivacySettings(enabled)).toEqual(enabled)
    expect(parsePrivacySettings(DEFAULT_PRIVACY_SETTINGS)).toEqual(DEFAULT_PRIVACY_SETTINGS)
    for (const invalid of [null, {}, { ...enabled, version: -1 }, { ...enabled, hasPasscode: false }, { ...enabled, initialized: false }, { ...enabled, version: 1.5 }]) {
      expect(() => parsePrivacySettings(invalid)).toThrow('隐私设置响应无效')
    }
  })

  it('does not retain credentials accidentally included in a settings response', () => {
    expect(parsePrivacySettings({ ...enabled, ...legacy })).toEqual(enabled)
  })
})
