const STORAGE_PREFIX = 'ai-bookkeeper:profile:'

export function normalizeDisplayName(value: string) {
  return value.trim().replace(/\s+/g, ' ')
}

export function validateDisplayName(value: string) {
  const normalized = normalizeDisplayName(value)
  return normalized.length >= 1 && normalized.length <= 50
}

export function loadDisplayName(accountId: string) {
  try {
    const value = localStorage.getItem(`${STORAGE_PREFIX}${accountId}`)
    return value && validateDisplayName(value) ? normalizeDisplayName(value) : null
  } catch {
    return null
  }
}

export function saveDisplayName(accountId: string, value: string) {
  const normalized = normalizeDisplayName(value)
  if (!normalized) {
    try {
      localStorage.removeItem(`${STORAGE_PREFIX}${accountId}`)
    } catch {
      // The in-memory override still resets the name for this session.
    }
    return null
  }
  if (!validateDisplayName(normalized)) throw new Error('显示名称需要为 1 到 50 个字符')
  try {
    localStorage.setItem(`${STORAGE_PREFIX}${accountId}`, normalized)
  } catch {
    // The in-memory override still applies the name for this session.
  }
  return normalized
}
