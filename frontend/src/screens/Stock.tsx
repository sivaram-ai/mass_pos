import { useCallback, useEffect, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import { api, fieldErrorsOf, messageOfFailure, milliFromUnits, quantity, rupees } from '../api'
import type { StockRow } from '../types'
import {
  Banner, Button, Empty, errorFor, Field, inputClass, KeyBox, Modal, Panel, smallInputClass,
} from '../components/ui'
import { useScreenKeys } from '../shortcuts'
import type { Toast } from '../App'

const SEARCH_KEY = 'F2'

type Action = 'receipts' | 'adjustments' | 'damages'

const ACTIONS: Action[] = ['receipts', 'adjustments', 'damages']

const ACTION_LABELS: Record<Action, string> = {
  receipts: 'Received',
  adjustments: 'Correct',
  damages: 'Damage',
}

type Movement = { kind: Action; row: StockRow }

/**
 * What is on the shelf, worked from the keyboard like the catalogue: the cursor starts in the list,
 * up and down choose an item, left and right choose what to do with it, Enter does it.
 */
export default function Stock({ onToast }: { onToast: Toast }) {
  const [rows, setRows] = useState<StockRow[]>([])
  const [filter, setFilter] = useState('')
  const [selected, setSelected] = useState(0)
  const [action, setAction] = useState<Action>('receipts')
  const [listFocused, setListFocused] = useState(false)
  const [movement, setMovement] = useState<Movement | null>(null)
  const list = useRef<HTMLDivElement>(null)
  const searchBox = useRef<HTMLInputElement>(null)
  const lines = useRef(new Map<string, HTMLTableRowElement>())

  const load = useCallback(() => {
    api.get<StockRow[]>('/api/stock')
      .then(setRows)
      .catch((failure) => onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not load the stock') }))
  }, [onToast])

  useEffect(load, [load])

  // The cursor starts in the list, so the arrows work the moment the screen opens.
  useEffect(() => {
    list.current?.focus()
  }, [])

  const shown = rows.filter((row) =>
    `${row.name} ${row.sku}`.toLowerCase().includes(filter.trim().toLowerCase()))
  const totalValue = rows.reduce((sum, row) => sum + row.stockValuePaise, 0)
  const outOfStock = rows.filter((row) => row.stockMilli <= 0).length

  const at = Math.min(selected, Math.max(shown.length - 1, 0))
  const current = shown[at]

  useEffect(() => {
    if (current) {
      lines.current.get(current.productId)?.scrollIntoView({ block: 'nearest' })
    }
  }, [current])

  useScreenKeys({
    [SEARCH_KEY]: () => {
      searchBox.current?.focus()
      searchBox.current?.select()
    },
  }, movement === null)

  const choose = (index: number) => {
    setSelected(index)
    setAction('receipts')
  }

  const closeDialog = (reload = false) => {
    setMovement(null)
    if (reload) {
      load()
    }
    // Back to the list, so the arrows carry on where they were.
    requestAnimationFrame(() => list.current?.focus())
  }

  const onListKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (movement || !current) {
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
        setMovement({ kind: action, row: current })
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
      title="Stock on hand"
      actions={
        <div className="flex items-center gap-2">
          <span className="whitespace-nowrap text-xs text-slate-500">
            {rows.length} items · <span className="num">{rupees(totalValue)}</span>
            {outOfStock > 0 && <span className="ml-2 text-rose-600">{outOfStock} out of stock</span>}
          </span>
          <KeyBox hint={SEARCH_KEY}>
            <input
              ref={searchBox}
              className={`${smallInputClass} w-64 pr-8`}
              placeholder="Name or item code"
              aria-label="Filter stock"
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
        <span><kbd>↑</kbd> <kbd>↓</kbd> choose an item</span>
        <span><kbd>←</kbd> <kbd>→</kbd> Received, Correct or Damage</span>
        <span><kbd>Enter</kbd> do it</span>
        <span><kbd>{SEARCH_KEY}</kbd> search</span>
        <span><kbd>F9</kbd> menu · <kbd>Esc</kbd> <kbd>Esc</kbd> billing</span>
      </p>

      <div
        ref={list}
        tabIndex={0}
        role="grid"
        aria-label="Stock"
        onKeyDown={onListKeyDown}
        onFocus={() => setListFocused(true)}
        onBlur={() => setListFocused(false)}
        className="overflow-x-auto rounded-md outline-none focus-visible:ring-2 focus-visible:ring-sky-200"
      >
        {shown.length === 0 ? (
          <Empty>
            {filter
              ? `Nothing matches "${filter}"`
              : 'Nothing to show. Add products first, then record what arrived.'}
          </Empty>
        ) : (
          <table className="w-full min-w-[52rem] table-fixed text-sm">
            <colgroup>
              <col />
              <col className="w-32" />
              <col className="w-24" />
              <col className="w-28" />
              <col className="w-60" />
            </colgroup>
            <thead className="text-xs uppercase tracking-wide text-slate-400">
              <tr>
                <th className="py-1 pl-2 text-left">Item</th>
                <th className="py-1 text-right">On hand</th>
                <th className="py-1 text-right">Price</th>
                <th className="py-1 text-right">Value</th>
                <th className="py-1"></th>
              </tr>
            </thead>
            <tbody>
              {shown.map((row, index) => {
                const isSelected = index === at
                return (
                  <tr
                    key={row.productId}
                    ref={(element) => {
                      if (element) {
                        lines.current.set(row.productId, element)
                      } else {
                        lines.current.delete(row.productId)
                      }
                    }}
                    onMouseDown={() => choose(index)}
                    onClick={() => setMovement({ kind: 'receipts', row })}
                    aria-selected={isSelected}
                    className={`cursor-pointer border-t border-slate-100 ${
                      isSelected ? 'bg-sky-50 shadow-[inset_3px_0_0_0_var(--color-sky-500)]' : 'hover:bg-slate-50'}`}
                  >
                    <td className="py-2 pl-2 pr-3">
                      {/* Long names are cut with the full name on hover, so the columns never move. */}
                      <div className="truncate font-medium text-slate-800" title={row.name}>{row.name}</div>
                      <div className="num truncate text-xs text-slate-400">{row.sku}</div>
                    </td>
                    <td className={`num py-2 text-right ${row.stockMilli <= 0 ? 'text-rose-600' : ''}`}>
                      {quantity(row.stockMilli)} <span className="text-xs text-slate-400">{row.unit}</span>
                    </td>
                    <td className="num py-2 text-right text-slate-500">{rupees(row.sellingPricePaise)}</td>
                    <td className="num py-2 text-right">{rupees(row.stockValuePaise)}</td>
                    <td className="whitespace-nowrap py-1.5 pr-2 text-right">
                      <div className="inline-flex gap-1">
                        {ACTIONS.map((name) => {
                          const focused = isSelected && action === name
                          return (
                            <button
                              key={name}
                              type="button"
                              tabIndex={-1}
                              onMouseDown={(event) => event.stopPropagation()}
                              onClick={(event) => {
                                event.stopPropagation()
                                choose(index)
                                setAction(name)
                                setMovement({ kind: name, row })
                              }}
                              className={`rounded-md px-2.5 py-1 text-xs font-medium transition ${
                                focused
                                  ? `bg-sky-600 text-white ${listFocused ? 'ring-2 ring-sky-300' : ''}`
                                  : name === 'damages'
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

      {movement && (
        <MovementDialog
          movement={movement}
          onClose={() => closeDialog()}
          onDone={() => closeDialog(true)}
          onToast={onToast}
        />
      )}
    </Panel>
  )
}

const TITLES: Record<Action, string> = {
  receipts: 'Goods received',
  adjustments: 'Correct the count',
  damages: 'Damaged or expired',
}

const HINTS: Record<Action, string> = {
  receipts: 'What arrived from the supplier',
  adjustments: 'The difference, not the new count. Use -2 if two are missing.',
  damages: 'How many are being written off',
}

function MovementDialog({ movement, onClose, onDone, onToast }: {
  movement: Movement
  onClose: () => void
  onDone: () => void
  onToast: Toast
}) {
  const [amount, setAmount] = useState('')
  const [note, setNote] = useState('')
  const [errors, setErrors] = useState<{ message: string; fields: Record<string, string> } | null>(null)

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    const signed = amount.trim().startsWith('-')
    const milli = milliFromUnits(amount) * (signed ? -1 : 1)
    if (milli === 0) {
      setErrors({ message: 'Enter how many, as a number.', fields: {} })
      return
    }
    try {
      const body = movement.kind === 'adjustments'
        ? { productId: movement.row.productId, deltaMilli: milli, note }
        : { productId: movement.row.productId, quantityMilli: Math.abs(milli), note }
      await api.post(`/api/stock/${movement.kind}`, body)
      onToast({ tone: 'good', text: `Stock updated for ${movement.row.name}` })
      onDone()
    } catch (failure) {
      // Shown inside the dialog, which stays open with what was typed.
      setErrors({ message: messageOfFailure(failure, 'Could not record that'), fields: fieldErrorsOf(failure) })
    }
  }

  return (
    <Modal title={TITLES[movement.kind]} onClose={onClose}>
      <form onSubmit={submit} className="space-y-3">
        {errors && (
          <Banner tone="bad" onDismiss={() => setErrors(null)}>{errors.message}</Banner>
        )}
        <p className="text-sm text-slate-500">
          {movement.row.name} · now {quantity(movement.row.stockMilli)} {movement.row.unit}
        </p>
        <Field label="Quantity" hint={HINTS[movement.kind]}
               error={errorFor(errors?.fields ?? {}, movement.kind === 'adjustments' ? 'deltaMilli' : 'quantityMilli')}>
          <input className={`${inputClass} num text-lg`} inputMode="decimal" autoFocus value={amount}
                 onChange={(event) => setAmount(event.target.value)} />
        </Field>
        <Field label="Note" hint="Supplier, invoice number, reason" error={errorFor(errors?.fields ?? {}, 'note')}>
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
