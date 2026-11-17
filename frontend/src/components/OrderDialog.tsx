import { useEffect, useMemo, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import {
  api, fieldErrorsOf, messageOfFailure, milliFromUnits, paiseFromRupees, rupees,
} from '../api'
import type { Product, PurchaseOrder, RawMaterial, Unit, Vendor } from '../types'
import { Banner, Button, errorFor, Field, inputClass, Modal } from './ui'
import type { Toast } from '../App'

/** How many matches the box offers at once. The same cap the billing grid uses. */
const MOST = 12

/**
 * Anything that can go on an order, from either list, in one shape - because somebody writing an
 * order is thinking about what the shop needs, not about which of the shop's two lists it is on.
 */
export type Orderable = {
  kind: 'p' | 'r'
  id: string
  code: string
  name: string
  unit: Unit
  /** What it cost last time, where anything is known: the figure to start the line at. */
  lastCostPaise?: number
}

/** An item the screen that opened this had already chosen, so the line starts filled in. */
export type OrderStart = Orderable

type Line = { key: number; item: Orderable | null; typed: string; quantity: string; cost: string }

function blank(key: number): Line {
  return { key, item: null, typed: '', quantity: '', cost: '' }
}

function lineFor(key: number, item: Orderable): Line {
  return {
    key,
    item,
    typed: '',
    quantity: '',
    cost: item.lastCostPaise ? (item.lastCostPaise / 100).toFixed(2) : '',
  }
}

/**
 * Ranked the way the billing grid ranks items, because it is the same hand doing the same thing:
 * the exact code first, then codes that start with what was typed, then names that start with it,
 * then anything that contains it. A list sorted any other way makes somebody read it.
 */
function matching(all: Orderable[], typed: string): Orderable[] {
  const looking = typed.trim().toLowerCase()
  if (!looking) {
    return all.slice(0, MOST)
  }
  const scored: { item: Orderable; score: number }[] = []
  for (const item of all) {
    const code = item.code.toLowerCase()
    const name = item.name.toLowerCase()
    const score = code === looking ? 0
      : code.startsWith(looking) ? 1
        : name.startsWith(looking) ? 2
          : code.includes(looking) ? 3
            : name.includes(looking) ? 4
              : -1
    if (score >= 0) {
      scored.push({ item, score })
    }
  }
  scored.sort((a, b) => a.score - b.score || a.item.code.localeCompare(b.item.code))
  return scored.slice(0, MOST).map((one) => one.item)
}

/**
 * Writing a purchase order: who it is going to, and what the shop wants.
 *
 * <p>Opened from the orders screen with nothing on it, or from the Items and non-billable lists
 * with whatever was ticked there already on its lines - which is the point of ticking several
 * things before pressing Purchase. One order can hold things off the shelf and things out of the
 * store together, because that is how a lorry arrives.
 *
 * <p>The item box searches like the billing grid: type a code or part of a name and the matches
 * appear as you type, from both lists, with the arrows and Enter choosing. A dropdown's Esc closes
 * the dropdown and nothing else, which is why it stops the key going any further.
 */
export function OrderDialog({ start = [], onClose, onDone, onToast }: {
  start?: OrderStart[]
  onClose: () => void
  onDone: () => void
  onToast: Toast
}) {
  const [vendors, setVendors] = useState<Vendor[]>([])
  const [catalogue, setCatalogue] = useState<Orderable[]>([])
  const [vendorId, setVendorId] = useState('')
  const [expectedOn, setExpectedOn] = useState('')
  const [note, setNote] = useState('')
  const nextKey = useRef(start.length + 2)
  const [lines, setLines] = useState<Line[]>(
    start.length > 0 ? start.map((item, index) => lineFor(index + 1, item)) : [blank(1)])
  const [errors, setErrors] = useState<{ message: string; fields: Record<string, string> } | null>(null)
  const [busy, setBusy] = useState(false)
  const quantities = useRef(new Map<number, HTMLInputElement>())

  useEffect(() => {
    api.get<Vendor[]>('/api/vendors')
      .then((all) => setVendors(all.filter((one) => one.active)))
      .catch(() => {})
    Promise.all([
      api.get<Product[]>('/api/products?all=true').catch(() => [] as Product[]),
      api.get<RawMaterial[]>('/api/raw-materials').catch(() => [] as RawMaterial[]),
    ]).then(([products, materials]) => {
      setCatalogue([
        ...products.filter((one) => one.active).map<Orderable>((one) => ({
          kind: 'p', id: one.id, code: one.sku, name: one.name, unit: one.unit,
        })),
        ...materials.filter((one) => one.active).map<Orderable>((one) => ({
          kind: 'r', id: one.id, code: one.code, name: one.name, unit: one.unit,
        })),
      ])
    })
  }, [])

  const setLine = (key: number, change: Partial<Line>) =>
    setLines((now) => now.map((line) => (line.key === key ? { ...line, ...change } : line)))

  /** Choosing an item on the last line opens another, so a long order is one hand on the keys. */
  const pick = (key: number, item: Orderable) => {
    setLines((now) => {
      const next = now.map((line) => (line.key === key ? { ...line, item, typed: '' } : line))
      return next[next.length - 1].key === key ? [...next, blank(nextKey.current++)] : next
    })
    requestAnimationFrame(() => quantities.current.get(key)?.focus())
  }

  const total = lines.reduce(
    (sum, line) => sum + Math.round(milliFromUnits(line.quantity) * paiseFromRupees(line.cost) / 1000), 0)

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    const wanted = lines
      .filter((line) => line.item && milliFromUnits(line.quantity) > 0)
      .map((line) => ({
        productId: line.item!.kind === 'p' ? line.item!.id : null,
        rawMaterialId: line.item!.kind === 'r' ? line.item!.id : null,
        quantityMilli: milliFromUnits(line.quantity),
        unitCostPaise: paiseFromRupees(line.cost),
      }))
    if (wanted.length === 0) {
      setErrors({ message: 'Put at least one thing on the order, with how much of it.', fields: {} })
      return
    }
    setBusy(true)
    try {
      const made = await api.post<PurchaseOrder>('/api/purchase-orders', {
        vendorId,
        expectedOn: expectedOn || null,
        note: note.trim(),
        lines: wanted,
      })
      onToast({ tone: 'good', text: `${made.number} written. It waits for an administrator.` })
      onDone()
    } catch (failure) {
      setErrors({ message: messageOfFailure(failure, 'Could not write that order'), fields: fieldErrorsOf(failure) })
    } finally {
      setBusy(false)
    }
  }

  const onThe = lines.filter((line) => line.item).length

  return (
    <Modal title="Write a purchase order" size="5xl" onClose={onClose}>
      <form onSubmit={submit} className="space-y-3">
        {errors && <Banner tone="bad" onDismiss={() => setErrors(null)}>{errors.message}</Banner>}

        <div className="grid grid-cols-3 gap-3">
          <Field label="Vendor" error={errorFor(errors?.fields ?? {}, 'vendorId')}
                 hint={vendors.length === 0 ? 'Add one on the Vendors screen first' : undefined}>
            <select className={inputClass} autoFocus value={vendorId}
                    onChange={(event) => setVendorId(event.target.value)}>
              <option value="">Choose one</option>
              {vendors.map((vendor) => <option key={vendor.id} value={vendor.id}>{vendor.name}</option>)}
            </select>
          </Field>
          <Field label="Expected" optional error={errorFor(errors?.fields ?? {}, 'expectedOn')}>
            <input className={inputClass} type="date" value={expectedOn}
                   onChange={(event) => setExpectedOn(event.target.value)} />
          </Field>
          <Field label="Note" optional error={errorFor(errors?.fields ?? {}, 'note')}>
            <input className={inputClass} value={note} onChange={(event) => setNote(event.target.value)} />
          </Field>
        </div>

        <div className="space-y-2">
          <div className="grid grid-cols-[1fr_7rem_7rem_2rem] gap-2 text-xs uppercase tracking-wide text-slate-400">
            <span>Item</span>
            <span className="text-right">How much</span>
            <span className="text-right">Each</span>
            <span />
          </div>
          {lines.map((line) => (
            <div key={line.key} className="grid grid-cols-[1fr_7rem_7rem_2rem] items-center gap-2">
              <ItemBox
                line={line}
                catalogue={catalogue}
                onTyped={(typed) => setLine(line.key, { typed })}
                onPick={(item) => pick(line.key, item)}
                onClear={() => setLine(line.key, { item: null, typed: '' })}
              />
              <div className="relative">
                <input
                  ref={(element) => {
                    if (element) {
                      quantities.current.set(line.key, element)
                    } else {
                      quantities.current.delete(line.key)
                    }
                  }}
                  className={`${inputClass} num text-right`}
                  inputMode="decimal"
                  aria-label={`How much of ${line.item?.name ?? 'the item'}`}
                  value={line.quantity}
                  onChange={(event) => setLine(line.key, { quantity: event.target.value })}
                />
                {line.item && (
                  <span className="pointer-events-none absolute right-2 top-1/2 -translate-y-1/2 text-xs text-slate-400">
                    {line.item.unit}
                  </span>
                )}
              </div>
              <input
                className={`${inputClass} num text-right`}
                inputMode="decimal"
                placeholder="0.00"
                aria-label={`What one of ${line.item?.name ?? 'the item'} costs`}
                value={line.cost}
                onChange={(event) => setLine(line.key, { cost: event.target.value })}
              />
              <button
                type="button"
                aria-label="Take this line off"
                className="rounded-md px-2 py-2 text-slate-400 hover:bg-slate-100 hover:text-rose-600"
                onClick={() => setLines((now) =>
                  now.length > 1 ? now.filter((other) => other.key !== line.key) : [blank(nextKey.current++)])}
              >
                ✕
              </button>
            </div>
          ))}
          <Button onClick={() => setLines((now) => [...now, blank(nextKey.current++)])}>Another line</Button>
        </div>

        <div className="flex flex-wrap items-center justify-end gap-3 border-t border-slate-100 pt-3">
          <span className="mr-auto text-xs text-slate-400">
            Type a code or part of a name. Both the items you sell and the non-billable ones are in
            the list.
          </span>
          <span className="text-sm text-slate-500">
            {onThe} {onThe === 1 ? 'thing' : 'things'} · comes to{' '}
            <span className="num font-semibold text-slate-800">{rupees(total)}</span>
          </span>
          <Button onClick={onClose} hint="Esc">Cancel</Button>
          <Button type="submit" tone="primary" disabled={busy}>Write it</Button>
        </div>
      </form>
    </Modal>
  )
}

/** One line's item box: a search that answers while it is typed, worked from the keyboard. */
function ItemBox({ line, catalogue, onTyped, onPick, onClear }: {
  line: Line
  catalogue: Orderable[]
  onTyped: (typed: string) => void
  onPick: (item: Orderable) => void
  onClear: () => void
}) {
  const [open, setOpen] = useState(false)
  const [at, setAt] = useState(0)
  const box = useRef<HTMLInputElement>(null)

  const found = useMemo(() => matching(catalogue, line.typed), [catalogue, line.typed])
  const here = Math.min(at, Math.max(found.length - 1, 0))

  const onKeyDown = (event: ReactKeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      setOpen(true)
      setAt(Math.max(0, Math.min(found.length - 1, here + (event.key === 'ArrowDown' ? 1 : -1))))
    } else if (event.key === 'Enter') {
      // Enter in this box chooses from the list; it must not submit the order half written.
      event.preventDefault()
      if (open && found[here]) {
        onPick(found[here])
        setOpen(false)
        setAt(0)
      }
    } else if (event.key === 'Tab' && open && found[here] && line.typed.trim()) {
      // Tab takes the highlighted one and carries on to the quantity, which is where the hand
      // was going anyway.
      onPick(found[here])
      setOpen(false)
      setAt(0)
    } else if (event.key === 'Escape') {
      if (open) {
        // Closes the list and nothing else: the dialog behind it stays, and so does the screen.
        event.preventDefault()
        event.stopPropagation()
        event.nativeEvent.stopImmediatePropagation()
        setOpen(false)
      }
    } else if (event.key === 'Backspace' && line.item && !line.typed) {
      event.preventDefault()
      onClear()
      setOpen(true)
    }
  }

  if (line.item) {
    return (
      <button
        type="button"
        onClick={() => {
          onClear()
          requestAnimationFrame(() => box.current?.focus())
        }}
        title="Choose something else"
        className={`${inputClass} flex items-center gap-2 text-left`}
      >
        <span className="num text-xs text-slate-400">{line.item.code}</span>
        <span className="truncate">{line.item.name}</span>
        <span className={`ml-auto shrink-0 rounded px-1.5 py-0.5 text-xs ${
          line.item.kind === 'r' ? 'bg-amber-100 text-amber-800' : 'bg-slate-100 text-slate-500'}`}>
          {line.item.kind === 'r' ? 'non billable' : 'sold'}
        </span>
      </button>
    )
  }

  return (
    <div className="relative">
      <input
        ref={box}
        className={inputClass}
        placeholder="Code or name"
        aria-label="Search for something to order"
        autoComplete="off"
        value={line.typed}
        onChange={(event) => {
          onTyped(event.target.value)
          setOpen(true)
          setAt(0)
        }}
        onFocus={() => setOpen(true)}
        // A click outside closes it, but not before the click on a row has been taken.
        onBlur={() => setTimeout(() => setOpen(false), 120)}
        onKeyDown={onKeyDown}
      />
      {open && found.length > 0 && (
        <ul className="absolute left-0 right-0 top-full z-20 mt-1 max-h-64 overflow-auto rounded-md border border-slate-200 bg-surface shadow-lg"
            role="listbox">
          {found.map((item, index) => (
            <li key={item.kind + item.id}>
              <button
                type="button"
                role="option"
                aria-selected={index === here}
                onMouseDown={(event) => event.preventDefault()}
                onClick={() => {
                  onPick(item)
                  setOpen(false)
                }}
                className={`flex w-full items-center gap-2 px-2 py-1.5 text-left text-sm ${
                  index === here ? 'bg-sky-50' : 'hover:bg-slate-50'}`}
              >
                <span className="num w-20 shrink-0 truncate text-xs text-slate-400">{item.code}</span>
                <span className="truncate">{item.name}</span>
                <span className="ml-auto shrink-0 text-xs text-slate-400">
                  {item.kind === 'r' ? 'non billable' : item.unit}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
      {open && line.typed.trim() && found.length === 0 && (
        <p className="absolute left-0 right-0 top-full z-20 mt-1 rounded-md border border-slate-200 bg-surface px-2 py-1.5 text-xs text-slate-500 shadow-lg">
          Nothing matches "{line.typed.trim()}". Add it on the Items screen first.
        </p>
      )}
    </div>
  )
}

