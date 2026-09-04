import { describe, expect, it } from 'vitest'
import { createMsalConfiguration } from './auth'
import { readRuntimeConfig } from './config'

describe('authentication configuration', () => {
  it('configures CIAM as the only known authority and uses session storage', () => {
    const authority = 'https://aibookkeeper.ciamlogin.com/aibookkeeper.onmicrosoft.com/'
    const config = createMsalConfiguration('https://bookkeeper.example', 'client-id', authority)
    expect(config.auth).toMatchObject({
      clientId: 'client-id',
      authority,
      knownAuthorities: ['aibookkeeper.ciamlogin.com'],
      redirectUri: 'https://bookkeeper.example',
    })
    expect(config.cache?.cacheLocation).toBe('sessionStorage')
  })

  it('requires auth values and supplies only the API base default', () => {
    expect(() => readRuntimeConfig({})).toThrow('VITE_ENTRA_AUTHORITY')
    const config = readRuntimeConfig({
      VITE_ENTRA_CLIENT_ID: 'client',
      VITE_ENTRA_AUTHORITY: 'https://aibookkeeper.ciamlogin.com/aibookkeeper.onmicrosoft.com/',
      VITE_API_SCOPE: 'api://example/sync.readwrite',
    })
    expect(config.baseUrl).toBe('https://aibookkeeper-sync-prod-yaerda.azurewebsites.net/api')
  })
})
