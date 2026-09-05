import { useState } from 'react'
import { CATEGORY_ICONS, categoriesForType, categoryIcon } from './categories'
import type { CategoryDraft, LedgerCategory, TransactionType } from './types'

export function CategoryForm({
  type,
  onCreate,
  onCancel,
}: {
  type: TransactionType
  onCreate: (category: CategoryDraft) => Promise<LedgerCategory>
  onCancel?: () => void
}) {
  const [name, setName] = useState('')
  const [icon, setIcon] = useState('tag')
  const [customIcon, setCustomIcon] = useState('')
  const [color, setColor] = useState('#607D8B')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    setSaving(true)
    setError('')
    try {
      await onCreate({ name: name.trim().replace(/\s+/g, ' '), type, icon: customIcon.trim() || icon, color })
      setName('')
      setCustomIcon('')
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '分类保存失败')
    } finally {
      setSaving(false)
    }
  }

  return <form className="category-form" onSubmit={(event) => void submit(event)}>
    <h3>新增{type === 'EXPENSE' ? '支出' : '收入'}分类</h3>
    <div className="form-grid">
      <label>分类名称<input required maxLength={100} value={name} onChange={(event) => setName(event.target.value)} placeholder="例如：宠物" /></label>
      <label>图标<select value={icon} onChange={(event) => { setIcon(event.target.value); setCustomIcon('') }}>{Object.entries(CATEGORY_ICONS).map(([key, emoji]) => <option key={key} value={key}>{emoji}</option>)}</select></label>
      <label>自定义 Emoji（可选）<input maxLength={64} value={customIcon} onChange={(event) => setCustomIcon(event.target.value)} placeholder="也可输入 🥬 等自定义图标" /></label>
      <label>颜色<input type="color" value={color} onChange={(event) => setColor(event.target.value)} /></label>
    </div>
    {error && <p className="field-error" role="alert">{error}</p>}
    <div className="modal-actions">
      {onCancel && <button type="button" className="secondary" disabled={saving} onClick={onCancel}>取消新增</button>}
      <button className="primary" disabled={saving || !name.trim()}>{saving ? '保存中…' : '添加到当前账本'}</button>
    </div>
  </form>
}

export function CategoryManager({
  ledgerName,
  categories,
  loading,
  error,
  editable,
  onClose,
  onCreate,
  onRetry,
}: {
  ledgerName: string
  categories: LedgerCategory[]
  loading: boolean
  error: string
  editable: boolean
  onClose: () => void
  onCreate: (category: CategoryDraft) => Promise<LedgerCategory>
  onRetry: () => void
}) {
  const [type, setType] = useState<TransactionType>('EXPENSE')
  return <div className="drawer-backdrop" onMouseDown={onClose}>
    <aside className="drawer" aria-label="账本分类" onMouseDown={(event) => event.stopPropagation()}>
      <div className="modal-header"><div><p className="eyebrow">账本分类</p><h2>{ledgerName}</h2></div><button className="icon-button" aria-label="关闭" onClick={onClose}>×</button></div>
      <p className="privacy-settings-note">分类名称和图标随此账本共享，其他账本不受影响。所有者和可编辑成员均可新增分类。</p>
      <div className="type-toggle">{(['EXPENSE', 'INCOME'] as const).map((value) => <button key={value} className={type === value ? 'active' : ''} onClick={() => setType(value)}>{value === 'EXPENSE' ? '支出' : '收入'}</button>)}</div>
      {loading && <p className="muted">正在读取账本分类…</p>}
      {error && <div className="field-error" role="alert">{error} <button className="small-button" onClick={onRetry}>重试</button></div>}
      <div className="category-catalog">{categoriesForType(categories, type).map((category) => <div className="category-catalog-item" key={category.id}><span className="category-dot" style={{ backgroundColor: category.color }}>{categoryIcon(category.icon, category.name)}</span><span>{category.name}</span><small>{category.isSystem ? '默认' : '自定义'}</small></div>)}</div>
      {editable && !loading && !error && <CategoryForm key={type} type={type} onCreate={onCreate} />}
      {!editable && <p className="permission-note">你拥有仅查看权限，不能新增分类。</p>}
    </aside>
  </div>
}
