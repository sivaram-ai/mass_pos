import { useCallback, useEffect, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import {
  api, billDate, fieldErrorsOf, messageOfFailure, milliFromUnits, paiseFromRupees, quantity, rupees,
} from '../api'
import type {
  Product, PurchaseOrder, PurchaseOrderStatus, RawMaterial, Role, Vendor,
} from '../types'
import {
  Banner, Button, Empty, errorFor, Field, inputClass, KeyBox, Modal, Panel, smallInputClass,
} from '../components/ui'
import { useScreenKeys } from '../shortcuts'
import type { Toast } from '../App'

const SEARCH_KEY = 'F2'
const ADD_KEY = 'F3'

/** The shop's own words for where an order has got to, and how each one reads on the list. */
const STATUS: Record<PurchaseOrderStatus, { label: string; tone: string }> = {
  PENDING_APPROVAL: { label: 'Waiting', tone: 'bg-amber-100 text-amber-800' },
  APPROVED: { label: 'Approved', tone: 'bg-sky-100 text-sky-800' },
  REJECTED: { label: 'Turned down', tone: 'bg-rose-100 text-rose-700' },
  RECEIVED: { label: 'Received', tone: 'bg-emerald-100 text-emerald-800' },
  CANCELLED: { label: 'Called off', tone: 'bg-slate-100 text-slate-500' },
}

const FILTERS: { label: string; status: PurchaseOrderStatus | null }[] = [
  { label: 'Everything', status: null },
  { label: 'Waiting', status: 'PENDING_APPROVAL' },
  { label: 'Approved', status: 'APPROVED' },
  { label: 'Received', status: 'RECEIVED' },
  { label: 'Turned down', status: 'REJECTED' },
]

type Draft = {
  vendorId: string
  expectedOn: string
  note: string
  lines: { key: number; what: string; quantity: string; cost: string }[]
}

/**
 * Ordering goods: asking, agreeing, and taking them in.
 *
 * <p>The point of the screen is the gap between the three. A manager writes down what the kitchen
 * needs, an administrator agrees to the money, and the counts move only when the lorry actually
 * arrives - with what actually came off it, which is not always what was ordered. Before this, a
 * delivery was the first anybody heard of a purchase.
 *
 * <p>Worked like every other list here: ↑ ↓ choose, Enter opens an order, F3 writes one, F2
 * searches, ← → pick which ones to show.
 */
export default function Purchases({ role, onToast }: { role: Role; onToast: Toast }) {
  const [rows, setRows] = useState<PurchaseOrder[]>([])
  const [filter, setFilter] = useState('')
  const [kind, setKind] = useState(0)
  const [selected, setSelected] = useState(0)
  const [writing, setWriting] = useState(false)
  const [openOn, setOpenOn] = useState<string | null>(null)
  const list = useRef<HTMLDivElement>(null)
  const searchBox = useRef<HTMLInputElement>(null)
  const lines = useRef(new Map<string, HTMLTableRowElement>())
  const dialogOpen = writing || openOn !== null

  const load = useCallback(() => {
    const status = FILTERS[kind].status
    api.get<PurchaseOrder[]>(`/api/purchase-orders${status ? `?status=${status}` : ''}`)
      .then(setRows)
      .catch((failure) => onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not load the orders') }))
  }, [onToast, kind])

  useEffect(load, [load])

  useEffect(() => {
    list.current?.focus()
  }, [])

  const looking = filter.trim().toLowerCase()
  const shown = rows.filter((row) =>
    `${row.number} ${row.vendorName} ${row.note}`.toLowerCase().includes(looking))
  const waiting = rows.filter((row) => row.status === 'PENDING_APPROVAL').length

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
    [ADD_KEY]: () => setWriting(true),
  }, !dialogOpen)

  const backToList = (reload = false) => {
    setWriting(false)
    setOpenOn(null)
    if (reload) {
      load()
    }
    requestAnimationFrame(() => list.current?.focus())
  }

  const onListKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (dialogOpen) {
      return
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      setSelected(Math.max(0, Math.min(shown.length - 1, at + (event.key === 'ArrowDown' ? 1 : -1))))
    } else if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
      event.preventDefault()
      setKind((now) => Math.max(0, Math.min(FILTERS.length - 1, now + (event.key === 'ArrowRight' ? 1 : -1))))
      setSelected(0)
    } else if (event.key === 'Home' || event.key === 'End') {
      event.preventDefault()
      setSelected(event.key === 'Home' ? 0 : shown.length - 1)
    } else if (event.key === 'Enter' && current && !event.repeat) {
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
      title="Purchase orders"
      actions={
        <div className="flex items-center gap-2">
          <span className="whitespace-nowrap text-xs text-slate-500">
            {rows.length} orders
            {waiting > 0 && <span className="ml-2 text-amber-700">{waiting} waiting to be approved</span>}
          </span>
          <Button className="whitespace-nowrap px-2 py-1" tone="primary" hint={ADD_KEY}
                  onClick={() => setWriting(true)}>
            Write an order
          </Button>
          <KeyBox hint={SEARCH_KEY}>
            <input
              ref={searchBox}
              className={`${smallInputClass} w-56 pr-8`}
              placeholder="Number, vendor or note"
              aria-label="Filter orders"
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
      <div className="mb-2 flex flex-wrap items-center gap-1" role="tablist" aria-label="Which orders">
        {FILTERS.map((one, index) => (
          <button
            key={one.label}
            type="button"
            role="tab"
            aria-selected={index === kind}
            onClick={() => {
              setKind(index)
              setSelected(0)
              list.current?.focus()
            }}
            className={`rounded-full px-3 py-1 text-xs transition ${
              index === kind
                ? 'bg-sky-600 text-on-primary font-medium'
                : 'border border-slate-200 text-slate-600 hover:bg-slate-50'}`}
          >
            {one.label}
          </button>
        ))}
      </div>

      <p className="mb-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-400">
        <span><kbd>↑</kbd> <kbd>↓</kbd> choose</span>
        <span><kbd>←</kbd> <kbd>→</kbd> which ones</span>
        <span><kbd>Enter</kbd> open</span>
        <span><kbd>{SEARCH_KEY}</kbd> search · <kbd>{ADD_KEY}</kbd> write one</span>
        <span><kbd>F9</kbd> menu · <kbd>Esc</kbd> <kbd>Esc</kbd> billing</span>
      </p>

      <div
        ref={list}
        tabIndex={0}
        role="grid"
        aria-label="Purchase orders"
        onKeyDown={onListKeyDown}
        className="overflow-x-auto rounded-md outline-none focus-visible:ring-2 focus-visible:ring-sky-200"
      >
        {shown.length === 0 ? (
          <Empty>
            {filter || kind > 0
              ? 'Nothing here matches.'
              : 'No orders yet. Write one for whatever the shop needs next.'}
          </Empty>
        ) : (
          <table className="w-full min-w-[54rem] table-fixed text-sm">
            <colgroup>
              <col className="w-40" />
              <col />
              <col className="w-32" />
              <col className="w-28" />
              <col className="w-32" />
            </colgroup>
            <thead className="text-xs uppercase tracking-wide text-slate-400">
              <tr>
                <th className="py-1 pl-2 text-left">Number</th>
                <th className="py-1 text-left">Vendor</th>
                <th className="py-1 text-left">Ordered</th>
                <th className="py-1 text-right">Value</th>
                <th className="py-1 pr-2 text-right">State</th>
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
                        : 'hover:bg-slate-50'}`}
                  >
                    <td className="num py-1.5 pl-2 font-medium text-slate-700">{row.number}</td>
                    <td className="truncate py-1.5 pr-2" title={row.vendorName}>{row.vendorName}</td>
                    <td className="num py-1.5 text-slate-500">{billDate(new Date(row.orderedOn))}</td>
                    <td className="num py-1.5 text-right">{rupees(row.valuePaise)}</td>
                    <td className="py-1.5 pr-2 text-right">
                      <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${STATUS[row.status].tone}`}>
                        {STATUS[row.status].label}
                      </span>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </div>

      {openOn && (
        <OrderPage
          orderId={openOn}
          role={role}
          onToast={onToast}
          onChanged={load}
          onClose={() => backToList()}
        />
      )}

      {writing && (
        <OrderDialog onToast={onToast} onClose={() => backToList()} onDone={() => backToList(true)} />
      )}
    </Panel>
  )
}

/** One order: what was asked for, who decided what, and whatever can be done to it next. */
function OrderPage({ orderId, role, onToast, onChanged, onClose }: {
  orderId: string
  role: Role
  onToast: Toast
  onChanged: () => void
  onClose: () => void
}) {
  const [order, setOrder] = useState<PurchaseOrder | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [why, setWhy] = useState('')
  const [receiving, setReceiving] = useState<Record<string, string>>({})
  const canDecide = role === 'ADMIN'

  const load = useCallback(() => {
    api.get<PurchaseOrder>(`/api/purchase-orders/${orderId}`)
      .then((one) => {
        setOrder(one)
        // Opens on what was ordered: the lorry usually brings what was asked for, and retyping it
        // every time is how somebody ends up recording the wrong thing.
        setReceiving(Object.fromEntries(one.lines.map((line) => [line.id, quantity(line.quantityMilli)])))
      })
      .catch((failure) => setError(messageOfFailure(failure, 'Could not open this order')))
  }, [orderId])

  useEffect(load, [load])

  const act = async (what: string, body?: unknown) => {
    setBusy(true)
    setError(null)
    try {
      await api.post(`/api/purchase-orders/${orderId}/${what}`, body ?? {})
      onToast({ tone: 'good', text: `Order ${order?.number ?? ''} ${what === 'receive' ? 'received' : what + 'd'}` })
      load()
      onChanged()
    } catch (failure) {
      setError(messageOfFailure(failure, 'Could not do that'))
    } finally {
      setBusy(false)
    }
  }

  const receive = () => act('receive', {
    arrived: (order?.lines ?? []).map((line) => ({
      lineId: line.id,
      quantityMilli: milliFromUnits(receiving[line.id] ?? ''),
      unitCostPaise: line.unitCostPaise,
    })),
  })

  return (
    <Modal title={order ? `${order.number} · ${order.vendorName}` : 'Order'} size="5xl" onClose={onClose}>
      {error && <div className="mb-3"><Banner tone="bad" onDismiss={() => setError(null)}>{error}</Banner></div>}
      {!order ? (
        <Empty>Opening…</Empty>
      ) : (
        <div className="space-y-4">
          <div className="flex flex-wrap items-center gap-3 text-sm">
            <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${STATUS[order.status].tone}`}>
              {STATUS[order.status].label}
            </span>
            <span className="text-slate-500">
              Ordered {billDate(new Date(order.orderedOn))} by {order.createdBy}
              {order.expectedOn && ` · expected ${billDate(new Date(order.expectedOn))}`}
            </span>
            <span className="num ml-auto text-base font-semibold">{rupees(order.valuePaise)}</span>
          </div>

          {order.note && <p className="text-sm text-slate-600">{order.note}</p>}

          {order.decision && (
            <Banner tone={order.status === 'REJECTED' ? 'bad' : 'warn'}>
              {STATUS[order.status].label} by {order.decidedBy}: {order.decision}
            </Banner>
          )}

          <div className="overflow-auto rounded-md border border-slate-100">
            <table className="w-full table-fixed text-sm">
              <colgroup>
                <col className="w-28" />
                <col />
                <col className="w-28" />
                <col className="w-28" />
                <col className="w-28" />
                <col className="w-32" />
              </colgroup>
              <thead className="text-xs uppercase tracking-wide text-slate-400">
                <tr>
                  <th className="py-1 pl-2 text-left">Code</th>
                  <th className="py-1 text-left">Item</th>
                  <th className="py-1 text-right">Ordered</th>
                  <th className="py-1 text-right">Each</th>
                  <th className="py-1 text-right">Value</th>
                  <th className="py-1 pr-2 text-right">
                    {order.status === 'APPROVED' ? 'Arrived' : 'Received'}
                  </th>
                </tr>
              </thead>
              <tbody>
                {order.lines.map((line) => (
                  <tr key={line.id} className="border-t border-slate-100">
                    <td className="num py-1.5 pl-2 text-slate-500">{line.itemCode}</td>
                    <td className="truncate py-1.5" title={line.name}>
                      {line.name}
                      <span className="ml-2 text-xs text-slate-400">
                        {line.fromTheStore ? 'store' : 'shelf'}
                      </span>
                    </td>
                    <td className="num py-1.5 text-right">{quantity(line.quantityMilli)} {line.unit}</td>
                    <td className="num py-1.5 text-right text-slate-500">{rupees(line.unitCostPaise)}</td>
                    <td className="num py-1.5 text-right">{rupees(line.valuePaise)}</td>
                    <td className="py-1 pr-2 text-right">
                      {order.status === 'APPROVED' ? (
                        <input
                          className={`${smallInputClass} num w-20 text-right`}
                          inputMode="decimal"
                          aria-label={`How much of ${line.name} arrived`}
                          value={receiving[line.id] ?? ''}
                          onChange={(event) =>
                            setReceiving((now) => ({ ...now, [line.id]: event.target.value }))}
                        />
                      ) : (
                        <span className="num text-slate-500">
                          {order.status === 'RECEIVED' ? quantity(line.receivedMilli) : '—'}
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {order.status === 'PENDING_APPROVAL' && (
            canDecide ? (
              <div className="flex flex-wrap items-end gap-2">
                <div className="min-w-[16rem] flex-1">
                  <Field label="If you are turning it down, why?">
                    <input className={inputClass} value={why} onChange={(event) => setWhy(event.target.value)} />
                  </Field>
                </div>
                <Button disabled={busy} onClick={() => void act('reject', { decision: why })}>Turn it down</Button>
                <Button tone="primary" disabled={busy} onClick={() => void act('approve')}>Approve</Button>
              </div>
            ) : (
              <Banner tone="warn">
                Waiting for an administrator. Only they can agree to the money.
              </Banner>
            )
          )}

          {order.status === 'APPROVED' && (
            <div className="flex flex-wrap items-center justify-end gap-2">
              <span className="mr-auto text-xs text-slate-400">
                Change the figures above if the lorry brought something different. The counts move
                when you take it in.
              </span>
              <Button disabled={busy} onClick={() => void act('cancel', { decision: 'Called off' })}>
                Call it off
              </Button>
              <Button tone="primary" disabled={busy} onClick={() => void receive()}>
                Take the goods in
              </Button>
            </div>
          )}

          {order.status === 'RECEIVED' && (
            <p className="text-xs text-slate-400">
              Taken in by {order.receivedBy}. The counts moved then, and each item's history shows
              this order against the delivery.
            </p>
          )}
        </div>
      )}
    </Modal>
  )
}

/** Writing one: who it is going to, and what the shop wants. */
function OrderDialog({ onClose, onDone, onToast }: {
  onClose: () => void
  onDone: () => void
  onToast: Toast
}) {
  const [vendors, setVendors] = useState<Vendor[]>([])
  const [products, setProducts] = useState<Product[]>([])
  const [materials, setMaterials] = useState<RawMaterial[]>([])
  const [form, setForm] = useState<Draft>({
    vendorId: '',
    expectedOn: '',
    note: '',
    lines: [{ key: 1, what: '', quantity: '', cost: '' }],
  })
  const [errors, setErrors] = useState<{ message: string; fields: Record<string, string> } | null>(null)
  const nextKey = useRef(2)

  useEffect(() => {
    api.get<Vendor[]>('/api/vendors').then((all) => setVendors(all.filter((one) => one.active))).catch(() => {})
    api.get<Product[]>('/api/products?all=true').then(setProducts).catch(() => {})
    api.get<RawMaterial[]>('/api/raw-materials').then(setMaterials).catch(() => {})
  }, [])

  const setLine = (key: number, what: Partial<Draft['lines'][number]>) =>
    setForm((now) => ({
      ...now,
      lines: now.lines.map((line) => (line.key === key ? { ...line, ...what } : line)),
    }))

  const total = form.lines.reduce(
    (sum, line) => sum + Math.round(milliFromUnits(line.quantity) * paiseFromRupees(line.cost) / 1000), 0)

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    const lines = form.lines
      .filter((line) => line.what && milliFromUnits(line.quantity) > 0)
      .map((line) => {
        const [kind, id] = line.what.split(':')
        return {
          productId: kind === 'p' ? id : null,
          rawMaterialId: kind === 'r' ? id : null,
          quantityMilli: milliFromUnits(line.quantity),
          unitCostPaise: paiseFromRupees(line.cost),
        }
      })
    if (lines.length === 0) {
      setErrors({ message: 'Put at least one thing on the order.', fields: {} })
      return
    }
    try {
      const made = await api.post<PurchaseOrder>('/api/purchase-orders', {
        vendorId: form.vendorId,
        expectedOn: form.expectedOn || null,
        note: form.note.trim(),
        lines,
      })
      onToast({ tone: 'good', text: `${made.number} written. It waits for an administrator.` })
      onDone()
    } catch (failure) {
      setErrors({ message: messageOfFailure(failure, 'Could not write that order'), fields: fieldErrorsOf(failure) })
    }
  }

  return (
    <Modal title="Write a purchase order" size="5xl" onClose={onClose}>
      <form onSubmit={submit} className="space-y-3">
        {errors && <Banner tone="bad" onDismiss={() => setErrors(null)}>{errors.message}</Banner>}
        <div className="grid grid-cols-3 gap-3">
          <Field label="Vendor" error={errorFor(errors?.fields ?? {}, 'vendorId')}>
            <select className={inputClass} autoFocus value={form.vendorId}
                    onChange={(event) => setForm((now) => ({ ...now, vendorId: event.target.value }))}>
              <option value="">Choose one</option>
              {vendors.map((vendor) => <option key={vendor.id} value={vendor.id}>{vendor.name}</option>)}
            </select>
          </Field>
          <Field label="Expected" hint="Optional" error={errorFor(errors?.fields ?? {}, 'expectedOn')}>
            <input className={inputClass} type="date" value={form.expectedOn}
                   onChange={(event) => setForm((now) => ({ ...now, expectedOn: event.target.value }))} />
          </Field>
          <Field label="Note" error={errorFor(errors?.fields ?? {}, 'note')}>
            <input className={inputClass} value={form.note}
                   onChange={(event) => setForm((now) => ({ ...now, note: event.target.value }))} />
          </Field>
        </div>

        <div className="space-y-2">
          {form.lines.map((line) => (
            <div key={line.key} className="grid grid-cols-[1fr_7rem_7rem_2rem] items-end gap-2">
              <Field label="Item">
                <select className={inputClass} value={line.what}
                        onChange={(event) => setLine(line.key, { what: event.target.value })}>
                  <option value="">Choose one</option>
                  <optgroup label="Off the shelf">
                    {products.map((product) => (
                      <option key={product.id} value={`p:${product.id}`}>{product.sku} · {product.name}</option>
                    ))}
                  </optgroup>
                  <optgroup label="From the store">
                    {materials.map((material) => (
                      <option key={material.id} value={`r:${material.id}`}>
                        {material.code} · {material.name}
                      </option>
                    ))}
                  </optgroup>
                </select>
              </Field>
              <Field label="How much">
                <input className={`${inputClass} num`} inputMode="decimal" value={line.quantity}
                       onChange={(event) => setLine(line.key, { quantity: event.target.value })} />
              </Field>
              <Field label="Each">
                <input className={`${inputClass} num`} inputMode="decimal" placeholder="0.00" value={line.cost}
                       onChange={(event) => setLine(line.key, { cost: event.target.value })} />
              </Field>
              <button
                type="button"
                aria-label="Take this line off"
                className="mb-1 rounded-md px-2 py-2 text-slate-400 hover:bg-slate-100 hover:text-rose-600"
                onClick={() => setForm((now) => ({
                  ...now,
                  lines: now.lines.length > 1 ? now.lines.filter((other) => other.key !== line.key) : now.lines,
                }))}
              >
                ✕
              </button>
            </div>
          ))}
          <Button onClick={() => setForm((now) => ({
            ...now,
            lines: [...now.lines, { key: nextKey.current++, what: '', quantity: '', cost: '' }],
          }))}>
            Another line
          </Button>
        </div>

        <div className="flex items-center justify-end gap-3 border-t border-slate-100 pt-3">
          <span className="text-sm text-slate-500">
            Comes to <span className="num font-semibold text-slate-800">{rupees(total)}</span>
          </span>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" tone="primary">Write it</Button>
        </div>
      </form>
    </Modal>
  )
}
