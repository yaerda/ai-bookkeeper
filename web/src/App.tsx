import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import type { AccountInfo } from '@azure/msal-browser'
import { apiConfig, configError } from './config'
import { getAccessToken, login, logout, msalInstance } from './auth'
import { BookkeeperApi } from './api'
import {
  calculateMonthlyExpenses,
  calculateMonthlyTrend,
  calculateMonthSummary,
  filterTransactionsByMonth,
  formatCompactAmount,
  groupTransactionsByDate,
} from './calculations'
import { canEditLedger, canManageMembers, getSyncLedgerId } from './permissions'
import {
  createPasscode,
  DEFAULT_PRIVACY_SETTINGS,
  loadPrivacySettings,
  savePrivacySettings,
  validatePasscode,
  verifyPasscode,
} from './privacy'
import type { PrivacySettings } from './privacy'
import type {
  FamilyInvitation,
  FamilyLedger,
  FamilyMember,
  LedgerRole,
  Transaction,
  TransactionDraft,
  TransactionType,
} from './types'

const currency = new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' })
const dateTitle = new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'short' })
const GITHUB_URL = 'https://github.com/yaerda/ai-bookkeeper'
const ANDROID_DOWNLOAD_URL = `${GITHUB_URL}/releases/latest/download/ai-bookkeeper-latest.apk`
const DEFAULT_CATEGORIES = {
  EXPENSE: ['餐饮', '交通', '购物', '居住', '娱乐', '医疗', '其他'],
  INCOME: ['工资', '奖金', '理财', '兼职', '其他收入'],
}
const CATEGORY_ICONS: Record<string, string> = {
  ic_food: '🍚',
  ic_transport: '🚗',
  ic_shopping: '🛒',
  ic_entertainment: '🎮',
  ic_housing: '🏠',
  ic_medical: '💊',
  ic_education: '📚',
  ic_communication: '📱',
  ic_clothing: '👔',
  ic_other: '📦',
  ic_salary: '💰',
  ic_bonus: '🎁',
  ic_parttime: '💼',
  ic_investment: '📈',
  ic_redpacket: '🧧',
  ic_other_income: '💵',
  ic_fruit: '🍎',
  ic_drink: '🥤',
  ic_pet: '🐱',
  ic_travel: '✈️',
  ic_sport: '⚽',
  ic_beauty: '💄',
  ic_baby: '🍼',
  ic_digital: '💻',
  ic_gift: '🎀',
  ic_repair: '🔧',
  tag: '🏷️',
}

function monthValue(date = new Date()) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`
}

function shiftMonth(month: string, offset: number) {
  const [year, monthNumber] = month.split('-').map(Number)
  return monthValue(new Date(year, monthNumber - 1 + offset, 1))
}

function monthLabel(month: string) {
  const [year, monthNumber] = month.split('-').map(Number)
  return `${year} 年 ${monthNumber} 月`
}

function categorySymbol(transaction: Transaction) {
  const icon = transaction.categoryIcon?.trim()
  if (icon && CATEGORY_ICONS[icon]) return CATEGORY_ICONS[icon]
  if (icon && !icon.startsWith('ic_')) return icon
  return transaction.categoryName?.trim().slice(0, 1) || (transaction.type === 'INCOME' ? '收' : '支')
}

function makeDraft(transaction?: Transaction): TransactionDraft {
  const now = Date.now()
  return transaction
    ? {
        syncId: transaction.syncId,
        serverVersion: transaction.serverVersion,
        amount: transaction.amount,
        type: transaction.type,
        categoryName: transaction.categoryName ?? '',
        merchantName: transaction.merchantName ?? '',
        note: transaction.note ?? '',
        date: new Date(transaction.date).toISOString().slice(0, 10),
      }
    : {
        amount: 0,
        type: 'EXPENSE',
        categoryName: '餐饮',
        merchantName: '',
        note: '',
        date: new Date(now).toISOString().slice(0, 10),
      }
}

function LoginScreen({ error }: { error?: string }) {
  const [loginError, setLoginError] = useState('')
  const [isSigningIn, setIsSigningIn] = useState(false)

  const handleLogin = async () => {
    setLoginError('')
    setIsSigningIn(true)
    try {
      await login()
    } catch (reason) {
      setLoginError(reason instanceof Error ? reason.message : '登录失败，请重试')
    } finally {
      setIsSigningIn(false)
    }
  }

  return (
    <main className="login-page">
      <section className="login-card">
        <div className="brand-mark" aria-hidden="true">账</div>
        <p className="eyebrow">AI BOOKKEEPER</p>
        <h1>聪明记账，清晰生活</h1>
        <p className="login-copy">安全同步你的收支记录，并在每一笔消费中看见生活的方向。</p>
        {(error || loginError) && <div className="alert error" role="alert">{error || loginError}</div>}
        <button
          className="primary wide"
          onClick={() => void handleLogin()}
          disabled={Boolean(configError) || isSigningIn}
        >
          {isSigningIn ? '正在跳转登录…' : '使用邮箱验证码登录'}
        </button>
        <a className="download-link wide" href={ANDROID_DOWNLOAD_URL}>
          <span aria-hidden="true">↓</span>
          下载 Android 客户端
        </a>
        <p className="privacy-note">登录后才会读取账本数据。你的访问由 Microsoft Entra External ID 保护。</p>
      </section>
    </main>
  )
}

function PasscodePrompt({
  settings,
  title,
  description,
  onSuccess,
  onCancel,
  onLogout,
}: {
  settings: PrivacySettings
  title: string
  description: string
  onSuccess: () => void
  onCancel?: () => void
  onLogout?: () => void
}) {
  const [passcode, setPasscode] = useState('')
  const [error, setError] = useState('')
  const [checking, setChecking] = useState(false)

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    setChecking(true)
    setError('')
    try {
      if (await verifyPasscode(passcode, settings)) onSuccess()
      else setError('口令不正确')
    } finally {
      setChecking(false)
    }
  }

  const content = <section className="passcode-card" role={onCancel ? 'dialog' : undefined} aria-modal={onCancel ? true : undefined}>
    <div className="privacy-shield" aria-hidden="true">●</div>
    <p className="eyebrow">隐私保护</p>
    <h2>{title}</h2>
    <p>{description}</p>
    <form onSubmit={(event) => void submit(event)}>
      <label>隐私口令<input autoFocus type="password" autoComplete="current-password" value={passcode} onChange={(event) => setPasscode(event.target.value)} /></label>
      {error && <div className="field-error" role="alert">{error}</div>}
      <button className="primary wide" disabled={checking || !passcode}>{checking ? '验证中…' : '确认'}</button>
    </form>
    <div className="passcode-secondary-actions">
      {onCancel && <button onClick={onCancel}>取消</button>}
      {onLogout && <button onClick={onLogout}>退出登录</button>}
    </div>
  </section>

  if (!onCancel) return <main className="login-page">{content}</main>
  return <div className="modal-backdrop" onMouseDown={onCancel}><div onMouseDown={(event) => event.stopPropagation()}>{content}</div></div>
}

function PrivacySettingsModal({
  settings,
  onClose,
  onSave,
}: {
  settings: PrivacySettings
  onClose: () => void
  onSave: (settings: PrivacySettings) => void
}) {
  const hasPasscode = Boolean(settings.passcodeHash)
  const [currentPasscode, setCurrentPasscode] = useState('')
  const [newPasscode, setNewPasscode] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [requireOnLogin, setRequireOnLogin] = useState(hasPasscode ? settings.requireOnLogin : true)
  const [requireForIncome, setRequireForIncome] = useState(hasPasscode ? settings.requireForIncome : true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')

  const save = async (event: React.FormEvent) => {
    event.preventDefault()
    setSaving(true)
    setError('')
    try {
      if (hasPasscode && !(await verifyPasscode(currentPasscode, settings))) {
        setError('当前口令不正确')
        return
      }
      const passcode = hasPasscode && !newPasscode ? null : newPasscode
      if (passcode !== null) {
        if (!validatePasscode(passcode)) {
          setError('新口令长度需要为 4 到 64 个字符')
          return
        }
        if (passcode !== confirmation) {
          setError('两次输入的新口令不一致')
          return
        }
      }
      const credentials = passcode === null
        ? { passcodeHash: settings.passcodeHash, salt: settings.salt }
        : await createPasscode(passcode)
      onSave({ ...credentials, requireOnLogin, requireForIncome })
      onClose()
    } finally {
      setSaving(false)
    }
  }

  const clearPasscode = async () => {
    setSaving(true)
    setError('')
    try {
      if (!(await verifyPasscode(currentPasscode, settings))) {
        setError('当前口令不正确')
        return
      }
      onSave(DEFAULT_PRIVACY_SETTINGS)
      onClose()
    } finally {
      setSaving(false)
    }
  }

  return <div className="modal-backdrop" onMouseDown={onClose}>
    <section className="modal privacy-settings-modal" role="dialog" aria-modal="true" aria-labelledby="privacy-settings-title" onMouseDown={(event) => event.stopPropagation()}>
      <div className="modal-header"><div><p className="eyebrow">本机隐私</p><h2 id="privacy-settings-title">隐私口令</h2></div><button className="icon-button" aria-label="关闭" onClick={onClose}>×</button></div>
      <p className="privacy-settings-note">口令只在当前浏览器中加盐保存，不会上传到云端。清除浏览器数据后需要重新设置。</p>
      <form className="privacy-settings-form" onSubmit={(event) => void save(event)}>
        {hasPasscode && <label>当前口令<input autoFocus required type="password" autoComplete="current-password" value={currentPasscode} onChange={(event) => setCurrentPasscode(event.target.value)} /></label>}
        <label>{hasPasscode ? '新口令（不修改请留空）' : '设置口令'}<input autoFocus={!hasPasscode} required={!hasPasscode} type="password" autoComplete="new-password" value={newPasscode} onChange={(event) => setNewPasscode(event.target.value)} /></label>
        {(!hasPasscode || newPasscode) && <label>确认新口令<input required type="password" autoComplete="new-password" value={confirmation} onChange={(event) => setConfirmation(event.target.value)} /></label>}
        <label className="privacy-toggle"><input type="checkbox" checked={requireOnLogin} onChange={(event) => setRequireOnLogin(event.target.checked)} /><span><strong>登录后需要口令解锁</strong><small>Microsoft 登录成功后仍需验证本机口令</small></span></label>
        <label className="privacy-toggle"><input type="checkbox" checked={requireForIncome} onChange={(event) => setRequireForIncome(event.target.checked)} /><span><strong>展示收入时验证口令</strong><small>每次开启 5 分钟展示前验证</small></span></label>
        {error && <div className="field-error" role="alert">{error}</div>}
        <div className="modal-actions">
          {hasPasscode && <button type="button" className="danger-link" disabled={saving || !currentPasscode} onClick={() => void clearPasscode()}>关闭并清除口令</button>}
          <button type="button" className="secondary" onClick={onClose}>取消</button>
          <button className="primary" disabled={saving}>{saving ? '保存中…' : '保存设置'}</button>
        </div>
      </form>
    </section>
  </div>
}

function TransactionModal({
  transaction,
  saving,
  onClose,
  onSave,
}: {
  transaction?: Transaction
  saving: boolean
  onClose: () => void
  onSave: (draft: TransactionDraft) => Promise<void>
}) {
  const [draft, setDraft] = useState(() => makeDraft(transaction))
  const categories = DEFAULT_CATEGORIES[draft.type]

  const update = <K extends keyof TransactionDraft>(key: K, value: TransactionDraft[K]) =>
    setDraft((current) => ({ ...current, [key]: value }))

  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={onClose}>
      <section className="modal" role="dialog" aria-modal="true" aria-labelledby="transaction-title" onMouseDown={(event) => event.stopPropagation()}>
        <div className="modal-header">
          <div>
            <p className="eyebrow">手动记录</p>
            <h2 id="transaction-title">{transaction ? '编辑交易' : '添加交易'}</h2>
          </div>
          <button className="icon-button" aria-label="关闭" onClick={onClose}>×</button>
        </div>
        <div className="type-toggle">
          {(['EXPENSE', 'INCOME'] as TransactionType[]).map((type) => (
            <button
              key={type}
              className={draft.type === type ? 'active' : ''}
              onClick={() => {
                update('type', type)
                update('categoryName', DEFAULT_CATEGORIES[type][0])
              }}
            >
              {type === 'EXPENSE' ? '支出' : '收入'}
            </button>
          ))}
        </div>
        <label className="amount-field">
          <span>金额</span>
          <div><b>¥</b><input autoFocus type="number" min="0.01" step="0.01" value={draft.amount || ''} onChange={(e) => update('amount', Number(e.target.value))} /></div>
        </label>
        <div className="form-grid">
          <label>日期<input type="date" value={draft.date} onChange={(e) => update('date', e.target.value)} /></label>
          <label>分类<select value={draft.categoryName} onChange={(e) => update('categoryName', e.target.value)}>{categories.map((category) => <option key={category}>{category}</option>)}</select></label>
          <label>商户<input maxLength={100} placeholder="例如：社区超市" value={draft.merchantName} onChange={(e) => update('merchantName', e.target.value)} /></label>
          <label>备注<input maxLength={300} placeholder="可选" value={draft.note} onChange={(e) => update('note', e.target.value)} /></label>
        </div>
        <div className="modal-actions">
          <button className="secondary" onClick={onClose}>取消</button>
          <button className="primary" disabled={saving || draft.amount <= 0 || !draft.date} onClick={() => void onSave(draft)}>
            {saving ? '保存中…' : '保存交易'}
          </button>
        </div>
      </section>
    </div>
  )
}

function CreateLedgerModal({
  saving,
  onClose,
  onCreate,
}: {
  saving: boolean
  onClose: () => void
  onCreate: (name: string, mode: FamilyLedger['mode']) => Promise<void>
}) {
  const [name, setName] = useState('')
  const [mode, setMode] = useState<FamilyLedger['mode']>('PERSONAL')
  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={onClose}>
      <section className="modal" role="dialog" aria-modal="true" aria-labelledby="create-ledger-title" onMouseDown={(event) => event.stopPropagation()}>
        <div className="modal-header">
          <div><p className="eyebrow">账本管理</p><h2 id="create-ledger-title">新建账本</h2></div>
          <button className="icon-button" aria-label="关闭" onClick={onClose}>×</button>
        </div>
        <div className="form-grid">
          <label>账本名称<input autoFocus maxLength={100} placeholder="例如：旅行基金" value={name} onChange={(event) => setName(event.target.value)} /></label>
          <label>账本类型<select value={mode} onChange={(event) => setMode(event.target.value as FamilyLedger['mode'])}><option value="PERSONAL">个人账本</option><option value="FAMILY">家庭账本</option></select></label>
        </div>
        <p className="privacy-note">{mode === 'FAMILY' ? '创建后可邀请成员共同查看或记账。' : '仅你本人可访问，之后可随时转为家庭账本。'}</p>
        <div className="modal-actions">
          <button className="secondary" onClick={onClose}>取消</button>
          <button className="primary" disabled={saving || !name.trim()} onClick={() => void onCreate(name.trim(), mode)}>{saving ? '创建中…' : '创建账本'}</button>
        </div>
      </section>
    </div>
  )
}

function LedgerDrawer({
  ledgers,
  selectedLedgerId,
  onClose,
  onSelect,
  onCreate,
  onManage,
}: {
  ledgers: FamilyLedger[]
  selectedLedgerId: string | null
  onClose: () => void
  onSelect: (ledgerId: string) => void
  onCreate: () => void
  onManage: () => void
}) {
  return (
    <div className="drawer-backdrop" onMouseDown={onClose}>
      <aside className="drawer ledger-drawer" aria-label="选择账本" onMouseDown={(event) => event.stopPropagation()}>
        <div className="modal-header">
          <div><p className="eyebrow">账本切换</p><h2>我的账本</h2></div>
          <button className="icon-button" aria-label="关闭" onClick={onClose}>×</button>
        </div>
        <button className="create-ledger-button" onClick={onCreate}>
          <span aria-hidden="true">＋</span>
          <span><strong>新建账本</strong><small>创建个人或家庭账本</small></span>
        </button>
        <div className="ledger-list">
          {ledgers.map((ledger) => {
            const selected = ledger.id === selectedLedgerId
            return (
              <button
                key={ledger.id}
                className={`ledger-list-item${selected ? ' selected' : ''}`}
                aria-current={selected ? 'true' : undefined}
                onClick={() => onSelect(ledger.id)}
              >
                <span className="ledger-list-icon" aria-hidden="true">{ledger.mode === 'FAMILY' ? '家' : '账'}</span>
                <span className="ledger-list-copy">
                  <strong>{ledger.name}</strong>
                  <small>
                    {ledger.mode === 'FAMILY' ? '家庭账本' : '个人账本'}
                    {' · '}
                    {ledger.role === 'OWNER' ? '所有者' : ledger.role === 'EDITOR' ? '可编辑' : '仅查看'}
                  </small>
                </span>
                {selected && <span className="ledger-selected-mark" aria-label="当前账本">✓</span>}
              </button>
            )
          })}
        </div>
        {selectedLedgerId && <button className="secondary wide manage-ledger-button" onClick={onManage}>管理当前账本</button>}
      </aside>
    </div>
  )
}

function MonthPicker({
  selectedMonth,
  expenses,
  onClose,
  onSelect,
}: {
  selectedMonth: string
  expenses: Record<string, number>
  onClose: () => void
  onSelect: (month: string) => void
}) {
  const currentYear = new Date().getFullYear()
  const selectedYear = Number(selectedMonth.slice(0, 4))
  const firstYear = Math.min(currentYear, selectedYear) - 10
  const lastYear = Math.max(currentYear, selectedYear) + 10
  const years = Array.from({ length: lastYear - firstYear + 1 }, (_, index) => firstYear + index)
  const scrollRef = useRef<HTMLDivElement>(null)
  const currentYearRef = useRef<HTMLElement>(null)

  useLayoutEffect(() => {
    const container = scrollRef.current
    const current = currentYearRef.current
    const firstYear = container?.firstElementChild as HTMLElement | null
    if (container && current && firstYear) {
      container.scrollTop = current.offsetTop - firstYear.offsetTop
    }
  }, [])

  return (
    <div className="month-picker-backdrop" onMouseDown={onClose}>
      <section className="month-picker" role="dialog" aria-modal="true" aria-label="选择月份" onMouseDown={(event) => event.stopPropagation()}>
        <header className="month-picker-header">
          <div><p className="eyebrow">月份总览</p><h2>{monthLabel(selectedMonth)}</h2></div>
          <button className="icon-button" aria-label="关闭" onClick={onClose}>×</button>
        </header>
        <div className="year-scroll" ref={scrollRef}>
          {years.map((year) => (
            <section className="year-page" key={year} ref={year === currentYear ? currentYearRef : undefined}>
              <h3>{year}<small>年</small></h3>
              <div className="month-grid">
                {Array.from({ length: 12 }, (_, index) => {
                  const value = `${year}-${String(index + 1).padStart(2, '0')}`
                  const expense = formatCompactAmount(expenses[value] ?? 0)
                  const selected = value === selectedMonth
                  return (
                    <button
                      key={value}
                      className={`month-cell${selected ? ' selected' : ''}`}
                      aria-current={selected ? 'date' : undefined}
                      onClick={() => onSelect(value)}
                    >
                      <span>{index + 1}<small>月</small></span>
                      {expense && <strong>¥{expense}</strong>}
                    </button>
                  )
                })}
              </div>
            </section>
          ))}
        </div>
      </section>
    </div>
  )
}

function CategoryDetailDrawer({
  categoryName,
  transactions,
  editable,
  onClose,
  onEdit,
}: {
  categoryName: string
  transactions: Transaction[]
  editable: boolean
  onClose: () => void
  onEdit: (transaction: Transaction) => void
}) {
  const total = transactions.reduce((sum, item) => sum + item.amount, 0)
  return (
    <div className="drawer-backdrop" onMouseDown={onClose}>
      <aside className="drawer category-detail-drawer" aria-label={`${categoryName}支出明细`} onMouseDown={(event) => event.stopPropagation()}>
        <div className="modal-header">
          <div><p className="eyebrow">分类明细</p><h2>{categoryName}</h2></div>
          <button className="icon-button" aria-label="关闭" onClick={onClose}>×</button>
        </div>
        <div className="category-detail-summary">
          <span>本月支出</span>
          <strong>{currency.format(total)}</strong>
          <small>{transactions.length} 笔记录</small>
        </div>
        <div className="category-detail-list">
          {transactions.map((item) => (
            <button className="transaction-row" key={item.syncId} disabled={!editable} onClick={() => onEdit(item)}>
              <span className="category-dot" style={{ backgroundColor: item.categoryColor || '#7A8B85' }}>{categorySymbol(item)}</span>
              <span className="transaction-info">
                <strong>{item.merchantName || item.categoryName || '未分类'}</strong>
                <small>{new Date(item.date).toLocaleDateString('zh-CN')}{item.note ? ` · ${item.note}` : ''}</small>
              </span>
              <span className="transaction-amount expense">-{currency.format(item.amount)}</span>
            </button>
          ))}
        </div>
      </aside>
    </div>
  )
}

function TwelveMonthTrend({
  transactions,
  endMonth,
}: {
  transactions: Transaction[]
  endMonth: string
}) {
  type TrendSeries = 'income' | 'expense' | 'balance'
  const seriesMeta: Record<TrendSeries, { label: string }> = {
    income: { label: '收入' },
    expense: { label: '支出' },
    balance: { label: '结余' },
  }
  const scrollRef = useRef<HTMLDivElement>(null)
  const [visibleSeries, setVisibleSeries] = useState<TrendSeries[]>(['expense'])
  const points = useMemo(
    () => calculateMonthlyTrend(transactions, endMonth),
    [endMonth, transactions],
  )
  const width = 12 * 76 + 28
  const height = 170
  const plotTop = 18
  const plotBottom = 126
  const values = visibleSeries.length
    ? points.flatMap((point) => visibleSeries.map((series) => point[series]))
    : [0]
  const minimum = Math.min(0, ...values)
  const maximum = Math.max(1, ...values)
  const range = maximum - minimum || 1
  const x = (index: number) => 28 + index * 76
  const y = (value: number) => plotBottom - ((value - minimum) / range) * (plotBottom - plotTop)
  const line = (key: 'income' | 'expense' | 'balance') =>
    points.map((point, index) => `${x(index)},${y(point[key])}`).join(' ')
  const axisLabel = (value: number) => {
    if (value === 0) return '¥0'
    const formatted = formatCompactAmount(Math.abs(value))
    return `${value < 0 ? '-' : ''}¥${formatted}`
  }
  const ticks = [maximum, (maximum + minimum) / 2, minimum]
  const toggleSeries = (series: TrendSeries) => {
    setVisibleSeries((current) =>
      current.includes(series)
        ? current.filter((item) => item !== series)
        : [...current, series],
    )
  }

  useLayoutEffect(() => {
    const container = scrollRef.current
    if (container) container.scrollLeft = container.scrollWidth
  }, [endMonth])

  return (
    <section className="trend-section">
      <div className="trend-heading">
        <div><h3>近 12 个月趋势</h3><p>滚轮可左右查看完整时间轴</p></div>
        <div className="trend-legend">
          {(Object.keys(seriesMeta) as TrendSeries[]).map((series) => (
            <button
              key={series}
              className={`${series}${visibleSeries.includes(series) ? ' active' : ''}`}
              aria-pressed={visibleSeries.includes(series)}
              onClick={() => toggleSeries(series)}
            >
              {seriesMeta[series].label}
            </button>
          ))}
        </div>
      </div>
      <div className="trend-chart-layout">
        <svg className="trend-axis" width="54" height={height} viewBox={`0 0 54 ${height}`} aria-hidden="true">
          {ticks.map((tick, index) => {
            const lineY = y(tick)
            return <g key={`${tick}-${index}`}>
              <text className="trend-y-label" x="49" y={lineY + 3} textAnchor="end">{axisLabel(tick)}</text>
              <line className="trend-axis-tick" x1="50" x2="54" y1={lineY} y2={lineY} />
            </g>
          })}
        </svg>
        <div
          className="trend-scroll"
          ref={scrollRef}
          onWheel={(event) => {
            if (Math.abs(event.deltaY) > Math.abs(event.deltaX)) {
              event.currentTarget.scrollLeft += event.deltaY
            }
          }}
        >
          <svg className="trend-chart" width={width} height={height} viewBox={`0 0 ${width} ${height}`} role="img" aria-label="过去十二个月收入、支出和结余趋势">
            {ticks.map((tick, index) => {
              const lineY = y(tick)
              return <line className="trend-grid-line" key={`${tick}-${index}`} x1="0" x2={width - 20} y1={lineY} y2={lineY} />
            })}
            {minimum < 0 && (
              <line className="trend-zero-line" x1="0" x2={width - 20} y1={y(0)} y2={y(0)} />
            )}
            {visibleSeries.map((series) => (
              <polyline className={`trend-line ${series}`} key={series} points={line(series)} />
            ))}
            {points.map((point, index) => (
              <g key={point.month}>
                {visibleSeries.map((series) => (
                  <circle className={`trend-point ${series}`} key={series} cx={x(index)} cy={y(point[series])} r="3">
                    <title>{point.month} {seriesMeta[series].label} {currency.format(point[series])}</title>
                  </circle>
                ))}
                <text className="trend-month-label" x={x(index)} y="154" textAnchor="middle">{point.label}</text>
              </g>
            ))}
          </svg>
        </div>
      </div>
    </section>
  )
}

function FamilyPanel({
  ledger,
  members,
  invitations,
  loading,
  onClose,
  onInvite,
  onRole,
  onRemove,
  onAccept,
  onSettings,
  onDelete,
}: {
  ledger: FamilyLedger | null
  members: FamilyMember[]
  invitations: FamilyInvitation[]
  loading: boolean
  onClose: () => void
  onInvite: (email: string, role: Exclude<LedgerRole, 'OWNER'>) => Promise<void>
  onRole: (id: string, role: Exclude<LedgerRole, 'OWNER'>) => Promise<void>
  onRemove: (id: string) => Promise<void>
  onAccept: (id: string) => Promise<void>
  onSettings: (mode: FamilyLedger['mode'], name?: string) => Promise<void>
  onDelete: () => Promise<void>
}) {
  const [email, setEmail] = useState('')
  const [role, setRole] = useState<Exclude<LedgerRole, 'OWNER'>>('EDITOR')
  const [ledgerName, setLedgerName] = useState(ledger?.name ?? '')
  const [confirmPersonal, setConfirmPersonal] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const owner = canManageMembers(ledger?.role)

  return (
    <div className="drawer-backdrop" onMouseDown={onClose}>
      <aside className="drawer" onMouseDown={(event) => event.stopPropagation()}>
        <div className="modal-header">
          <div><p className="eyebrow">家庭账本</p><h2>{ledger?.name ?? '邀请与成员'}</h2></div>
          <button className="icon-button" aria-label="关闭" onClick={onClose}>×</button>
        </div>
        {invitations.length > 0 && <section className="drawer-section"><h3>待接受邀请</h3>{invitations.map((item) => <div className="member-row" key={item.id}><div><strong>{item.ledgerName}</strong><small>{item.inviterEmail} 邀请你 · {item.role}</small></div><button className="small-button" onClick={() => void onAccept(item.id)}>接受</button></div>)}</section>}
        {ledger && owner && <section className="drawer-section mode-settings">
          <div className="mode-heading"><div><h3>账本模式</h3><p>{ledger.mode === 'FAMILY' ? '家庭账本可与受邀成员共同使用。' : '个人账本仅你自己可访问。'}</p></div><span className={`mode-badge ${ledger.mode.toLowerCase()}`}>{ledger.mode === 'FAMILY' ? '家庭' : '个人'}</span></div>
          <label>账本名称<input maxLength={60} value={ledgerName} onChange={(event) => setLedgerName(event.target.value)} /></label>
          <button className="primary wide" disabled={loading || !ledgerName.trim() || ledgerName.trim() === ledger.name} onClick={() => void onSettings(ledger.mode, ledgerName.trim())}>保存账本名称</button>
          {ledger.mode === 'PERSONAL'
            ? <button className="secondary wide" disabled={loading} onClick={() => void onSettings('FAMILY', ledgerName)}>转换为家庭账本</button>
            : <button className="danger-button wide" disabled={loading} onClick={() => setConfirmPersonal(true)}>转换为个人账本</button>}
          {confirmPersonal && <div className="destructive-confirm" role="alertdialog" aria-labelledby="personal-confirm-title">
            <strong id="personal-confirm-title">确认转换为个人账本？</strong>
            <p>所有成员的访问权限和待处理邀请都会立即撤销；已有交易会完整保留，不会删除。</p>
            <div><button className="secondary" onClick={() => setConfirmPersonal(false)}>取消</button><button className="danger-button" disabled={loading} onClick={() => void onSettings('PERSONAL', ledgerName).then(() => setConfirmPersonal(false))}>撤销共享并转换</button></div>
          </div>}
        </section>}
        {ledger?.mode === 'FAMILY' && <section className="drawer-section">
          <h3>成员</h3>
          {loading ? <p className="muted">正在加载…</p> : members.map((member) => <div className="member-row" key={member.id}><div className="member-identity"><span className="avatar">{member.email.slice(0, 1).toUpperCase()}</span><div><strong>{member.displayName || member.email}</strong><small>{member.email}</small></div></div>{owner && member.role !== 'OWNER' ? <div className="member-actions"><select aria-label={`${member.email} 的权限`} value={member.role} onChange={(e) => void onRole(member.id, e.target.value as 'EDITOR' | 'VIEWER')}><option value="EDITOR">可编辑</option><option value="VIEWER">仅查看</option></select><button className="danger-link" onClick={() => void onRemove(member.id)}>移除</button></div> : <span className="role-badge">{member.role === 'OWNER' ? '所有者' : member.role === 'EDITOR' ? '可编辑' : '仅查看'}</span>}</div>)}
        </section>}
        {ledger && owner && <section className="drawer-section invite-form"><h3>邀请成员</h3>{ledger.mode === 'PERSONAL' && <p className="conversion-note">发送首个邀请会自动将此账本转换为家庭模式。</p>}<label>邮箱<input type="email" placeholder="name@example.com" value={email} onChange={(e) => setEmail(e.target.value)} /></label><label>权限<select value={role} onChange={(e) => setRole(e.target.value as 'EDITOR' | 'VIEWER')}><option value="EDITOR">可编辑</option><option value="VIEWER">仅查看</option></select></label><button className="primary wide" disabled={!email || loading} onClick={() => void onInvite(email, role).then(() => setEmail(''))}>发送邀请</button></section>}
        {ledger && !owner && <p className="permission-note">只有账本所有者可以邀请或管理成员。</p>}
        {ledger && <section className="drawer-section ledger-danger-zone">
          <div className="mode-heading">
            <div>
              <h3>{owner ? '删除自己的账本' : '退出受邀账本'}</h3>
              <p>{owner
                ? ledger.isDefault
                  ? '默认账本承载本地离线数据，不能删除。'
                  : '账本及其交易将从应用中永久删除，所有成员和邀请会立即失效。'
                : '你将失去此账本的查看和编辑权限；如需再次加入，必须由 owner 重新邀请。'}</p>
            </div>
            <span className={`mode-badge ${owner ? 'personal' : 'family'}`}>{owner ? '我的账本' : '受邀账本'}</span>
          </div>
          {!ledger.isDefault && <button className="danger-button wide" disabled={loading} onClick={() => setConfirmDelete(true)}>{owner ? '永久删除账本' : '退出此账本'}</button>}
          {confirmDelete && <div className="destructive-confirm" role="alertdialog" aria-labelledby="delete-ledger-confirm-title">
            <strong id="delete-ledger-confirm-title">{owner ? `再次确认永久删除“${ledger.name}”` : `再次确认退出“${ledger.name}”`}</strong>
            <p>{owner
              ? '此操作在应用中不可恢复。账本会被标记为已删除，所有共享权限都会撤销。'
              : '退出后不会删除 owner 的账本或交易，但你的访问权限会被移除，恢复访问需要重新邀请。'}</p>
            <div><button className="secondary" onClick={() => setConfirmDelete(false)}>取消</button><button className="danger-button" disabled={loading} onClick={() => void onDelete()}>{loading ? '处理中…' : owner ? '确认永久删除' : '确认退出'}</button></div>
          </div>}
        </section>}
      </aside>
    </div>
  )
}

interface LedgerData {
  transactions: Transaction[]
  cursor: number
  loading: boolean
}

function mergeTransactions(previous: Transaction[], incoming: Transaction[]) {
  const merged = new Map(previous.map((item) => [item.syncId, item]))
  for (const item of incoming) merged.set(item.syncId, item)
  return [...merged.values()]
}

function Dashboard({
  account,
  privacySettings,
  onPrivacySettingsChange,
}: {
  account: AccountInfo
  privacySettings: PrivacySettings
  onPrivacySettingsChange: (settings: PrivacySettings) => void
}) {
  const [ledgerData, setLedgerData] = useState<Record<string, LedgerData>>({})
  const ledgerCursors = useRef<Record<string, number>>({})
  const [ledgers, setLedgers] = useState<FamilyLedger[]>([])
  const [invitations, setInvitations] = useState<FamilyInvitation[]>([])
  const [ledgerId, setLedgerId] = useState<string | null>(null)
  const [month, setMonth] = useState(monthValue)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [editing, setEditing] = useState<Transaction | null | undefined>(undefined)
  const [ledgerDrawerOpen, setLedgerDrawerOpen] = useState(false)
  const [monthPickerOpen, setMonthPickerOpen] = useState(false)
  const [categoryDetail, setCategoryDetail] = useState<string | null>(null)
  const [accountMenuOpen, setAccountMenuOpen] = useState(false)
  const accountMenuRef = useRef<HTMLDivElement>(null)
  const [privacySettingsOpen, setPrivacySettingsOpen] = useState(false)
  const [passcodePromptOpen, setPasscodePromptOpen] = useState(false)
  const [sensitiveVisible, setSensitiveVisible] = useState(false)
  const sensitiveDeadline = useRef(0)
  const [familyOpen, setFamilyOpen] = useState(false)
  const [createLedgerOpen, setCreateLedgerOpen] = useState(false)
  const [members, setMembers] = useState<FamilyMember[]>([])
  const selectedLedger = ledgers.find((item) => item.id === ledgerId) ?? null
  const syncLedgerId = getSyncLedgerId(selectedLedger)
  const ledgerKey = selectedLedger
    ? `${selectedLedger.mode.toLowerCase()}:${selectedLedger.id}`
    : 'personal:default'
  const currentLedgerData = ledgerData[ledgerKey] ?? { transactions: [], cursor: 0, loading: true }
  const transactions = currentLedgerData.transactions.filter((item) => item.deletedAt == null)
  const loading = currentLedgerData.loading
  const api = useMemo(() => new BookkeeperApi(apiConfig.baseUrl, () => getAccessToken(account), () => selectedLedger?.role), [account, selectedLedger?.role])

  const loadFamily = useCallback(async () => {
    const result = await api.getLedgers()
    setLedgers(result.ledgers)
    setInvitations(result.invitations)
    setLedgerId((current) => current && result.ledgers.some((item) => item.id === current) ? current : result.ledgers[0]?.id ?? null)
  }, [api])

  useEffect(() => {
    let active = true
    api.getLedgers().then((result) => {
      if (!active) return
      setLedgers(result.ledgers)
      setInvitations(result.invitations)
      setLedgerId((current) => current && result.ledgers.some((item) => item.id === current) ? current : result.ledgers[0]?.id ?? null)
    }).catch((reason) => {
      if (active) setError(reason instanceof Error ? reason.message : '家庭账本加载失败')
    })
    return () => { active = false }
  }, [api])

  useEffect(() => {
    let active = true
    const cursor = ledgerCursors.current[ledgerKey] ?? 0
    api.pull(syncLedgerId, cursor).then((result) => {
      if (!active) return
      ledgerCursors.current[ledgerKey] = result.cursor
      setLedgerData((current) => ({
        ...current,
        [ledgerKey]: {
          transactions: cursor === 0
            ? result.transactions
            : mergeTransactions(current[ledgerKey]?.transactions ?? [], result.transactions),
          cursor: result.cursor,
          loading: false,
        },
      }))
    }).catch((reason) => {
      if (active) setError(reason instanceof Error ? reason.message : '账本同步失败')
    }).finally(() => {
      if (active) setLedgerData((current) => ({
        ...current,
        [ledgerKey]: { ...(current[ledgerKey] ?? { transactions: [], cursor }), loading: false },
      }))
    })
    return () => { active = false }
  }, [api, ledgerKey, syncLedgerId])

  useEffect(() => {
    if (!familyOpen || !ledgerId || selectedLedger?.mode !== 'FAMILY') return
    api.getMembers(ledgerId).then(setMembers).catch((reason) => setError(reason instanceof Error ? reason.message : '成员加载失败'))
  }, [api, familyOpen, ledgerId, selectedLedger?.mode])

  const visible = useMemo(() => filterTransactionsByMonth(transactions, month), [transactions, month])
  const monthlyExpenses = useMemo(() => calculateMonthlyExpenses(transactions), [transactions])
  const summary = useMemo(() => calculateMonthSummary(visible), [visible])
  const groups = useMemo(() => groupTransactionsByDate(visible), [visible])
  const categoryTransactions = useMemo(
    () => visible.filter((item) => item.type === 'EXPENSE' && (item.categoryName || '未分类') === categoryDetail),
    [categoryDetail, visible],
  )
  const editable = canEditLedger(selectedLedger?.role)

  useEffect(() => {
    if (!sensitiveVisible) return
    const hideIfExpired = () => {
      if (Date.now() >= sensitiveDeadline.current) setSensitiveVisible(false)
    }
    const timeout = window.setTimeout(hideIfExpired, Math.max(0, sensitiveDeadline.current - Date.now()))
    window.addEventListener('focus', hideIfExpired)
    document.addEventListener('visibilitychange', hideIfExpired)
    return () => {
      window.clearTimeout(timeout)
      window.removeEventListener('focus', hideIfExpired)
      document.removeEventListener('visibilitychange', hideIfExpired)
    }
  }, [sensitiveVisible])

  useEffect(() => {
    if (!accountMenuOpen) return
    const closeOnOutsideClick = (event: PointerEvent) => {
      if (!accountMenuRef.current?.contains(event.target as Node)) {
        setAccountMenuOpen(false)
      }
    }
    document.addEventListener('pointerdown', closeOnOutsideClick)
    return () => document.removeEventListener('pointerdown', closeOnOutsideClick)
  }, [accountMenuOpen])

  const revealSensitive = () => {
    sensitiveDeadline.current = Date.now() + 5 * 60 * 1000
    setSensitiveVisible(true)
  }

  const toggleSensitiveVisibility = () => {
    if (sensitiveVisible) {
      setSensitiveVisible(false)
    } else if (privacySettings.requireForIncome && privacySettings.passcodeHash) {
      setPasscodePromptOpen(true)
    } else {
      revealSensitive()
    }
  }

  async function saveTransaction(draft: TransactionDraft) {
    setSaving(true)
    setError('')
    const now = Date.now()
    const existing = draft.syncId ? transactions.find((item) => item.syncId === draft.syncId) : undefined
    const item: Transaction = {
      syncId: draft.syncId ?? crypto.randomUUID(),
      serverVersion: draft.serverVersion ?? 0,
      amount: Math.abs(draft.amount),
      type: draft.type,
      categoryId: existing?.categoryId ?? null,
      categoryName: draft.categoryName || null,
      categoryIcon: existing?.categoryIcon ?? (draft.type === 'EXPENSE' ? '●' : '↑'),
      categoryColor: existing?.categoryColor ?? (draft.type === 'EXPENSE' ? '#F37B61' : '#2BAE84'),
      merchantName: draft.merchantName || null,
      note: draft.note || null,
      originalInput: existing?.originalInput ?? null,
      date: new Date(`${draft.date}T12:00:00`).getTime(),
      createdAt: existing?.createdAt ?? now,
      updatedAt: now,
      source: existing?.source ?? 'MANUAL',
      status: 'CONFIRMED',
      aiConfidence: existing?.aiConfidence ?? null,
      deletedAt: null,
    }
    try {
      await api.push([item], syncLedgerId)
      const refreshed = await api.pull(syncLedgerId, currentLedgerData.cursor)
      ledgerCursors.current[ledgerKey] = refreshed.cursor
      setLedgerData((current) => ({
        ...current,
        [ledgerKey]: {
          transactions: currentLedgerData.cursor === 0
            ? refreshed.transactions
            : mergeTransactions(current[ledgerKey]?.transactions ?? [], refreshed.transactions),
          cursor: refreshed.cursor,
          loading: false,
        },
      }))
      setEditing(undefined)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '交易保存失败')
    } finally {
      setSaving(false)
    }
  }

  async function refreshMembers() {
    if (ledgerId) setMembers(await api.getMembers(ledgerId))
  }

  async function runFamilyAction(action: () => Promise<unknown>, refresh: 'members' | 'ledgers' = 'members') {
    setSaving(true)
    setError('')
    try {
      await action()
      if (refresh === 'members') await refreshMembers()
      else await loadFamily()
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '操作失败')
    } finally {
      setSaving(false)
    }
  }

  async function createLedger(name: string, mode: FamilyLedger['mode']) {
    setSaving(true)
    setError('')
    try {
      const created = await api.createLedger(name, mode)
      await loadFamily()
      setLedgerId(created.id)
      setCreateLedgerOpen(false)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '账本创建失败')
    } finally {
      setSaving(false)
    }
  }

  async function deleteCurrentLedger() {
    if (!ledgerId) return
    setSaving(true)
    setError('')
    try {
      await api.deleteLedger(ledgerId)
      await loadFamily()
      setFamilyOpen(false)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '账本删除失败')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand"><span className="brand-mark small">账</span><div><strong>AI Bookkeeper</strong><small>智能账本</small></div></div>
        <div className="header-actions">
          <button className="ledger-button" aria-label="切换账本" onClick={() => setLedgerDrawerOpen(true)}>
            <span>{selectedLedger?.mode === 'FAMILY' ? '家庭账本' : '个人账本'}</span>
            <strong>{selectedLedger?.name ?? '我的账本'} <i aria-hidden="true">›</i></strong>
          </button>
          <div className="account-menu-wrap" ref={accountMenuRef}>
            <button className="account-button" aria-expanded={accountMenuOpen} aria-label="账户菜单" onClick={() => setAccountMenuOpen((open) => !open)}><span className="avatar">{(account.name || account.username).slice(0, 1).toUpperCase()}</span><span className="account-name">{account.name || account.username}</span></button>
            {accountMenuOpen && <div className="account-popover">
              <div className="account-summary"><strong>{account.name || account.username}</strong><small>{account.username}</small></div>
              <a href={GITHUB_URL} target="_blank" rel="noreferrer"><span aria-hidden="true">⌘</span><span><strong>GitHub 项目</strong><small>查看源码与版本</small></span></a>
              <a href={ANDROID_DOWNLOAD_URL}><span aria-hidden="true">↓</span><span><strong>下载 Android</strong><small>获取最新 APK</small></span></a>
              <button className="account-menu-item" onClick={() => { setAccountMenuOpen(false); setPrivacySettingsOpen(true) }}><span aria-hidden="true">●</span><span><strong>隐私口令</strong><small>{privacySettings.passcodeHash ? '已启用 · 管理设置' : '保护登录和收入'}</small></span></button>
              <button className="account-logout" onClick={() => void logout(account)}>退出登录</button>
            </div>}
          </div>
        </div>
      </header>
      <main className="content">
        <section className="page-heading">
          <div><p className="eyebrow">财务概览</p><h1>你好，{account.name?.split(' ')[0] || '今天也要好好生活'}</h1><p>每一笔认真记录，都让未来更从容。</p></div>
          <div className="heading-controls">
            <button className="month-select" onClick={() => setMonthPickerOpen(true)}>
              <span>月份</span><strong>{monthLabel(month)}</strong>
            </button>
            {editable && <button className="primary add-button" onClick={() => setEditing(null)}>＋ 记一笔</button>}
          </div>
        </section>
        {error && <div className="alert error" role="alert"><span>{error}</span><button onClick={() => setError('')}>×</button></div>}
        {!editable && <div className="alert info">你在此账本中拥有仅查看权限，交易编辑和同步提交已关闭。</div>}
        <section className="summary-grid">
          <article className="summary-card expense"><div><span>本月支出</span><strong>{currency.format(summary.expense)}</strong></div><span className="summary-icon">↗</span><small>{summary.count} 笔记录</small></article>
          <article className="summary-card income"><div><span className="sensitive-title">本月收入<button className="visibility-button" aria-label={sensitiveVisible ? '隐藏收入和结余' : '显示收入和结余'} aria-pressed={sensitiveVisible} onClick={toggleSensitiveVisibility}>{sensitiveVisible ? '◉' : '⊘'}</button></span><strong className={!sensitiveVisible ? 'masked-value' : ''}>{sensitiveVisible ? currency.format(summary.income) : '••••••'}</strong></div><span className="summary-icon">↙</span><small>{sensitiveVisible ? '5 分钟后自动隐藏' : '点击眼睛临时展示'}</small></article>
          <article className="summary-card balance"><div><span>本月结余</span><strong className={!sensitiveVisible ? 'masked-value' : ''}>{sensitiveVisible ? currency.format(summary.balance) : '••••••'}</strong></div><span className="summary-icon">≈</span><small>{sensitiveVisible ? '收入减去支出' : '已保护隐私'}</small></article>
        </section>
        <div className="dashboard-grid">
          <button className="month-panel-button previous" aria-label="上一个月" onClick={() => setMonth((current) => shiftMonth(current, -1))}>‹</button>
          <button className="month-panel-button next" aria-label="下一个月" onClick={() => setMonth((current) => shiftMonth(current, 1))}>›</button>
          <section className="panel transactions-panel">
            <div className="panel-header"><div><h2>收支明细</h2><p>{month.replace('-', ' 年 ')} 月</p></div>{loading && <span className="sync-status">同步中…</span>}</div>
            {!loading && groups.length === 0 && <div className="empty-state"><span>✦</span><h3>这个月还没有记录</h3><p>{editable ? '点击“记一笔”开始整理收支。' : '此账本本月暂无交易。'}</p></div>}
            {groups.map((group) => <div className="date-group" key={group.dateKey}><div className="date-heading"><strong>{dateTitle.format(new Date(group.timestamp))}</strong><span>支出 {currency.format(group.expense)}</span></div>{group.transactions.map((item) => {
              const incomeHidden = item.type === 'INCOME' && !sensitiveVisible
              return <button className={`transaction-row${incomeHidden ? ' sensitive-hidden' : ''}`} key={item.syncId} disabled={!editable || incomeHidden} onClick={() => editable && setEditing(item)}><span className="category-dot" style={{ backgroundColor: item.categoryColor || '#7A8B85' }}>{categorySymbol(item)}</span><span className="transaction-info"><strong>{item.merchantName || item.categoryName || '未分类'}</strong><small>{item.categoryName || '其他'}{item.note ? ` · ${item.note}` : ''}</small></span><span className={`transaction-amount ${item.type.toLowerCase()}`}>{incomeHidden ? '••••••' : `${item.type === 'INCOME' ? '+' : '-'}${currency.format(item.amount)}`}</span></button>
            })}</div>)}
          </section>
          <aside className="panel statistics-panel">
            <div className="panel-header"><div><h2>支出分类</h2><p>本月消费构成</p></div></div>
            <div className="donut-wrap"><div className="donut" style={{ '--expense': summary.expense ? '76%' : '0%' } as React.CSSProperties}><div><strong>{summary.categories.length}</strong><span>个分类</span></div></div></div>
            <div className="category-list">{summary.categories.slice(0, 6).map((category, index) => <button className="category-stat" key={category.name} onClick={() => setCategoryDetail(category.name)}><span className={`stat-color color-${index % 6}`} /><span className="category-stat-main"><strong>{category.name}</strong><span className="progress"><i style={{ width: `${category.percentage}%` }} /></span></span><span className="category-stat-value"><strong>{currency.format(category.amount)}</strong><small>{category.percentage.toFixed(0)}% · 查看</small></span></button>)}</div>
            {summary.categories.length === 0 && <p className="empty-small">暂无支出统计</p>}
            <TwelveMonthTrend transactions={transactions} endMonth={month} />
          </aside>
        </div>
      </main>
      <button className="month-grid-launcher" aria-label="展开月份选择" onClick={() => setMonthPickerOpen(true)}>
        {Array.from({ length: 6 }, (_, index) => <i key={index} />)}
      </button>
      {editing !== undefined && <TransactionModal transaction={editing ?? undefined} saving={saving} onClose={() => setEditing(undefined)} onSave={saveTransaction} />}
      {monthPickerOpen && <MonthPicker
        selectedMonth={month}
        expenses={monthlyExpenses}
        onClose={() => setMonthPickerOpen(false)}
        onSelect={(selectedMonth) => {
          setMonth(selectedMonth)
          setMonthPickerOpen(false)
        }}
      />}
      {categoryDetail && <CategoryDetailDrawer
        categoryName={categoryDetail}
        transactions={categoryTransactions}
        editable={editable}
        onClose={() => setCategoryDetail(null)}
        onEdit={(transaction) => {
          setCategoryDetail(null)
          setEditing(transaction)
        }}
      />}
      {ledgerDrawerOpen && <LedgerDrawer
        ledgers={ledgers}
        selectedLedgerId={ledgerId}
        onClose={() => setLedgerDrawerOpen(false)}
        onSelect={(selectedId) => {
          setLedgerId(selectedId)
          setLedgerDrawerOpen(false)
        }}
        onCreate={() => {
          setLedgerDrawerOpen(false)
          setCreateLedgerOpen(true)
        }}
        onManage={() => {
          setLedgerDrawerOpen(false)
          setFamilyOpen(true)
        }}
      />}
      {createLedgerOpen && <CreateLedgerModal saving={saving} onClose={() => setCreateLedgerOpen(false)} onCreate={createLedger} />}
      {privacySettingsOpen && <PrivacySettingsModal settings={privacySettings} onClose={() => setPrivacySettingsOpen(false)} onSave={onPrivacySettingsChange} />}
      {passcodePromptOpen && <PasscodePrompt settings={privacySettings} title="展示收入与结余" description="验证隐私口令后，敏感金额将展示 5 分钟。" onCancel={() => setPasscodePromptOpen(false)} onSuccess={() => { setPasscodePromptOpen(false); revealSensitive() }} />}
      {familyOpen && <FamilyPanel
        key={`${selectedLedger?.id ?? 'none'}:${selectedLedger?.mode ?? 'PERSONAL'}`}
        ledger={selectedLedger}
        members={members}
        invitations={invitations}
        loading={saving}
        onClose={() => setFamilyOpen(false)}
        onAccept={(id) => runFamilyAction(() => api.acceptInvitation(id), 'ledgers')}
        onInvite={(email, role) => runFamilyAction(async () => {
          if (selectedLedger?.mode === 'PERSONAL') {
            await api.updateLedgerSettings('FAMILY', selectedLedger.name, ledgerId)
          }
          await api.inviteMember(email, role, ledgerId)
        }, 'ledgers')}
        onSettings={(mode, name) => runFamilyAction(() => api.updateLedgerSettings(mode, name, ledgerId), 'ledgers')}
        onDelete={deleteCurrentLedger}
        onRole={(id, role) => runFamilyAction(() => api.updateMember(id, role, ledgerId))}
        onRemove={(id) => runFamilyAction(() => api.removeMember(id, ledgerId))}
      />}
    </div>
  )
}

export default function App() {
  const [account, setAccount] = useState<AccountInfo | null>(() => msalInstance?.getActiveAccount() ?? msalInstance?.getAllAccounts()[0] ?? null)
  const [privacyOverrides, setPrivacyOverrides] = useState<Record<string, PrivacySettings>>({})
  const [unlockedAccountId, setUnlockedAccountId] = useState<string | null>(null)
  const authError = configError

  useEffect(() => {
    if (!msalInstance) return
    const instance = msalInstance
    const callback = instance.addEventCallback((event) => {
      if (event.eventType.includes('LOGIN_SUCCESS') || event.eventType.includes('ACQUIRE_TOKEN_SUCCESS')) {
        const next = instance.getActiveAccount() ?? instance.getAllAccounts()[0]
        if (next) setAccount(next)
      }
    })
    return () => { if (callback) instance.removeEventCallback(callback) }
  }, [])

  if (!account) return <LoginScreen error={authError || undefined} />
  const accountId = account.homeAccountId
  const privacySettings = privacyOverrides[accountId] ?? loadPrivacySettings(accountId)
  if (privacySettings.requireOnLogin && privacySettings.passcodeHash && unlockedAccountId !== accountId) {
    return <PasscodePrompt
      settings={privacySettings}
      title="解锁账本"
      description="Microsoft 登录已完成，请输入本机隐私口令继续。"
      onSuccess={() => setUnlockedAccountId(accountId)}
      onLogout={() => void logout(account)}
    />
  }
  return <Dashboard
    account={account}
    privacySettings={privacySettings}
    onPrivacySettingsChange={(next) => {
      savePrivacySettings(accountId, next)
      setPrivacyOverrides((current) => ({ ...current, [accountId]: next }))
      setUnlockedAccountId(accountId)
    }}
  />
}
