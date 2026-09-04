import {
  BrowserCacheLocation,
  EventType,
  InteractionRequiredAuthError,
  PublicClientApplication,
  type AccountInfo,
  type Configuration,
} from '@azure/msal-browser'
import { apiConfig, configError } from './config'

export function createMsalConfiguration(origin: string, clientId = apiConfig.clientId, authority = apiConfig.authority): Configuration {
  const authorityHost = authority ? new URL(authority).host : ''
  return {
    auth: {
      clientId,
      authority,
      knownAuthorities: authorityHost ? [authorityHost] : [],
      redirectUri: origin,
      postLogoutRedirectUri: origin,
    },
    cache: {
      cacheLocation: BrowserCacheLocation.SessionStorage,
    },
  }
}

export const msalInstance = configError
  ? null
  : typeof window === 'undefined'
    ? null
    : new PublicClientApplication(createMsalConfiguration(window.location.origin))

export async function initializeAuth() {
  if (!msalInstance) return
  await msalInstance.initialize()
  const result = await msalInstance.handleRedirectPromise()
  const account = result?.account ?? msalInstance.getAllAccounts()[0]
  if (account) msalInstance.setActiveAccount(account)
  msalInstance.addEventCallback((event) => {
    if (event.eventType === EventType.LOGIN_SUCCESS && event.payload && 'account' in event.payload) {
      const next = (event.payload as { account?: AccountInfo }).account
      if (next) msalInstance.setActiveAccount(next)
    }
  })
}

export async function login() {
  if (!msalInstance) throw new Error(configError || '登录未配置')
  await msalInstance.loginRedirect({
    scopes: ['openid', 'profile', 'email', apiConfig.apiScope],
  })
}

export async function getAccessToken(account: AccountInfo): Promise<string> {
  if (!msalInstance) throw new Error('登录未配置')
  try {
    const response = await msalInstance.acquireTokenSilent({ account, scopes: [apiConfig.apiScope] })
    return response.accessToken
  } catch (error) {
    if (error instanceof InteractionRequiredAuthError) {
      await msalInstance.acquireTokenRedirect({ account, scopes: [apiConfig.apiScope] })
    }
    throw error
  }
}

export async function logout(account: AccountInfo) {
  if (!msalInstance) return
  await msalInstance.logoutRedirect({ account })
}
