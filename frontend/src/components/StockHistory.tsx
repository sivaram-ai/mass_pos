import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import { api, billDate, clockTime, messageOfFailure, quantity } from '../api'
import type { StockMovement, StockMovementType } from '../types'
import { Banner, Empty, KeyBox, Modal, smallInputClass } from '../components/ui'

const SEARCH_KEY = 'F2'
const SCOPE_KEY = 'F3'
const SORT_KEY = 'F4'

/** How long a window across every item looks back, when nobody has asked about one item. */
const SHOP_DAYS = 30

/**
 * The shop's own word for each kind of movement. The server sends the ledger's name; a cashier
 * reads "Sold", not "SALE", and "Correction", not "ADJUSTMENT".
 */
const WHAT: Record<StockMovementType, string> = {
  OPENING_STOCK: 'Opening stock',
  PURCHASE_RECEIPT: 'Received',
  PURCHASE_RETURN: 'Sent back',
  SALE: 'Sold',
  SALE_RETURN: 'Returned',
  SALE_CANCELLED: 'Bill cancelled',
  DAMAGE: 'Damage',
  TRANSFER_IN: 'Transfer in',
  TRANSFER_OUT: 'Transfer out',
  ADJUSTMENT: 'Correction',
}

/**
 * The kinds somebody actually asks for, each gathering the ledger names that mean it. "Why is this
 * four" is answered by what came in, what went out on bills, what came back, what was written off
 * and what somebody corrected by hand - not by ten separate names for those five things.
 */
const KINDS: { label: string; types: StockMovementType[] }[] = [
  { label: 'Everything', types: [] },
  { label: 'Came in', types: ['PURCHASE_RECEIPT', 'OPENING_STOCK', 'TRANSFER_IN'] },
  { label: 'Sold', types: ['SALE'] },
  { label: 'Came back', types: ['SALE_RETURN', 'SALE_CANCELLED'] },
  { label: 'Corrections', types: ['ADJUSTMENT'] },
  { label: 'Losses', types: ['DAMAGE', 'PURCHASE_RETURN', 'TRANSFER_OUT'] },
]

type Order = 'newest' | 'oldest' | 'biggest'

const ORDERS: { key: Order; label: string }[] = [
  { key: 'newest', label: 'Newest first' },
  { key: 'oldest', label: 'Oldest first' },
  { key: 'biggest', label: 'Biggest change' },
]

/** The item a history was opened on, or null for the whole shop. */
export type HistoryOf = { productId: string; sku: string; name: string } | null

/**
 * Where a count came from.
 *
 * <p>A number on its own is something a shopkeeper has to take on trust, and the first thing
 * anybody does when the screen says four and the shelf holds six is ask why. This is the answer:
 * every movement ever recorded against the item, newest first, each with what the shelf held once
 * it had been applied, and with the till, the person and the time it happened.
 *
 * <p>It is read-only on purpose. Nothing here can be edited - the ledger is append-only, which is
 * what makes the count trustworthy in the first place - so a correction is made on the Stock
 * screen and turns up here as its own line, with whatever reason was typed against it.
 *
 * <p>Worked from the keyboard like every other list in the till: up and down choose a line, left
 * and right choose what kind of movement to show, F2 searches, F3 swaps between this item and the
 * whole shop, F4 orders. Esc closes and hands the cursor back where it came from.
 */
export function StockHistory({ of, onClose }: { of: HistoryOf; onClose: () => void }) {
  const [movements, setMovements] = useState<StockMovement[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [wholeShop, setWholeShop] = useState(of === null)
  const [search, setSearch] = useState('')
  const [kind, setKind] = useState(0)
  const [order, setOrder] = useState<Order>('newest')
  const [at, setAt] = useState(0)
  const list = useRef<HTMLDivElement>(null)
  const searchBox = useRef<HTMLInputElement>(null)
  const lines = useRef(new Map<string, HTMLTableRowElement>())

  const load = useCallback(() => {
    setMovements(null)
    setError(null)
    const asking = wholeShop || !of
      ? `/api/stock/history?days=${SHOP_DAYS}`
      : `/api/stock/history?productId=${of.productId}`
    api.get<StockMovement[]>(asking)
      .then(setMovements)
      .catch((failure) => {
        setMovements([])
        setError(messageOfFailure(failure, 'Could not read the stock history'))
      })
  }, [of, wholeShop])

  useEffect(load, [load])

  /**
   * The cursor starts in the list, so the keys work the moment it opens - twice over, and both
   * times are needed.
   *
   * <p>At once, because a key pressed in the same breath as the one that opened this must not land
   * on the screen behind: F6 then F2 is one movement of somebody's hand, and a frame is long
   * enough for both to arrive.
   *
   * <p>And again through a frame, because the screen underneath hands focus back to its own list
   * when a dialog closes, and it does that through a frame. Closing this and opening it again -
   * Esc, F6 - would otherwise leave the cursor on the list behind, where the arrows move the
   * wrong rows.
   */
  useLayoutEffect(() => {
    list.current?.focus()
    const frame = requestAnimationFrame(() => list.current?.focus())
    return () => cancelAnimationFrame(frame)
  }, [])

  const shown = useMemo(() => {
    const wanted = KINDS[kind].types
    const looking = search.trim().toLowerCase()
    const rows = (movements ?? []).filter((row) => {
      if (wanted.length > 0 && !wanted.includes(row.type)) {
        return false
      }
      if (!looking) {
        return true
      }
      return `${row.name} ${row.sku} ${row.note} ${row.by} ${row.counterCode} ${WHAT[row.type]}`
        .toLowerCase().includes(looking)
    })
    const byTime = (a: StockMovement, b: StockMovement) => a.occurredAt.localeCompare(b.occurredAt)
    return [...rows].sort((a, b) => {
      if (order === 'biggest') {
        return Math.abs(b.quantityDeltaMilli) - Math.abs(a.quantityDeltaMilli) || byTime(b, a)
      }
      return order === 'oldest' ? byTime(a, b) : byTime(b, a)
    })
  }, [movements, kind, search, order])

  const here = Math.min(at, Math.max(shown.length - 1, 0))
  const current = shown[here]

  useEffect(() => {
    if (current) {
      lines.current.get(current.id)?.scrollIntoView({ block: 'nearest' })
    }
  }, [current])

  const onListKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      setAt(Math.max(0, Math.min(shown.length - 1, here + (event.key === 'ArrowDown' ? 1 : -1))))
    } else if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
      event.preventDefault()
      const step = event.key === 'ArrowRight' ? 1 : -1
      setKind((now) => Math.max(0, Math.min(KINDS.length - 1, now + step)))
      setAt(0)
    } else if (event.key === 'Home' || event.key === 'End') {
      event.preventDefault()
      setAt(event.key === 'Home' ? 0 : shown.length - 1)
    } else if (event.key === SEARCH_KEY) {
      event.preventDefault()
      searchBox.current?.focus()
      searchBox.current?.select()
    } else if (event.key === SCOPE_KEY && of) {
      event.preventDefault()
      setWholeShop((whole) => !whole)
      setAt(0)
    } else if (event.key === SORT_KEY) {
      event.preventDefault()
      setOrder(ORDERS[(ORDERS.findIndex((one) => one.key === order) + 1) % ORDERS.length].key)
      setAt(0)
    }
  }

  const onSearchKeyDown = (event: ReactKeyboardEvent<HTMLInputElement>) => {
    if (['Enter', 'Escape', 'ArrowDown'].includes(event.key)) {
      // Esc here only leaves the search box; the dialog stays open.
      event.preventDefault()
      event.stopPropagation()
      event.nativeEvent.stopImmediatePropagation()
      setAt(0)
      list.current?.focus()
    }
  }

  const title = wholeShop || !of
    ? `Stock history · the whole shop, last ${SHOP_DAYS} days`
    : `Stock history · ${of.name}`

  return (
    <Modal title={title} size="5xl" onClose={onClose}>
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <div className="flex flex-wrap items-center gap-1" role="tablist" aria-label="Kind of movement">
          {KINDS.map((one, index) => (
            <button
              key={one.label}
              type="button"
              role="tab"
              aria-selected={index === kind}
              onClick={() => {
                setKind(index)
                setAt(0)
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
        <div className="ml-auto flex items-center gap-2">
          {of && (
            <button
              type="button"
              onClick={() => {
                setWholeShop((whole) => !whole)
                setAt(0)
                list.current?.focus()
              }}
              className="whitespace-nowrap rounded-md border border-slate-300 px-2 py-1 text-xs text-slate-600 hover:bg-slate-50"
            >
              {wholeShop ? `Just ${of.sku}` : 'The whole shop'} <kbd>{SCOPE_KEY}</kbd>
            </button>
          )}
          <button
            type="button"
            onClick={() => {
              setOrder(ORDERS[(ORDERS.findIndex((one) => one.key === order) + 1) % ORDERS.length].key)
              setAt(0)
              list.current?.focus()
            }}
            className="whitespace-nowrap rounded-md border border-slate-300 px-2 py-1 text-xs text-slate-600 hover:bg-slate-50"
          >
            {ORDERS.find((one) => one.key === order)?.label} <kbd>{SORT_KEY}</kbd>
          </button>
          <KeyBox hint={SEARCH_KEY}>
            <input
              ref={searchBox}
              className={`${smallInputClass} w-56 pr-8`}
              placeholder="Bill, reason, person"
              aria-label="Search the history"
              value={search}
              onChange={(event) => {
                setSearch(event.target.value)
                setAt(0)
              }}
              onKeyDown={onSearchKeyDown}
            />
          </KeyBox>
        </div>
      </div>

      {error && <div className="mb-3"><Banner tone="bad">{error}</Banner></div>}

      <div
        ref={list}
        tabIndex={0}
        role="grid"
        aria-label="Stock history"
        onKeyDown={onListKeyDown}
        className="max-h-[60vh] overflow-auto rounded-md outline-none focus-visible:ring-2 focus-visible:ring-sky-200"
      >
        {movements === null ? (
          <Empty>Reading the history…</Empty>
        ) : shown.length === 0 ? (
          <Empty>
            {search || kind > 0
              ? 'Nothing here matches. Press ← → for another kind, or clear the search.'
              : 'Nothing has moved this item yet.'}
          </Empty>
        ) : (
          <table className="w-full min-w-[56rem] table-fixed text-sm">
            <colgroup>
              <col className="w-40" />
              {(wholeShop || !of) && <col className="w-56" />}
              <col className="w-32" />
              <col className="w-28" />
              <col className="w-28" />
              <col />
              <col className="w-36" />
            </colgroup>
            <thead className="sticky top-0 bg-surface text-xs uppercase tracking-wide text-slate-400">
              <tr>
                <th className="py-1 pl-2 text-left">When</th>
                {(wholeShop || !of) && <th className="py-1 text-left">Item</th>}
                <th className="py-1 text-left">What</th>
                <th className="py-1 text-right">Change</th>
                <th className="py-1 text-right">Count after</th>
                <th className="py-1 pl-3 text-left">Reason or bill</th>
                <th className="py-1 pr-2 text-right">Who</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((row, index) => {
                const when = new Date(row.occurredAt)
                const isSelected = index === here
                const up = row.quantityDeltaMilli > 0
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
                    onMouseDown={() => setAt(index)}
                    aria-selected={isSelected}
                    className={`border-t border-slate-100 ${
                      isSelected
                        ? 'bg-sky-50 shadow-[inset_3px_0_0_0_var(--color-sky-500)]'
                        : 'hover:bg-slate-50'}`}
                  >
                    <td className="py-1.5 pl-2">
                      <span className="num">{billDate(when)}</span>
                      <span className="num ml-2 text-xs text-slate-400">{clockTime(when)}</span>
                    </td>
                    {(wholeShop || !of) && (
                      <td className="truncate py-1.5 pr-2" title={`${row.sku} · ${row.name}`}>
                        <span className="num text-xs text-slate-400">{row.sku}</span> {row.name}
                      </td>
                    )}
                    <td className="py-1.5">{WHAT[row.type]}</td>
                    <td className={`num py-1.5 text-right font-medium ${up ? 'text-emerald-700' : 'text-rose-600'}`}>
                      {up ? '+' : '−'}{quantity(Math.abs(row.quantityDeltaMilli))}
                    </td>
                    <td className="num py-1.5 text-right text-slate-500">
                      {row.balanceAfterMilli === null ? '—' : quantity(row.balanceAfterMilli)}
                    </td>
                    <td className="truncate py-1.5 pl-3 text-slate-600" title={row.note}>{row.note || '—'}</td>
                    <td className="truncate py-1.5 pr-2 text-right text-slate-500" title={row.by}>
                      {row.by || '—'}
                      {row.counterCode && <span className="num ml-1 text-xs text-slate-400">{row.counterCode}</span>}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </div>

      <p className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-400">
        <span><kbd>↑</kbd> <kbd>↓</kbd> move</span>
        <span><kbd>←</kbd> <kbd>→</kbd> kind</span>
        <span><kbd>{SEARCH_KEY}</kbd> search</span>
        {of && <span><kbd>{SCOPE_KEY}</kbd> this item or the shop</span>}
        <span><kbd>{SORT_KEY}</kbd> order</span>
        <span><kbd>Esc</kbd> close</span>
        {movements !== null && <span className="ml-auto">{shown.length} of {movements.length}</span>}
      </p>
    </Modal>
  )
}
