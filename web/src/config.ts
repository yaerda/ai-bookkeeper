const DEFAULT_API_BASE_URL = 'https://aibookkeeper-sync-prod-yaerda.azurewebsites.net/api'

function required(value: string | undefined, name: string) {
  if (!value?.trim()) throw new Error(`缺少环境变量 ${name}`)
  return value.trim()
}

export interface RuntimeConfig {
  clientId: string
  authority: string
  apiScope: string
  baseUrl: string
}

export function readRuntimeConfig(env: Record<string, string | undefined>): RuntimeConfig {
  const authority = required(env.VITE_ENTRA_AUTHORITY, 'VITE_ENTRA_AUTHORITY')
  const parsedAuthority = new URL(authority)
  if (parsedAuthority.protocol !== 'https:') throw new Error('VITE_ENTRA_AUTHORITY 必须使用 HTTPS')
  const baseUrl = (env.VITE_API_BASE_URL || DEFAULT_API_BASE_URL).replace(/\/+$/, '')
  return {
    clientId: required(env.VITE_ENTRA_CLIENT_ID, 'VITE_ENTRA_CLIENT_ID'),
    authority: authority.replace(/\/?$/, '/'),
    apiScope: required(env.VITE_API_SCOPE, 'VITE_API_SCOPE'),
    baseUrl,
  }
}

let runtimeConfig: RuntimeConfig | null = null
let runtimeError = ''
try {
  runtimeConfig = readRuntimeConfig(import.meta.env)
} catch (error) {
  runtimeError = error instanceof Error ? error.message : '登录配置无效'
}

export const apiConfig = runtimeConfig ?? {
  clientId: '',
  authority: '',
  apiScope: '',
  baseUrl: DEFAULT_API_BASE_URL,
}
export const configError = runtimeError
