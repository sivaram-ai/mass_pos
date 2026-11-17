import { useCallback, useEffect, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import { api, billDate, fieldErrorsOf, messageOfFailure, paiseFromRupees, quantity, rupees } from '../api'
import type { PurchaseOrder, Role, Vendor, VendorPaymentMode, VendorProfile } from '../types'
import {
  Banner, Button, Empty, errorFor, Field, inputClass, KeyBox, Modal, Panel, smallInputClass,
} from '../components/ui'
import { useScreenKeys } from '../shortcuts'
import type { Toast } from '../App'

const SEARCH_KEY = 'F2'
const ADD_KEY = 'F3'
const OPEN_KEY = 'F6'

const MODES: { key: VendorPaymentMode; label: string }[] = [
  { key: 'CASH', label: 'Cash' },
  { key: 'UPI', label: 'UPI' },
  { key: 'BANK_TRANSFER', label: 'Bank transfer' },
  { key: 'CHEQUE', label: 'Cheque' },
  { key: 'CARD', label: 'Card' },
]

const MODE_LABELS: Record<VendorPaymentMode, string> =
  Object.fromEntries(MODES.map((one) => [one.key, one.label])) as Record<VendorPaymentMode, string>

type Editing = {
  id: string | null
  code: string
  name: string
  phone: string
  gstin: string
  address: string
  note: string
  active: boolean
}

const BLANK: Editing = {
  id: null, code: '', name: '', phone: '', gstin: '', address: '', note: '', active: true,
}

/**
 * The people the shop buys from, and what it still owes each of them.
 *
 * <p>The balance is arithmetic over things that already existed: everything bought from a vendor,
 * at the price it was bought for, less every payment an administrator has agreed to. A payment
 * somebody has recorded but nobody has agreed to is shown as its own figure and left out of the
 * balance, because the shop should see that somebody says they paid without the books moving on
 * one person's say-so.
 *
 * <p>Worked like every other list here: ↑ ↓ choose, Enter opens the vendor's page, F3 adds one,
 * F2 searches.
 */
export default function Vendors({ role, onToast }: { role: Role; onToast: Toast }) {
  const [rows, setRows] = useState<Vendor[]>([])
  const [filter, setFilter] = useState('')
  const [selected, setSelected] = useState(0)
  const [editing, setEditing] = useState<Editing | null>(null)
  const [openOn, setOpenOn] = useState<string | null>(null)
  const [removing, setRemoving] = useState<Vendor | null>(null)
  const list = useRef<HTMLDivElement>(null)
  const searchBox = useRef<HTMLInputElement>(null)
  const lines = useRef(new Map<string, HTMLTableRowElement>())
  const dialogOpen = editing !== null || openOn !== null || removing !== null

  const load = useCallback(() => {
    api.get<Vendor[]>('/api/vendors')
      .then(setRows)
      .catch((failure) => onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not load the vendors') }))
  }, [onToast])

  useEffect(load, [load])

  useEffect(() => {
    list.current?.focus()
  }, [])

  const looking = filter.trim().toLowerCase()
  const shown = rows.filter((row) =>
    `${row.name} ${row.code} ${row.phone} ${row.gstin}`.toLowerCase().includes(looking))
  const owed = rows.reduce((sum, row) => sum + Math.max(0, row.outstandingPaise), 0)
  const waiting = rows.reduce((sum, row) => sum + row.waitingPaise, 0)

  const at = Math.min(selected, Math.max(shown.length - 1, 0))
  const current = shown[at]

  useEffect(() => {
    if (current) {
      lines.current.get(current.id)?.scrollIntoView({ block: 'nearest' })
    }
  }, [current])

  useScreenKeys({
    [SEARCH_KEY]: () => {
      searchBox.current?.focus()
      searchBox.current?.select()
    },
    [ADD_KEY]: () => setEditing(BLANK),
    [OPEN_KEY]: () => current && setOpenOn(current.id),
  }, !dialogOpen)

  const backToList = (reload = false) => {
    setEditing(null)
    setOpenOn(null)
    setRemoving(null)
    if (reload) {
      load()
    }
    requestAnimationFrame(() => list.current?.focus())
  }

  const edit = (vendor: Vendor) => setEditing({
    id: vendor.id,
    code: vendor.code,
    name: vendor.name,
    phone: vendor.phone,
    gstin: vendor.gstin,
    address: vendor.address,
    note: vendor.note,
    active: vendor.active,
  })

  const onListKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (dialogOpen || !current) {
      return
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      setSelected(Math.max(0, Math.min(shown.length - 1, at + (event.key === 'ArrowDown' ? 1 : -1))))
    } else if (event.key === 'Home' || event.key === 'End') {
      event.preventDefault()
      setSelected(event.key === 'Home' ? 0 : shown.length - 1)
    } else if (event.key === 'Delete') {
      event.preventDefault()
      setRemoving(current)
    } else if (event.key === 'Enter' && !event.repeat) {
      event.preventDefault()
      setOpenOn(current.id)
    }
  }

  const onSearchKeyDown = (event: ReactKeyboardEvent<HTMLInputElement>) => {
    if (['Enter', 'Escape', 'ArrowDown'].includes(event.key)) {
      event.preventDefault()
      event.stopPropagation()
      event.nativeEvent.stopImmediatePropagation()
      setSelected(0)
      list.current?.focus()
    }
  }

  return (
    <Panel
      title="Vendors"
      actions={
        <div className="flex items-center gap-2">
          <span className="whitespace-nowrap text-xs text-slate-500">
            {rows.length} vendors · owed <span className="num font-medium text-slate-700">{rupees(owed)}</span>
            {waiting > 0 && (
              <span className="ml-2 text-amber-700">
                <span className="num">{rupees(waiting)}</span> waiting to be approved
              </span>
            )}
          </span>
          <Button className="whitespace-nowrap px-2 py-1" tone="primary" hint={ADD_KEY}
                  onClick={() => setEditing(BLANK)}>
            Add a vendor
          </Button>
          <KeyBox hint={SEARCH_KEY}>
            <input
              ref={searchBox}
              className={`${smallInputClass} w-56 pr-8`}
              placeholder="Name, code or phone"
              aria-label="Filter vendors"
              value={filter}
              onChange={(event) => {
                setFilter(event.target.value)
                setSelected(0)
              }}
              onKeyDown={onSearchKeyDown}
            />
          </KeyBox>
        </div>
      }
    >
      <p className="mb-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-400">
        <span><kbd>↑</kbd> <kbd>↓</kbd> choose</span>
        <span><kbd>Enter</kbd> or <kbd>{OPEN_KEY}</kbd> open · <kbd>Delete</kbd> remove</span>
        <span><kbd>{SEARCH_KEY}</kbd> search · <kbd>{ADD_KEY}</kbd> add</span>
        <span><kbd>F9</kbd> menu · <kbd>Esc</kbd> <kbd>Esc</kbd> billing</span>
      </p>

      <div
        ref={list}
        tabIndex={0}
        role="grid"
        aria-label="Vendors"
        onKeyDown={onListKeyDown}
        className="overflow-x-auto rounded-md outline-none focus-visible:ring-2 focus-visible:ring-sky-200"
      >
        {shown.length === 0 ? (
          <Empty>
            {filter ? `Nothing matches "${filter}"` : 'Nobody yet. Add whoever the shop buys from.'}
          </Empty>
        ) : (
          <table className="w-full min-w-[56rem] table-fixed text-sm">
            <colgroup>
              <col className="w-28" />
              <col />
              <col className="w-36" />
              <col className="w-32" />
              <col className="w-32" />
              <col className="w-32" />
            </colgroup>
            <thead className="text-xs uppercase tracking-wide text-slate-400">
              <tr>
                <th className="py-1 pl-2 text-left">Code</th>
                <th className="py-1 text-left">Vendor</th>
                <th className="py-1 text-left">Phone</th>
                <th className="py-1 text-right">Bought</th>
                <th className="py-1 text-right">Paid</th>
                <th className="py-1 pr-2 text-right">Owed</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((row, index) => {
                const isSelected = index === at
                return (
                  <tr
                    key={row.id}
                    ref={(element) => {
                      if (element) {
                        lines.current.set(row.id, element)
                      } else {
                        lines.current.delete(row.id)
                      }
                    }}
                    onMouseDown={() => setSelected(index)}
                    onClick={() => setOpenOn(row.id)}
                    aria-selected={isSelected}
                    className={`cursor-pointer border-t border-slate-100 ${
                      isSelected
                        ? 'bg-sky-50 shadow-[inset_3px_0_0_0_var(--color-sky-500)]'
                        : row.active ? 'hover:bg-slate-50' : 'bg-slate-50/80 hover:bg-slate-100'}`}
                  >
                    <td className="num py-1.5 pl-2 text-slate-500">{row.code}</td>
                    <td className="truncate py-1.5 pr-2" title={row.name}>
                      {row.name}
                      {!row.active && <span className="ml-2 text-xs text-slate-400">stopped</span>}
                      {row.waitingPaise > 0 && (
                        <span className="ml-2 text-xs text-amber-700">
                          <span className="num">{rupees(row.waitingPaise)}</span> waiting
                        </span>
                      )}
                    </td>
                    <td className="num py-1.5 text-slate-500">{row.phone || '—'}</td>
                    <td className="num py-1.5 text-right text-slate-500">{rupees(row.boughtPaise)}</td>
                    <td className="num py-1.5 text-right text-slate-500">{rupees(row.paidPaise)}</td>
                    <td className={`num py-1.5 pr-2 text-right font-medium ${
                      row.outstandingPaise > 0 ? 'text-rose-600' : 'text-slate-400'}`}>
                      {rupees(row.outstandingPaise)}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </div>

      {openOn && (
        <VendorPage
          vendorId={openOn}
          role={role}
          onToast={onToast}
          onChanged={load}
          onEdit={edit}
          onClose={() => backToList()}
        />
      )}

      {editing && (
        <VendorDialog
          draft={editing}
          onToast={onToast}
          onClose={() => backToList()}
          onDone={() => backToList(true)}
        />
      )}

      {removing && (
        <Modal title={`Remove ${removing.name}?`} onClose={() => backToList()}>
          <p className="text-sm text-slate-600">
            They go from this list and their code is free again. Everything bought from them and
            every payment stays: both are records of goods and money that really moved.
          </p>
          <div className="mt-4 flex justify-end gap-2">
            <Button onClick={() => backToList()}>Keep them</Button>
            <Button
              tone="danger"
              onClick={async () => {
                try {
                  await api.delete(`/api/vendors/${removing.id}`)
                  onToast({ tone: 'good', text: `${removing.name} removed` })
                  backToList(true)
                } catch (failure) {
                  onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not remove them') })
                }
              }}
            >
              Remove
            </Button>
          </div>
        </Modal>
      )}
    </Panel>
  )
}

/** One vendor: what was bought, what was paid, what is left, and the way to settle some of it. */
function VendorPage({ vendorId, role, onToast, onChanged, onEdit, onClose }: {
  vendorId: string
  role: Role
  onToast: Toast
  onChanged: () => void
  onEdit: (vendor: Vendor) => void
  onClose: () => void
}) {
  const [profile, setProfile] = useState<VendorProfile | null>(null)
  const [orders, setOrders] = useState<PurchaseOrder[]>([])
  const [paying, setPaying] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const canApprove = role === 'ADMIN'

  const load = useCallback(() => {
    api.get<VendorProfile>(`/api/vendors/${vendorId}`)
      .then(setProfile)
      .catch((failure) => setError(messageOfFailure(failure, 'Could not open this vendor')))
    // The vendor's own orders, so money paid can be put against the one it was for - and so the
    // payments below can say which. A whole year of them, because an old order gets paid late.
    const since = new Date()
    since.setFullYear(since.getFullYear() - 1)
    api.get<PurchaseOrder[]>(
      `/api/purchase-orders?vendorId=${vendorId}&from=${since.toISOString().slice(0, 10)}`)
      .then(setOrders)
      .catch(() => setOrders([]))
  }, [vendorId])

  useEffect(load, [load])

  const approve = async (paymentId: string) => {
    try {
      await api.post(`/api/vendors/payments/${paymentId}/approve`)
      onToast({ tone: 'good', text: 'Payment approved' })
      load()
      onChanged()
    } catch (failure) {
      setError(messageOfFailure(failure, 'Could not approve that payment'))
    }
  }

  const vendor = profile?.vendor

  return (
    <Modal title={vendor ? `${vendor.name} · ${vendor.code}` : 'Vendor'} size="5xl" onClose={onClose}>
      {error && <div className="mb-3"><Banner tone="bad" onDismiss={() => setError(null)}>{error}</Banner></div>}
      {!profile || !vendor ? (
        <Empty>Opening…</Empty>
      ) : (
        <div className="space-y-4">
          <div className="flex flex-wrap items-end gap-4">
            <dl className="grid flex-1 grid-cols-3 gap-x-6 gap-y-1 rounded-lg bg-slate-50 px-4 py-3 text-sm">
              <dt className="text-slate-500">Bought</dt>
              <dt className="text-slate-500">Paid</dt>
              <dt className="text-slate-500">Owed</dt>
              <dd className="num font-medium text-slate-800">{rupees(vendor.boughtPaise)}</dd>
              <dd className="num font-medium text-slate-800">{rupees(vendor.paidPaise)}</dd>
              <dd className={`num font-semibold ${vendor.outstandingPaise > 0 ? 'text-rose-600' : 'text-emerald-700'}`}>
                {rupees(vendor.outstandingPaise)}
              </dd>
            </dl>
            <div className="flex gap-2">
              <Button onClick={() => onEdit(vendor)}>Edit</Button>
              <Button tone="primary" onClick={() => setPaying(true)}>Record a payment</Button>
            </div>
          </div>

          {vendor.waitingPaise > 0 && (
            <Banner tone="warn">
              <span className="num">{rupees(vendor.waitingPaise)}</span> recorded as paid and waiting
              for an administrator. It is not counted in what is owed until somebody approves it.
            </Banner>
          )}

          {(vendor.phone || vendor.gstin || vendor.address || vendor.note) && (
            <p className="text-xs text-slate-500">
              {[vendor.phone, vendor.gstin, vendor.address, vendor.note].filter(Boolean).join(' · ')}
            </p>
          )}

          <section>
            <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-slate-400">
              Bought from them
            </h3>
            <div className="max-h-56 overflow-auto rounded-md border border-slate-100">
              {profile.purchases.length === 0 ? (
                <Empty>Nothing bought from them yet.</Empty>
              ) : (
                <table className="w-full table-fixed text-sm">
                  <colgroup>
                    <col className="w-36" />
                    <col />
                    <col className="w-24" />
                    <col className="w-28" />
                    <col className="w-28" />
                  </colgroup>
                  <tbody>
                    {profile.purchases.map((purchase) => (
                      <tr key={purchase.id} className="border-t border-slate-100">
                        <td className="num py-1.5 pl-2 text-slate-500">
                          {billDate(new Date(purchase.occurredAt))}
                        </td>
                        <td className="truncate py-1.5" title={`${purchase.itemCode} · ${purchase.name}`}>
                          {purchase.name}
                          <span className="ml-2 text-xs text-slate-400">
                            {purchase.fromTheStore ? 'store' : 'shelf'}
                          </span>
                        </td>
                        <td className="num py-1.5 text-right text-slate-500">
                          {quantity(purchase.quantityMilli)} {purchase.unit}
                        </td>
                        <td className="num py-1.5 text-right text-slate-500">
                          {rupees(purchase.buyingPricePaise)}
                        </td>
                        <td className="num py-1.5 pr-2 text-right font-medium">{rupees(purchase.valuePaise)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          </section>

          <section>
            <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-slate-400">Paid to them</h3>
            <div className="max-h-56 overflow-auto rounded-md border border-slate-100">
              {profile.payments.length === 0 ? (
                <Empty>Nothing paid yet.</Empty>
              ) : (
                <table className="w-full table-fixed text-sm">
                  <colgroup>
                    <col className="w-36" />
                    <col className="w-32" />
                    <col className="w-32" />
                    <col />
                    <col className="w-28" />
                    <col className="w-28" />
                  </colgroup>
                  <tbody>
                    {profile.payments.map((payment) => (
                      <tr key={payment.id} className="border-t border-slate-100">
                        <td className="num py-1.5 pl-2 text-slate-500">{payment.paidOn}</td>
                        <td className="py-1.5 text-slate-600">{MODE_LABELS[payment.mode]}</td>
                        <td className="num truncate py-1.5 text-xs text-slate-500">
                          {payment.purchaseOrderId
                            ? orders.find((order) => order.id === payment.purchaseOrderId)?.number ?? 'an order'
                            : <span className="text-slate-400">on account</span>}
                        </td>
                        <td className="truncate py-1.5 text-xs text-slate-400" title={payment.reference}>
                          {payment.reference}
                        </td>
                        <td className="num py-1.5 text-right font-medium">{rupees(payment.amountPaise)}</td>
                        <td className="py-1.5 pr-2 text-right">
                          {payment.approved ? (
                            <span className="text-xs text-emerald-700">approved</span>
                          ) : canApprove ? (
                            <button type="button" onClick={() => void approve(payment.id)}
                                    className="rounded-md bg-sky-600 px-2 py-0.5 text-xs font-medium text-on-primary">
                              Approve
                            </button>
                          ) : (
                            <span className="text-xs text-amber-700">waiting</span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          </section>
        </div>
      )}

      {paying && vendor && (
        <PaymentDialog
          vendor={vendor}
          orders={orders}
          onToast={onToast}
          onClose={() => setPaying(false)}
          onDone={() => {
            setPaying(false)
            load()
            onChanged()
          }}
        />
      )}
    </Modal>
  )
}

/** Money handed over, which waits for an administrator before the balance moves. */
function PaymentDialog({ vendor, orders, onClose, onDone, onToast }: {
  vendor: Vendor
  orders: PurchaseOrder[]
  onClose: () => void
  onDone: () => void
  onToast: Toast
}) {
  const [amount, setAmount] = useState('')
  const [mode, setMode] = useState<VendorPaymentMode>('CASH')
  const [reference, setReference] = useState('')
  const [note, setNote] = useState('')
  const [against, setAgainst] = useState('')
  const [errors, setErrors] = useState<{ message: string; fields: Record<string, string> } | null>(null)

  /**
   * The orders money can be put against: ones the goods are owed for and not yet settled. An
   * order nobody has approved is not owed for, and a rejected one never will be.
   */
  const owing = orders.filter((order) => order.owedPaise > 0 && order.settled !== 'PAID')

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    const paise = paiseFromRupees(amount)
    if (paise <= 0) {
      setErrors({ message: 'Enter how much was paid.', fields: {} })
      return
    }
    try {
      await api.post(`/api/vendors/${vendor.id}/payments`, {
        amountPaise: paise,
        paidOn: new Date().toISOString().slice(0, 10),
        mode,
        reference: reference.trim(),
        note: note.trim(),
        purchaseOrderId: against || null,
      })
      onToast({ tone: 'good', text: 'Recorded. It waits for an administrator to approve it.' })
      onDone()
    } catch (failure) {
      setErrors({ message: messageOfFailure(failure, 'Could not record that'), fields: fieldErrorsOf(failure) })
    }
  }

  return (
    <Modal title={`Pay ${vendor.name}`} onClose={onClose}>
      <form onSubmit={submit} className="space-y-3">
        {errors && <Banner tone="bad" onDismiss={() => setErrors(null)}>{errors.message}</Banner>}
        <p className="text-sm text-slate-500">
          Owed <span className="num font-medium text-slate-700">{rupees(vendor.outstandingPaise)}</span>
        </p>
        <Field label="Amount" error={errorFor(errors?.fields ?? {}, 'amountPaise')}>
          <input className={`${inputClass} num text-lg`} inputMode="decimal" autoFocus value={amount}
                 onChange={(event) => setAmount(event.target.value)} />
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="How" error={errorFor(errors?.fields ?? {}, 'mode')}>
            <select className={inputClass} value={mode}
                    onChange={(event) => setMode(event.target.value as VendorPaymentMode)}>
              {MODES.map((one) => <option key={one.key} value={one.key}>{one.label}</option>)}
            </select>
          </Field>
          <Field label="Reference" hint="Cheque number, UPI reference"
                 error={errorFor(errors?.fields ?? {}, 'reference')}>
            <input className={inputClass} value={reference}
                   onChange={(event) => setReference(event.target.value)} />
          </Field>
        </div>
        <Field
          label="Against which order"
          optional
          hint={owing.length === 0
            ? 'Nothing is waiting to be paid for. This goes against the running account.'
            : 'Leave it on the account if this is not for one delivery'}
          error={errorFor(errors?.fields ?? {}, 'purchaseOrderId')}
        >
          <select className={inputClass} value={against}
                  onChange={(event) => {
                    setAgainst(event.target.value)
                    // Fills in what is left on that order, which is what somebody is usually
                    // paying. Typed over freely: a part payment is ordinary.
                    const order = owing.find((one) => one.id === event.target.value)
                    if (order && !amount.trim()) {
                      setAmount(((order.owedPaise - order.paidPaise) / 100).toFixed(2))
                    }
                  }}>
            <option value="">The running account</option>
            {owing.map((order) => (
              <option key={order.id} value={order.id}>
                {order.number} · {billDate(new Date(order.orderedOn))} ·{' '}
                {rupees(order.owedPaise - order.paidPaise)} left
              </option>
            ))}
          </select>
        </Field>
        <Field label="Note" error={errorFor(errors?.fields ?? {}, 'note')}>
          <input className={inputClass} value={note} onChange={(event) => setNote(event.target.value)} />
        </Field>
        <p className="text-xs text-slate-400">
          It is recorded straight away and counts against what is owed once an administrator
          approves it.
        </p>
        <div className="flex justify-end gap-2">
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" tone="primary">Record the payment</Button>
        </div>
      </form>
    </Modal>
  )
}

/** Adding one, or changing who they are. */
function VendorDialog({ draft, onClose, onDone, onToast }: {
  draft: Editing
  onClose: () => void
  onDone: () => void
  onToast: Toast
}) {
  const [form, setForm] = useState(draft)
  const [errors, setErrors] = useState<{ message: string; fields: Record<string, string> } | null>(null)
  const set = <K extends keyof Editing>(key: K, value: Editing[K]) => setForm((now) => ({ ...now, [key]: value }))

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    const body = {
      code: form.code.trim(),
      name: form.name.trim(),
      phone: form.phone.trim(),
      gstin: form.gstin.trim().toUpperCase(),
      address: form.address.trim(),
      note: form.note.trim(),
      active: form.active,
    }
    try {
      if (form.id) {
        await api.put(`/api/vendors/${form.id}`, body)
      } else {
        await api.post('/api/vendors', body)
      }
      onToast({ tone: 'good', text: `${body.name} saved` })
      onDone()
    } catch (failure) {
      setErrors({ message: messageOfFailure(failure, 'Could not save them'), fields: fieldErrorsOf(failure) })
    }
  }

  return (
    <Modal title={form.id ? `Edit ${draft.name}` : 'Add a vendor'} size="xl" onClose={onClose}>
      <form onSubmit={submit} className="space-y-3">
        {errors && <Banner tone="bad" onDismiss={() => setErrors(null)}>{errors.message}</Banner>}
        <div className="grid grid-cols-3 gap-3">
          <Field label="Code" error={errorFor(errors?.fields ?? {}, 'code')}>
            <input className={`${inputClass} num`} autoFocus value={form.code}
                   onChange={(event) => set('code', event.target.value)} />
          </Field>
          <div className="col-span-2">
            <Field label="Name" error={errorFor(errors?.fields ?? {}, 'name')}>
              <input className={inputClass} value={form.name}
                     onChange={(event) => set('name', event.target.value)} />
            </Field>
          </div>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Phone" error={errorFor(errors?.fields ?? {}, 'phone')}>
            <input className={`${inputClass} num`} value={form.phone}
                   onChange={(event) => set('phone', event.target.value)} />
          </Field>
          <Field label="GSTIN" hint="Only if they are registered"
                 error={errorFor(errors?.fields ?? {}, 'gstin')}>
            <input className={`${inputClass} num uppercase`} value={form.gstin}
                   onChange={(event) => set('gstin', event.target.value)} />
          </Field>
        </div>
        <Field label="Address" error={errorFor(errors?.fields ?? {}, 'address')}>
          <input className={inputClass} value={form.address}
                 onChange={(event) => set('address', event.target.value)} />
        </Field>
        <Field label="Note" error={errorFor(errors?.fields ?? {}, 'note')}>
          <input className={inputClass} value={form.note}
                 onChange={(event) => set('note', event.target.value)} />
        </Field>
        <label className="flex items-center gap-2 text-sm text-slate-600">
          <input type="checkbox" checked={form.active} onChange={(event) => set('active', event.target.checked)} />
          Still buying from them
        </label>
        <div className="flex justify-end gap-2">
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" tone="primary">Save</Button>
        </div>
      </form>
    </Modal>
  )
}
