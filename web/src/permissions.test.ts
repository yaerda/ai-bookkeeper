import { describe, expect, it } from 'vitest'
import { assertCanManageMembers, assertCanPush, canEditLedger, canManageMembers, getSyncLedgerId } from './permissions'

describe('ledger permissions', () => {
  it('allows owners and editors to edit but never viewers', () => {
    expect(canEditLedger('OWNER')).toBe(true)
    expect(canEditLedger('EDITOR')).toBe(true)
    expect(canEditLedger('VIEWER')).toBe(false)
    expect(() => assertCanPush('VIEWER')).toThrow('VIEWER_PERMISSION_DENIED')
  })

  it('reserves member management for owners', () => {
    expect(canManageMembers('OWNER')).toBe(true)
    expect(canManageMembers('EDITOR')).toBe(false)
    expect(() => assertCanManageMembers('EDITOR')).toThrow('OWNER_PERMISSION_REQUIRED')
  })

  it('uses explicit IDs for every selected ledger', () => {
    expect(getSyncLedgerId({ id: 'family-1', mode: 'FAMILY' })).toBe('family-1')
    expect(getSyncLedgerId({ id: 'personal-1', mode: 'PERSONAL' })).toBe('personal-1')
    expect(getSyncLedgerId(null)).toBeNull()
  })
})
