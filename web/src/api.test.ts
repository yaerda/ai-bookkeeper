import { afterEach, describe, expect, it, vi } from 'vitest'
import { BookkeeperApi } from './api'
import type { Transaction } from './types'

const sample = {} as Transaction

afterEach(() => vi.unstubAllGlobals())

describe('API authorization and permission boundaries', () => {
  it('blocks VIEWER pushes before requesting a token or calling fetch', async () => {
    const token = vi.fn(async () => 'secret')
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const api = new BookkeeperApi('https://api.example/api', token, () => 'VIEWER')
    await expect(api.push([sample], 'ledger-1')).rejects.toThrow('VIEWER_PERMISSION_DENIED')
    expect(token).not.toHaveBeenCalled()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('adds bearer auth and ledgerId for permitted sync pushes', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)
    const api = new BookkeeperApi('https://api.example/api', async () => 'access-token', () => 'EDITOR')
    await api.push([sample], 'family-ledger')
    expect(fetchMock).toHaveBeenCalledOnce()
    const [url, init] = fetchMock.mock.calls[0]
    expect(String(url)).toContain('/sync/push?ledgerId=family-ledger')
    expect((init as RequestInit).headers).toMatchObject({ Authorization: 'Bearer access-token' })
  })

  it('keeps pull cursors and explicit family ledger IDs in the request', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL) => new Response('{"transactions":[],"nextCursor":42}', { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)
    const api = new BookkeeperApi('https://api.example/api', async () => 'token', () => 'OWNER')
    await expect(api.pull('family-ledger', 17)).resolves.toMatchObject({ transactions: [], cursor: 42 })
    expect(String(fetchMock.mock.calls[0][0])).toContain('/sync/pull?cursor=17&limit=500&ledgerId=family-ledger')
  })

  it('keeps the personal default sync endpoint free of ledgerId', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL) => new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)
    const api = new BookkeeperApi('https://api.example/api', async () => 'token', () => 'OWNER')
    await api.pull(null)
    expect(String(fetchMock.mock.calls[0][0])).toBe('https://api.example/api/sync/pull?cursor=0&limit=500')
  })

  it('creates a named ledger with the selected mode', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => new Response(
      '{"id":"ledger-2","name":"旅行基金","mode":"PERSONAL"}',
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    ))
    vi.stubGlobal('fetch', fetchMock)
    const api = new BookkeeperApi('https://api.example/api', async () => 'token', () => 'OWNER')

    await api.createLedger(' 旅行基金 ', 'PERSONAL')

    const [url, init] = fetchMock.mock.calls[0]
    expect(String(url)).toContain('/family/ledgers')
    expect((init as RequestInit).method).toBe('POST')
    expect(JSON.parse(String((init as RequestInit).body))).toEqual({
      name: '旅行基金',
      mode: 'PERSONAL',
    })
  })

  it('allows only owners to change ledger mode and sends the settings contract', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)
    const api = new BookkeeperApi('https://api.example/api', async () => 'token', () => 'OWNER')
    await api.updateLedgerSettings('FAMILY', '共同账本', 'ledger-1')
    const [url, init] = fetchMock.mock.calls[0]
    expect(String(url)).toContain('/family/settings?ledgerId=ledger-1')
    expect(JSON.parse(String((init as RequestInit).body))).toEqual({ mode: 'FAMILY', name: '共同账本' })
  })

  it('uses the same delete endpoint for owner deletion and invited-member leave', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => new Response(
      '{"action":"LEFT"}',
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    ))
    vi.stubGlobal('fetch', fetchMock)
    const api = new BookkeeperApi('https://api.example/api', async () => 'token', () => 'EDITOR')

    await expect(api.deleteLedger('shared-ledger')).resolves.toEqual({ action: 'LEFT' })

    const [url, init] = fetchMock.mock.calls[0]
    expect(String(url)).toBe('https://api.example/api/family/ledgers/shared-ledger')
    expect((init as RequestInit).method).toBe('DELETE')
  })

  it('blocks non-owner family administration without network access', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const api = new BookkeeperApi('https://api.example/api', async () => 'token', () => 'EDITOR')
    await expect(api.inviteMember('member@example.com', 'VIEWER')).rejects.toThrow('OWNER_PERMISSION_REQUIRED')
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
