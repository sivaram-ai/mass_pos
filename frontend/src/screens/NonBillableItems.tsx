import { useCallback, useEffect, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import { api, fieldErrorsOf, messageOfFailure, milliFromUnits, quantity } from '../api'
import type { RawMaterial, Unit } from '../types'
import { UNIT_NAMES } from '../types'
import {
  Banner, Button, Empty, errorFor, Field, inputClass, KeyBox, Modal, Panel, smallInputClass,
} from '../components/ui'
import { useScreenKeys } from '../shortcuts'
import { StockHistory } from '../components/StockHistory'
import { PriceHistory } from '../components/PriceHistory'
import { CorrectionDialog } from '../components/CorrectionDialog'
import { OrderDialog } from '../components/OrderDialog'
import type { Orderable } from '../components/OrderDialog'
import type { Toast } from '../App'

const SEARCH_KEY = 'F2'
const ADD_KEY = 'F3'
const HISTORY_KEY = 'F6'
const ORDER_KEY = 'F7'
const PRICES_KEY = 'F8'

/** What can be done to the item the cursor is on, chosen with ← →. */
type Action = 'edit' | 'history' | 'purchase' | 'used' | 'correct'

const ACTIONS: Action[] = ['edit', 'history', 'purchase', 'used', 'correct']

const ACTION_LABELS: Record<Action, string> = {
  edit: 'Edit',
  history: 'History',
  purchase: 'Purchase',
  used: 'Used',
  correct: 'Correct',
}

const TITLES: Record<Action, string> = {
  edit: 'Change what it is called, how it is measured, when to be warned',
  history: 'Where this count came from: everything that has ever moved it',
  purchase: 'Write a purchase order for it. Stock only arrives that way.',
  used: 'What the kitchen took out of the store',
  correct: 'Put the count right: a stock take, wastage, what went back to the vendor',
}

type Editing = { id: string | null; code: string; name: string; unit: Unit; reorderLevel: string; active: boolean }

const BLANK: Editing = { id: null, code: '', name: '', unit: 'NOS', reorderLevel: '', active: true }

function orderable(row: RawMaterial): Orderable {
  return { kind: 'r', id: row.id, code: row.code, name: row.name, unit: row.unit }
}

/**
 * Non billable items: the store behind the kitchen. Butter, onions, oil, flour, carrier bags -
 * everything the shop buys and uses and never sells.
 *
 * <p>A list of its own rather than a corner of Items, because none of what a sold item needs means
 * anything here: a sack of potatoes has no selling price, no MRP, no GST rate and no HSN code. And
 * because of the billing grid - a cashier typing "on" must never be offered Onions among the
 * things they can ring up, and the surest way to see to that is for these never to be in the
 * catalogue at all.
 *
 * <p>Worked exactly like Items, so nobody has to learn it twice: the cursor starts in the list, up
 * and down choose, left and right choose what to do, Enter does it. Space ticks several for one
 * order. Nothing here adds stock either - it arrives against a purchase order, and a count that is
 * wrong is corrected with a reason.
 */
export default function NonBillableItems({ by, onToast }: { by: string; onToast: Toast }) {
  const [rows, setRows] = useState<RawMaterial[]>([])
  const [filter, setFilter] = useState('')
  const [selected, setSelected] = useState(0)
  const [action, setAction] = useState<Action>('edit')
  const [listFocused, setListFocused] = useState(false)
  const [ticked, setTicked] = useState<string[]>([])
  const [using, setUsing] = useState<RawMaterial | null>(null)
  const [correcting, setCorrecting] = useState<RawMaterial | null>(null)
  const [ordering, setOrdering] = useState<Orderable[] | null>(null)
  const [pricesOn, setPricesOn] = useState<RawMaterial | null>(null)
  const [editing, setEditing] = useState<Editing | null>(null)
  const [removing, setRemoving] = useState<RawMaterial | null>(null)
  const [historyOpen, setHistoryOpen] = useState(false)
  const list = useRef<HTMLDivElement>(null)
  const searchBox = useRef<HTMLInputElement>(null)
  const lines = useRef(new Map<string, HTMLTableRowElement>())
  const dialogOpen = using !== null || correcting !== null || ordering !== null || pricesOn !== null
    || editing !== null || removing !== null || historyOpen

  const load = useCallback(() => {
    api.get<RawMaterial[]>('/api/raw-materials')
      .then(setRows)
      .catch((failure) => onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not load the store') }))
  }, [onToast])

  useEffect(load, [load])

  useEffect(() => {
    list.current?.focus()
  }, [])

  const looking = filter.trim().toLowerCase()
  const shown = rows.filter((row) => `${row.name} ${row.code}`.toLowerCase().includes(looking))
  const lowOnSomething = rows.filter((row) => row.lowOnIt).length
  const stopped = rows.filter((row) => !row.active).length

  const at = Math.min(selected, Math.max(shown.length - 1, 0))
  const current = shown[at]

  useEffect(() => {
    if (current) {
      lines.current.get(current.id)?.scrollIntoView({ block: 'nearest' })
    }
  }, [current])

  /** What Purchase would put on an order: everything ticked, or whatever the cursor is on. */
  const forOrder = (): Orderable[] => {
    const chosen = rows.filter((row) => ticked.includes(row.id))
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
    [ADD_KEY]: () => setEditing(BLANK),
    [HISTORY_KEY]: () => setHistoryOpen(true),
    [ORDER_KEY]: () => setOrdering(forOrder()),
    [PRICES_KEY]: () => setPricesOn(current ?? null),
  }, !dialogOpen)

  const choose = (index: number) => {
    setSelected(index)
    setAction('edit')
  }

  const backToList = (reload = false) => {
    setUsing(null)
    setCorrecting(null)
    setOrdering(null)
    setPricesOn(null)
    setEditing(null)
    setRemoving(null)
    setHistoryOpen(false)
    if (reload) {
      load()
    }
    requestAnimationFrame(() => list.current?.focus())
  }

  const run = (what: Action, row: RawMaterial) => {
    if (what === 'edit') {
      setEditing({
        id: row.id,
        code: row.code,
        name: row.name,
        unit: row.unit,
        reorderLevel: row.reorderLevelMilli > 0 ? quantity(row.reorderLevelMilli) : '',
        active: row.active,
      })
    } else if (what === 'history') {
      setHistoryOpen(true)
    } else if (what === 'purchase') {
      setOrdering(forOrder())
    } else if (what === 'used') {
      setUsing(row)
    } else {
      setCorrecting(row)
    }
  }

  const tick = (id: string) =>
    setTicked((now) => (now.includes(id) ? now.filter((other) => other !== id) : [...now, id]))

  const onListKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (dialogOpen || !current) {
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
      event.preventDefault()
      tick(current.id)
    } else if (event.key === 'Escape' && ticked.length > 0) {
      // Clears the ticks and nothing else, so it does not count toward Esc Esc back to billing.
      event.preventDefault()
      event.stopPropagation()
      event.nativeEvent.stopImmediatePropagation()
      setTicked([])
    } else if (event.key === 'Delete') {
      event.preventDefault()
      setRemoving(current)
    } else if (event.key === 'Enter' && !event.repeat) {
      event.preventDefault()
      run(action, current)
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
      title="Non billable items"
      actions={
        <div className="flex flex-wrap items-center justify-end gap-2">
          <span className="whitespace-nowrap text-xs text-slate-500">
            {rows.length} in the store
            {lowOnSomething > 0 && <span className="ml-2 text-amber-700">{lowOnSomething} running low</span>}
            {stopped > 0 && <span className="ml-2 text-slate-400">{stopped} stopped</span>}
          </span>
          <Button className="whitespace-nowrap px-2 py-1" hint={HISTORY_KEY} onClick={() => setHistoryOpen(true)}
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
          <Button className="whitespace-nowrap px-2 py-1" hint={ADD_KEY} onClick={() => setEditing(BLANK)}>
            Add an item
          </Button>
          <KeyBox hint={SEARCH_KEY}>
            <input
              ref={searchBox}
              className={`${smallInputClass} w-56 pr-8`}
              placeholder="Name or code"
              aria-label="Filter non billable items"
              value={filter}
              onChange={(event) => {
                setFilter(event.target.value)
                choose(0)
              }}
              onKeyDown={onSearchKeyDown}
            />
          </KeyBox>
        </div>
      }
    >
      <p className="mb-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-400">
        <span><kbd>↑</kbd> <kbd>↓</kbd> choose</span>
        <span><kbd>←</kbd> <kbd>→</kbd> what to do</span>
        <span><kbd>Enter</kbd> do it · <kbd>Delete</kbd> remove</span>
        <span><kbd>Space</kbd> tick for one order</span>
        <span>
          <kbd>{SEARCH_KEY}</kbd> search · <kbd>{ADD_KEY}</kbd> add · <kbd>{HISTORY_KEY}</kbd> history
          {' · '}<kbd>{ORDER_KEY}</kbd> purchase · <kbd>{PRICES_KEY}</kbd> prices
        </span>
        <span><kbd>F9</kbd> menu · <kbd>Esc</kbd> <kbd>Esc</kbd> billing</span>
      </p>

      <div
        ref={list}
        tabIndex={0}
        role="grid"
        aria-label="Non billable items"
        onKeyDown={onListKeyDown}
        onFocus={() => setListFocused(true)}
        onBlur={() => setListFocused(false)}
        className="overflow-x-auto rounded-md outline-none focus-visible:ring-2 focus-visible:ring-sky-200"
      >
        {shown.length === 0 ? (
          <Empty>
            {filter
              ? `Nothing matches "${filter}"`
              : 'Nothing in the store yet. Add what the kitchen buys: oil, onions, flour, carrier bags.'}
          </Empty>
        ) : (
          <table className="w-full min-w-[58rem] table-fixed text-sm">
            <colgroup>
              <col className="w-8" />
              <col className="w-32" />
              <col />
              <col className="w-32" />
              <col className="w-32" />
              <col className="w-[22rem]" />
            </colgroup>
            <thead className="text-xs uppercase tracking-wide text-slate-400">
              <tr>
                <th className="py-1 pl-2"></th>
                <th className="py-1 text-left">Code</th>
                <th className="py-1 text-left">Item</th>
                <th className="py-1 text-right">In store</th>
                <th className="py-1 text-right">Tell me below</th>
                <th className="py-1"></th>
              </tr>
            </thead>
            <tbody>
              {shown.map((row, index) => {
                const isSelected = index === at
                const isTicked = ticked.includes(row.id)
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
                    aria-selected={isSelected}
                    className={`cursor-pointer border-t border-slate-100 ${
                      isSelected
                        ? 'bg-sky-50 shadow-[inset_3px_0_0_0_var(--color-sky-500)]'
                        : isTicked ? 'bg-sky-50/40'
                          : row.active ? 'hover:bg-slate-50' : 'bg-slate-50/80 hover:bg-slate-100'}`}
                  >
                    <td className="py-1.5 pl-2">
                      <input
                        type="checkbox"
                        tabIndex={-1}
                        aria-label={`Put ${row.name} on an order`}
                        checked={isTicked}
                        onMouseDown={(event) => event.stopPropagation()}
                        onClick={(event) => {
                          event.stopPropagation()
                          choose(index)
                          tick(row.id)
                        }}
                        onChange={() => {}}
                      />
                    </td>
                    <td className="num py-1.5 text-slate-500">{row.code}</td>
                    <td className="truncate py-1.5 pr-2" title={row.name}>
                      {row.name}
                      {!row.active && <span className="ml-2 text-xs text-slate-400">stopped</span>}
                    </td>
                    <td className={`num py-1.5 text-right ${row.lowOnIt ? 'font-medium text-amber-700' : ''}`}>
                      {quantity(row.inStoreMilli)} <span className="text-xs text-slate-400">{row.unit}</span>
                    </td>
                    <td className="num py-1.5 text-right text-slate-400">
                      {row.reorderLevelMilli > 0 ? quantity(row.reorderLevelMilli) : '—'}
                    </td>
                    <td className="py-1 pr-2">
                      <div className="flex justify-end gap-1">
                        {ACTIONS.map((name) => {
                          const focused = isSelected && name === action
                          return (
                            <button
                              key={name}
                              type="button"
                              tabIndex={-1}
                              title={TITLES[name]}
                              onMouseDown={(event) => event.stopPropagation()}
                              onClick={(event) => {
                                event.stopPropagation()
                                choose(index)
                                setAction(name)
                                run(name, row)
                              }}
                              className={`rounded-md px-2 py-1 text-xs font-medium transition ${
                                focused
                                  ? `bg-sky-600 text-on-primary ${listFocused ? 'ring-2 ring-sky-300' : ''}`
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

      {historyOpen && (
        <StockHistory
          source="raw"
          of={current ? { itemId: current.id, itemCode: current.code, name: current.name } : null}
          onClose={() => {
            setHistoryOpen(false)
            requestAnimationFrame(() => list.current?.focus())
          }}
        />
      )}

      {pricesOn && (
        <PriceHistory
          of={{ itemId: pricesOn.id, itemCode: pricesOn.code, name: pricesOn.name, fromTheStore: true }}
          onClose={() => {
            setPricesOn(null)
            requestAnimationFrame(() => list.current?.focus())
          }}
        />
      )}

      {using && (
        <UsedDialog
          row={using}
          onToast={onToast}
          onClose={() => backToList()}
          onDone={() => backToList(true)}
        />
      )}

      {correcting && (
        <CorrectionDialog
          of={{
            itemId: correcting.id,
            itemCode: correcting.code,
            name: correcting.name,
            unit: correcting.unit,
            haveMilli: correcting.inStoreMilli,
          }}
          source="raw"
          by={by}
          onToast={onToast}
          onClose={() => backToList()}
          onDone={() => backToList(true)}
        />
      )}

      {ordering && (
        <OrderDialog
          start={ordering}
          onToast={onToast}
          onClose={() => backToList()}
          onDone={() => {
            setTicked([])
            backToList(true)
          }}
        />
      )}

      {editing && (
        <ItemDialog
          draft={editing}
          onToast={onToast}
          onClose={() => backToList()}
          onDone={() => backToList(true)}
        />
      )}

      {removing && (
        <Modal title={`Remove ${removing.name}?`} onClose={() => backToList()}>
          <p className="text-sm text-slate-600">
            It goes from this list and its code is free again. What the store did with it stays in
            the history, because that is what every count was made of.
          </p>
          <div className="mt-4 flex justify-end gap-2">
            <Button onClick={() => backToList()}>Keep it</Button>
            <Button
              tone="danger"
              onClick={async () => {
                try {
                  await api.delete(`/api/raw-materials/${removing.id}`)
                  onToast({ tone: 'good', text: `${removing.name} removed` })
                  backToList(true)
                } catch (failure) {
                  onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not remove it') })
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

/** What the kitchen took out of the store, which is the one movement that is nobody's mistake. */
function UsedDialog({ row, onClose, onDone, onToast }: {
  row: RawMaterial
  onClose: () => void
  onDone: () => void
  onToast: Toast
}) {
  const [amount, setAmount] = useState('')
  const [note, setNote] = useState('')
  const [errors, setErrors] = useState<{ message: string; fields: Record<string, string> } | null>(null)

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    const milli = milliFromUnits(amount)
    if (milli === 0) {
      setErrors({ message: 'Enter how much, as a number.', fields: {} })
      return
    }
    try {
      const after = await api.post<RawMaterial>(`/api/raw-materials/${row.id}/consumption`,
        { quantityMilli: Math.abs(milli), note })
      // What the store holds now, said back: a take-out stops at empty, so somebody who typed five
      // and had three should be told rather than left to find it on the list.
      onToast({
        tone: 'good',
        text: `${row.name}: ${quantity(after.inStoreMilli)} ${after.unit} in the store`,
      })
      onDone()
    } catch (failure) {
      setErrors({ message: messageOfFailure(failure, 'Could not record that'), fields: fieldErrorsOf(failure) })
    }
  }

  return (
    <Modal title={`Used in the kitchen · ${row.name}`} onClose={onClose}>
      <form onSubmit={submit} className="space-y-3">
        {errors && <Banner tone="bad" onDismiss={() => setErrors(null)}>{errors.message}</Banner>}
        <p className="text-sm text-slate-500">
          now {quantity(row.inStoreMilli)} {row.unit} in the store
        </p>
        <Field label="How much was taken" error={errorFor(errors?.fields ?? {}, 'quantityMilli')}>
          <input className={`${inputClass} num text-lg`} inputMode="decimal" autoFocus value={amount}
                 onChange={(event) => setAmount(event.target.value)} />
        </Field>
        <Field label="Note" optional hint="Which dish, which shift, anything worth keeping"
               error={errorFor(errors?.fields ?? {}, 'note')}>
          <input className={inputClass} value={note} onChange={(event) => setNote(event.target.value)} />
        </Field>
        <div className="flex justify-end gap-2">
          <Button onClick={onClose} hint="Esc">Cancel</Button>
          <Button type="submit" tone="primary" hint="Enter">Record</Button>
        </div>
      </form>
    </Modal>
  )
}

/** Adding one, or changing what it is called and when to be told it is running out. */
function ItemDialog({ draft, onClose, onDone, onToast }: {
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
      unit: form.unit,
      reorderLevelMilli: milliFromUnits(form.reorderLevel),
      active: form.active,
    }
    try {
      if (form.id) {
        await api.put(`/api/raw-materials/${form.id}`, body)
      } else {
        await api.post('/api/raw-materials', body)
      }
      onToast({ tone: 'good', text: `${body.name} saved` })
      onDone()
    } catch (failure) {
      setErrors({ message: messageOfFailure(failure, 'Could not save it'), fields: fieldErrorsOf(failure) })
    }
  }

  return (
    <Modal title={form.id ? `Edit ${draft.name}` : 'Add a non billable item'} size="xl" onClose={onClose}>
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
          <Field label="Measured in" error={errorFor(errors?.fields ?? {}, 'unit')}>
            <select className={inputClass} value={form.unit}
                    onChange={(event) => set('unit', event.target.value as Unit)}>
              {Object.entries(UNIT_NAMES).map(([key, label]) => (
                <option key={key} value={key}>{label}</option>
              ))}
            </select>
          </Field>
          <Field label="Tell me below" hint="Leave empty if nobody is watching this one"
                 error={errorFor(errors?.fields ?? {}, 'reorderLevelMilli')}>
            <input className={`${inputClass} num`} inputMode="decimal" value={form.reorderLevel}
                   onChange={(event) => set('reorderLevel', event.target.value)} />
          </Field>
        </div>
        <label className="flex items-center gap-2 text-sm text-slate-600">
          <input type="checkbox" checked={form.active} onChange={(event) => set('active', event.target.checked)} />
          Still used in this kitchen
        </label>
        <div className="flex justify-end gap-2">
          <Button onClick={onClose} hint="Esc">Cancel</Button>
          <Button type="submit" tone="primary" hint="Enter">Save</Button>
        </div>
      </form>
    </Modal>
  )
}
