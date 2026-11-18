import { useCallback, useEffect, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import {
  api, downloadCsv, fieldErrorsOf, messageOfFailure, paiseFromRupees, percent, quantity, rupees, uploadCsv,
} from '../api'
import type { Product, Unit } from '../types'
import { UNIT_NAMES } from '../types'
import {
  Banner, Button, Empty, errorFor, Field, inputClass, KeyBox, Modal, Panel, smallInputClass,
} from '../components/ui'
import { useScreenKeys } from '../shortcuts'
import { SortMenu } from '../components/SortMenu'
import { StockHistory } from '../components/StockHistory'
import { PriceHistory } from '../components/PriceHistory'
import { CorrectionDialog } from '../components/CorrectionDialog'
import { WriteOrder } from '../components/WriteOrder'
import type { Orderable } from '../components/WriteOrder'
import { ITEM_SORT_KEY, loadSort, saveSort, SORT_FIELDS, SORT_LABELS, sorted } from '../sorting'
import type { Sort } from '../sorting'
import type { Toast } from '../App'

const UNITS: Unit[] = ['NOS', 'PCS', 'BOX', 'PAC', 'DOZ', 'KGS', 'GMS', 'LTR', 'MLT', 'MTR']
const GST_SLABS = [0, 500, 1200, 1800, 2800]

const SEARCH_KEY = 'F2'
const ADD_KEY = 'F3'
const SORT_KEY = 'F4'
const HISTORY_KEY = 'F6'
const ORDER_KEY = 'F7'
const PRICES_KEY = 'F8'

/** What can be done to the item the cursor is on, chosen with ← →. */
type Action = 'edit' | 'history' | 'purchase' | 'correct' | 'status' | 'delete'

const ACTIONS: Action[] = ['edit', 'history', 'purchase', 'correct', 'status', 'delete']

/** The fifth action reads Stop for an item being sold and Start for one that was stopped. */
function labelOf(action: Action, product: Product): string {
  switch (action) {
    case 'edit':
      return 'Edit'
    case 'history':
      return 'History'
    case 'purchase':
      return 'Purchase'
    case 'correct':
      return 'Correct'
    case 'delete':
      return 'Delete'
    default:
      return product.active ? 'Stop' : 'Start'
  }
}

const TITLES: Record<Action, (product: Product) => string> = {
  edit: () => 'Change this item: its name, code, price, tax',
  history: () => 'Where this count came from: everything that has ever moved it',
  purchase: () => 'Write a purchase order for it. Stock only arrives that way.',
  correct: () => 'Put the count right: a stock take, damage, wastage, opening stock',
  status: (product) => product.active
    ? 'Stop selling it. It stays on this screen, marked, with its count.'
    : 'Sell it again',
  delete: () => 'Remove it from the system for good and free its code',
}

type Draft = {
  sku: string
  barcode: string
  name: string
  hsnCode: string
  unit: Unit
  sellingPrice: string
  mrp: string
  taxInclusive: boolean
  gstRateBp: number
  cessRateBp: number
  billWithoutStock: boolean
}

const EMPTY: Draft = {
  sku: '', barcode: '', name: '', hsnCode: '', unit: 'NOS',
  sellingPrice: '', mrp: '', taxInclusive: true, gstRateBp: 1800, cessRateBp: 0, billWithoutStock: true,
}

function draftOf(product: Product): Draft {
  return {
    sku: product.sku,
    barcode: product.barcode ?? '',
    name: product.name,
    hsnCode: product.hsnCode,
    unit: product.unit,
    sellingPrice: product.sellingPricePaise ? (product.sellingPricePaise / 100).toFixed(2) : '',
    mrp: product.mrpPaise ? (product.mrpPaise / 100).toFixed(2) : '',
    taxInclusive: product.taxInclusive,
    gstRateBp: product.gstRateBp,
    cessRateBp: product.cessRateBp,
    billWithoutStock: product.billWithoutStock,
  }
}

function orderable(product: Product): Orderable {
  return { kind: 'p', id: product.id, code: product.sku, name: product.name, unit: product.unit }
}

/**
 * Everything the shop sells: what it is, what it costs, and how much of it there is.
 *
 * <p>One screen, where there were two. Products and Stock listed the same items from the same
 * table and asked somebody to remember which of them answered which question - the price was on
 * one, the count on the other, and the item they were both about was in two places. A shopkeeper
 * holding a thing in their hand has one question about it, and it is "what is this and how many
 * are there".
 *
 * <p>Nothing here adds stock. Goods reach the shelf through a purchase order an administrator
 * agreed to, which is what Purchase writes; what is already on the shelf and wrong is put right
 * with Correct, which says why and shows up in the history as the correction it is.
 *
 * <p>Worked from the keyboard throughout: the cursor starts in the list, up and down choose an
 * item, left and right choose what to do with it, Enter does it. Space ticks items for one order
 * together. F2 searches, F3 adds, F4 sorts, F6 shows the history, F7 orders, F8 shows what it has
 * cost.
 */
export default function Items({ by, onToast }: { by: string; onToast: Toast }) {
  const [search, setSearch] = useState('')
  const [products, setProducts] = useState<Product[]>([])
  const [sort, setSort] = useState<Sort>(() => loadSort(ITEM_SORT_KEY))
  const [sortOpen, setSortOpen] = useState(false)
  const [printing, setPrinting] = useState(false)
  const [selected, setSelected] = useState(0)
  const [action, setAction] = useState<Action>('edit')
  const [listFocused, setListFocused] = useState(false)
  const [ticked, setTicked] = useState<string[]>([])
  const [editing, setEditing] = useState<{ id: string | null; draft: Draft } | null>(null)
  const [confirming, setConfirming] = useState<{ product: Product; kind: 'status' | 'delete' } | null>(null)
  const [saveErrors, setSaveErrors] = useState<{ message: string; fields: Record<string, string> } | null>(null)
  const [historyOpen, setHistoryOpen] = useState(false)
  const [pricesOn, setPricesOn] = useState<Product | null>(null)
  const [correcting, setCorrecting] = useState<Product | null>(null)
  const [ordering, setOrdering] = useState<Orderable[] | null>(null)
  /** How far below zero this shop's counts have gone, and whether to offer to put it right. */
  const [belowEmpty, setBelowEmpty] = useState<{ items: number; quantityMilli: number } | null>(null)
  const [zeroing, setZeroing] = useState(false)
  const fileBox = useRef<HTMLInputElement>(null)
  const list = useRef<HTMLDivElement>(null)
  const searchBox = useRef<HTMLInputElement>(null)
  const rows = useRef(new Map<string, HTMLTableRowElement>())
  const dialogOpen = editing !== null || confirming !== null || historyOpen || pricesOn !== null
    || correcting !== null || ordering !== null

  const load = useCallback(() => {
    api.get<Product[]>('/api/products?all=true')
      .then(setProducts)
      .catch((failure) => onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not load the items') }))
    // Counts that went below zero on an older build. Asked for separately so the offer to put them
    // right can say what it would be correcting before anybody agrees to it.
    api.get<{ items: number; quantityMilli: number }>('/api/stock/below-empty')
      .then(setBelowEmpty)
      .catch(() => setBelowEmpty(null))
  }, [onToast])

  useEffect(load, [load])

  // The cursor starts in the list, so the arrows work the moment the screen opens.
  useEffect(() => {
    list.current?.focus()
  }, [])

  const chooseSort = (next: Sort) => {
    setSort(next)
    saveSort(ITEM_SORT_KEY, next)
    setSelected(0)
  }

  // The same fields the server matches, minus its closest-match ranking: an explicit sort is the
  // point of this screen, and a ranking would fight it.
  const needle = search.trim().toLowerCase()
  const shown = sorted(products.filter((product) => !needle
    || `${product.name} ${product.sku} ${product.barcode ?? ''} ${product.hsnCode}`.toLowerCase().includes(needle)),
  sort)
  const stopped = products.filter((product) => !product.active).length
  const outOfStock = products.filter((product) => !product.billWithoutStock && product.stockMilli <= 0).length
  const totalValue = products.reduce(
    (sum, product) => sum + Math.round(product.stockMilli * product.sellingPricePaise / 1000), 0)

  const at = Math.min(selected, Math.max(shown.length - 1, 0))
  const current = shown[at]

  useEffect(() => {
    if (current) {
      rows.current.get(current.id)?.scrollIntoView({ block: 'nearest' })
    }
  }, [current])

  const openEditor = (id: string | null, draft: Draft) => {
    setSaveErrors(null)
    setEditing({ id, draft })
  }

  /** What Purchase would put on an order: everything ticked, or whatever the cursor is on. */
  const forOrder = (): Orderable[] => {
    const chosen = products.filter((product) => ticked.includes(product.id))
    if (chosen.length > 0) {
      return chosen.map(orderable)
    }
    return current ? [orderable(current)] : []
  }

  useScreenKeys({
    [SEARCH_KEY]: () => {
      searchBox.current?.focus()
      searchBox.current?.select()
    },
    [ADD_KEY]: () => openEditor(null, EMPTY),
    [SORT_KEY]: () => setSortOpen(true),
    [HISTORY_KEY]: () => setHistoryOpen(true),
    [ORDER_KEY]: () => setOrdering(forOrder()),
    [PRICES_KEY]: () => setPricesOn(current ?? null),
  }, !dialogOpen && !sortOpen)

  const save = async (id: string | null, draft: Draft) => {
    const body = {
      sku: draft.sku.trim(),
      barcode: draft.barcode.trim() || null,
      name: draft.name.trim(),
      hsnCode: draft.hsnCode.trim(),
      unit: draft.unit,
      sellingPricePaise: paiseFromRupees(draft.sellingPrice),
      mrpPaise: paiseFromRupees(draft.mrp),
      taxInclusive: draft.taxInclusive,
      gstRateBp: draft.gstRateBp,
      cessRateBp: draft.cessRateBp,
      billWithoutStock: draft.billWithoutStock,
    }
    try {
      if (id) {
        await api.put(`/api/products/${id}`, body)
      } else {
        await api.post('/api/products', body)
      }
      closeDialog(true)
      onToast({ tone: 'good', text: `Saved ${draft.name}` })
    } catch (failure) {
      // Shown inside the dialog, which stays open with what was typed.
      setSaveErrors({ message: messageOfFailure(failure, 'Could not save'), fields: fieldErrorsOf(failure) })
    }
  }

  /** Stopping asks first; starting again is harmless, so it just happens. */
  const setActive = async (product: Product, active: boolean) => {
    try {
      await api.post(`/api/products/${product.id}/${active ? 'activate' : 'deactivate'}`)
      closeDialog(true)
      onToast({
        tone: 'good',
        text: active ? `${product.name} is on sale again` : `${product.name} is no longer sold`,
      })
    } catch (failure) {
      onToast({
        tone: 'bad',
        text: messageOfFailure(failure, active ? 'Could not start selling it' : 'Could not stop selling it'),
      })
    }
  }

  const remove = async (product: Product) => {
    try {
      await api.delete(`/api/products/${product.id}`)
      closeDialog(true)
      onToast({ tone: 'good', text: `${product.name} is deleted. The code ${product.sku} is free again.` })
    } catch (failure) {
      onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not delete it') })
    }
  }

  /**
   * One correction per item, each with its reason. Nothing is rewritten: the ledger is
   * append-only, and what it says happened is what happened.
   */
  const bringUpToZero = async () => {
    setZeroing(true)
    try {
      const done = await api.post<{ items: number; quantityMilli: number }>('/api/stock/below-empty/correct')
      onToast({
        tone: 'good',
        text: `${done.items} ${done.items === 1 ? 'count' : 'counts'} brought up to zero`,
      })
      load()
    } catch (failure) {
      onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not correct those counts') })
    } finally {
      setZeroing(false)
    }
  }

  /** A sheet of items: codes already in the catalogue are repriced, new codes are added. */
  const loadFromFile = async (file: File) => {
    try {
      const done = await uploadCsv<{ added: number; updated: number }>('/api/products/import', await file.text())
      onToast({ tone: 'good', text: `${done.added} added and ${done.updated} updated from ${file.name}` })
      load()
    } catch (failure) {
      onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not read that file') })
    }
  }

  const extract = async () => {
    try {
      await downloadCsv('/api/products/export', `items-${new Date().toISOString().slice(0, 10)}.csv`)
    } catch (failure) {
      onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not make the file') })
    }
  }

  /** The catalogue on paper, from the printer this till bills on. The server waits for the job. */
  const printList = async () => {
    setPrinting(true)
    try {
      await api.post('/api/products/print-list')
      onToast({ tone: 'good', text: 'Price list sent to the printer' })
    } catch (failure) {
      onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not print the price list') })
    } finally {
      setPrinting(false)
    }
  }

  function closeDialog(reload = false) {
    setEditing(null)
    setConfirming(null)
    setSaveErrors(null)
    setCorrecting(null)
    setOrdering(null)
    setPricesOn(null)
    setHistoryOpen(false)
    if (reload) {
      load()
    }
    // Back to the list, so the arrows carry on where they were.
    requestAnimationFrame(() => list.current?.focus())
  }

  const run = (product: Product, chosen: Action) => {
    if (chosen === 'edit') {
      openEditor(product.id, draftOf(product))
    } else if (chosen === 'history') {
      setHistoryOpen(true)
    } else if (chosen === 'purchase') {
      setOrdering(forOrder())
    } else if (chosen === 'correct') {
      setCorrecting(product)
    } else if (chosen === 'delete' || product.active) {
      setConfirming({ product, kind: chosen as 'status' | 'delete' })
    } else {
      void setActive(product, true)
    }
  }

  const choose = (index: number) => {
    setSelected(index)
    setAction('edit')
  }

  const tick = (id: string) =>
    setTicked((now) => (now.includes(id) ? now.filter((other) => other !== id) : [...now, id]))

  const onListKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (dialogOpen || sortOpen || !current) {
      return
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      choose(Math.max(0, Math.min(shown.length - 1, at + (event.key === 'ArrowDown' ? 1 : -1))))
    } else if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
      event.preventDefault()
      const step = event.key === 'ArrowRight' ? 1 : -1
      setAction(ACTIONS[Math.max(0, Math.min(ACTIONS.length - 1, ACTIONS.indexOf(action) + step))])
    } else if (event.key === 'Home' || event.key === 'End') {
      event.preventDefault()
      choose(event.key === 'Home' ? 0 : shown.length - 1)
    } else if (event.key === ' ') {
      // Ticks it for an order with others. Billing's Space takes the bill; nothing on this screen
      // competes for it, and the search box is a text field, where it types a space.
      event.preventDefault()
      tick(current.id)
    } else if (event.key === 'Escape' && ticked.length > 0) {
      // Clears the ticks and nothing else: it must not count toward Esc Esc back to billing while
      // there is something in hand to put down.
      event.preventDefault()
      event.stopPropagation()
      event.nativeEvent.stopImmediatePropagation()
      setTicked([])
    } else if (event.key === 'Enter') {
      event.preventDefault()
      if (!event.repeat) {
        run(current, action)
      }
    }
  }

  const onSearchKeyDown = (event: ReactKeyboardEvent<HTMLInputElement>) => {
    if (['Enter', 'Escape', 'ArrowDown'].includes(event.key)) {
      // Esc here only leaves the search box: it must not count toward Esc Esc back to billing.
      event.preventDefault()
      event.stopPropagation()
      event.nativeEvent.stopImmediatePropagation()
      choose(0)
      list.current?.focus()
    }
  }

  return (
    <Panel
      title="Items"
      actions={
        <div className="flex flex-wrap items-center justify-end gap-2">
          <span className="whitespace-nowrap text-xs text-slate-500">
            {products.length} {products.length === 1 ? 'item' : 'items'} ·{' '}
            <span className="num">{rupees(totalValue)}</span>
            {outOfStock > 0 && <span className="ml-2 text-rose-600">{outOfStock} out of stock</span>}
            {stopped > 0 && <span className="ml-2 text-amber-700">{stopped} stopped</span>}
          </span>
          {/* Relative, so the sort popover hangs off this button rather than the page. */}
          <div className="relative">
            <Button className="whitespace-nowrap px-2 py-1" hint={SORT_KEY}
                    onClick={() => setSortOpen((open) => !open)}
                    title="Choose what the list is ordered by">
              {SORT_LABELS[sort.field]} {sort.dir === 'asc' ? '↑' : '↓'}
            </Button>
            {sortOpen && (
              <SortMenu
                sort={sort}
                fields={SORT_FIELDS}
                labels={SORT_LABELS}
                onChange={chooseSort}
                onClose={() => {
                  setSortOpen(false)
                  requestAnimationFrame(() => list.current?.focus())
                }}
              />
            )}
          </div>
          <Button className="whitespace-nowrap px-2 py-1" hint={HISTORY_KEY}
                  onClick={() => setHistoryOpen(true)}
                  title="Where this count came from: everything that has moved this item">
            History
          </Button>
          <Button className="whitespace-nowrap px-2 py-1" hint={PRICES_KEY}
                  onClick={() => setPricesOn(current ?? null)}
                  title="What the shop has paid for this, every delivery of it">
            Prices
          </Button>
          <Button tone="primary" className="whitespace-nowrap px-2 py-1" hint={ORDER_KEY}
                  onClick={() => setOrdering(forOrder())}
                  title="Write a purchase order. Tick several with Space to put them on one order.">
            {ticked.length > 0 ? `Purchase ${ticked.length}` : 'Purchase'}
          </Button>
          <KeyBox hint={SEARCH_KEY}>
            <input
              ref={searchBox}
              className={`${smallInputClass} w-64 pr-8`}
              placeholder="Name, code, barcode or HSN"
              aria-label="Search items"
              value={search}
              onChange={(event) => {
                setSearch(event.target.value)
                choose(0)
              }}
              onKeyDown={onSearchKeyDown}
            />
          </KeyBox>
          <input
            ref={fileBox}
            type="file"
            accept=".csv,text/csv"
            className="hidden"
            onChange={(event) => {
              const chosen = event.target.files?.[0]
              event.target.value = '' // so picking the same file again still counts as a change
              if (chosen) {
                void loadFromFile(chosen)
              }
            }}
          />
          <Button className="whitespace-nowrap px-2 py-1" onClick={() => fileBox.current?.click()}
                  title="Add or reprice items from a sheet. Start from one Extract items made.">
            Load from file
          </Button>
          <Button className="whitespace-nowrap px-2 py-1" onClick={() => void extract()}
                  title="Save the whole catalogue as a sheet you can edit in Excel">
            Extract items
          </Button>
          <Button className="whitespace-nowrap px-2 py-1" onClick={() => void printList()} disabled={printing}
                  title="Print every item in code order - code, name and rate - on this till's printer">
            {printing ? 'Printing…' : 'Print'}
          </Button>
          <Button tone="primary" className="whitespace-nowrap px-2 py-1" hint={ADD_KEY}
                  onClick={() => openEditor(null, EMPTY)}>
            Add item
          </Button>
        </div>
      }
    >
      {belowEmpty && belowEmpty.items > 0 && (
        <div className="mb-2">
          <Banner tone="warn">
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
              <span>
                <span className="num font-medium">{belowEmpty.items}</span>
                {belowEmpty.items === 1 ? ' item has' : ' items have'} a count below zero, adding up
                to <span className="num font-medium">{quantity(belowEmpty.quantityMilli)}</span>. That
                cannot happen any more, but the figures already recorded stay wrong until somebody
                says what the shelf really holds.
              </span>
              <Button className="whitespace-nowrap px-2 py-1" disabled={zeroing}
                      onClick={() => void bringUpToZero()}>
                {zeroing ? 'Correcting…' : 'Bring them to zero'}
              </Button>
            </div>
          </Banner>
        </div>
      )}

      <p className="mb-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-400">
        <span><kbd>↑</kbd> <kbd>↓</kbd> choose an item</span>
        <span><kbd>←</kbd> <kbd>→</kbd> what to do</span>
        <span><kbd>Enter</kbd> do it</span>
        <span><kbd>Space</kbd> tick for one order</span>
        <span>
          <kbd>{SEARCH_KEY}</kbd> search · <kbd>{SORT_KEY}</kbd> sort · <kbd>{HISTORY_KEY}</kbd> history
          {' · '}<kbd>{ORDER_KEY}</kbd> purchase · <kbd>{PRICES_KEY}</kbd> prices · <kbd>{ADD_KEY}</kbd> add
        </span>
        <span><kbd>F9</kbd> menu · <kbd>Esc</kbd> <kbd>Esc</kbd> billing</span>
      </p>

      <div
        ref={list}
        tabIndex={0}
        role="grid"
        aria-label="Items"
        onKeyDown={onListKeyDown}
        onFocus={() => setListFocused(true)}
        onBlur={() => setListFocused(false)}
        className="overflow-x-auto rounded-md outline-none focus-visible:ring-2 focus-visible:ring-sky-200"
      >
        {shown.length === 0 ? (
          <Empty>
            {search
              ? `No item matches "${search}"`
              : 'No items yet. Add the first one to start billing.'}
          </Empty>
        ) : (
          /*
           * Code has its own column, so the list can be read and sorted by it. The table stays
           * table-fixed and Item carries no width, so Item takes whatever the fixed columns leave
           * and a 150-character name is truncated instead of pushing the columns off screen.
           */
          <table className="w-full min-w-[74rem] table-fixed text-sm">
            <colgroup>
              <col className="w-8" />
              <col className="w-28" />
              <col />
              <col className="w-20" />
              <col className="w-16" />
              <col className="w-24" />
              <col className="w-20" />
              <col className="w-28" />
              <col className="w-24" />
              {/* Six actions: Edit, History, Purchase, Correct, Stop or Start, Delete. */}
              <col className="w-[23rem]" />
            </colgroup>
            <thead className="text-xs uppercase tracking-wide text-slate-400">
              <tr>
                <th className="py-1 pl-2"></th>
                <th className="py-1 text-left">Code</th>
                <th className="py-1 text-left">Item</th>
                <th className="py-1 text-left">HSN</th>
                <th className="py-1 text-right">GST</th>
                <th className="py-1 text-right">Price</th>
                <th className="py-1 text-right">MRP</th>
                <th className="py-1 text-right">On hand</th>
                <th className="py-1 text-right">Value</th>
                <th className="py-1"></th>
              </tr>
            </thead>
            <tbody>
              {shown.map((product, index) => {
                const isSelected = index === at
                const isTicked = ticked.includes(product.id)
                const value = Math.round(product.stockMilli * product.sellingPricePaise / 1000)
                return (
                  <tr
                    key={product.id}
                    ref={(element) => {
                      if (element) {
                        rows.current.set(product.id, element)
                      } else {
                        rows.current.delete(product.id)
                      }
                    }}
                    onMouseDown={() => choose(index)}
                    onClick={() => run(product, 'edit')}
                    aria-selected={isSelected}
                    className={`cursor-pointer border-t border-slate-100 ${
                      isSelected
                        ? 'bg-sky-50 shadow-[inset_3px_0_0_0_var(--color-sky-500)]'
                        : isTicked ? 'bg-sky-50/40'
                          : product.active ? 'hover:bg-slate-50' : 'bg-slate-50/80 hover:bg-slate-100'}`}
                  >
                    <td className="py-2 pl-2">
                      <input
                        type="checkbox"
                        tabIndex={-1}
                        aria-label={`Put ${product.name} on an order`}
                        checked={isTicked}
                        onMouseDown={(event) => event.stopPropagation()}
                        onClick={(event) => {
                          event.stopPropagation()
                          choose(index)
                          tick(product.id)
                        }}
                        onChange={() => {}}
                      />
                    </td>
                    <td className="py-2 pr-3">
                      {/* The barcode sits under the code: it is the other code that bills the item. */}
                      <div className={`num truncate font-medium ${product.active ? 'text-slate-800' : 'text-slate-400'}`}
                           title={product.sku}>
                        {product.sku}
                      </div>
                      {product.barcode && (
                        <div className="num truncate text-xs text-slate-400" title={product.barcode}>
                          {product.barcode}
                        </div>
                      )}
                    </td>
                    <td className="py-2 pr-3">
                      {/* Long names are cut with the full name on hover, so the columns never move. */}
                      <div className={`flex items-center gap-2 ${product.active ? '' : 'text-slate-400'}`}>
                        <span className={`truncate ${product.active ? 'text-slate-800' : ''}`} title={product.name}>
                          {product.name}
                        </span>
                        {!product.active && (
                          <span className="shrink-0 rounded bg-amber-100 px-1.5 py-0.5 text-xs font-medium text-amber-800"
                                title="Stopped: still counted and still here, but not sold">
                            Inactive
                          </span>
                        )}
                      </div>
                    </td>
                    <td className="num truncate py-2 pr-2 text-slate-600" title={product.hsnCode}>
                      {product.hsnCode || <span className="text-slate-300">—</span>}
                    </td>
                    <td className="num py-2 text-right">
                      {percent(product.gstRateBp)}
                      <div className="text-xs text-slate-400">{product.taxInclusive ? 'in price' : 'on top'}</div>
                    </td>
                    <td className="num py-2 text-right">
                      {product.sellingPricePaise > 0
                        ? rupees(product.sellingPricePaise)
                        : <span className="text-xs text-amber-700">typed at billing</span>}
                    </td>
                    <td className="num py-2 text-right text-slate-500">
                      {product.mrpPaise ? rupees(product.mrpPaise) : '—'}
                    </td>
                    <td className={`num py-2 text-right ${
                      product.billWithoutStock ? 'text-slate-500' : product.stockMilli <= 0 ? 'text-rose-600' : ''}`}>
                      {quantity(product.stockMilli)} <span className="text-xs text-slate-400">{product.unit}</span>
                      {product.billWithoutStock && (
                        <div className="text-xs text-slate-400" title="Sold whatever the stock count says">
                          not counted
                        </div>
                      )}
                    </td>
                    <td className="num py-2 text-right text-slate-500">{rupees(value)}</td>
                    <td className="whitespace-nowrap py-1.5 pr-2 text-right">
                      <div className="inline-flex gap-1">
                        {ACTIONS.map((name) => {
                          const focused = isSelected && action === name
                          const warns = name === 'delete' || (name === 'status' && product.active)
                          return (
                            <button
                              key={name}
                              type="button"
                              tabIndex={-1}
                              title={TITLES[name](product)}
                              onMouseDown={(event) => event.stopPropagation()}
                              onClick={(event) => {
                                event.stopPropagation()
                                choose(index)
                                setAction(name)
                                run(product, name)
                              }}
                              className={`rounded-md px-2 py-1 text-xs font-medium transition ${
                                focused
                                  ? `bg-sky-600 text-on-primary ${listFocused ? 'ring-2 ring-sky-300' : ''}`
                                  : warns ? 'text-rose-600 hover:bg-rose-50' : 'text-slate-600 hover:bg-slate-100'}`}
                            >
                              {labelOf(name, product)}
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

      {historyOpen && (
        <StockHistory
          of={current ? { itemId: current.id, itemCode: current.sku, name: current.name } : null}
          onClose={() => {
            setHistoryOpen(false)
            requestAnimationFrame(() => list.current?.focus())
          }}
        />
      )}

      {pricesOn && (
        <PriceHistory
          of={{ itemId: pricesOn.id, itemCode: pricesOn.sku, name: pricesOn.name, fromTheStore: false }}
          onClose={() => {
            setPricesOn(null)
            requestAnimationFrame(() => list.current?.focus())
          }}
        />
      )}

      {correcting && (
        <CorrectionDialog
          of={{
            itemId: correcting.id,
            itemCode: correcting.sku,
            name: correcting.name,
            unit: correcting.unit,
            haveMilli: correcting.stockMilli,
          }}
          source="stock"
          by={by}
          onToast={onToast}
          onClose={() => closeDialog()}
          onDone={() => closeDialog(true)}
        />
      )}

      {ordering && (
        <WriteOrder
          start={ordering}
          framed
          onToast={onToast}
          onClose={() => closeDialog()}
          onWritten={() => {
            setTicked([])
            closeDialog(true)
          }}
        />
      )}

      {editing && (
        <ItemDialog
          id={editing.id}
          draft={editing.draft}
          errors={saveErrors}
          onClose={() => closeDialog()}
          onSave={save}
        />
      )}

      {confirming?.kind === 'status' && (
        <Modal title={`Stop selling ${confirming.product.name}?`} onClose={() => closeDialog()}>
          <p className="text-sm text-slate-600">
            It disappears from billing. It stays on this screen, marked inactive, so its count is
            still there and it can be started again. Bills already taken keep it, and nothing is
            deleted.
          </p>
          <div className="mt-4 flex justify-end gap-2">
            <Button onClick={() => closeDialog()} hint="Esc">Keep selling it</Button>
            <ConfirmButton onClick={() => void setActive(confirming.product, false)}>Stop selling it</ConfirmButton>
          </div>
        </Modal>
      )}

      {confirming?.kind === 'delete' && (
        <Modal title="Are you sure you want to delete this item?" onClose={() => closeDialog()}>
          <p className="text-sm font-medium text-slate-800">This action cannot be undone.</p>
          <p className="mt-2 text-sm text-slate-600">
            {confirming.product.name} goes from this screen for good. Bills already taken keep it and
            still read it, and the code{' '}
            <span className="num font-medium text-slate-800">{confirming.product.sku}</span> becomes
            free to use again.
          </p>
          <div className="mt-4 flex justify-end gap-2">
            <Button onClick={() => closeDialog()} hint="Esc">Cancel</Button>
            <ConfirmButton onClick={() => void remove(confirming.product)}>Delete</ConfirmButton>
          </div>
        </Modal>
      )}
    </Panel>
  )
}

/** The button a confirm dialog opens on, so Enter alone finishes the job. */
function ConfirmButton({ children, onClick }: { children: string; onClick: () => void }) {
  const button = useRef<HTMLButtonElement>(null)
  useEffect(() => button.current?.focus(), [])
  return (
    <button
      ref={button}
      type="button"
      onClick={onClick}
      onKeyDown={(event) => {
        if (event.key === 'Enter') {
          event.preventDefault()
          onClick()
        }
      }}
      className="inline-flex items-center gap-2 rounded-md bg-rose-600 px-3 py-2 text-sm font-medium text-white hover:bg-rose-500"
    >
      {children}
      <kbd>Enter</kbd>
    </button>
  )
}

function ItemDialog({ id, draft, errors, onClose, onSave }: {
  id: string | null
  draft: Draft
  errors: { message: string; fields: Record<string, string> } | null
  onClose: () => void
  onSave: (id: string | null, draft: Draft) => void
}) {
  const [form, setForm] = useState(draft)
  const set = (change: Partial<Draft>) => setForm({ ...form, ...change })
  const err = (field: string) => errorFor(errors?.fields ?? {}, field)

  return (
    <Modal title={id ? 'Edit item' : 'New item'} onClose={onClose} wide>
      <form
        className="grid grid-cols-2 gap-3"
        onSubmit={(event) => {
          event.preventDefault()
          onSave(id, form)
        }}
        // Enter saves from any box, on every keyboard; a dropdown keeps Enter for choosing.
        onKeyDown={(event) => {
          if (event.key === 'Enter' && !event.repeat && event.target instanceof HTMLInputElement
            && event.target.type !== 'checkbox') {
            event.preventDefault()
            onSave(id, form)
          }
        }}
      >
        {errors && (
          <div className="col-span-2">
            <Banner tone="bad">{errors.message}</Banner>
          </div>
        )}
        <Field label="Name" error={err('name')}>
          <input className={inputClass} value={form.name} autoFocus
                 onChange={(event) => set({ name: event.target.value })} />
        </Field>
        <Field label="Item code" error={err('sku')}
               hint={id
                 ? 'Typed in the code column when billing. Bills already taken keep the code they were sold under'
                 : 'Typed in the code column when billing, e.g. 19'}>
          <input className={inputClass} value={form.sku}
                 onChange={(event) => set({ sku: event.target.value })} />
        </Field>
        <Field label="Barcode" optional error={err('barcode')}>
          <input className={`${inputClass} num`} value={form.barcode}
                 onChange={(event) => set({ barcode: event.target.value })} />
        </Field>
        <Field label="HSN / SAC code" optional error={err('hsnCode')}
               hint="4, 6 or 8 digits. Not needed if the shop has no GSTIN">
          <input className={`${inputClass} num`} value={form.hsnCode}
                 onChange={(event) => set({ hsnCode: event.target.value })} />
        </Field>
        <Field label="Sold by" error={err('unit')}>
          <select className={inputClass} value={form.unit}
                  onChange={(event) => set({ unit: event.target.value as Unit })}>
            {UNITS.map((unit) => <option key={unit} value={unit}>{unit} - {UNIT_NAMES[unit]}</option>)}
          </select>
        </Field>
        <Field label="GST rate" error={err('gstRateBp')} hint="Ignored when the shop has no GSTIN">
          <select className={inputClass} value={form.gstRateBp}
                  onChange={(event) => set({ gstRateBp: Number(event.target.value) })}>
            {GST_SLABS.map((slab) => <option key={slab} value={slab}>{percent(slab)}</option>)}
          </select>
        </Field>
        <Field label="Selling price" optional error={err('sellingPricePaise')}
               hint={`Leave empty to type the rate on each bill. ${form.taxInclusive ? 'Tax included' : 'Tax added on top'}`}>
          <input className={`${inputClass} num`} inputMode="decimal" value={form.sellingPrice}
                 onChange={(event) => set({ sellingPrice: event.target.value })} />
        </Field>
        <Field label="MRP" optional error={err('mrpPaise')} hint="Printed maximum retail price, if the item has one">
          <input className={`${inputClass} num`} inputMode="decimal" value={form.mrp}
                 onChange={(event) => set({ mrp: event.target.value })} />
        </Field>
        <label className="flex items-center gap-2 text-sm text-slate-600">
          <input type="checkbox" checked={form.taxInclusive}
                 onChange={(event) => set({ taxInclusive: event.target.checked })} />
          Selling price already includes GST
        </label>
        <label className="flex items-start gap-2 text-sm text-slate-600">
          <input type="checkbox" className="mt-1" checked={form.billWithoutStock}
                 onChange={(event) => set({ billWithoutStock: event.target.checked })} />
          <span>
            Bill without stock
            <span className="block text-xs text-slate-400">
              Sell it whatever the stock count says. For cooked food, loose produce and services, whose
              stock is never counted.
            </span>
          </span>
        </label>
        <div className="col-span-2 flex justify-end gap-2">
          <Button onClick={onClose} hint="Esc">Cancel</Button>
          <Button type="submit" tone="primary" hint="Enter">Save</Button>
        </div>
      </form>
    </Modal>
  )
}
