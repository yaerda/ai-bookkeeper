import { assertCanManageMembers, assertCanPush } from './permissions'
import type { FamilyInvitation, FamilyLedger, FamilyMember, LedgerMode, LedgerRole, Transaction } from './types'

export class ApiError extends Error {
  readonly status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

type TokenProvider = () => Promise<string>
type RoleProvider = () => LedgerRole | undefined
export interface PullResult {
  transactions: Transaction[]
  cursor: number
}

export class BookkeeperApi {
  private readonly baseUrl: string
  private readonly tokenProvider: TokenProvider
  private readonly roleProvider: RoleProvider

  constructor(
    baseUrl: string,
    tokenProvider: TokenProvider,
    roleProvider: RoleProvider = () => undefined,
  ) {
    this.baseUrl = baseUrl
    this.tokenProvider = tokenProvider
    this.roleProvider = roleProvider
  }

  private url(path: string, ledgerId?: string | null) {
    const url = new URL(`${this.baseUrl.replace(/\/+$/, '')}/${path.replace(/^\/+/, '')}`)
    if (ledgerId) url.searchParams.set('ledgerId', ledgerId)
    return url.toString()
  }

  private async request<T>(path: string, init?: RequestInit, ledgerId?: string | null): Promise<T> {
    const token = await this.tokenProvider()
    const response = await fetch(this.url(path, ledgerId), {
      ...init,
      headers: {
        Accept: 'application/json',
        Authorization: `Bearer ${token}`,
        ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
        ...init?.headers,
      },
    })
    if (!response.ok) {
      let detail = ''
      try {
        const body = await response.json() as { message?: string; error?: string }
        detail = body.message || body.error || ''
      } catch {
        detail = await response.text().catch(() => '')
      }
      throw new ApiError(detail || `请求失败 (${response.status})`, response.status)
    }
    if (response.status === 204) return undefined as T
    return response.json() as Promise<T>
  }

  async pull(ledgerId?: string | null, cursor = 0): Promise<PullResult> {
    const response = await this.request<{ transactions?: Transaction[]; cursor?: number; nextCursor?: number } | Transaction[]>(`sync/pull?cursor=${encodeURIComponent(cursor)}&limit=500`, undefined, ledgerId)
    if (Array.isArray(response)) return { transactions: response, cursor }
    return {
      transactions: response.transactions ?? [],
      cursor: response.nextCursor ?? response.cursor ?? cursor,
    }
  }

  async push(transactions: Transaction[], ledgerId?: string | null): Promise<void> {
    assertCanPush(this.roleProvider())
    await this.request('sync/push', { method: 'POST', body: JSON.stringify({ transactions }) }, ledgerId)
  }

  getLedgers() {
    return this.request<{ ledgers: FamilyLedger[]; invitations: FamilyInvitation[] }>('family/ledgers')
  }

  createLedger(name: string, mode: LedgerMode) {
    return this.request<{ id: string; name: string; mode: LedgerMode }>(
      'family/ledgers',
      {
        method: 'POST',
        body: JSON.stringify({ name: name.trim(), mode }),
      },
    )
  }

  deleteLedger(ledgerId: string) {
    return this.request<{ action: 'DELETED' | 'LEFT' }>(
      `family/ledgers/${encodeURIComponent(ledgerId)}`,
      { method: 'DELETE' },
    )
  }

  async getMembers(ledgerId?: string | null): Promise<FamilyMember[]> {
    const response = await this.request<{ members?: FamilyMember[] } | FamilyMember[]>('family/members', undefined, ledgerId)
    return Array.isArray(response) ? response : response.members ?? []
  }

  async inviteMember(email: string, role: Exclude<LedgerRole, 'OWNER'>, ledgerId?: string | null) {
    assertCanManageMembers(this.roleProvider())
    return this.request('family/invitations', { method: 'POST', body: JSON.stringify({ email, role }) }, ledgerId)
  }

  acceptInvitation(id: string) {
    return this.request(`family/invitations/${encodeURIComponent(id)}/accept`, { method: 'POST' })
  }

  async updateLedgerSettings(mode: LedgerMode, name?: string, ledgerId?: string | null) {
    assertCanManageMembers(this.roleProvider())
    return this.request(
      'family/settings',
      { method: 'PATCH', body: JSON.stringify({ mode, ...(name?.trim() ? { name: name.trim() } : {}) }) },
      ledgerId,
    )
  }

  async updateMember(memberId: string, role: Exclude<LedgerRole, 'OWNER'>, ledgerId?: string | null) {
    assertCanManageMembers(this.roleProvider())
    return this.request(`family/members/${encodeURIComponent(memberId)}`, { method: 'PATCH', body: JSON.stringify({ role }) }, ledgerId)
  }

  async removeMember(memberId: string, ledgerId?: string | null) {
    assertCanManageMembers(this.roleProvider())
    return this.request(`family/members/${encodeURIComponent(memberId)}`, { method: 'DELETE' }, ledgerId)
  }
}
