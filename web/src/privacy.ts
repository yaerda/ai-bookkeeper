export interface PrivacySettings {
  initialized: boolean
  hasPasscode: boolean
  requireOnLogin: boolean
  requireForIncome: boolean
  version: number
}

export interface PrivacySettingsUpdate {
  version: number
  currentPasscode?: string
  newPasscode?: string
  clearPasscode?: boolean
  requireOnLogin: boolean
  requireForIncome: boolean
}

export interface LegacyPrivacySettings {
  passcodeHash: string
  salt: string
  requireOnLogin: boolean
  requireForIncome: boolean
}

export const DEFAULT_PRIVACY_SETTINGS: PrivacySettings = {
  initialized: false,
  hasPasscode: false,
  requireOnLogin: false,
  requireForIncome: false,
  version: 0,
}

const STORAGE_PREFIX = 'ai-bookkeeper:privacy:'

export function validatePasscode(passcode: string) {
  return passcode.length >= 4 && passcode.length <= 64
}

export function privacyErrorMessage(reason: unknown) {
  if (reason && typeof reason === 'object' && 'status' in reason) {
    switch (reason.status) {
      case 400: return '隐私设置无效，请检查口令和开关后重试'
      case 401: return '登录已过期，请退出后重新登录'
      case 403: return '口令不正确'
      case 409: return '账号隐私设置已在其他设备上更改，请重新验证'
      case 429: return '口令连续错误次数过多，请在五分钟后重试'
    }
    if (typeof reason.status === 'number' && reason.status >= 500) return '账号隐私服务暂时不可用，请稍后重试'
  }
  return reason instanceof Error ? reason.message : '账号隐私操作失败，请重试'
}

export function parsePrivacySettings(value: unknown): PrivacySettings {
  if (!value || typeof value !== 'object'
    || !('initialized' in value) || typeof value.initialized !== 'boolean'
    || !('hasPasscode' in value) || typeof value.hasPasscode !== 'boolean'
    || !('requireOnLogin' in value) || typeof value.requireOnLogin !== 'boolean'
    || !('requireForIncome' in value) || typeof value.requireForIncome !== 'boolean'
    || !('version' in value) || typeof value.version !== 'number'
    || !Number.isSafeInteger(value.version) || value.version < 0
    || (value.initialized ? value.version === 0 : value.version !== 0 || value.hasPasscode)
    || (!value.hasPasscode && (value.requireOnLogin || value.requireForIncome))) {
    throw new Error('账号隐私设置响应无效，请稍后重试')
  }
  return {
    initialized: value.initialized,
    hasPasscode: value.hasPasscode,
    requireOnLogin: value.requireOnLogin,
    requireForIncome: value.requireForIncome,
    version: value.version,
  }
}

export function loadLegacyPrivacySettings(accountId: string): LegacyPrivacySettings | null {
  try {
    const value = localStorage.getItem(`${STORAGE_PREFIX}${accountId}`)
    if (!value) return null
    const parsed: unknown = JSON.parse(value)
    if (!parsed || typeof parsed !== 'object'
      || !('passcodeHash' in parsed) || typeof parsed.passcodeHash !== 'string'
      || !('salt' in parsed) || typeof parsed.salt !== 'string'
      || !/^[0-9a-f]{64}$/i.test(parsed.passcodeHash)
      || !/^[0-9a-f]{32}$/i.test(parsed.salt)) {
      console.warn('旧版本机口令格式无效，将以账号云端设置为准。')
      return null
    }
    return {
      passcodeHash: parsed.passcodeHash,
      salt: parsed.salt,
      requireOnLogin: 'requireOnLogin' in parsed && parsed.requireOnLogin === true,
      requireForIncome: 'requireForIncome' in parsed && parsed.requireForIncome === true,
    }
  } catch (error) {
    if (!(error instanceof SyntaxError) && !(error instanceof DOMException)) throw error
    console.warn('无法读取旧版本机口令，将以账号云端设置为准。')
    return null
  }
}

export function clearLegacyPrivacySettings(accountId: string) {
  try {
    localStorage.removeItem(`${STORAGE_PREFIX}${accountId}`)
  } catch (error) {
    if (!(error instanceof DOMException)) throw error
    console.warn('无法清理旧版本机口令；账号云端设置仍然优先。')
  }
}

interface PrivacyMigrationApi {
  getPrivacySettings(): Promise<PrivacySettings>
  migratePrivacySettings(settings: LegacyPrivacySettings): Promise<PrivacySettings>
}

export async function loadAccountPrivacy(api: PrivacyMigrationApi, accountId: string): Promise<PrivacySettings> {
  let settings = await api.getPrivacySettings()
  if (!settings.initialized) {
    const legacy = loadLegacyPrivacySettings(accountId)
    if (legacy) settings = await api.migratePrivacySettings(legacy)
  }
  if (settings.initialized) clearLegacyPrivacySettings(accountId)
  return settings
}
