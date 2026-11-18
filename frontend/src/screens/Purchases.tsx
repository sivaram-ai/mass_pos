import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import {
  api, billDate, fieldErrorsOf, messageOfFailure, milliFromUnits, paiseFromRupees, quantity, rupees,
} from '../api'
import type {
  OrderStory, PurchaseOrder, PurchaseOrderStatus, PurchaseReturn, Role, Settled, VendorPaymentMode,
  VendorPaymentRow,
} from '../types'
import {
  Banner, Button, Empty, errorFor, Field, inputClass, KeyBox, Modal, Panel, smallInputClass,
} from '../components/ui'
import { useScreenKeys } from '../shortcuts'
import { SortMenu } from '../components/SortMenu'
import { WriteOrder } from '../components/WriteOrder'
import { PriceHistory } from '../components/PriceHistory'
import type { PricesOf } from '../components/PriceHistory'
import { savedOrderDraft } from '../orderDraft'
import type { SortDir } from '../sorting'
import type { Toast } from '../App'

const SEARCH_KEY = 'F2'
const ADD_KEY = 'F3'
const SORT_KEY = 'F4'
const WHICH_KEY = 'F8'
const RETURN_KEY = 'F12'

/** What can be done to the row the cursor is on, chosen with ← →, exactly as on the Bills list. */
type Action = 'open' | 'edit' | 'pay' | 'receive' | 'back'

const ACTION_LABELS: Record<Action, string> = {
  open: 'Open', edit: 'Edit', pay: 'Pay', receive: 'Receive', back: 'Send back',
}

/** Where a line of this list has got to: the five an order has, and goods that went back. */
type State = PurchaseOrderStatus | 'SENT_BACK'

/** The shop's own words for each, and how it reads on the list. */
const STATUS: Record<State, { label: string; tone: string }> = {
  PENDING_APPROVAL: { label: 'Waiting', tone: 'bg-amber-100 text-amber-800' },
  APPROVED: { label: 'Approved', tone: 'bg-sky-100 text-sky-800' },
  REJECTED: { label: 'Turned down', tone: 'bg-rose-100 text-rose-700' },
  RECEIVED: { label: 'Received', tone: 'bg-emerald-100 text-emerald-800' },
  CANCELLED: { label: 'Called off', tone: 'bg-slate-100 text-slate-500' },
  SENT_BACK: { label: 'Sent back', tone: 'bg-rose-100 text-rose-700' },
}

/** And whether the money has followed the goods. */
const PAID: Record<Settled, { label: string; tone: string }> = {
  UNPAID: { label: 'Not paid', tone: 'text-rose-600' },
  PART_PAID: { label: 'Part paid', tone: 'text-amber-700' },
  PAID: { label: 'Paid', tone: 'text-emerald-700' },
  NOT_DUE: { label: '—', tone: 'text-slate-300' },
}

const FILTERS: { label: string; state: State | null }[] = [
  { label: 'Everything', state: null },
  { label: 'Waiting', state: 'PENDING_APPROVAL' },
  { label: 'Approved', state: 'APPROVED' },
  { label: 'Received', state: 'RECEIVED' },
  { label: 'Turned down', state: 'REJECTED' },
  { label: 'Sent back', state: 'SENT_BACK' },
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

/**
 * One line of the list, whether it is an order or goods that went back.
 *
 * <p>They are listed together on purpose: a shopkeeper asking what has happened with a vendor
 * this month is asking one question, and two screens would make them ask it twice and add the
 * answers up themselves.
 */
type Listed = {
  id: string
  kind: 'order' | 'return'
  number: string
  on: string
  vendorName: string
  firstItem: string
  lines: number
  quantityMilli: number
  eachPaise: number
  totalPaise: number
  state: State
  settled: Settled
  words: string
  order?: PurchaseOrder
  sentBack?: PurchaseReturn
}

function firstOf(names: string[]): string {
  return [...names].sort(collator.compare)[0] ?? ''
}

function listedOrder(order: PurchaseOrder): Listed {
  const much = order.lines.reduce((sum, line) => sum + line.quantityMilli, 0)
  return {
    id: order.id,
    kind: 'order',
    number: order.number,
    on: order.orderedOn,
    vendorName: order.vendorName,
    firstItem: firstOf(order.lines.map((line) => line.name)),
    lines: order.lines.length,
    quantityMilli: much,
    eachPaise: much > 0 ? Math.round(order.valuePaise * 1000 / much) : 0,
    totalPaise: order.valuePaise,
    state: order.status,
    settled: order.settled,
    words: [
      order.number, order.vendorName, order.note, order.createdBy, order.decidedBy, order.receivedBy,
      STATUS[order.status].label, PAID[order.settled].label,
      ...order.lines.map((line) => `${line.itemCode} ${line.name}`),
    ].join(' ').toLowerCase(),
    order,
  }
}

function listedReturn(sentBack: PurchaseReturn): Listed {
  const much = sentBack.lines.reduce((sum, line) => sum + line.quantityMilli, 0)
  return {
    id: sentBack.id,
    kind: 'return',
    number: sentBack.number,
    on: sentBack.returnedOn,
    vendorName: sentBack.vendorName,
    firstItem: firstOf(sentBack.lines.map((line) => line.name)),
    lines: sentBack.lines.length,
    quantityMilli: much,
    eachPaise: much > 0 ? Math.round(sentBack.valuePaise * 1000 / much) : 0,
    totalPaise: sentBack.valuePaise,
    state: 'SENT_BACK',
    settled: 'NOT_DUE',
    words: [
      sentBack.number, sentBack.vendorName, sentBack.reason, sentBack.by, sentBack.againstOrderNumber,
      'sent back',
      ...sentBack.lines.map((line) => `${line.itemCode} ${line.name}`),
    ].join(' ').toLowerCase(),
    sentBack,
  }
}

function compareBy(field: SortBy, left: Listed, right: Listed): number {
  switch (field) {
    case 'date':
      return left.on.localeCompare(right.on)
    case 'vendor':
      return collator.compare(left.vendorName, right.vendorName)
    case 'item':
      return collator.compare(left.firstItem, right.firstItem)
    case 'quantity':
      return left.quantityMilli - right.quantityMilli
    case 'each':
      return left.eachPaise - right.eachPaise
    case 'total':
      return left.totalPaise - right.totalPaise
    case 'approval':
      return Object.keys(STATUS).indexOf(left.state) - Object.keys(STATUS).indexOf(right.state)
    case 'payment':
      return Object.keys(PAID).indexOf(left.settled) - Object.keys(PAID).indexOf(right.settled)
  }
}

/**
 * Everything the shop has ordered and everything it has sent back: what was asked for, who agreed
 * to it, what arrived, whether it has been paid for, and what went back on the lorry.
 *
 * <p>Worked like the Items list, because it is the same job - a long list somebody needs to find
 * one row in. The cursor starts in the list, ↑ ↓ choose, Enter opens, ← → pick which states to
 * show, F2 searches every word including the things on the lines, F3 writes an order, F4 sorts
 * and F12 sends goods back.
 *
 * <p>Writing an order takes the whole screen, the way billing does, rather than opening a dialog
 * over a list nobody is reading: it is the same grid, the same keys and the same habits.
 */
export default function Purchases({ role, onToast }: { role: Role; onToast: Toast }) {
  const [orders, setOrders] = useState<PurchaseOrder[]>([])
  const [returns, setReturns] = useState<PurchaseReturn[]>([])
  const [filter, setFilter] = useState('')
  const [kind, setKind] = useState(0)
  const [money, setMoney] = useState(0)
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [sort, setSort] = useState(loadSort)
  const [sortOpen, setSortOpen] = useState(false)
  const [selected, setSelected] = useState(0)
  /**
   * Writing one takes the screen. `startReturn` opens it straight into sending goods back and
   * `startEdit` straight into changing an order, which writes a new one in its place.
   */
  const [writing, setWriting] = useState<
    { startReturn: PurchaseOrder | null; startEdit: PurchaseOrder | null } | null>(null)
  const [action, setAction] = useState<Action>('open')
  const [listFocused, setListFocused] = useState(false)
  const [openOn, setOpenOn] = useState<string | null>(null)
  const [openReturn, setOpenReturn] = useState<PurchaseReturn | null>(null)
  const [paying, setPaying] = useState<PurchaseOrder | null>(null)
  const [pricesOn, setPricesOn] = useState<PricesOf | null>(null)
  const list = useRef<HTMLDivElement>(null)
  const searchBox = useRef<HTMLInputElement>(null)
  const lines = useRef(new Map<string, HTMLTableRowElement>())
  const dialogOpen = openOn !== null || openReturn !== null || pricesOn !== null || paying !== null
  const canManage = role === 'MANAGER' || role === 'ADMIN'

  const load = useCallback(() => {
    const asking = new URLSearchParams()
    const state = FILTERS[kind].state
    if (state && state !== 'SENT_BACK') {
      asking.set('status', state)
    }
    if (from) {
      asking.set('from', from)
    }
    if (to) {
      asking.set('to', to)
    }
    api.get<PurchaseOrder[]>(`/api/purchase-orders?${asking.toString()}`)
      .then(setOrders)
      .catch((failure) => onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not load the orders') }))
    const forReturns = new URLSearchParams()
    if (from) {
      forReturns.set('from', from)
    }
    if (to) {
      forReturns.set('to', to)
    }
    api.get<PurchaseReturn[]>(`/api/purchase-returns?${forReturns.toString()}`)
      .then(setReturns)
      .catch(() => setReturns([]))
  }, [onToast, kind, from, to])

  useEffect(load, [load])

  useEffect(() => {
    if (!writing) {
      list.current?.focus()
    }
  }, [writing])

  /**
   * An order half written when somebody walked off to another screen is still half written. The
   * lines themselves are kept by this machine either way; opening straight back onto them is what
   * makes that worth anything, instead of a list and a manager wondering where their order went.
   */
  useEffect(() => {
    let gone = false
    savedOrderDraft()
      .then((draft) => {
        if (!gone && draft && draft.lines.length > 0) {
          setWriting({ startReturn: null, startEdit: null })
        }
      })
      .catch(() => undefined)
    return () => {
      gone = true
    }
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
    const state = FILTERS[kind].state
    const everything: Listed[] = [
      ...(state && state !== 'SENT_BACK' ? orders : state === 'SENT_BACK' ? [] : orders).map(listedOrder),
      ...(state && state !== 'SENT_BACK' ? [] : returns).map(listedReturn),
    ]
    const looking = filter.trim().toLowerCase()
    const wanted = MONEY[money].of
    const matching = everything.filter((row) => {
      if (wanted && row.settled !== wanted) {
        return false
      }
      return !looking || row.words.includes(looking)
    })
    const sign = sort.dir === 'asc' ? 1 : -1
    return [...matching].sort((left, right) => {
      const by = compareBy(sort.field, left, right) * sign
      // Ties fall back to the number, which is unique and carries the day it was written.
      return by !== 0 ? by : collator.compare(right.number, left.number)
    })
  }, [orders, returns, filter, kind, money, sort])

  const waiting = orders.filter((row) => row.status === 'PENDING_APPROVAL').length
  const owed = orders.filter((row) => row.settled === 'UNPAID' || row.settled === 'PART_PAID')
    .reduce((sum, row) => sum + Math.max(0, row.owedPaise - row.paidPaise), 0)

  const at = Math.min(selected, Math.max(shown.length - 1, 0))
  const current = shown[at]

  useEffect(() => {
    if (current) {
      lines.current.get(current.id)?.scrollIntoView({ block: 'nearest' })
    }
  }, [current])

  /** Goods go back on an order that has actually arrived; anything else needs no order at all. */
  const sendGoodsBack = () => {
    const on = current?.kind === 'order' && current.order?.status === 'RECEIVED' ? current.order : null
    setWriting({ startReturn: on, startEdit: null })
  }

  /**
   * What can be done to a row, and why not where it cannot. The same shape the Bills list uses,
   * so the arrows skip what is blocked and a click on it says why rather than doing nothing.
   */
  const actionsFor = (row: Listed): [Action, string | null][] => {
    const manager = canManage ? null : 'Only a manager can do this'
    if (row.kind === 'return' || !row.order) {
      return [['open', null]];
    }
    const order = row.order
    const gone = order.status === 'RECEIVED' ? 'The goods have already come in' : null
    const decided = order.status === 'REJECTED' ? 'This order was turned down'
      : order.status === 'CANCELLED' ? 'This order was called off' : null
    // Money is recorded for anybody the shop paid, on the list or not: refusing it never stopped
    // the payment, only the record of it. What a paid-up order refuses is paying it twice.
    const owes = order.owedPaise > 0 && order.paidPaise >= order.owedPaise
      ? 'It has been paid for' : null
    return [
      ['open', null],
      ['edit', manager ?? gone ?? decided],
      ['pay', manager ?? decided ?? owes],
      ['receive', manager ?? (order.status === 'APPROVED' ? null : 'It has not been approved yet')],
      ['back', manager ?? (order.status === 'RECEIVED' ? null : 'Nothing has come in on it yet')],
    ]
  }

  const run = (row: Listed, chosen: Action) => {
    const blocked = actionsFor(row).find(([name]) => name === chosen)?.[1]
    if (blocked) {
      onToast({ tone: 'warn', text: blocked })
      return
    }
    if (chosen === 'open' || !row.order) {
      open(row)
    } else if (chosen === 'edit') {
      setWriting({ startReturn: null, startEdit: row.order })
    } else if (chosen === 'pay') {
      setPaying(row.order)
    } else if (chosen === 'receive') {
      setOpenOn(row.id)
    } else {
      setWriting({ startReturn: row.order, startEdit: null })
    }
  }

  useScreenKeys({
    [SEARCH_KEY]: () => {
      searchBox.current?.focus()
      searchBox.current?.select()
    },
    [ADD_KEY]: () => setWriting({ startReturn: null, startEdit: null }),
    [SORT_KEY]: () => setSortOpen(true),
    // The chips move with a key of their own now that ← → belong to the row's actions.
    [WHICH_KEY]: () => {
      setKind((now) => (now + 1) % FILTERS.length)
      setSelected(0)
      list.current?.focus()
    },
    [RETURN_KEY]: sendGoodsBack,
  }, !dialogOpen && !sortOpen && !writing)

  const backToList = (reload = false) => {
    setWriting(null)
    setOpenOn(null)
    setOpenReturn(null)
    setPricesOn(null)
    setPaying(null)
    if (reload) {
      load()
    }
    requestAnimationFrame(() => list.current?.focus())
  }

  const open = (row: Listed) => {
    if (row.kind === 'return') {
      setOpenReturn(row.sentBack ?? null)
    } else {
      setOpenOn(row.id)
    }
  }

  const choose = (index: number) => {
    setSelected(index)
    setAction('open')
  }

  const onListKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (dialogOpen || sortOpen || !current) {
      return
    }
    // ← → walk what can be done to this row, as they do on the Bills list, skipping what cannot.
    const available = actionsFor(current).filter(([, blocked]) => !blocked).map(([name]) => name)
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      choose(Math.max(0, Math.min(shown.length - 1, at + (event.key === 'ArrowDown' ? 1 : -1))))
    } else if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
      event.preventDefault()
      const from = Math.max(0, available.indexOf(action))
      const to = Math.max(0, Math.min(available.length - 1, from + (event.key === 'ArrowRight' ? 1 : -1)))
      setAction(available[to] ?? 'open')
    } else if (event.key === 'Home' || event.key === 'End') {
      event.preventDefault()
      choose(event.key === 'Home' ? 0 : shown.length - 1)
    } else if (event.key === 'Enter' && !event.repeat) {
      event.preventDefault()
      run(current, available.includes(action) ? action : 'open')
    }
  }

  const onSearchKeyDown = (event: ReactKeyboardEvent<HTMLInputElement>) => {
    if (['Enter', 'Escape', 'ArrowDown'].includes(event.key)) {
      // Esc here only leaves the search box: it must not count toward Esc Esc back to billing.
      event.preventDefault()
      event.stopPropagation()
      event.nativeEvent.stopImmediatePropagation()
      // Back to Open as well as back to the list: coming out of the search box is arriving at a
      // row, and Enter on arrival should open it rather than run whatever was last chosen.
      choose(0)
      list.current?.focus()
    }
  }

  // Writing one takes the whole screen, as billing does. Everything above it is still mounted, so
  // the list is exactly where it was when the order is written or put down.
  if (writing) {
    return (
      <Panel
        title={writing.startReturn ? `Send goods back on ${writing.startReturn.number}`
          : writing.startEdit ? `Change ${writing.startEdit.number}` : 'Write a purchase order'}
        actions={
          <Button className="whitespace-nowrap px-2 py-1" onClick={() => backToList()}>
            Back to the list
          </Button>
        }
      >
        <WriteOrder
          startReturn={writing.startReturn}
          startEdit={writing.startEdit}
          onToast={onToast}
          onClose={() => backToList()}
          onWritten={() => backToList(true)}
        />
      </Panel>
    )
  }

  return (
    <Panel
      title="Purchase orders"
      actions={
        <div className="flex flex-wrap items-center justify-end gap-2">
          <span className="whitespace-nowrap text-xs text-slate-500">
            {orders.length} {orders.length === 1 ? 'order' : 'orders'}
            {returns.length > 0 && <span className="ml-2">{returns.length} sent back</span>}
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
          <Button className="whitespace-nowrap px-2 py-1" hint={RETURN_KEY} onClick={sendGoodsBack}
                  title="Send goods back to the vendor, on the order they came in on">
            Send goods back
          </Button>
          <Button tone="primary" className="whitespace-nowrap px-2 py-1" hint={ADD_KEY}
                  onClick={() => setWriting({ startReturn: null, startEdit: null })}>
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
        <span><kbd>↑</kbd> <kbd>↓</kbd> choose a row</span>
        <span><kbd>←</kbd> <kbd>→</kbd> Open, Edit, Pay, Receive or Send back</span>
        <span><kbd>Enter</kbd> do it</span>
        <span>
          <kbd>{SEARCH_KEY}</kbd> search · <kbd>{SORT_KEY}</kbd> sort · <kbd>{WHICH_KEY}</kbd> which ones
          {' · '}<kbd>{ADD_KEY}</kbd> write one · <kbd>{RETURN_KEY}</kbd> send goods back
        </span>
        <span><kbd>F9</kbd> menu · <kbd>Esc</kbd> <kbd>Esc</kbd> billing</span>
      </p>

      <div
        ref={list}
        tabIndex={0}
        role="grid"
        aria-label="Purchase orders"
        onKeyDown={onListKeyDown}
        onFocus={() => setListFocused(true)}
        onBlur={() => setListFocused(false)}
        className="overflow-x-auto rounded-md outline-none focus-visible:ring-2 focus-visible:ring-sky-200"
      >
        {shown.length === 0 ? (
          <Empty>
            {filter || kind > 0 || money > 0 || from || to
              ? 'Nothing here matches.'
              : 'No orders yet. Write one for whatever the shop needs next.'}
          </Empty>
        ) : (
          <table className="w-full min-w-[86rem] table-fixed text-sm">
            <colgroup>
              <col className="w-36" />
              <col className="w-28" />
              <col className="w-44" />
              <col />
              <col className="w-24" />
              <col className="w-28" />
              <col className="w-28" />
              <col className="w-24" />
              {/* Open, Edit, Pay, Receive, Send back. */}
              <col className="w-[20rem]" />
            </colgroup>
            <thead className="text-xs uppercase tracking-wide text-slate-400">
              <tr>
                <th className="py-1 pl-2 text-left">Number</th>
                <th className="py-1 text-left">Date</th>
                <th className="py-1 text-left">Vendor</th>
                <th className="py-1 text-left">What for</th>
                <th className="py-1 text-right">How much</th>
                <th className="py-1 text-right">Total</th>
                <th className="py-1 text-right">State</th>
                <th className="py-1 text-right">Money</th>
                <th className="py-1 pr-2"></th>
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
                    onMouseDown={() => choose(index)}
                    onClick={() => open(row)}
                    aria-selected={isSelected}
                    className={`cursor-pointer border-t border-slate-100 ${
                      isSelected
                        ? 'bg-sky-50 shadow-[inset_3px_0_0_0_var(--color-sky-500)]'
                        : 'hover:bg-slate-50'}`}
                  >
                    <td className="num py-1.5 pl-2 font-medium text-slate-700">
                      {row.number}
                      {/* An order that was changed says so on the list, as an edited bill does. */}
                      {row.order?.replacesOrderNumber && (
                        <div className="text-xs font-normal text-amber-700">
                          replaces {row.order.replacesOrderNumber}
                        </div>
                      )}
                    </td>
                    <td className="num py-1.5 text-slate-500">{billDate(new Date(row.on))}</td>
                    <td className="truncate py-1.5 pr-2" title={row.vendorName}>{row.vendorName}</td>
                    <td className="truncate py-1.5 pr-2" title={row.firstItem}>
                      {row.firstItem}
                      {row.lines > 1 && (
                        <span className="ml-1 text-xs text-slate-400">and {row.lines - 1} more</span>
                      )}
                    </td>
                    <td className="num py-1.5 text-right text-slate-500">{quantity(row.quantityMilli)}</td>
                    <td className={`num py-1.5 text-right ${row.kind === 'return' ? 'text-rose-600' : ''}`}>
                      {row.kind === 'return' ? '−' : ''}{rupees(row.totalPaise)}
                    </td>
                    <td className="py-1.5 text-right">
                      <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${STATUS[row.state].tone}`}>
                        {STATUS[row.state].label}
                      </span>
                    </td>
                    <td className={`py-1.5 text-right text-xs font-medium ${PAID[row.settled].tone}`}>
                      {PAID[row.settled].label}
                      {row.settled === 'PART_PAID' && row.order && (
                        <div className="num text-xs font-normal text-slate-400">
                          {rupees(row.order.paidPaise)}
                        </div>
                      )}
                    </td>
                    <td className="whitespace-nowrap py-1 pr-2 text-right">
                      <div className="inline-flex gap-1">
                        {actionsFor(row).map(([name, blocked]) => {
                          const focused = isSelected && action === name
                          return (
                            <button
                              key={name}
                              type="button"
                              tabIndex={-1}
                              disabled={Boolean(blocked)}
                              title={blocked ?? undefined}
                              onMouseDown={(event) => event.stopPropagation()}
                              onClick={(event) => {
                                event.stopPropagation()
                                choose(index)
                                setAction(name)
                                run(row, name)
                              }}
                              className={`rounded-md px-2 py-1 text-xs font-medium transition disabled:cursor-not-allowed disabled:text-slate-300 ${
                                focused
                                  ? `bg-sky-600 text-on-primary ${listFocused ? 'ring-2 ring-sky-300' : ''}`
                                  : name === 'back' ? 'text-rose-600 hover:bg-rose-50'
                                    : 'text-slate-600 hover:bg-slate-100'}`}
                            >
                              {ACTION_LABELS[name]}
                            </button>
                          )
                        })}
                      </div>
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
          onSendBack={(order) => {
            setOpenOn(null)
            setWriting({ startReturn: order, startEdit: null })
          }}
          onEdit={(order) => {
            setOpenOn(null)
            setWriting({ startReturn: null, startEdit: order })
          }}
          onPay={(order) => {
            setOpenOn(null)
            setPaying(order)
          }}
          onClose={() => backToList()}
        />
      )}

      {paying && (
        <PayOrderDialog
          order={paying}
          onToast={onToast}
          onClose={() => backToList()}
          onPaid={() => backToList(true)}
        />
      )}

      {openReturn && <ReturnPage sentBack={openReturn} onClose={() => backToList()} />}

      {pricesOn && <PriceHistory of={pricesOn} onClose={() => setPricesOn(null)} />}
    </Panel>
  )
}

/** One order: what was asked for, who decided what, and whatever can be done to it next. */
function OrderPage({ orderId, role, onToast, onChanged, onPrices, onSendBack, onEdit, onPay, onClose }: {
  orderId: string
  role: Role
  onToast: Toast
  onChanged: () => void
  onPrices: (of: PricesOf) => void
  onSendBack: (order: PurchaseOrder) => void
  onEdit: (order: PurchaseOrder) => void
  onPay: (order: PurchaseOrder) => void
  onClose: () => void
}) {
  const [order, setOrder] = useState<PurchaseOrder | null>(null)
  /** Every version this order went through, and what went back against any of them. */
  const [story, setStory] = useState<OrderStory | null>(null)
  /** What has been paid against it, agreed to or not. */
  const [payments, setPayments] = useState<VendorPaymentRow[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [why, setWhy] = useState('')
  const [receiving, setReceiving] = useState<Record<string, string>>({})
  const canDecide = role === 'ADMIN'
  const openedSection = useRef<HTMLElement>(null)
  const body = useRef<HTMLDivElement>(null)

  const load = useCallback(() => {
    api.get<PurchaseOrder>(`/api/purchase-orders/${orderId}`)
      .then((one) => {
        setOrder(one)
        // Opens on what was ordered: the lorry usually brings what was asked for, and retyping it
        // every time is how somebody ends up recording the wrong thing.
        setReceiving(Object.fromEntries(one.lines.map((line) => [line.id, quantity(line.quantityMilli)])))
      })
      .catch((failure) => setError(messageOfFailure(failure, 'Could not open this order')))
    // The whole line of it, so a manager opening a cancelled version is told what replaced it
    // rather than left wondering where the order went.
    api.get<OrderStory>(`/api/purchase-orders/${orderId}/history`)
      .then(setStory)
      .catch(() => setStory(null))
    // Money against this order, which is the only place a payment to somebody off the vendors
    // list can be seen at all - and therefore the only place one can be approved.
    api.get<VendorPaymentRow[]>(`/api/purchase-orders/${orderId}/payments`)
      .then(setPayments)
      .catch(() => setPayments([]))
  }, [orderId])

  useEffect(load, [load])

  // Take the focus off the list behind, and show the document that was opened. Three versions of
  // an order stack up taller than the dialog, and the one that was clicked may be the last of them.
  useEffect(() => {
    body.current?.focus()
    openedSection.current?.scrollIntoView({ block: 'nearest' })
  }, [story])

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

  /**
   * Every version it went through and everything that went back off it, oldest first, with the
   * one that was opened named and ringed. The same story a bill tells, for the same reason: with
   * three documents stacked in one dialog the first question is always which one was clicked.
   *
   * <p>When the story cannot be had the dialog still opens on the order the list handed it,
   * because a manager who clicked an order should see the order rather than an empty box.
   */
  const versions = story?.versions ?? (order ? [order] : [])
  const returns = story?.returns ?? []
  const several = versions.length + returns.length > 1
  /** Which order replaced which, so a cancelled version can say where the order went. */
  const replacedBy = new Map<string, string>()
  for (const version of versions) {
    if (version.replacesOrderNumber) {
      replacedBy.set(version.replacesOrderNumber, version.number)
    }
  }

  return (
    <Modal title={order ? `${order.number} · ${order.vendorName || 'No vendor'}` : 'Order'} size="5xl"
           onClose={onClose}>
      {error && <div className="mb-3"><Banner tone="bad" onDismiss={() => setError(null)}>{error}</Banner></div>}
      {!order ? (
        <Empty>Opening…</Empty>
      ) : (
        <div ref={body} tabIndex={-1} className="max-h-[72vh] space-y-4 overflow-auto outline-none">
          {versions.length > 1 && (
            <p className="rounded-md bg-amber-50 px-3 py-2 text-sm text-amber-900">
              This order was changed, so all {versions.length} versions are shown, oldest first. Each change
              called the one before it off and wrote a new order carrying its number.
            </p>
          )}
          {returns.length > 0 && (
            <p className="rounded-md bg-rose-50 px-3 py-2 text-sm text-rose-900">
              {returns.length === 1 ? 'Goods went' : `Goods went back ${returns.length} times`} back against this
              order, so the order and everything that went back off it are shown together.
            </p>
          )}

          {versions.map((version) => {
            const isOpened = version.id === order.id
            // The opened one is the copy this dialog loaded and keeps fresh; the others are the
            // story's, which is all they need to be - nothing can be done to them.
            const shown = isOpened ? order : version
            const wentTo = replacedBy.get(shown.number)
            return (
              <section
                key={shown.id}
                ref={isOpened ? openedSection : undefined}
                className={`rounded-lg border p-3 ${
                  isOpened ? 'border-sky-500 ring-2 ring-sky-100' : 'border-slate-200'}`}
              >
                {several && (
                  <h3 className="mb-2 flex flex-wrap items-baseline gap-2 font-semibold text-slate-800">
                    Order {shown.number}
                    {isOpened && <span className="text-sky-700">(Opened)</span>}
                    {shown.replacesOrderNumber && (
                      <span className="text-xs font-normal text-amber-700">
                        replaces {shown.replacesOrderNumber}
                      </span>
                    )}
                    {wentTo && (
                      <span className="text-xs font-normal text-amber-700">replaced by {wentTo}</span>
                    )}
                  </h3>
                )}
                <OrderHead order={shown} replacedByNumber={wentTo} />
                <OrderLines
                  order={shown}
                  receiving={isOpened && shown.status === 'APPROVED' ? receiving : null}
                  onReceiving={(lineId, value) => setReceiving((now) => ({ ...now, [lineId]: value }))}
                  onPrices={onPrices}
                />

                {isOpened && (
                  <>
                    {/* What the money has done, which is a different question from what the goods
                        have done. Shown on the opened version only: what was paid against a version
                        that has been changed follows the order that replaced it. */}
                    <OrderPayments order={shown} role={role} payments={payments} onToast={onToast}
                                   onApproved={load} />

                    {/* What can be done to it, wherever it has got to. Changing one writes a new
                        order in its place; paying one records money against it. */}
                    <div className="mt-3 flex flex-wrap items-center gap-2">
                      {shown.status !== 'RECEIVED' && shown.status !== 'REJECTED'
                        && shown.status !== 'CANCELLED' && (
                        <Button onClick={() => onEdit(shown)}>Change this order</Button>
                      )}
                      {shown.status !== 'REJECTED' && shown.status !== 'CANCELLED'
                        && !(shown.owedPaise > 0 && shown.paidPaise >= shown.owedPaise) && (
                        <Button onClick={() => onPay(shown)}>
                          {shown.owedPaise > shown.paidPaise
                            ? `Pay ${rupees(shown.owedPaise - shown.paidPaise)}`
                            : 'Pay for this order'}
                        </Button>
                      )}
                    </div>

                    {shown.status === 'PENDING_APPROVAL' && (
                      canDecide ? (
                        <div className="mt-3 flex flex-wrap items-end gap-2">
                          <div className="min-w-[16rem] flex-1">
                            <Field label="If you are turning it down, why?">
                              <input className={inputClass} value={why}
                                     onChange={(event) => setWhy(event.target.value)} />
                            </Field>
                          </div>
                          <Button disabled={busy} onClick={() => void act('reject', { decision: why })}>
                            Turn it down
                          </Button>
                          <Button tone="primary" disabled={busy} onClick={() => void act('approve')}>
                            Approve
                          </Button>
                        </div>
                      ) : (
                        <div className="mt-3">
                          <Banner tone="warn">
                            Waiting for an administrator. Only they can agree to the money.
                          </Banner>
                        </div>
                      )
                    )}

                    {shown.status === 'APPROVED' && (
                      <div className="mt-3 flex flex-wrap items-center justify-end gap-2">
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

                    {shown.status === 'RECEIVED' && (
                      <div className="mt-3 flex flex-wrap items-center justify-end gap-2">
                        <span className="mr-auto text-xs text-slate-400">
                          Taken in by {shown.receivedBy}. The counts moved then, and each item's history shows
                          this order against the delivery.
                        </span>
                        <Button onClick={() => onSendBack(shown)}>Send goods back</Button>
                      </div>
                    )}
                  </>
                )}
              </section>
            )
          })}

          {returns.map((sentBack) => (
            <section key={sentBack.id} className="rounded-lg border border-rose-200 bg-rose-50/40 p-3">
              {several && (
                <h3 className="mb-2 flex flex-wrap items-baseline gap-2 font-semibold text-rose-900">
                  Sent back {sentBack.number}
                  {sentBack.againstOrderNumber && (
                    <span className="text-xs font-normal text-rose-700">
                      off order {sentBack.againstOrderNumber}
                    </span>
                  )}
                </h3>
              )}
              <ReturnDetails sentBack={sentBack} />
            </section>
          ))}
        </div>
      )}
    </Modal>
  )
}

/**
 * Where one version of an order has got to: who wrote it, when it was expected, what it comes to,
 * and whatever was decided about it. The head of every section of the story.
 */
function OrderHead({ order, replacedByNumber }: { order: PurchaseOrder; replacedByNumber?: string }) {
  return (
    <>
      <div className="flex flex-wrap items-center gap-3 text-sm">
        <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${STATUS[order.status].tone}`}>
          {STATUS[order.status].label}
        </span>
        <span className="text-slate-500">
          Ordered {billDate(new Date(order.orderedOn))} by {order.createdBy}
          {order.expectedOn && ` · expected ${billDate(new Date(order.expectedOn))}`}
        </span>
        <span className="text-slate-500">{order.vendorName || 'No vendor'}</span>
        <span className="num ml-auto text-base font-semibold">{rupees(order.valuePaise)}</span>
      </div>

      {order.note && <p className="mt-2 text-sm text-slate-600">{order.note}</p>}

      {order.decision && (
        <div className="mt-2">
          <Banner tone={order.status === 'REJECTED' ? 'bad' : 'warn'}>
            {STATUS[order.status].label} by {order.decidedBy}: {order.decision}
            {/* Only where the reason does not already name it: "Edited, replaced by PO-…" does. */}
            {replacedByNumber && !order.decision.includes(replacedByNumber)
              && ` Order ${replacedByNumber} took its place.`}
          </Banner>
        </div>
      )}
    </>
  )
}

/**
 * What was asked for, line by line.
 *
 * @param receiving what the lorry brought, per line, while that is being typed. Null on anything
 *                  that cannot be received - an old version, or an order that has not been
 *                  approved - which is what makes this one table serve the whole story.
 */
function OrderLines({ order, receiving, onReceiving, onPrices }: {
  order: PurchaseOrder
  receiving: Record<string, string> | null
  onReceiving: (lineId: string, value: string) => void
  onPrices: (of: PricesOf) => void
}) {
  return (
    <div className="mt-3 overflow-auto rounded-md border border-slate-100">
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
            <th className="py-1 text-right">{receiving ? 'Arrived' : 'Received'}</th>
            <th className="py-1 pr-2"></th>
          </tr>
        </thead>
        <tbody>
          {order.lines.map((line) => (
            <tr key={line.id} className="border-t border-slate-100">
              <td className="num py-1.5 pl-2 text-slate-500">{line.itemCode || '—'}</td>
              <td className="truncate py-1.5" title={line.name}>
                {line.name}
                <span className="ml-2 text-xs text-slate-400">
                  {line.oneOff ? 'one-off' : line.fromTheStore ? 'non billable' : 'sold'}
                </span>
              </td>
              <td className="num py-1.5 text-right">{quantity(line.quantityMilli)} {line.unit}</td>
              <td className="num py-1.5 text-right text-slate-500">{rupees(line.unitCostPaise)}</td>
              <td className="num py-1.5 text-right">{rupees(line.valuePaise)}</td>
              <td className="py-1 text-right">
                {receiving ? (
                  <input
                    className={`${smallInputClass} num w-20 text-right`}
                    inputMode="decimal"
                    aria-label={`How much of ${line.name} arrived`}
                    value={receiving[line.id] ?? ''}
                    onChange={(event) => onReceiving(line.id, event.target.value)}
                  />
                ) : (
                  <span className="num text-slate-500">
                    {order.status === 'RECEIVED' ? quantity(line.receivedMilli) : '—'}
                  </span>
                )}
              </td>
              <td className="py-1 pr-2 text-right">
                {/* What this one thing has cost before, reached from the line it is on. */}
                {!line.oneOff && line.itemId && (
                  <button
                    type="button"
                    className="rounded-md px-2 py-1 text-xs font-medium text-slate-600 hover:bg-slate-100"
                    title="What the shop has paid for this before"
                    onClick={() => onPrices({
                      itemId: line.itemId as string,
                      itemCode: line.itemCode,
                      name: line.name,
                      fromTheStore: line.fromTheStore,
                    })}
                  >
                    Prices
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/**
 * What has been paid for this order, and whether anybody has agreed to it.
 *
 * <p>It is here and not only on the vendor's page because a payment to somebody who is not on that
 * list has no page of their own: this is where the shop sees it, and the only place an
 * administrator can agree to one.
 */
function OrderPayments({ order, role, payments, onToast, onApproved }: {
  order: PurchaseOrder
  role: Role
  payments: VendorPaymentRow[]
  onToast: Toast
  onApproved: () => void
}) {
  const canApprove = role === 'ADMIN'
  const waiting = payments.filter((payment) => !payment.approved)
    .reduce((sum, payment) => sum + payment.amountPaise, 0)

  const approve = async (paymentId: string) => {
    try {
      await api.post(`/api/vendors/payments/${paymentId}/approve`)
      onToast({ tone: 'good', text: 'Payment approved' })
      onApproved()
    } catch (failure) {
      onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not approve that payment') })
    }
  }

  if (order.owedPaise <= 0 && payments.length === 0) {
    return null
  }

  return (
    <div className="mt-3 space-y-1">
      {order.owedPaise > 0 && (
        <p className="text-sm text-slate-500">
          <span className={`font-medium ${PAID[order.settled].tone}`}>{PAID[order.settled].label}</span>
          {' · '}
          <span className="num">{rupees(order.paidPaise)}</span> of{' '}
          <span className="num">{rupees(order.owedPaise)}</span>
          {order.vendorId
            ? ' · what is paid shows on the vendor’s page too'
            : ' · not on the vendors list, so this order is where the money shows'}
        </p>
      )}
      {payments.map((payment) => (
        <div key={payment.id} className="flex flex-wrap items-center gap-2 text-sm">
          <span className="num font-medium text-slate-700">{rupees(payment.amountPaise)}</span>
          <span className="text-slate-500">
            {MODES.find((one) => one.key === payment.mode)?.label ?? payment.mode}
            {payment.reference && ` · ${payment.reference}`}
          </span>
          <span className="text-xs text-slate-400">
            {billDate(new Date(payment.paidOn))} · {payment.by}
          </span>
          {payment.approved ? (
            <span className="text-xs text-emerald-700">
              approved{payment.approvedBy && ` by ${payment.approvedBy}`}
            </span>
          ) : canApprove ? (
            <button type="button" onClick={() => void approve(payment.id)}
                    className="rounded-md px-2 py-0.5 text-xs font-medium text-sky-700 hover:bg-sky-50">
              Approve
            </button>
          ) : (
            <span className="text-xs text-amber-700">waiting</span>
          )}
        </div>
      ))}
      {waiting > 0 && (
        <p className="text-xs text-amber-700">
          <span className="num">{rupees(waiting)}</span> recorded as paid and waiting for an administrator.
          It is not counted against what the shop owes until somebody approves it.
        </p>
      )}
    </div>
  )
}

/**
 * Money paid against one order, recorded on the vendor's account and waiting for an
 * administrator - the same payment the vendor's own page records, reached from the order it is
 * for so nobody has to go and find the vendor to pay for a delivery in front of them.
 *
 * <p>It is posted to the order rather than to a vendor because an order may not have one. A shop
 * that paid the man off the lorry has paid him, and a till that would not write that down is
 * simply a till with a hole in its records; what there is no vendor row for has no running
 * balance to move, so the order is where it shows and where it is approved.
 */
function PayOrderDialog({ order, onToast, onClose, onPaid }: {
  order: PurchaseOrder
  onToast: Toast
  onClose: () => void
  onPaid: () => void
}) {
  const left = Math.max(0, order.owedPaise - order.paidPaise)
  const [amount, setAmount] = useState((left / 100).toFixed(2))
  const [mode, setMode] = useState<VendorPaymentMode>('CASH')
  const [reference, setReference] = useState('')
  const [note, setNote] = useState('')
  const [errors, setErrors] = useState<{ message: string; fields: Record<string, string> } | null>(null)
  const [saving, setSaving] = useState(false)

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    const paise = paiseFromRupees(amount)
    if (paise <= 0) {
      setErrors({ message: 'Enter how much was paid.', fields: {} })
      return
    }
    if (mode !== 'CASH' && reference.trim() === '') {
      setErrors({ message: 'Put the transaction details in, so this payment can be found again.', fields: {} })
      return
    }
    setSaving(true)
    try {
      await api.post(`/api/purchase-orders/${order.id}/payments`, {
        amountPaise: paise,
        paidOn: new Date().toISOString().slice(0, 10),
        mode,
        reference: reference.trim(),
        note: note.trim() || `Against order ${order.number}`,
      })
      onToast({ tone: 'good', text: `Recorded against ${order.number}. It waits for an administrator.` })
      onPaid()
    } catch (failure) {
      setErrors({ message: messageOfFailure(failure, 'Could not record that'), fields: fieldErrorsOf(failure) })
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal title={`Pay for ${order.number}`} onClose={onClose}>
      <form onSubmit={submit} className="space-y-3">
        {errors && <Banner tone="bad" onDismiss={() => setErrors(null)}>{errors.message}</Banner>}
        <p className="text-sm text-slate-500">
          {order.vendorName || 'No vendor'}
          {left > 0
            ? <> · owed <span className="num font-medium text-slate-700">{rupees(left)}</span></>
            : ' · nothing owed on it yet, so this is paying ahead'}
          {order.paidPaise > 0 && (
            <> · already paid <span className="num">{rupees(order.paidPaise)}</span></>
          )}
          {!order.vendorId && ' · not on the vendors list'}
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
          <Field label={mode === 'CASH' ? 'Reference' : 'Transaction details'} optional={mode === 'CASH'}
                 hint={mode === 'CASH' ? 'Anything worth keeping' : 'The reference the shop can find it by'}
                 error={errorFor(errors?.fields ?? {}, 'reference')}>
            <input className={inputClass} value={reference}
                   onChange={(event) => setReference(event.target.value)} />
          </Field>
        </div>
        <Field label="Note" optional error={errorFor(errors?.fields ?? {}, 'note')}>
          <input className={inputClass} value={note} onChange={(event) => setNote(event.target.value)} />
        </Field>
        <p className="text-xs text-slate-400">
          It shows on this order at once, marked as waiting, and counts against what the shop owes
          once an administrator approves it.
          {order.vendorId
            ? ' It is on the vendor’s page as well: one payment, whichever screen records it.'
            : ' Nobody on the vendors list was paid, so the order is the only place it shows.'}
        </p>
        <div className="flex justify-end gap-2">
          <Button onClick={onClose} hint="Esc">Cancel</Button>
          <Button type="submit" tone="primary" hint="Enter" disabled={saving}>
            {saving ? 'Saving…' : 'Record the payment'}
          </Button>
        </div>
      </form>
    </Modal>
  )
}

/** How a payment can be made, in the shop's words. The same list the vendor's page offers. */
const MODES: { key: VendorPaymentMode; label: string }[] = [
  { key: 'CASH', label: 'Cash' },
  { key: 'UPI', label: 'UPI' },
  { key: 'BANK_TRANSFER', label: 'Bank transfer' },
  { key: 'CHEQUE', label: 'Cheque' },
  { key: 'CARD', label: 'Card' },
]

/** Goods that went back: what went, what it was worth, and why. Nothing can be done to it. */
function ReturnPage({ sentBack, onClose }: { sentBack: PurchaseReturn; onClose: () => void }) {
  return (
    <Modal title={`${sentBack.number} · ${sentBack.vendorName || 'No vendor'}`} size="5xl" onClose={onClose}>
      <ReturnDetails sentBack={sentBack} />
    </Modal>
  )
}

/**
 * One return, as its own dialog shows it and as an order's story shows it underneath the order.
 * One component for both, so the two cannot drift - the same reasoning the bills screen follows.
 */
function ReturnDetails({ sentBack }: { sentBack: PurchaseReturn }) {
  return (
      <div className="space-y-4">
        <div className="flex flex-wrap items-center gap-3 text-sm">
          <span className="rounded-full bg-rose-100 px-2 py-0.5 text-xs font-medium text-rose-700">Sent back</span>
          <span className="text-slate-500">
            {billDate(new Date(sentBack.returnedOn))} by {sentBack.by}
            {sentBack.againstOrderNumber && ` · on ${sentBack.againstOrderNumber}`}
          </span>
          <span className="num ml-auto text-base font-semibold text-rose-700">
            −{rupees(sentBack.valuePaise)}
          </span>
        </div>

        {sentBack.reason && <p className="text-sm text-slate-600">{sentBack.reason}</p>}

        <div className="overflow-auto rounded-md border border-slate-100">
          <table className="w-full table-fixed text-sm">
            <colgroup>
              <col className="w-28" />
              <col />
              <col className="w-32" />
              <col className="w-28" />
              <col className="w-28" />
            </colgroup>
            <thead className="text-xs uppercase tracking-wide text-slate-400">
              <tr>
                <th className="py-1 pl-2 text-left">Code</th>
                <th className="py-1 text-left">Item</th>
                <th className="py-1 text-right">Went back</th>
                <th className="py-1 text-right">Each</th>
                <th className="py-1 pr-2 text-right">Value</th>
              </tr>
            </thead>
            <tbody>
              {sentBack.lines.map((line) => (
                <tr key={line.id} className="border-t border-slate-100">
                  <td className="num py-1.5 pl-2 text-slate-500">{line.itemCode || '—'}</td>
                  <td className="truncate py-1.5" title={line.name}>
                    {line.name}
                    <span className="ml-2 text-xs text-slate-400">
                      {line.oneOff ? 'one-off' : line.fromTheStore ? 'non billable' : 'sold'}
                    </span>
                  </td>
                  <td className="num py-1.5 text-right">{quantity(line.quantityMilli)} {line.unit}</td>
                  <td className="num py-1.5 text-right text-slate-500">{rupees(line.unitCostPaise)}</td>
                  <td className="num py-1.5 pr-2 text-right">{rupees(line.valuePaise)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <p className="text-xs text-slate-400">
          The stock came off when this was written, and {rupees(sentBack.valuePaise)} came off what the
          shop owes {sentBack.vendorName || 'whoever it came from'}. Nothing here can be changed: goods
          that went back went back.
        </p>
      </div>
  )
}
