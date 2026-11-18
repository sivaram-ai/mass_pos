import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import { api, billDate, clockTime, messageOfFailure, rupees } from '../api'
import type { OrderStory, PurchaseOrder, VendorPaymentRow } from '../types'
import { Banner, Empty, KeyBox, Modal, smallInputClass } from '../components/ui'
import { SortMenu } from './SortMenu'
import type { SortDir } from '../sorting'

const SEARCH_KEY = 'F2'
const WHICH_KEY = 'F3'
const SORT_KEY = 'F4'

/** Everything that can happen to an order, in the order it usually happens. */
type Kind = 'WRITTEN' | 'DECIDED' | 'RECEIVED' | 'PAID' | 'SENT_BACK'

const WHAT: Record<Kind, { label: string; tone: string }> = {
  WRITTEN: { label: 'Written', tone: 'bg-slate-100 text-slate-700' },
  DECIDED: { label: 'Decided', tone: 'bg-amber-100 text-amber-800' },
  RECEIVED: { label: 'Goods in', tone: 'bg-emerald-100 text-emerald-800' },
  PAID: { label: 'Money', tone: 'bg-sky-100 text-sky-800' },
  SENT_BACK: { label: 'Goods back', tone: 'bg-rose-100 text-rose-700' },
}

const KINDS: { label: string; kinds: Kind[] }[] = [
  { label: 'Everything', kinds: [] },
  { label: 'Written', kinds: ['WRITTEN'] },
  { label: 'Decisions', kinds: ['DECIDED'] },
  { label: 'Goods', kinds: ['RECEIVED', 'SENT_BACK'] },
  { label: 'Money', kinds: ['PAID'] },
]

type SortBy = 'when' | 'kind' | 'what' | 'amount' | 'who'

const FIELDS: SortBy[] = ['when', 'kind', 'what', 'amount', 'who']

const LABELS: Record<SortBy, string> = {
  when: 'When',
  kind: 'What happened',
  what: 'What',
  amount: 'Amount',
  who: 'Who',
}

/** One thing that happened to the order. */
type Happened = {
  id: string
  when: string
  kind: Kind
  reference: string
  what: string
  amountPaise: number
  by: string
}

const collator = new Intl.Collator('en-IN', { numeric: true, sensitivity: 'base' })

function compareBy(field: SortBy, left: Happened, right: Happened): number {
  switch (field) {
    case 'when':
      return left.when.localeCompare(right.when)
    case 'kind':
      return Object.keys(WHAT).indexOf(left.kind) - Object.keys(WHAT).indexOf(right.kind)
    case 'what':
      return collator.compare(left.what, right.what)
    case 'amount':
      return left.amountPaise - right.amountPaise
    case 'who':
      return collator.compare(left.by, right.by)
  }
}

/** A day with no time on it still sorts against a timestamp, instead of landing before all of them. */
function onDay(date: string): string {
  return `${date}T00:00:00`
}

/**
 * Everything that has happened to one order, oldest first.
 *
 * <p>An order is not one event but a handful spread over days - somebody writes it, somebody else
 * agrees to the money, it is changed and written again, the lorry turns up with eight of the
 * twelve, money is handed over, a sack goes back. The order's own page shows where all that has
 * got to; this shows how it got there, which is the question asked when something looks wrong.
 *
 * <p>Assembled on the screen out of what the order already answers - its versions, its returns and
 * its payments - because every one of those is already a record with a time and a person on it.
 * Nothing is stored as history, so nothing can drift from the thing it describes.
 */
export function OrderHistory({ order, onClose }: { order: PurchaseOrder; onClose: () => void }) {
  const [story, setStory] = useState<OrderStory | null>(null)
  const [payments, setPayments] = useState<VendorPaymentRow[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [kind, setKind] = useState(0)
  const [sort, setSort] = useState<{ field: SortBy; dir: SortDir }>({ field: 'when', dir: 'asc' })
  const [sorting, setSorting] = useState(false)
  const [at, setAt] = useState(0)
  const list = useRef<HTMLDivElement>(null)
  const searchBox = useRef<HTMLInputElement>(null)
  const lines = useRef(new Map<string, HTMLTableRowElement>())

  const load = useCallback(() => {
    setError(null)
    api.get<OrderStory>(`/api/purchase-orders/${order.id}/history`)
      .then(setStory)
      .catch((failure) => {
        setStory({ versions: [order], returns: [] })
        setError(messageOfFailure(failure, 'Could not read the rest of this order’s history'))
      })
    api.get<VendorPaymentRow[]>(`/api/purchase-orders/${order.id}/payments`)
      .then(setPayments)
      .catch(() => setPayments([]))
  }, [order])

  useEffect(load, [load])

  useLayoutEffect(() => {
    list.current?.focus()
    const frame = requestAnimationFrame(() => list.current?.focus())
    return () => cancelAnimationFrame(frame)
  }, [])

  /** Every record this order has, turned into the lines of its story. */
  const everything = useMemo(() => {
    if (!story) {
      return null
    }
    const events: Happened[] = []
    for (const version of story.versions) {
      events.push({
        id: `${version.id}-written`,
        when: onDay(version.orderedOn),
        kind: 'WRITTEN',
        reference: version.number,
        what: version.replacesOrderNumber
          ? `Written in place of ${version.replacesOrderNumber}, for ${version.vendorName || 'no vendor'}`
          : `Written for ${version.vendorName || 'no vendor'}, ${version.lines.length} ${
            version.lines.length === 1 ? 'line' : 'lines'}`,
        amountPaise: version.valuePaise,
        by: version.createdBy,
      })
      if (version.decidedAt) {
        events.push({
          id: `${version.id}-decided`,
          when: version.decidedAt,
          kind: 'DECIDED',
          reference: version.number,
          // What was decided, not where the order has got to since: a received order was
          // approved, and reading its approval back as "called off" is simply false. A decision
          // with no words on it is an approval - turning one down and calling one off both
          // carry a reason, because somebody has to be able to act on them.
          what: version.decision
            ? version.status === 'REJECTED' ? `Turned down: ${version.decision}` : version.decision
            : 'Approved, so the shop agreed to the money',
          amountPaise: version.valuePaise,
          by: version.decidedBy,
        })
      }
      if (version.receivedAt) {
        const came = version.lines.reduce((sum, line) =>
          sum + Math.round(line.receivedMilli * line.unitCostPaise / 1000), 0)
        const short = version.lines.filter((line) => line.receivedMilli < line.quantityMilli).length
        events.push({
          id: `${version.id}-received`,
          when: version.receivedAt,
          kind: 'RECEIVED',
          reference: version.number,
          what: short === 0
            ? 'Everything on it arrived'
            : `${short} of ${version.lines.length} came up short of what was ordered`,
          amountPaise: came,
          by: version.receivedBy,
        })
      }
    }
    for (const sentBack of story.returns) {
      events.push({
        id: `${sentBack.id}-back`,
        when: sentBack.writtenAt,
        kind: 'SENT_BACK',
        reference: sentBack.number,
        what: sentBack.reason
          ? `${sentBack.lines.length} sent back: ${sentBack.reason}`
          : `${sentBack.lines.length} sent back`,
        amountPaise: sentBack.valuePaise,
        by: sentBack.by,
      })
    }
    for (const payment of payments ?? []) {
      events.push({
        id: `${payment.id}-paid`,
        // Placed by when it was written down rather than by the day somebody typed on it: the
        // typed day may be last week, and a history is the order things actually happened in.
        when: payment.recordedAt,
        kind: 'PAID',
        reference: payment.reference ? `${payment.mode} ${payment.reference}` : payment.mode,
        what: payment.approved ? payment.note : `${payment.note} · waiting for an administrator`,
        amountPaise: payment.amountPaise,
        by: payment.by,
      })
      if (payment.approved && payment.approvedAt) {
        events.push({
          id: `${payment.id}-approved`,
          when: payment.approvedAt,
          kind: 'PAID',
          reference: payment.mode,
          what: 'Payment approved, so it counts against what the shop owes',
          amountPaise: payment.amountPaise,
          by: payment.approvedBy,
        })
      }
    }
    return events
  }, [story, payments])

  const looking = search.trim().toLowerCase()
  const shown = useMemo(() => {
    const wanted = KINDS[kind].kinds
    const kept = (everything ?? []).filter((event) =>
      (wanted.length === 0 || wanted.includes(event.kind))
      && (looking === '' || `${event.reference} ${event.what} ${event.by} ${WHAT[event.kind].label}`
        .toLowerCase().includes(looking)))
    const sign = sort.dir === 'asc' ? 1 : -1
    return [...kept].sort((left, right) => {
      const by = compareBy(sort.field, left, right) * sign
      return by !== 0 ? by : left.when.localeCompare(right.when)
    })
  }, [everything, kind, looking, sort])

  const here = Math.min(at, Math.max(shown.length - 1, 0))
  const current = shown[here]

  useEffect(() => {
    if (current) {
      lines.current.get(current.id)?.scrollIntoView({ block: 'nearest' })
    }
  }, [current])

  const onKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (sorting) {
      return
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      setAt(Math.max(0, Math.min(shown.length - 1, here + (event.key === 'ArrowDown' ? 1 : -1))))
    } else if (event.key === 'Home' || event.key === 'End') {
      event.preventDefault()
      setAt(event.key === 'Home' ? 0 : shown.length - 1)
    } else if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
      event.preventDefault()
      setKind((now) => (now + (event.key === 'ArrowRight' ? 1 : KINDS.length - 1)) % KINDS.length)
      setAt(0)
    } else if (event.key === SEARCH_KEY) {
      event.preventDefault()
      searchBox.current?.focus()
      searchBox.current?.select()
    } else if (event.key === WHICH_KEY) {
      event.preventDefault()
      setKind((now) => (now + 1) % KINDS.length)
      setAt(0)
    } else if (event.key === SORT_KEY) {
      event.preventDefault()
      setSorting(true)
    }
  }

  return (
    <Modal title={`${order.number} · history`} size="5xl" onClose={onClose}>
      {error && <div className="mb-3"><Banner tone="bad" onDismiss={() => setError(null)}>{error}</Banner></div>}

      <div className="mb-2 flex flex-wrap items-center gap-2">
        <div className="flex flex-wrap items-center gap-1">
          {KINDS.map((one, index) => (
            <button
              key={one.label}
              type="button"
              role="tab"
              aria-selected={index === kind}
              onClick={() => {
                setKind(index)
                setAt(0)
                requestAnimationFrame(() => list.current?.focus())
              }}
              className={`rounded-full px-2.5 py-0.5 text-xs font-medium transition ${
                index === kind ? 'bg-sky-600 text-on-primary' : 'text-slate-600 hover:bg-slate-100'}`}
            >
              {one.label}
            </button>
          ))}
          <KeyBox hint={WHICH_KEY}><span className="pr-6 text-xs text-slate-400">which</span></KeyBox>
        </div>

        <div className="relative ml-auto">
          <button
            type="button"
            title="Choose what the history is ordered by"
            onClick={() => setSorting((open) => !open)}
            className="rounded-md border border-slate-200 px-2 py-1 text-xs font-medium text-slate-600 hover:bg-slate-100"
          >
            {LABELS[sort.field]} {sort.dir === 'asc' ? '↑' : '↓'} <kbd className="ml-1">{SORT_KEY}</kbd>
          </button>
          {sorting && (
            <SortMenu sort={sort} fields={FIELDS} labels={LABELS} onChange={setSort}
                      onClose={() => {
                        setSorting(false)
                        requestAnimationFrame(() => list.current?.focus())
                      }} />
          )}
        </div>

        <KeyBox hint={SEARCH_KEY}>
          <input
            ref={searchBox}
            className={`${smallInputClass} w-56 pr-8`}
            placeholder="Number, reason or person"
            aria-label="Search this order’s history"
            value={search}
            onChange={(event) => {
              setSearch(event.target.value)
              setAt(0)
            }}
            onKeyDown={(event) => {
              if (['Enter', 'Escape', 'ArrowDown'].includes(event.key)) {
                event.preventDefault()
                event.stopPropagation()
                event.nativeEvent.stopImmediatePropagation()
                setAt(0)
                list.current?.focus()
              }
            }}
          />
        </KeyBox>
      </div>

      <p className="mb-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-400">
        <span><kbd>↑</kbd> <kbd>↓</kbd> choose · <kbd>←</kbd> <kbd>→</kbd> which kind</span>
        <span><kbd>{SEARCH_KEY}</kbd> search · <kbd>{SORT_KEY}</kbd> sort · <kbd>Esc</kbd> back</span>
      </p>

      <div
        ref={list}
        tabIndex={0}
        role="grid"
        aria-label={`History of ${order.number}`}
        onKeyDown={onKeyDown}
        className="max-h-[52vh] overflow-auto rounded-md outline-none focus-visible:ring-2 focus-visible:ring-sky-200"
      >
        {everything === null ? (
          <Empty>Reading…</Empty>
        ) : shown.length === 0 ? (
          <Empty>Nothing here matches.</Empty>
        ) : (
          <table className="w-full min-w-[58rem] table-fixed text-sm">
            <colgroup>
              <col className="w-36" />
              <col className="w-28" />
              <col />
              <col className="w-40" />
              <col className="w-28" />
              <col className="w-32" />
            </colgroup>
            <thead className="sticky top-0 bg-surface text-xs uppercase tracking-wide text-slate-400">
              <tr>
                <th className="py-1 pl-2 text-left">When</th>
                <th className="py-1 text-left">What happened</th>
                <th className="py-1 text-left">What</th>
                <th className="py-1 text-left">Reference</th>
                <th className="py-1 text-right">Amount</th>
                <th className="py-1 pr-2 text-left">Who</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((event, index) => {
                const isHere = index === here
                // A payment or an order carries a day and no clock; only a decision, a delivery
                // or an approval has a time anybody recorded.
                const onlyADay = event.when.endsWith('T00:00:00')
                return (
                  <tr
                    key={event.id}
                    ref={(element) => {
                      if (element) {
                        lines.current.set(event.id, element)
                      } else {
                        lines.current.delete(event.id)
                      }
                    }}
                    onMouseDown={() => setAt(index)}
                    aria-selected={isHere}
                    className={`border-t border-slate-100 ${
                      isHere ? 'bg-sky-50 shadow-[inset_3px_0_0_0_var(--color-sky-500)]' : ''}`}
                  >
                    <td className="num py-1.5 pl-2 text-slate-500">
                      {billDate(new Date(event.when))}
                      {!onlyADay && (
                        <span className="ml-1 text-xs text-slate-400">{clockTime(new Date(event.when))}</span>
                      )}
                    </td>
                    <td className="py-1.5">
                      <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${WHAT[event.kind].tone}`}>
                        {WHAT[event.kind].label}
                      </span>
                    </td>
                    <td className="whitespace-normal break-words py-1.5 pr-2">{event.what || '—'}</td>
                    <td className="num truncate py-1.5 pr-2 text-slate-500" title={event.reference}>
                      {event.reference || '—'}
                    </td>
                    <td className={`num py-1.5 text-right font-medium ${
                      event.kind === 'SENT_BACK' ? 'text-rose-600'
                        : event.kind === 'PAID' ? 'text-sky-700' : 'text-slate-700'}`}>
                      {event.kind === 'SENT_BACK' ? '−' : ''}{rupees(event.amountPaise)}
                    </td>
                    <td className="truncate py-1.5 pr-2 text-slate-500" title={event.by}>{event.by || '—'}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </div>

      <p className="mt-2 text-xs text-slate-400">
        {shown.length} of {(everything ?? []).length} lines. Every one of them is a record of
        something that happened; nothing here can be edited.
      </p>
    </Modal>
  )
}
