import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import { api, billDate, messageOfFailure, quantity, rupees } from '../api'
import type { PaidPrice } from '../types'
import { Banner, Empty, KeyBox, Modal, smallInputClass } from './ui'

const SEARCH_KEY = 'F2'
const SORT_KEY = 'F4'

/** The item being asked about, and which of the two lists it came from. */
export type PricesOf = { itemId: string; itemCode: string; name: string; fromTheStore: boolean }

type Order = 'newest' | 'dearest' | 'cheapest'

const ORDERS: { key: Order; label: string }[] = [
  { key: 'newest', label: 'Newest first' },
  { key: 'dearest', label: 'Dearest first' },
  { key: 'cheapest', label: 'Cheapest first' },
]

/**
 * What the shop has paid for one thing, every time it has bought it.
 *
 * <p>The item itself carries one buying price - whatever the last delivery cost - and that answers
 * none of the questions worth asking: whether onions have got dearer since Deepavali, whether the
 * new vegetable man is cheaper than the old one, whether somebody agreed to a price nobody should
 * have. A price is a fact about a delivery, so this reads the deliveries.
 *
 * <p>Every row is one delivery, with the order it came in against and who agreed to the money.
 * Deliveries a till took in by hand, before an order was the only way in, have neither and say so
 * rather than showing a blank.
 */
export function PriceHistory({ of, onClose }: { of: PricesOf; onClose: () => void }) {
  const [prices, setPrices] = useState<PaidPrice[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [order, setOrder] = useState<Order>('newest')
  const [at, setAt] = useState(0)
  const list = useRef<HTMLDivElement>(null)
  const searchBox = useRef<HTMLInputElement>(null)
  const rows = useRef(new Map<string, HTMLTableRowElement>())

  const load = useCallback(() => {
    setPrices(null)
    setError(null)
    const asking = of.fromTheStore ? `rawMaterialId=${of.itemId}` : `productId=${of.itemId}`
    api.get<PaidPrice[]>(`/api/purchase-orders/prices?${asking}`)
      .then(setPrices)
      .catch((failure) => {
        setPrices([])
        setError(messageOfFailure(failure, 'Could not read what this has cost'))
      })
  }, [of])

  useEffect(load, [load])

  // At once and again through a frame: the same two focus moves every dialog here needs, because a
  // key pressed in the same breath as the one that opened this must not land on the screen behind,
  // and the screen behind hands focus back to its own list through a frame when a dialog closes.
  useLayoutEffect(() => {
    list.current?.focus()
    const frame = requestAnimationFrame(() => list.current?.focus())
    return () => cancelAnimationFrame(frame)
  }, [])

  const shown = useMemo(() => {
    const looking = search.trim().toLowerCase()
    const matching = (prices ?? []).filter((row) => !looking
      || `${row.vendorName} ${row.orderNumber} ${row.approvedBy} ${row.by}`.toLowerCase().includes(looking))
    return [...matching].sort((a, b) => {
      if (order === 'dearest') {
        return b.unitCostPaise - a.unitCostPaise
      }
      if (order === 'cheapest') {
        return a.unitCostPaise - b.unitCostPaise
      }
      return b.when.localeCompare(a.when)
    })
  }, [prices, search, order])

  const here = Math.min(at, Math.max(shown.length - 1, 0))
  const current = shown[here]

  useEffect(() => {
    if (current) {
      rows.current.get(current.when + current.orderNumber)?.scrollIntoView({ block: 'nearest' })
    }
  }, [current])

  // The two figures the whole screen exists for: the dearest and the cheapest it has ever been.
  const dearest = shown.reduce((most, row) => Math.max(most, row.unitCostPaise), 0)
  const cheapest = shown.reduce(
    (least, row) => (least === 0 ? row.unitCostPaise : Math.min(least, row.unitCostPaise)), 0)
  const latest = prices && prices.length > 0 ? prices[0] : null

  const onListKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      setAt(Math.max(0, Math.min(shown.length - 1, here + (event.key === 'ArrowDown' ? 1 : -1))))
    } else if (event.key === 'Home' || event.key === 'End') {
      event.preventDefault()
      setAt(event.key === 'Home' ? 0 : shown.length - 1)
    } else if (event.key === SEARCH_KEY) {
      event.preventDefault()
      searchBox.current?.focus()
      searchBox.current?.select()
    } else if (event.key === SORT_KEY) {
      event.preventDefault()
      setOrder(ORDERS[(ORDERS.findIndex((one) => one.key === order) + 1) % ORDERS.length].key)
      setAt(0)
    }
  }

  return (
    <Modal title={`What this has cost · ${of.name}`} size="5xl" onClose={onClose}>
      <div className="mb-3 flex flex-wrap items-center gap-3">
        <span className="text-sm text-slate-500">
          <span className="num">{of.itemCode}</span>
          {latest && (
            <>
              {' · last paid '}
              <span className="num font-medium text-slate-700">{rupees(latest.unitCostPaise)}</span>
              {latest.vendorName && ` to ${latest.vendorName}`}
            </>
          )}
          {shown.length > 1 && dearest !== cheapest && (
            <>
              {' · between '}
              <span className="num">{rupees(cheapest)}</span>
              {' and '}
              <span className="num">{rupees(dearest)}</span>
            </>
          )}
        </span>
        <div className="ml-auto flex items-center gap-2">
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
              placeholder="Vendor, order, person"
              aria-label="Search what this has cost"
              value={search}
              onChange={(event) => {
                setSearch(event.target.value)
                setAt(0)
              }}
              onKeyDown={(event) => {
                if (['Enter', 'Escape', 'ArrowDown'].includes(event.key)) {
                  // Esc here only leaves the search box; the dialog stays open.
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
      </div>

      {error && <div className="mb-3"><Banner tone="bad">{error}</Banner></div>}

      <div
        ref={list}
        tabIndex={0}
        role="grid"
        aria-label="What this has cost"
        onKeyDown={onListKeyDown}
        className="max-h-[60vh] overflow-auto rounded-md outline-none focus-visible:ring-2 focus-visible:ring-sky-200"
      >
        {prices === null ? (
          <Empty>Reading what this has cost…</Empty>
        ) : shown.length === 0 ? (
          <Empty>
            {search
              ? 'Nothing here matches. Clear the search.'
              : 'Nothing bought yet. Write a purchase order, and every delivery against it lands here.'}
          </Empty>
        ) : (
          <table className="w-full min-w-[56rem] table-fixed text-sm">
            <colgroup>
              <col className="w-28" />
              <col />
              <col className="w-28" />
              <col className="w-28" />
              <col className="w-28" />
              <col className="w-32" />
              <col className="w-36" />
            </colgroup>
            <thead className="sticky top-0 bg-surface text-xs uppercase tracking-wide text-slate-400">
              <tr>
                <th className="py-1 pl-2 text-left">When</th>
                <th className="py-1 text-left">Vendor</th>
                <th className="py-1 text-right">How much</th>
                <th className="py-1 text-right">Each</th>
                <th className="py-1 text-right">Came to</th>
                <th className="py-1 pl-3 text-left">Order</th>
                <th className="py-1 pr-2 text-right">Approved by</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((row, index) => {
                const key = row.when + row.orderNumber
                const isSelected = index === here
                return (
                  <tr
                    key={key + index}
                    ref={(element) => {
                      if (element) {
                        rows.current.set(key, element)
                      } else {
                        rows.current.delete(key)
                      }
                    }}
                    onMouseDown={() => setAt(index)}
                    aria-selected={isSelected}
                    className={`border-t border-slate-100 ${
                      isSelected
                        ? 'bg-sky-50 shadow-[inset_3px_0_0_0_var(--color-sky-500)]'
                        : 'hover:bg-slate-50'}`}
                  >
                    <td className="num py-1.5 pl-2">{billDate(new Date(row.when))}</td>
                    <td className="truncate py-1.5 pr-2" title={row.vendorName}>
                      {row.vendorName || <span className="text-slate-400">not recorded</span>}
                    </td>
                    <td className="num py-1.5 text-right">
                      {quantity(Math.abs(row.quantityMilli))}{' '}
                      <span className="text-xs text-slate-400">{row.unit}</span>
                    </td>
                    <td className={`num py-1.5 text-right font-medium ${
                      row.unitCostPaise === dearest && dearest !== cheapest ? 'text-rose-600' : ''}`}>
                      {row.unitCostPaise > 0 ? rupees(row.unitCostPaise) : <span className="text-slate-300">—</span>}
                    </td>
                    <td className="num py-1.5 text-right text-slate-500">{rupees(row.valuePaise)}</td>
                    <td className="num truncate py-1.5 pl-3 text-slate-600" title={row.orderNumber}>
                      {row.orderNumber || <span className="text-xs text-slate-400">taken in by hand</span>}
                    </td>
                    <td className="truncate py-1.5 pr-2 text-right text-slate-500"
                        title={`${row.approvedBy || 'nobody'} · taken in by ${row.by}`}>
                      {row.approvedBy || <span className="text-xs text-slate-400">—</span>}
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
        <span><kbd>{SEARCH_KEY}</kbd> search</span>
        <span><kbd>{SORT_KEY}</kbd> order</span>
        <span><kbd>Esc</kbd> close</span>
        {prices !== null && <span className="ml-auto">{shown.length} of {prices.length} deliveries</span>}
      </p>
    </Modal>
  )
}
