import type { FamilyLedger, LedgerRole } from './types'

export function canEditLedger(role?: LedgerRole): boolean {
  return role !== 'VIEWER'
}

export function canManageMembers(role?: LedgerRole): boolean {
  return role === 'OWNER'
}

export function assertCanPush(role?: LedgerRole): void {
  if (!canEditLedger(role)) throw new Error('VIEWER_PERMISSION_DENIED')
}

export function assertCanManageMembers(role?: LedgerRole): void {
  if (!canManageMembers(role)) throw new Error('OWNER_PERMISSION_REQUIRED')
}

export function getSyncLedgerId(ledger?: Pick<FamilyLedger, 'id' | 'mode'> | null): string | null {
  return ledger?.id ?? null
}
