import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import { api, billDate, messageOfFailure, milliFromUnits, quantity, rupees } from '../api'
import type { PurchaseOrder, PurchaseOrderStatus, Role, Settled } from '../types'
import {
  Banner, Button, Empty, Field, inputClass, KeyBox, Modal, Panel, smallInputClass,
} from '../components/ui'
import { useScreenKeys } from '../shortcuts'
import { SortMenu } from '../components/SortMenu'
import { OrderDialog } from '../components/OrderDialog'
import { PriceHistory } from '../components/PriceHistory'
import type { PricesOf } from '../components/PriceHistory'
import type { SortDir } from '../sorting'
import type { Toast } from '../App'

const SEARCH_KEY = 'F2'
const ADD_KEY = 'F3'
const SORT_KEY = 'F4'

/** The shop's own words for where an order has got to, and how each one reads on the list. */
const STATUS: Record<PurchaseOrderStatus, { label: string; tone: string }> = {
  PENDING_APPROVAL: { label: 'Waiting', tone: 'bg-amber-100 text-amber-800' },
  APPROVED: { label: 'Approved', tone: 'bg-sky-100 text-sky-800' },
  REJECTED: { label: 'Turned down', tone: 'bg-rose-100 text-rose-700' },
  RECEIVED: { label: 'Received', tone: 'bg-emerald-100 text-emerald-800' },
  CANCELLED: { label: 'Called off', tone: 'bg-slate-100 text-slate-500' },
}

/** And whether the money has followed the goods. */
const PAID: Record<Settled, { label: string; tone: string }> = {
  UNPAID: { label: 'Not paid', tone: 'text-rose-600' },
  PART_PAID: { label: 'Part paid', tone: 'text-amber-700' },
  PAID: { label: 'Paid', tone: 'text-emerald-700' },
  NOT_DUE: { label: '—', tone: 'text-slate-300' },
}

const FILTERS: { label: string; status: PurchaseOrderStatus | null }[] = [
  { label: 'Everything', status: null },
  { label: 'Waiting', status: 'PENDING_APPROVAL' },
  { label: 'Approved', status: 'APPROVED' },
  { label: 'Received', status: 'RECEIVED' },
  { label: 'Turned down', status: 'REJECTED' },
]

/** Which money question is being asked, chosen beside the state chips. */
const MONEY: { label: string; of: Settled | null }[] = [
  { label: 'Any', of: null },
  { label: 'Not paid', of: 'UNPAID' },
  { label: 'Part paid', of: 'PART_PAID' },
  { label: 'Paid', of: 'PAID' },
]

type SortBy = 'date' | 'vendor' | 'item' | 'quantity' | 'each' | 'total' | 'approval' | 'payment'

const FIELDS: SortBy[] = ['date', 'vendor', 'item', 'quantity', 'each', 'total', 'approval', 'payment']

const LABELS: Record<SortBy, string> = {
  date: 'Date',
  vendor: 'Vendor',
  item: 'Item',
  quantity: 'How much',
  each: 'Cost each',
  total: 'Total amount',
  approval: 'Approval',
  payment: 'Payment',
}

const SORT_KEY_NAME = 'masspos.orderSort'

/** Oldest first would bury what is waiting, so the list opens newest first. */
const DEFAULT_SORT: { field: SortBy; dir: SortDir } = { field: 'date', dir: 'desc' }

function loadSort(): { field: SortBy; dir: SortDir } {
  try {
    const saved = localStorage.getItem(SORT_KEY_NAME)
    if (!saved) {
      return DEFAULT_SORT
    }
    const { field, dir } = JSON.parse(saved) as { field?: SortBy; dir?: SortDir }
    return {
      field: field && FIELDS.includes(field) ? field : DEFAULT_SORT.field,
      dir: dir === 'asc' ? 'asc' : 'desc',
    }
  } catch {
    return DEFAULT_SORT
  }
}

const collator = new Intl.Collator('en-IN', { numeric: true, sensitivity: 'base' })

/** How much is on an order, across every line of it, whichever list they came off. */
function howMuch(order: PurchaseOrder): number {
  return order.lines.reduce((sum, line) => sum + line.quantityMilli, 0)
}

/** What one unit on it has averaged, which is the figure worth comparing between two orders. */
function each(order: PurchaseOrder): number {
  const much = howMuch(order)
  return much > 0 ? Math.round(order.valuePaise * 1000 / much) : 0
}

/** The item that reads first on the order, so sorting by item settles on something. */
function firstItem(order: PurchaseOrder): string {
  return [...order.lines].map((line) => line.name).sort(collator.compare)[0] ?? ''
}

function compareBy(field: SortBy, left: PurchaseOrder, right: PurchaseOrder): number {
  switch (field) {
    case 'date':
      return left.orderedOn.localeCompare(right.orderedOn)
    case 'vendor':
      return collator.compare(left.vendorName, right.vendorName)
    case 'item':
      return collator.compare(firstItem(left), firstItem(right))
    case 'quantity':
      return howMuch(left) - howMuch(right)
    case 'each':
      return each(left) - each(right)
    case 'total':
      return left.valuePaise - right.valuePaise
    case 'approval':
      return Object.keys(STATUS).indexOf(left.status) - Object.keys(STATUS).indexOf(right.status)
    case 'payment':
      return Object.keys(PAID).indexOf(left.settled) - Object.keys(PAID).indexOf(right.settled)
  }
}

/**
 * Every order the shop has written: what it asked for, who agreed to it, what arrived and whether
 * it has been paid for.
 *
 * <p>Worked like the Items list, because it is the same job - a long list somebody needs to find
 * one row in. The cursor starts in the list, ↑ ↓ choose, Enter opens an order, ← → pick which
 * states to show, F2 searches every word on an order including the things on its lines, F3 writes
 * one and F4 sorts.
 *
 * <p>The dates are the one thing the server is asked about. Everything else is sifted here, over
 * the window that came back, exactly as the Items list sifts the catalogue: a server asked again
 * on every keystroke would be slower and would sort the wrong rows the moment it capped an answer.
 */
export default function Purchases({ role, onToast }: { role: Role; onToast: Toast }) {
  const [rows, setRows] = useState<PurchaseOrder[]>([])
  const [filter, setFilter] = useState('')
  const [kind, setKind] = useState(0)
  const [money, setMoney] = useState(0)
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [sort, setSort] = useState(loadSort)
  const [sortOpen, setSortOpen] = useState(false)
  const [selected, setSelected] = useState(0)
  const [writing, setWriting] = useState(false)
  const [openOn, setOpenOn] = useState<string | null>(null)
  const [pricesOn, setPricesOn] = useState<PricesOf | null>(null)
  const list = useRef<HTMLDivElement>(null)
  const searchBox = useRef<HTMLInputElement>(null)
  const lines = useRef(new Map<string, HTMLTableRowElement>())
  const dialogOpen = writing || openOn !== null || pricesOn !== null

  const load = useCallback(() => {
    const asking = new URLSearchParams()
    const status = FILTERS[kind].status
    if (status) {
      asking.set('status', status)
    }
    if (from) {
      asking.set('from', from)
    }
    if (to) {
      asking.set('to', to)
    }
    api.get<PurchaseOrder[]>(`/api/purchase-orders?${asking.toString()}`)
      .then(setRows)
      .catch((failure) => onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not load the orders') }))
  }, [onToast, kind, from, to])

  useEffect(load, [load])

  useEffect(() => {
    list.current?.focus()
  }, [])

  const chooseSort = (next: { field: SortBy; dir: SortDir }) => {
    setSort(next)
    try {
      localStorage.setItem(SORT_KEY_NAME, JSON.stringify(next))
    } catch {
      // Not fatal: the list falls back to newest first next time.
    }
    setSelected(0)
  }

  const shown = useMemo(() => {
    const looking = filter.trim().toLowerCase()
    const wanted = MONEY[money].of
    const matching = rows.filter((row) => {
      if (wanted && row.settled !== wanted) {
        return false
      }
      if (!looking) {
        return true
      }
      // Everything on the order, the things on its lines included: somebody looking for an order
      // remembers the onions, not the number.
      const words = [
        row.number, row.vendorName, row.note, row.createdBy, row.decidedBy, row.receivedBy,
        STATUS[row.status].label, PAID[row.settled].label,
        ...row.lines.map((line) => `${line.itemCode} ${line.name}`),
      ].join(' ').toLowerCase()
      return words.includes(looking)
    })
    const sign = sort.dir === 'asc' ? 1 : -1
    return [...matching].sort((left, right) => {
      const by = compareBy(sort.field, left, right) * sign
      // Ties fall back to the number, which is unique and carries the day it was written.
      return by !== 0 ? by : collator.compare(right.number, left.number)
    })
  }, [rows, filter, money, sort])

  const waiting = rows.filter((row) => row.status === 'PENDING_APPROVAL').length
  const owed = rows.filter((row) => row.settled === 'UNPAID' || row.settled === 'PART_PAID')
    .reduce((sum, row) => sum + Math.max(0, row.owedPaise - row.paidPaise), 0)

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
    [SORT_KEY]: () => setSortOpen(true),
  }, !dialogOpen && !sortOpen)

  const backToList = (reload = false) => {
    setWriting(false)
    setOpenOn(null)
    setPricesOn(null)
    if (reload) {
      load()
    }
    requestAnimationFrame(() => list.current?.focus())
  }

  const onListKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (dialogOpen || sortOpen) {
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
      // Esc here only leaves the search box: it must not count toward Esc Esc back to billing.
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
        <div className="flex flex-wrap items-center justify-end gap-2">
          <span className="whitespace-nowrap text-xs text-slate-500">
            {rows.length} {rows.length === 1 ? 'order' : 'orders'}
            {waiting > 0 && <span className="ml-2 text-amber-700">{waiting} waiting to be approved</span>}
            {owed > 0 && (
              <span className="ml-2 text-rose-600"><span className="num">{rupees(owed)}</span> unpaid</span>
            )}
          </span>
          {/* Relative, so the sort popover hangs off this button rather than the page. */}
          <div className="relative">
            <Button className="whitespace-nowrap px-2 py-1" hint={SORT_KEY}
                    onClick={() => setSortOpen((open) => !open)}
                    title="Choose what the list is ordered by">
              {LABELS[sort.field]} {sort.dir === 'asc' ? '↑' : '↓'}
            </Button>
            {sortOpen && (
              <SortMenu
                sort={sort}
                fields={FIELDS}
                labels={LABELS}
                onChange={chooseSort}
                onClose={() => {
                  setSortOpen(false)
                  requestAnimationFrame(() => list.current?.focus())
                }}
              />
            )}
          </div>
          <Button tone="primary" className="whitespace-nowrap px-2 py-1" hint={ADD_KEY}
                  onClick={() => setWriting(true)}>
            Write an order
          </Button>
          <KeyBox hint={SEARCH_KEY}>
            <input
              ref={searchBox}
              className={`${smallInputClass} w-64 pr-8`}
              placeholder="Number, vendor, item or person"
              aria-label="Search the orders"
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
      <div className="mb-2 flex flex-wrap items-center gap-x-4 gap-y-2">
        <div className="flex flex-wrap items-center gap-1" role="tablist" aria-label="Which orders">
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

        <div className="flex flex-wrap items-center gap-1" role="tablist" aria-label="Paid or not">
          <span className="mr-1 text-xs uppercase tracking-wide text-slate-400">Money</span>
          {MONEY.map((one, index) => (
            <button
              key={one.label}
              type="button"
              role="tab"
              aria-selected={index === money}
              onClick={() => {
                setMoney(index)
                setSelected(0)
                list.current?.focus()
              }}
              className={`rounded-full px-3 py-1 text-xs transition ${
                index === money
                  ? 'bg-secondary text-on-secondary font-medium'
                  : 'border border-slate-200 text-slate-600 hover:bg-slate-50'}`}
            >
              {one.label}
            </button>
          ))}
        </div>

        <div className="flex items-center gap-1 text-xs text-slate-500">
          <span>From</span>
          <input type="date" className={`${smallInputClass} w-36`} value={from}
                 aria-label="Orders written from"
                 onChange={(event) => {
                   setFrom(event.target.value)
                   setSelected(0)
                 }} />
          <span>to</span>
          <input type="date" className={`${smallInputClass} w-36`} value={to}
                 aria-label="Orders written up to"
                 onChange={(event) => {
                   setTo(event.target.value)
                   setSelected(0)
                 }} />
          {from || to ? (
            <button type="button" className="text-slate-400 underline hover:text-slate-600"
                    onClick={() => {
                      setFrom('')
                      setTo('')
                    }}>
              clear
            </button>
          ) : (
            <span className="text-slate-400">last 90 days</span>
          )}
        </div>
      </div>

      <p className="mb-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-400">
        <span><kbd>↑</kbd> <kbd>↓</kbd> choose</span>
        <span><kbd>←</kbd> <kbd>→</kbd> which ones</span>
        <span><kbd>Enter</kbd> open</span>
        <span><kbd>{SEARCH_KEY}</kbd> search · <kbd>{SORT_KEY}</kbd> sort · <kbd>{ADD_KEY}</kbd> write one</span>
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
            {filter || kind > 0 || money > 0 || from || to
              ? 'Nothing here matches.'
              : 'No orders yet. Write one for whatever the shop needs next.'}
          </Empty>
        ) : (
          <table className="w-full min-w-[68rem] table-fixed text-sm">
            <colgroup>
              <col className="w-36" />
              <col className="w-28" />
              <col className="w-48" />
              <col />
              <col className="w-24" />
              <col className="w-28" />
              <col className="w-28" />
              <col className="w-28" />
            </colgroup>
            <thead className="text-xs uppercase tracking-wide text-slate-400">
              <tr>
                <th className="py-1 pl-2 text-left">Number</th>
                <th className="py-1 text-left">Ordered</th>
                <th className="py-1 text-left">Vendor</th>
                <th className="py-1 text-left">What for</th>
                <th className="py-1 text-right">How much</th>
                <th className="py-1 text-right">Total</th>
                <th className="py-1 text-right">State</th>
                <th className="py-1 pr-2 text-right">Money</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((row, index) => {
                const isSelected = index === at
                const much = howMuch(row)
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
                    <td className="num py-1.5 text-slate-500">{billDate(new Date(row.orderedOn))}</td>
                    <td className="truncate py-1.5 pr-2" title={row.vendorName}>{row.vendorName}</td>
                    <td className="truncate py-1.5 pr-2"
                        title={row.lines.map((line) => `${line.itemCode} ${line.name}`).join(', ')}>
                      {firstItem(row)}
                      {row.lines.length > 1 && (
                        <span className="ml-1 text-xs text-slate-400">and {row.lines.length - 1} more</span>
                      )}
                    </td>
                    <td className="num py-1.5 text-right text-slate-500">
                      {quantity(much)}
                      {row.lines.length === 1 && (
                        <span className="ml-1 text-xs text-slate-400">{row.lines[0].unit}</span>
                      )}
                    </td>
                    <td className="num py-1.5 text-right">{rupees(row.valuePaise)}</td>
                    <td className="py-1.5 text-right">
                      <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${STATUS[row.status].tone}`}>
                        {STATUS[row.status].label}
                      </span>
                    </td>
                    <td className={`py-1.5 pr-2 text-right text-xs font-medium ${PAID[row.settled].tone}`}>
                      {PAID[row.settled].label}
                      {row.settled === 'PART_PAID' && (
                        <div className="num text-xs font-normal text-slate-400">{rupees(row.paidPaise)}</div>
                      )}
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
          onPrices={setPricesOn}
          onClose={() => backToList()}
        />
      )}

      {pricesOn && <PriceHistory of={pricesOn} onClose={() => setPricesOn(null)} />}

      {writing && (
        <OrderDialog onToast={onToast} onClose={() => backToList()} onDone={() => backToList(true)} />
      )}
    </Panel>
  )
}

/** One order: what was asked for, who decided what, and whatever can be done to it next. */
function OrderPage({ orderId, role, onToast, onChanged, onPrices, onClose }: {
  orderId: string
  role: Role
  onToast: Toast
  onChanged: () => void
  onPrices: (of: PricesOf) => void
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
                <col className="w-28" />
                <col className="w-24" />
              </colgroup>
              <thead className="text-xs uppercase tracking-wide text-slate-400">
                <tr>
                  <th className="py-1 pl-2 text-left">Code</th>
                  <th className="py-1 text-left">Item</th>
                  <th className="py-1 text-right">Ordered</th>
                  <th className="py-1 text-right">Each</th>
                  <th className="py-1 text-right">Value</th>
                  <th className="py-1 text-right">
                    {order.status === 'APPROVED' ? 'Arrived' : 'Received'}
                  </th>
                  <th className="py-1 pr-2"></th>
                </tr>
              </thead>
              <tbody>
                {order.lines.map((line) => (
                  <tr key={line.id} className="border-t border-slate-100">
                    <td className="num py-1.5 pl-2 text-slate-500">{line.itemCode}</td>
                    <td className="truncate py-1.5" title={line.name}>
                      {line.name}
                      <span className="ml-2 text-xs text-slate-400">
                        {line.fromTheStore ? 'non billable' : 'sold'}
                      </span>
                    </td>
                    <td className="num py-1.5 text-right">{quantity(line.quantityMilli)} {line.unit}</td>
                    <td className="num py-1.5 text-right text-slate-500">{rupees(line.unitCostPaise)}</td>
                    <td className="num py-1.5 text-right">{rupees(line.valuePaise)}</td>
                    <td className="py-1 text-right">
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
                    <td className="py-1 pr-2 text-right">
                      {/* What this one thing has cost before, reached from the line it is on. */}
                      <button
                        type="button"
                        className="rounded-md px-2 py-1 text-xs font-medium text-slate-600 hover:bg-slate-100"
                        title="What the shop has paid for this before"
                        onClick={() => onPrices({
                          itemId: line.itemId,
                          itemCode: line.itemCode,
                          name: line.name,
                          fromTheStore: line.fromTheStore,
                        })}
                      >
                        Prices
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* What the money has done, which is a different question from what the goods have done. */}
          {order.owedPaise > 0 && (
            <p className="text-sm text-slate-500">
              <span className={`font-medium ${PAID[order.settled].tone}`}>{PAID[order.settled].label}</span>
              {' · '}
              <span className="num">{rupees(order.paidPaise)}</span> of{' '}
              <span className="num">{rupees(order.owedPaise)}</span>
              {order.settled !== 'PAID' && ' · what is paid is recorded on the vendor’s page'}
            </p>
          )}

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
