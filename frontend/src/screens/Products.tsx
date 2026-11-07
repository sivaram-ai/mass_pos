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
import { loadSort, PRODUCT_SORT_KEY, saveSort, SORT_LABELS, sorted } from '../sorting'
import type { Sort } from '../sorting'
import type { Toast } from '../App'

const UNITS: Unit[] = ['NOS', 'PCS', 'BOX', 'PAC', 'DOZ', 'KGS', 'GMS', 'LTR', 'MLT', 'MTR']
const GST_SLABS = [0, 500, 1200, 1800, 2800]

const SEARCH_KEY = 'F2'
const ADD_KEY = 'F3'
const SORT_KEY = 'F4'

type Action = 'edit' | 'status' | 'delete'

const ACTIONS: Action[] = ['edit', 'status', 'delete']

/** The middle action reads Stop for an item being sold and Start for one that was stopped. */
function labelOf(action: Action, product: Product): string {
  return action === 'edit' ? 'Edit' : action === 'delete' ? 'Delete' : product.active ? 'Stop' : 'Start'
}

const TITLES: Record<Action, (product: Product) => string> = {
  edit: () => 'Change this item',
  status: (product) => product.active
    ? 'Stop selling it. It stays on this screen and in Stock, marked inactive.'
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

/**
 * The catalogue, worked from the keyboard like the bills screen: the cursor starts in the list, up and
 * down choose an item, left and right choose Edit, Stop or Delete, and Enter does it. F2 searches,
 * F3 adds, F4 sorts.
 *
 * The whole catalogue is loaded once, stopped items included, and filtered and sorted here. The
 * server's ranked search is capped at twenty-five rows for the billing grid, and sorting by price or
 * stock has to cover every item rather than whichever twenty-five arrived first.
 */
export default function Products({ onToast }: { onToast: Toast }) {
  const [search, setSearch] = useState('')
  const [products, setProducts] = useState<Product[]>([])
  const [sort, setSort] = useState<Sort>(() => loadSort(PRODUCT_SORT_KEY))
  const [sortOpen, setSortOpen] = useState(false)
  const [printing, setPrinting] = useState(false)
  const [selected, setSelected] = useState(0)
  const [action, setAction] = useState<Action>('edit')
  const [listFocused, setListFocused] = useState(false)
  const [editing, setEditing] = useState<{ id: string | null; draft: Draft } | null>(null)
  const [confirming, setConfirming] = useState<{ product: Product; kind: 'status' | 'delete' } | null>(null)
  const [saveErrors, setSaveErrors] = useState<{ message: string; fields: Record<string, string> } | null>(null)
  const fileBox = useRef<HTMLInputElement>(null)
  const list = useRef<HTMLDivElement>(null)
  const searchBox = useRef<HTMLInputElement>(null)
  const rows = useRef(new Map<string, HTMLTableRowElement>())
  const dialogOpen = editing !== null || confirming !== null

  const load = useCallback(() => {
    api.get<Product[]>('/api/products?all=true')
      .then(setProducts)
      .catch((failure) => onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not load the products') }))
  }, [onToast])

  useEffect(load, [load])

  const chooseSort = (next: Sort) => {
    setSort(next)
    saveSort(PRODUCT_SORT_KEY, next)
    setSelected(0)
  }

  /** A sheet of items: codes already in the catalogue are repriced, new codes are added. */
  const loadFromFile = async (file: File) => {
    try {
      const done = await uploadCsv<{ added: number; updated: number }>('/api/products/import', await file.text())
      onToast({
        tone: 'good',
        text: `${done.added} added and ${done.updated} updated from ${file.name}`,
      })
      load()
    } catch (failure) {
      onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not read that file') })
    }
  }

  const extract = async () => {
    try {
      await downloadCsv('/api/products/export', `products-${new Date().toISOString().slice(0, 10)}.csv`)
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

  // The cursor starts in the list, so the arrows work the moment the screen opens.
  useEffect(() => {
    list.current?.focus()
  }, [])

  // The same fields the server matches, minus its closest-match ranking: an explicit sort is the
  // point of this screen, and a ranking would fight it.
  const needle = search.trim().toLowerCase()
  const shown = sorted(products.filter((product) => !needle
    || `${product.name} ${product.sku} ${product.barcode ?? ''} ${product.hsnCode}`.toLowerCase().includes(needle)),
  sort)
  const stopped = products.filter((product) => !product.active).length

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

  useScreenKeys({
    [SEARCH_KEY]: () => {
      searchBox.current?.focus()
      searchBox.current?.select()
    },
    [ADD_KEY]: () => openEditor(null, EMPTY),
    [SORT_KEY]: () => setSortOpen(true),
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

  function closeDialog(reload = false) {
    setEditing(null)
    setConfirming(null)
    setSaveErrors(null)
    if (reload) {
      load()
    }
    // Back to the list, so the arrows carry on where they were.
    requestAnimationFrame(() => list.current?.focus())
  }

  const run = (product: Product, chosen: Action) => {
    if (chosen === 'edit') {
      openEditor(product.id, draftOf(product))
    } else if (chosen === 'delete' || product.active) {
      setConfirming({ product, kind: chosen })
    } else {
      void setActive(product, true)
    }
  }

  const choose = (index: number) => {
    setSelected(index)
    setAction('edit')
  }

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
      title="Products"
      actions={
        <div className="flex items-center gap-2">
          <span className="whitespace-nowrap text-xs text-slate-500">
            {products.length} items
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
                onChange={chooseSort}
                onClose={() => {
                  setSortOpen(false)
                  requestAnimationFrame(() => list.current?.focus())
                }}
              />
            )}
          </div>
          <KeyBox hint={SEARCH_KEY}>
            <input
              ref={searchBox}
              className={`${smallInputClass} w-64 pr-8`}
              placeholder="Name, code, barcode or HSN"
              aria-label="Search products"
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
                  title="Add or reprice items from a sheet. Start from one Extract products made.">
            Load from file
          </Button>
          <Button className="whitespace-nowrap px-2 py-1" onClick={() => void extract()}
                  title="Save the whole catalogue as a sheet you can edit in Excel">
            Extract products
          </Button>
          <Button className="whitespace-nowrap px-2 py-1" onClick={() => void printList()} disabled={printing}
                  title="Print every item in code order - code, name and rate - on this till's printer">
            {printing ? 'Printing…' : 'Print products'}
          </Button>
          <Button tone="primary" className="whitespace-nowrap px-2 py-1" hint={ADD_KEY}
                  onClick={() => openEditor(null, EMPTY)}>
            Add product
          </Button>
        </div>
      }
    >
      <p className="mb-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-400">
        <span><kbd>↑</kbd> <kbd>↓</kbd> choose an item</span>
        <span><kbd>←</kbd> <kbd>→</kbd> Edit, Stop or Delete</span>
        <span><kbd>Enter</kbd> do it</span>
        <span><kbd>{SEARCH_KEY}</kbd> search · <kbd>{SORT_KEY}</kbd> sort · <kbd>{ADD_KEY}</kbd> add, then <kbd>Enter</kbd> or <kbd>Esc</kbd> back to the list</span>
        <span><kbd>F9</kbd> menu · <kbd>Esc</kbd> <kbd>Esc</kbd> billing</span>
      </p>

      <div
        ref={list}
        tabIndex={0}
        role="grid"
        aria-label="Products"
        onKeyDown={onListKeyDown}
        onFocus={() => setListFocused(true)}
        onBlur={() => setListFocused(false)}
        className="overflow-x-auto rounded-md outline-none focus-visible:ring-2 focus-visible:ring-sky-200"
      >
        {shown.length === 0 ? (
          <Empty>
            {search
              ? `No product matches "${search}"`
              : 'No products yet. Add the first one to start billing.'}
          </Empty>
        ) : (
          /*
           * Code has its own column, so the list can be read and sorted by it. The table stays
           * table-fixed and Item carries no width, so Item takes whatever the fixed columns leave
           * and a 150-character name is truncated instead of pushing the columns off screen.
           */
          <table className="w-full min-w-[60rem] table-fixed text-sm">
            <colgroup>
              <col className="w-28" />
              <col />
              <col className="w-20" />
              <col className="w-20" />
              <col className="w-28" />
              <col className="w-24" />
              <col className="w-28" />
              {/* Three buttons: Edit, Stop or Start, Delete. */}
              <col className="w-44" />
            </colgroup>
            <thead className="text-xs uppercase tracking-wide text-slate-400">
              <tr>
                <th className="py-1 pl-2 text-left">Code</th>
                <th className="py-1 text-left">Item</th>
                <th className="py-1 text-left">HSN</th>
                <th className="py-1 text-right">GST</th>
                <th className="py-1 text-right">Price</th>
                <th className="py-1 text-right">MRP</th>
                <th className="py-1 text-right">Stock</th>
                <th className="py-1"></th>
              </tr>
            </thead>
            <tbody>
              {shown.map((product, index) => {
                const isSelected = index === at
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
                        : product.active ? 'hover:bg-slate-50' : 'bg-slate-50/80 hover:bg-slate-100'}`}
                  >
                    <td className="py-2 pl-2 pr-3">
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
                              className={`rounded-md px-2.5 py-1 text-xs font-medium transition ${
                                focused
                                  ? `bg-sky-600 text-white ${listFocused ? 'ring-2 ring-sky-300' : ''}`
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

      {editing && (
        <ProductDialog
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
            It disappears from billing. It stays on this screen and in Stock, marked inactive, so its
            count is still there and it can be started again. Bills already taken keep it, and
            nothing is deleted.
          </p>
          <div className="mt-4 flex justify-end gap-2">
            <Button onClick={() => closeDialog()} hint="Esc">Keep selling it</Button>
            <ConfirmButton onClick={() => void setActive(confirming.product, false)}>Stop selling it</ConfirmButton>
          </div>
        </Modal>
      )}

      {confirming?.kind === 'delete' && (
        <Modal title="Are you sure you want to delete this product?" onClose={() => closeDialog()}>
          <p className="text-sm font-medium text-slate-800">This action cannot be undone.</p>
          <p className="mt-2 text-sm text-slate-600">
            {confirming.product.name} goes from the Products and Stock screens for good. Bills already
            taken keep it and still read it, and the code{' '}
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

function ProductDialog({ id, draft, errors, onClose, onSave }: {
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
    <Modal title={id ? 'Edit product' : 'New product'} onClose={onClose} wide>
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
