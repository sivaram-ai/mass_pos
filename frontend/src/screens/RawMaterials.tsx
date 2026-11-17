import { useCallback, useEffect, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import {
  api, fieldErrorsOf, messageOfFailure, milliFromUnits, paiseFromRupees, quantity, rupees,
} from '../api'
import type { RawMaterial, Unit } from '../types'
import { UNIT_NAMES } from '../types'
import {
  Banner, Button, Empty, errorFor, Field, inputClass, KeyBox, Modal, Panel, smallInputClass,
} from '../components/ui'
import { useScreenKeys } from '../shortcuts'
import { StockHistory } from '../components/StockHistory'
import { VendorPicker } from '../components/VendorPicker'
import type { ChosenVendor } from '../components/VendorPicker'
import type { Toast } from '../App'

const SEARCH_KEY = 'F2'
const ADD_KEY = 'F3'
const HISTORY_KEY = 'F6'

/** What can be done to the material the cursor is on, chosen with ← →. */
type Action = 'purchase' | 'consume' | 'waste' | 'correct' | 'edit'

const ACTIONS: Action[] = ['purchase', 'consume', 'waste', 'correct', 'edit']

const ACTION_LABELS: Record<Action, string> = {
  purchase: 'Bought',
  consume: 'Used',
  waste: 'Wasted',
  correct: 'Correct',
  edit: 'Edit',
}

/** The endpoint each movement posts to, and how its dialog reads. */
const MOVEMENTS: Record<Exclude<Action, 'edit'>, { path: string; title: string; hint: string }> = {
  purchase: { path: 'purchases', title: 'Bought in', hint: 'How much arrived' },
  consume: { path: 'consumption', title: 'Used in the kitchen', hint: 'How much was taken' },
  waste: { path: 'wastage', title: 'Wasted', hint: 'How much was spoiled or thrown out' },
  correct: { path: 'corrections', title: 'Correct the count', hint: 'The difference, - to take off' },
}

type Editing = { id: string | null; code: string; name: string; unit: Unit; reorderLevel: string; active: boolean }

const BLANK: Editing = { id: null, code: '', name: '', unit: 'NOS', reorderLevel: '', active: true }

/**
 * The store behind the kitchen: butter, onions, oil, flour, carrier bags - everything the shop
 * buys and uses and never sells.
 *
 * <p>A screen of its own rather than a corner of Products, because none of what a product needs
 * means anything here: a sack of potatoes has no selling price, no MRP, no GST rate and no HSN
 * code. And because of the billing grid - a cashier typing "on" must never be offered Onions
 * among the things they can ring up, and the surest way to see to that is for raw materials never
 * to be in the catalogue at all.
 *
 * <p>Worked exactly like Stock, so nobody has to learn it twice: the cursor starts in the list, up
 * and down choose a material, left and right choose what to do with it, Enter does it. F2 filters,
 * F3 adds, F6 shows where a count came from.
 */
export default function RawMaterials({ onToast }: { onToast: Toast }) {
  const [rows, setRows] = useState<RawMaterial[]>([])
  const [filter, setFilter] = useState('')
  const [selected, setSelected] = useState(0)
  const [action, setAction] = useState<Action>('purchase')
  const [listFocused, setListFocused] = useState(false)
  const [movement, setMovement] = useState<{ kind: Exclude<Action, 'edit'>; row: RawMaterial } | null>(null)
  const [editing, setEditing] = useState<Editing | null>(null)
  const [removing, setRemoving] = useState<RawMaterial | null>(null)
  const [historyOpen, setHistoryOpen] = useState(false)
  const list = useRef<HTMLDivElement>(null)
  const searchBox = useRef<HTMLInputElement>(null)
  const lines = useRef(new Map<string, HTMLTableRowElement>())
  const dialogOpen = movement !== null || editing !== null || removing !== null || historyOpen

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

  useScreenKeys({
    [SEARCH_KEY]: () => {
      searchBox.current?.focus()
      searchBox.current?.select()
    },
    [ADD_KEY]: () => setEditing(BLANK),
    [HISTORY_KEY]: () => setHistoryOpen(true),
  }, !dialogOpen)

  const choose = (index: number) => {
    setSelected(index)
    setAction('purchase')
  }

  const backToList = (reload = false) => {
    setMovement(null)
    setEditing(null)
    setRemoving(null)
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
      return
    }
    setMovement({ kind: what, row })
  }

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
      title="Raw materials"
      actions={
        <div className="flex items-center gap-2">
          <span className="whitespace-nowrap text-xs text-slate-500">
            {rows.length} in the store
            {lowOnSomething > 0 && <span className="ml-2 text-amber-700">{lowOnSomething} running low</span>}
            {stopped > 0 && <span className="ml-2 text-slate-400">{stopped} stopped</span>}
          </span>
          <Button className="whitespace-nowrap px-2 py-1" hint={HISTORY_KEY} onClick={() => setHistoryOpen(true)}
                  title="Where this count came from: everything that has moved this material">
            History
          </Button>
          <Button className="whitespace-nowrap px-2 py-1" tone="primary" hint={ADD_KEY}
                  onClick={() => setEditing(BLANK)}>
            Add a raw material
          </Button>
          <KeyBox hint={SEARCH_KEY}>
            <input
              ref={searchBox}
              className={`${smallInputClass} w-56 pr-8`}
              placeholder="Name or code"
              aria-label="Filter raw materials"
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
        <span><kbd>←</kbd> <kbd>→</kbd> Bought, Used, Wasted, Correct or Edit</span>
        <span><kbd>Enter</kbd> do it · <kbd>Delete</kbd> remove</span>
        <span><kbd>{SEARCH_KEY}</kbd> search · <kbd>{ADD_KEY}</kbd> add · <kbd>{HISTORY_KEY}</kbd> history</span>
        <span><kbd>F9</kbd> menu · <kbd>Esc</kbd> <kbd>Esc</kbd> billing</span>
      </p>

      <div
        ref={list}
        tabIndex={0}
        role="grid"
        aria-label="Raw materials"
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
          <table className="w-full min-w-[54rem] table-fixed text-sm">
            <colgroup>
              <col className="w-32" />
              <col />
              <col className="w-32" />
              <col className="w-32" />
              <col className="w-[22rem]" />
            </colgroup>
            <thead className="text-xs uppercase tracking-wide text-slate-400">
              <tr>
                <th className="py-1 pl-2 text-left">Code</th>
                <th className="py-1 text-left">Material</th>
                <th className="py-1 text-right">In store</th>
                <th className="py-1 text-right">Tell me below</th>
                <th className="py-1"></th>
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
                    aria-selected={isSelected}
                    className={`cursor-pointer border-t border-slate-100 ${
                      isSelected
                        ? 'bg-sky-50 shadow-[inset_3px_0_0_0_var(--color-sky-500)]'
                        : row.active ? 'hover:bg-slate-50' : 'bg-slate-50/80 hover:bg-slate-100'}`}
                  >
                    <td className="num py-1.5 pl-2 text-slate-500">{row.code}</td>
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
                                  : name === 'waste'
                                    ? 'text-rose-600 hover:bg-rose-50'
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

      {movement && (
        <MovementDialog
          kind={movement.kind}
          row={movement.row}
          onToast={onToast}
          onClose={() => backToList()}
          onDone={() => backToList(true)}
        />
      )}

      {editing && (
        <MaterialDialog
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

/** Bought, used, wasted or corrected: one quantity, one note, and a price when it is a purchase. */
function MovementDialog({ kind, row, onClose, onDone, onToast }: {
  kind: Exclude<Action, 'edit'>
  row: RawMaterial
  onClose: () => void
  onDone: () => void
  onToast: Toast
}) {
  const [amount, setAmount] = useState('')
  const [note, setNote] = useState('')
  const [price, setPrice] = useState('')
  const [vendor, setVendor] = useState<ChosenVendor>({ vendorId: null, vendorName: '' })
  const [errors, setErrors] = useState<{ message: string; fields: Record<string, string> } | null>(null)
  const buying = kind === 'purchase'
  const unitCost = paiseFromRupees(price)
  const milliNow = milliFromUnits(amount)

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    const signed = amount.trim().startsWith('-')
    const milli = milliFromUnits(amount) * (signed ? -1 : 1)
    if (milli === 0) {
      setErrors({ message: 'Enter how much, as a number.', fields: {} })
      return
    }
    try {
      const body = kind === 'correct'
        ? { deltaMilli: milli, note }
        : buying
          ? {
            quantityMilli: Math.abs(milli),
            note,
            buyingPricePaise: unitCost,
            vendorName: vendor.vendorName.trim(),
            vendorId: vendor.vendorId,
          }
          : { quantityMilli: Math.abs(milli), note }
      const after = await api.post<RawMaterial>(`/api/raw-materials/${row.id}/${MOVEMENTS[kind].path}`, body)
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
    <Modal title={`${MOVEMENTS[kind].title} · ${row.name}`} onClose={onClose}>
      <form onSubmit={submit} className="space-y-3">
        {errors && <Banner tone="bad" onDismiss={() => setErrors(null)}>{errors.message}</Banner>}
        <p className="text-sm text-slate-500">
          now {quantity(row.inStoreMilli)} {row.unit} in the store
        </p>
        <Field label="Quantity" hint={MOVEMENTS[kind].hint}
               error={errorFor(errors?.fields ?? {}, kind === 'correct' ? 'deltaMilli' : 'quantityMilli')}>
          <input className={`${inputClass} num text-lg`} inputMode="decimal" autoFocus value={amount}
                 onChange={(event) => setAmount(event.target.value)} />
        </Field>
        {buying && (
          <div className="grid grid-cols-2 gap-3">
            <Field
              label="Buying price"
              hint={unitCost > 0 && milliNow > 0
                ? `${quantity(milliNow)} × ${rupees(unitCost)} = ${rupees(Math.round(milliNow * unitCost / 1000))}`
                : 'What one costs you. Leave it empty if you do not track this.'}
              error={errorFor(errors?.fields ?? {}, 'buyingPricePaise')}
            >
              <input className={`${inputClass} num`} inputMode="decimal" placeholder="0.00" value={price}
                     onChange={(event) => setPrice(event.target.value)} />
            </Field>
            <VendorPicker value={vendor} onChange={setVendor}
                          error={errorFor(errors?.fields ?? {}, 'vendorName')} />
          </div>
        )}
        <Field label="Note" hint={buying ? 'Invoice number, lorry, anything worth keeping' : 'Reason'}
               error={errorFor(errors?.fields ?? {}, 'note')}>
          <input className={inputClass} value={note} onChange={(event) => setNote(event.target.value)} />
        </Field>
        <div className="flex justify-end gap-2">
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" tone="primary">Record</Button>
        </div>
      </form>
    </Modal>
  )
}

/** Adding one, or changing what it is called and when to be told it is running out. */
function MaterialDialog({ draft, onClose, onDone, onToast }: {
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
    <Modal title={form.id ? `Edit ${draft.name}` : 'Add a raw material'} size="xl" onClose={onClose}>
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
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" tone="primary">Save</Button>
        </div>
      </form>
    </Modal>
  )
}
