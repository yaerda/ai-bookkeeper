export type TransactionType = 'INCOME' | 'EXPENSE'
export type TransactionSource = 'MANUAL' | 'TEXT_AI' | 'VOICE_AI' | 'PHOTO_AI' | 'AUTO_CAPTURE' | 'NOTIFICATION_QUICK'
export type TransactionStatus = 'CONFIRMED' | 'PENDING'
export type LedgerRole = 'OWNER' | 'EDITOR' | 'VIEWER'
export type LedgerMode = 'PERSONAL' | 'FAMILY'

export interface LedgerCategory {
  id: number
  name: string
  type: TransactionType
  icon: string
  color: string
  sortOrder: number
  isSystem: boolean
}

export interface CategoryDraft {
  name: string
  type: TransactionType
  icon: string
  color: string
  sortOrder?: number
}

export interface Transaction {
  syncId: string
  serverVersion: number
  amount: number
  type: TransactionType
  categoryId: number | null
  categoryName: string | null
  categoryIcon: string | null
  categoryColor: string | null
  merchantName: string | null
  note: string | null
  originalInput: string | null
  date: number
  createdAt: number
  updatedAt: number
  source: TransactionSource
  status: TransactionStatus
  aiConfidence: number | null
  deletedAt: number | null
}

export interface TransactionDraft {
  syncId?: string
  serverVersion?: number
  amount: number
  type: TransactionType
  categoryName: string
  merchantName: string
  note: string
  date: string
}

export interface FamilyLedger {
  id: string
  name: string
  ownerEmail: string
  role: LedgerRole
  mode: LedgerMode
  isDefault?: boolean
}

export interface FamilyInvitation {
  id: string
  ledgerName: string
  inviterEmail: string
  role: Exclude<LedgerRole, 'OWNER'>
}

export interface FamilyMember {
  id: string
  email: string
  displayName?: string
  role: LedgerRole
}
