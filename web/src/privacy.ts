export interface PrivacySettings {
  passcodeHash: string
  salt: string
  requireOnLogin: boolean
  requireForIncome: boolean
}

export const DEFAULT_PRIVACY_SETTINGS: PrivacySettings = {
  passcodeHash: '',
  salt: '',
  requireOnLogin: false,
  requireForIncome: false,
}

const STORAGE_PREFIX = 'ai-bookkeeper:privacy:'

export function loadPrivacySettings(accountId: string): PrivacySettings {
  try {
    const value = localStorage.getItem(`${STORAGE_PREFIX}${accountId}`)
    if (!value) return DEFAULT_PRIVACY_SETTINGS
    const parsed = JSON.parse(value) as Partial<PrivacySettings>
    return {
      passcodeHash: typeof parsed.passcodeHash === 'string' ? parsed.passcodeHash : '',
      salt: typeof parsed.salt === 'string' ? parsed.salt : '',
      requireOnLogin: parsed.requireOnLogin === true,
      requireForIncome: parsed.requireForIncome === true,
    }
  } catch {
    return DEFAULT_PRIVACY_SETTINGS
  }
}

export function savePrivacySettings(accountId: string, settings: PrivacySettings) {
  if (!settings.passcodeHash) {
    localStorage.removeItem(`${STORAGE_PREFIX}${accountId}`)
    return
  }
  localStorage.setItem(`${STORAGE_PREFIX}${accountId}`, JSON.stringify(settings))
}

export function validatePasscode(passcode: string) {
  return passcode.length >= 4 && passcode.length <= 64
}

async function hashPasscode(passcode: string, salt: string) {
  const material = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(passcode),
    'PBKDF2',
    false,
    ['deriveBits'],
  )
  const derived = await crypto.subtle.deriveBits(
    {
      name: 'PBKDF2',
      hash: 'SHA-256',
      salt: new TextEncoder().encode(salt),
      iterations: 120_000,
    },
    material,
    256,
  )
  return Array.from(new Uint8Array(derived), (value) => value.toString(16).padStart(2, '0')).join('')
}

export async function createPasscode(passcode: string) {
  if (!validatePasscode(passcode)) throw new Error('口令长度需要为 4 到 64 个字符')
  const saltBytes = crypto.getRandomValues(new Uint8Array(16))
  const salt = Array.from(saltBytes, (value) => value.toString(16).padStart(2, '0')).join('')
  return { passcodeHash: await hashPasscode(passcode, salt), salt }
}

export async function verifyPasscode(passcode: string, settings: PrivacySettings) {
  if (!settings.passcodeHash || !settings.salt) return false
  return (await hashPasscode(passcode, settings.salt)) === settings.passcodeHash
}
