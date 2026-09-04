import { describe, expect, it } from 'vitest'
import {
  createPasscode,
  DEFAULT_PRIVACY_SETTINGS,
  loadPrivacySettings,
  savePrivacySettings,
  validatePasscode,
  verifyPasscode,
} from './privacy'

describe('privacy settings', () => {
  it('validates useful passcode lengths', () => {
    expect(validatePasscode('123')).toBe(false)
    expect(validatePasscode('1234')).toBe(true)
    expect(validatePasscode('a'.repeat(64))).toBe(true)
    expect(validatePasscode('a'.repeat(65))).toBe(false)
  })

  it('hashes and verifies passcodes without storing plaintext', async () => {
    const credentials = await createPasscode('我的口令')
    const settings = { ...DEFAULT_PRIVACY_SETTINGS, ...credentials, requireForIncome: true }

    expect(credentials.passcodeHash).not.toContain('我的口令')
    await expect(verifyPasscode('我的口令', settings)).resolves.toBe(true)
    await expect(verifyPasscode('错误口令', settings)).resolves.toBe(false)
  })

  it('keeps settings isolated by account', async () => {
    const values = new Map<string, string>()
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      value: {
        getItem: (key: string) => values.get(key) ?? null,
        setItem: (key: string, value: string) => values.set(key, value),
        removeItem: (key: string) => values.delete(key),
      },
    })
    const credentials = await createPasscode('1234')
    const settings = { ...DEFAULT_PRIVACY_SETTINGS, ...credentials, requireOnLogin: true }

    savePrivacySettings('account-a', settings)

    expect(loadPrivacySettings('account-a')).toEqual(settings)
    expect(loadPrivacySettings('account-b')).toEqual(DEFAULT_PRIVACY_SETTINGS)
  })
})
