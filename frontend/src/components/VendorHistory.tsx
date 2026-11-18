import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import { api, billDate, clockTime, messageOfFailure, quantity, rupees } from '../api'
import type { Vendor, VendorEvent, VendorEventKind } from '../types'
import { Banner, Empty, KeyBox, Modal, smallInputClass } from '../components/ui'
import { SortMenu } from './SortMenu'
import type { SortDir } from '../sorting'

const SEARCH_KEY = 'F2'
const WHICH_KEY = 'F3'
const SORT_KEY = 'F4'

/** The shop's own word for each kind of line, and how it reads on the page. */
const WHAT: Record<VendorEventKind, { label: string; tone: string }> = {
  ORDERED: { label: 'Ordered', tone: 'bg-slate-100 text-slate-700' },
  BOUGHT: { label: 'Came in', tone: 'bg-emerald-100 text-emerald-800' },
  SENT_BACK: { label: 'Sent back', tone: 'bg-rose-100 text-rose-700' },
  PAID: { label: 'Paid', tone: 'bg-sky-100 text-sky-800' },
}

/**
 * The four things somebody asks about a vendor, each on its own. "What have we had from him",
 * "what did we send back", "what have we paid him" and "what is on order" are four questions, and
 * a list that could only answer all four at once would make somebody read past three of them.
 */
const KINDS: { label: string; kinds: VendorEventKind[] }[] = [
  { label: 'Everything', kinds: [] },
  { label: 'Came in', kinds: ['BOUGHT'] },
  { label: 'Sent back', kinds: ['SENT_BACK'] },
  { label: 'Money', kinds: ['PAID'] },
  { label: 'Orders', kinds: ['ORDERED'] },
]

type SortBy = 'when' | 'what' | 'kind' | 'amount' | 'reference' | 'who'

const FIELDS: SortBy[] = ['when', 'what', 'kind', 'amount', 'reference', 'who']

const LABELS: Record<SortBy, string> = {
  when: 'When',
  what: 'What',
  kind: 'What happened',
  amount: 'Amount',
  reference: 'Reference',
  who: 'Who',
}

const collator = new Intl.Collator('en-IN', { numeric: true, sensitivity: 'base' })

/**
 * Whether a moment is a day rather than a time of day. An order has only the date it was written
 * on, and the server dates it from the start of that day in the shop's own zone - so a clock on it
 * would be a figure nobody recorded.
 */
function atMidnight(when: string): boolean {
  const at = new Date(when)
  return at.getHours() === 0 && at.getMinutes() === 0 && at.getSeconds() === 0
}

function compareBy(field: SortBy, left: VendorEvent, right: VendorEvent): number {
  switch (field) {
    case 'when':
      return left.when.localeCompare(right.when)
    case 'what':
      return collator.compare(left.what, right.what)
    case 'kind':
      return Object.keys(WHAT).indexOf(left.kind) - Object.keys(WHAT).indexOf(right.kind)
    case 'amount':
      return left.amountPaise - right.amountPaise
    case 'reference':
      return collator.compare(left.reference, right.reference)
    case 'who':
      return collator.compare(left.by, right.by)
  }
}

/**
 * Everything that has happened with one vendor.
 *
 * <p>His page answers what the shop owes him. This answers why, which is what anybody asks the
 * moment the figure looks wrong: every order written to him, everything that arrived on the shelf
 * or in the store, everything that went back on a lorry and every rupee handed over, newest first,
 * with who did it and when.
 *
 * <p>Read-only, like the stock history and for the same reason: every line of it is a record of
 * something that really happened, and the way to change what the shop owes is to record the next
 * thing that happens rather than to edit the last one.
 *
 * <p>Worked from the keyboard like every other list here - ↑ ↓ choose, F2 searches, F3 picks
 * which kind, F4 orders, Esc closes and hands the cursor back.
 */
export function VendorHistory({ of, onClose }: { of: Vendor; onClose: () => void }) {
  const [events, setEvents] = useState<VendorEvent[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [kind, setKind] = useState(0)
  const [sort, setSort] = useState<{ field: SortBy; dir: SortDir }>({ field: 'when', dir: 'desc' })
  const [sorting, setSorting] = useState(false)
  const [at, setAt] = useState(0)
  const list = useRef<HTMLDivElement>(null)
  const searchBox = useRef<HTMLInputElement>(null)
  const lines = useRef(new Map<string, HTMLTableRowElement>())

  const load = useCallback(() => {
    setEvents(null)
    setError(null)
    api.get<VendorEvent[]>(`/api/vendors/${of.id}/history`)
      .then(setEvents)
      .catch((failure) => {
        setEvents([])
        setError(messageOfFailure(failure, 'Could not read this vendor’s history'))
      })
  }, [of.id])

  useEffect(load, [load])

  /**
   * The cursor starts in the list, twice over and both times needed - at once, so a key pressed in
   * the same breath as the one that opened this does not land on the screen behind; and again
   * through a frame, because the screen underneath hands focus back to its own list when a dialog
   * closes and does that through a frame. Both were found by driving the stock history.
   */
  useLayoutEffect(() => {
    list.current?.focus()
    const frame = requestAnimationFrame(() => list.current?.focus())
    return () => cancelAnimationFrame(frame)
  }, [])

  const looking = search.trim().toLowerCase()
  const shown = useMemo(() => {
    const wanted = KINDS[kind].kinds
    const kept = (events ?? []).filter((event) =>
      (wanted.length === 0 || wanted.includes(event.kind))
      && (looking === '' || `${event.reference} ${event.what} ${event.by} ${event.state} ${WHAT[event.kind].label}`
        .toLowerCase().includes(looking)))
    const sign = sort.dir === 'asc' ? 1 : -1
    // Ties fall back to when it happened, so two lines of one delivery keep a settled order.
    return [...kept].sort((left, right) => {
      const by = compareBy(sort.field, left, right) * sign
      return by !== 0 ? by : right.when.localeCompare(left.when)
    })
  }, [events, kind, looking, sort])

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

  const came = (events ?? []).filter((event) => event.kind === 'BOUGHT')
    .reduce((sum, event) => sum + event.amountPaise, 0)
  const back = (events ?? []).filter((event) => event.kind === 'SENT_BACK')
    .reduce((sum, event) => sum + event.amountPaise, 0)
  const paid = (events ?? []).filter((event) => event.kind === 'PAID' && event.state === 'Approved')
    .reduce((sum, event) => sum + event.amountPaise, 0)

  return (
    <Modal title={`${of.name} · history`} size="5xl" onClose={onClose}>
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
            placeholder="Item, number or person"
            aria-label="Search this vendor’s history"
            value={search}
            onChange={(event) => {
              setSearch(event.target.value)
              setAt(0)
            }}
            onKeyDown={(event) => {
              if (['Enter', 'Escape', 'ArrowDown'].includes(event.key)) {
                event.preventDefault()
                // Esc here goes back to the list, and must not close the dialog behind it.
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
        aria-label={`History of ${of.name}`}
        onKeyDown={onKeyDown}
        className="max-h-[52vh] overflow-auto rounded-md outline-none focus-visible:ring-2 focus-visible:ring-sky-200"
      >
        {events === null ? (
          <Empty>Reading…</Empty>
        ) : shown.length === 0 ? (
          <Empty>
            {looking || kind > 0
              ? 'Nothing here matches.'
              : `Nothing has happened with ${of.name} yet.`}
          </Empty>
        ) : (
          <table className="w-full min-w-[62rem] table-fixed text-sm">
            <colgroup>
              <col className="w-36" />
              <col className="w-28" />
              <col />
              <col className="w-36" />
              <col className="w-28" />
              <col className="w-32" />
              <col className="w-28" />
            </colgroup>
            <thead className="sticky top-0 bg-surface text-xs uppercase tracking-wide text-slate-400">
              <tr>
                <th className="py-1 pl-2 text-left">When</th>
                <th className="py-1 text-left">What happened</th>
                <th className="py-1 text-left">What</th>
                <th className="py-1 text-left">Reference</th>
                <th className="py-1 text-right">How much</th>
                <th className="py-1 text-right">Amount</th>
                <th className="py-1 pr-2 text-left">Who</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((event, index) => {
                const isHere = index === here
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
                      {/* An order carries a day and no clock, so it says the day and no clock -
                          rather than claiming it was written at midnight. */}
                      {!atMidnight(event.when) && (
                        <span className="ml-1 text-xs text-slate-400">{clockTime(new Date(event.when))}</span>
                      )}
                    </td>
                    <td className="py-1.5">
                      <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${WHAT[event.kind].tone}`}>
                        {WHAT[event.kind].label}
                      </span>
                    </td>
                    {/* The one column that may wrap: a note or an item name is what somebody
                        opened this screen to read, and a truncated one answers nothing. */}
                    <td className="whitespace-normal break-words py-1.5 pr-2">
                      {event.what || '—'}
                      {event.state && (
                        <span className={`ml-2 text-xs ${
                          event.state === 'Waiting' ? 'text-amber-700' : 'text-slate-400'}`}>
                          {event.state}
                        </span>
                      )}
                    </td>
                    <td className="num truncate py-1.5 pr-2 text-slate-500" title={event.reference}>
                      {event.reference || '—'}
                    </td>
                    <td className="num py-1.5 text-right text-slate-500">
                      {event.quantityMilli > 0 ? `${quantity(event.quantityMilli)} ${event.unit}` : '—'}
                    </td>
                    <td className={`num py-1.5 text-right font-medium ${
                      event.kind === 'SENT_BACK' ? 'text-rose-600'
                        : event.kind === 'PAID' ? 'text-sky-700' : 'text-slate-700'}`}>
                      {event.kind === 'SENT_BACK' ? '−' : ''}{rupees(event.amountPaise)}
                    </td>
                    <td className="truncate py-1.5 pr-2 text-slate-500" title={event.by}>
                      {event.by || '—'}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </div>

      {/* What the lines above come to, which is the balance on his page taken apart. */}
      <p className="mt-2 flex flex-wrap gap-x-6 gap-y-1 text-xs text-slate-500">
        <span>Came in <span className="num font-medium text-slate-700">{rupees(came)}</span></span>
        <span>Sent back <span className="num font-medium text-rose-600">{rupees(back)}</span></span>
        <span>Paid and approved <span className="num font-medium text-slate-700">{rupees(paid)}</span></span>
        <span className="text-slate-400">
          {shown.length} of {(events ?? []).length} lines
        </span>
      </p>
    </Modal>
  )
}
