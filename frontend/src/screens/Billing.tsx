import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import {
  api, billDate, clockTime, messageOfFailure, milliFromUnits, paiseFromRupees, percent, quantity, rupees,
  rupeesForInput,
} from '../api'
import type {
  CartLine, Customer, HeldBill, PaymentMode, Product, QuotedCart, SaleResponse, SettingsView, Tender,
} from '../types'
import { WHOLE_UNITS } from '../types'
import { AskModal, Banner, Button, Empty, Field, inputClass, Modal } from '../components/ui'
import { actionFor, loadShortcuts, SHORTCUT_LABELS, useShortcuts } from '../shortcuts'
import type { ShortcutAction } from '../shortcuts'
import type { Toast } from '../App'

const ONE_UNIT = 1000
const NO_CUSTOMER: Customer = { name: '', gstin: '', placeOfSupply: '' }

/** The editable columns of a bill row, left to right. Amount is worked out by the server. */
type Col = 'code' | 'name' | 'rate' | 'qty' | 'disc'
const COLS: Col[] = ['code', 'name', 'rate', 'qty', 'disc']
/** Typing in these looks an item up; the rest take numbers. */
const SEARCH_COLS: Col[] = ['code', 'name']

type Row = CartLine & { key: number }
type Cell = { row: number; col: Col }
/** The cell the cursor is in. `dirty` once something has been typed that is not applied yet. */
type Edit = Cell & { text: string; dirty: boolean }
type Found = Cell & { text: string; items: Product[] }

type Ask = {
  action: 'hold' | 'drawer'
  title: string
  label: string
  initial: string
}

const cellId = (row: number, col: Col) => `${row}:${col}`
const isSearchCol = (col: Col) => SEARCH_COLS.includes(col)

function cartBody(lines: CartLine[]) {
  return lines.map((line) => ({
    productId: line.productId,
    quantityMilli: line.quantityMilli,
    discountPaise: line.discountPaise,
    unitPricePaise: line.unitPricePaise,
  }))
}

/** A held bill stores the lines as the server knows them, without the screen's row keys. */
function withoutKey(row: Row): CartLine {
  return {
    productId: row.productId,
    sku: row.sku,
    name: row.name,
    unit: row.unit,
    quantityMilli: row.quantityMilli,
    discountPaise: row.discountPaise,
    unitPricePaise: row.unitPricePaise,
  }
}

function useNow(): Date {
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), 1000)
    return () => clearInterval(timer)
  }, [])
  return now
}

/**
 * The counter screen. The bill is a grid: every row's code and name cells look an item up as you
 * type, and its rate, quantity and discount cells take numbers directly, so nothing pops up while
 * billing. Arrow keys move between cells, Enter moves on, Space takes the bill.
 */
export default function Billing({ settings, onToast }: { settings: SettingsView | null; onToast: Toast }) {
  const [lines, setLines] = useState<Row[]>([])
  const [customer, setCustomer] = useState<Customer>(NO_CUSTOMER)
  const [quote, setQuote] = useState<QuotedCart | null>(null)
  const [edit, setEdit] = useState<Edit>({ row: 0, col: 'code', text: '', dirty: false })
  const [found, setFound] = useState<Found | null>(null)
  const [dropdownOpen, setDropdownOpen] = useState(false)
  const [highlight, setHighlight] = useState(0)
  const [invalid, setInvalid] = useState<Cell | null>(null)
  const [focusRequest, setFocusRequest] = useState<(Cell & { at: number }) | null>(null)
  const [ask, setAsk] = useState<Ask | null>(null)
  const [payment, setPayment] = useState<{ due: number; cart: Row[] } | null>(null)
  const [holds, setHolds] = useState<HeldBill[] | null>(null)
  const [editingCustomer, setEditingCustomer] = useState(false)
  const [confirmClear, setConfirmClear] = useState(false)
  const [lastSale, setLastSale] = useState<SaleResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const shortcuts = useMemo(loadShortcuts, [])
  const now = useNow()
  const cells = useRef(new Map<string, HTMLInputElement>())
  const listItems = useRef(new Map<number, HTMLTableRowElement>())
  const nextKey = useRef(1)
  // Handlers read these rather than render-time state: a key press can change the bill and move on
  // before React has rendered the change.
  const linesRef = useRef(lines)
  linesRef.current = lines
  const editRef = useRef(edit)
  editRef.current = edit
  /** Set while a bill is being taken, so a second Space press can never take it twice. */
  const busy = useRef(false)

  const gst = settings?.gstRegistered ?? true
  const notReady = Boolean(settings && !settings.readyToInvoice)
  const modalOpen = ask !== null || payment !== null || holds !== null || editingCustomer || confirmClear
  const blankRow = lines.length

  const inSearchCell = isSearchCol(edit.col)
  const typed = edit.dirty ? edit.text.trim() : ''
  const listFresh = found !== null && found.row === edit.row && found.col === edit.col && found.text === typed
  const listOpen = dropdownOpen && inSearchCell && listFresh

  // ---- Server pricing: the screen never computes tax itself -------------------------------------
  useEffect(() => {
    if (lines.length === 0) {
      setQuote(null)
      return
    }
    let current = true
    api.post<QuotedCart>('/api/sales/quote', { lines: cartBody(lines), placeOfSupply: customer.placeOfSupply })
      .then((priced) => current && setQuote(priced))
      .catch((failure) => current && setError(messageOfFailure(failure, 'Could not price the bill')))
    return () => {
      current = false
    }
  }, [lines, customer.placeOfSupply])

  // ---- Item lookup while typing in a code or name cell ------------------------------------------
  useEffect(() => {
    if (!inSearchCell || !edit.dirty || typed === '') {
      return
    }
    const { row, col } = edit
    const timer = setTimeout(() => {
      api.get<Product[]>(`/api/products?q=${encodeURIComponent(typed)}`)
        .then((items) => {
          const latest = editRef.current
          // The cashier may have picked something or moved on while this was on its way.
          if (latest.row !== row || latest.col !== col || !latest.dirty || latest.text.trim() !== typed) {
            return
          }
          setFound({ row, col, text: typed, items })
          setHighlight(0)
          setDropdownOpen(true)
        })
        .catch(() => setFound({ row, col, text: typed, items: [] }))
    }, 120)
    return () => clearTimeout(timer)
  }, [inSearchCell, edit.dirty, typed, edit.row, edit.col])

  // ---- Focus ---------------------------------------------------------------------------------------
  const requestFocus = useCallback((row: number, col: Col) => {
    setEdit({ row, col, text: '', dirty: false })
    setDropdownOpen(false)
    setFocusRequest({ row, col, at: performance.now() })
  }, [])

  useLayoutEffect(() => {
    if (!focusRequest) {
      return
    }
    const input = cells.current.get(cellId(focusRequest.row, focusRequest.col))
    input?.focus()
    input?.select()
  }, [focusRequest])

  useEffect(() => {
    requestFocus(0, 'code')
  }, [requestFocus])

  // Keep the highlighted item of the lookup list in view.
  useEffect(() => {
    listItems.current.get(highlight)?.scrollIntoView({ block: 'nearest' })
  }, [highlight, listOpen])

  const refuse = (row: number, col: Col, message: string) => {
    setInvalid({ row, col })
    setError(message)
    const input = cells.current.get(cellId(row, col))
    if (document.activeElement !== input) {
      requestFocus(row, col)
    }
  }

  const replaceLines = (updated: Row[]) => {
    linesRef.current = updated
    setLines(updated)
  }

  /**
   * Applies what was typed in the current cell. Returns the bill as it now stands, or null when the
   * value was refused and the cursor must stay. Unpicked lookup text is simply dropped.
   */
  const commitEdit = (): Row[] | null => {
    const current = linesRef.current
    const pending = editRef.current
    if (!pending.dirty) {
      return current
    }
    const { row, col, text } = pending
    const line = current[row]
    const settle = () => {
      const clean = { row, col, text: '', dirty: false }
      editRef.current = clean
      setEdit(clean)
    }
    if (!line || isSearchCol(col)) {
      settle()
      setDropdownOpen(false)
      return current
    }
    const value = text.trim()
    let change: Partial<CartLine>
    if (col === 'rate') {
      const paise = value === '' ? line.unitPricePaise : paiseFromRupees(value)
      if (paise < 0) {
        refuse(row, col, 'The rate cannot be negative')
        return null
      }
      change = { unitPricePaise: paise }
    } else if (col === 'qty') {
      const milli = value === '' ? line.quantityMilli : milliFromUnits(value)
      if (milli <= 0) {
        refuse(row, col, `Enter a quantity for ${line.name}`)
        return null
      }
      if (WHOLE_UNITS.includes(line.unit) && milli % ONE_UNIT !== 0) {
        refuse(row, col, `${line.name} is sold in whole ${line.unit}, not ${value}`)
        return null
      }
      change = { quantityMilli: milli }
    } else {
      const paise = value === '' ? 0 : paiseFromRupees(value)
      if (paise < 0) {
        refuse(row, col, 'The discount cannot be negative')
        return null
      }
      change = { discountPaise: paise }
    }
    const updated = current.map((existing, at) => (at === row ? { ...existing, ...change } : existing))
    replaceLines(updated)
    settle()
    setInvalid(null)
    return updated
  }

  /** Moves the cursor, applying the cell being left. Quantity waits until the item has a rate. */
  const move = (row: number, col: Col) => {
    const committed = commitEdit()
    if (!committed) {
      return
    }
    const target = Math.max(0, Math.min(row, committed.length))
    let targetCol = col
    if (target === committed.length && !isSearchCol(targetCol)) {
      targetCol = 'code'
    }
    const line = committed[target]
    if (line && (targetCol === 'qty' || targetCol === 'disc') && line.unitPricePaise <= 0) {
      refuse(target, 'rate', `Enter the rate for ${line.name} first`)
      requestFocus(target, 'rate')
      return
    }
    requestFocus(target, targetCol)
  }

  /** Puts an item on a row: a new row at the bottom, or a replacement for the item already there. */
  const pick = (row: number, product: Product) => {
    const current = linesRef.current
    let at = row
    let updated: Row[]
    if (row >= current.length) {
      updated = [...current, {
        key: nextKey.current++,
        productId: product.id,
        sku: product.sku,
        name: product.name,
        unit: product.unit,
        quantityMilli: ONE_UNIT,
        discountPaise: 0,
        unitPricePaise: product.sellingPricePaise,
      }]
      at = updated.length - 1
    } else {
      updated = current.map((line, index) => (index === row ? {
        ...line,
        productId: product.id,
        sku: product.sku,
        name: product.name,
        unit: product.unit,
        discountPaise: 0,
        unitPricePaise: product.sellingPricePaise,
      } : line))
    }
    replaceLines(updated)
    setError(null)
    setInvalid(null)
    // No price in the catalogue: the rate is asked for, in its own cell, before the quantity.
    requestFocus(at, product.sellingPricePaise > 0 ? 'qty' : 'rate')
  }

  const lookUp = async (text: string): Promise<Product[] | null> => {
    try {
      return await api.get<Product[]>(`/api/products?q=${encodeURIComponent(text)}`)
    } catch (failure) {
      setError(messageOfFailure(failure, 'Could not look the item up'))
      return null
    }
  }

  /** Enter in a code or name cell: the highlighted item, or for a code or barcode its exact match. */
  const choose = async (row: number, col: Col) => {
    if (listOpen && found && found.items.length > 0) {
      pick(row, found.items[Math.min(highlight, found.items.length - 1)])
      return
    }
    const text = editRef.current.dirty ? editRef.current.text.trim() : ''
    if (text === '') {
      const line = linesRef.current[row]
      if (line) {
        move(row, line.unitPricePaise > 0 ? 'qty' : 'rate')
      }
      return
    }
    // A scanner types faster than the lookup list refreshes, so ask directly.
    const items = await lookUp(text)
    if (!items) {
      return
    }
    const exact = items.find((item) => item.sku.toLowerCase() === text.toLowerCase() || item.barcode === text)
    const chosen = exact ?? items[0]
    if (!chosen) {
      refuse(row, col, `No item matches "${text}"`)
      return
    }
    pick(row, chosen)
  }

  /** Arrow down in an empty lookup cell lists the catalogue to scroll through. */
  const browse = async (row: number, col: Col) => {
    const items = await lookUp('')
    if (items) {
      setFound({ row, col, text: '', items })
      setHighlight(0)
      setDropdownOpen(true)
    }
  }

  const removeRow = (row: number) => {
    const current = linesRef.current
    if (!current[row]) {
      return
    }
    const updated = current.filter((_, at) => at !== row)
    replaceLines(updated)
    setInvalid(null)
    setError(null)
    requestFocus(Math.min(row, updated.length), 'code')
  }

  const clearBill = () => {
    replaceLines([])
    setCustomer(NO_CUSTOMER)
    setQuote(null)
    setInvalid(null)
    setConfirmClear(false)
    requestFocus(0, 'code')
  }

  /** The checks every way of taking a bill shares. */
  const readyToBill = (cart: Row[]): boolean => {
    if (cart.length === 0) {
      setError('Add an item before taking the bill')
      requestFocus(0, 'code')
      return false
    }
    if (notReady) {
      setError(`Bills cannot be taken until the shop's ${settings?.missingForInvoicing.join(', ')} are filled in on the Settings screen`)
      return false
    }
    const unpriced = cart.findIndex((line) => line.unitPricePaise <= 0)
    if (unpriced >= 0) {
      refuse(unpriced, 'rate', `Enter the rate for ${cart[unpriced].name} before taking the bill`)
      requestFocus(unpriced, 'rate')
      return false
    }
    return true
  }

  const priceOf = (cart: Row[]) =>
    api.post<QuotedCart>('/api/sales/quote', { lines: cartBody(cart), placeOfSupply: customer.placeOfSupply })

  const sell = async (cart: Row[], tenders: Tender[]) => {
    const sale = await api.post<SaleResponse>('/api/sales', {
      lines: cartBody(cart),
      payments: tenders,
      buyerGstin: gst ? customer.gstin || null : null,
      buyerName: customer.name || null,
      placeOfSupply: gst ? customer.placeOfSupply || null : null,
      print: true,
      openDrawer: tenders.some((tender) => tender.mode === 'CASH'),
    })
    setLastSale(sale)
    clearBill()
    setError(null)
    onToast(sale.printed
      ? { tone: 'good', text: `Bill ${sale.invoice.invoiceNumber} taken and printed` }
      : { tone: 'warn', text: `Bill ${sale.invoice.invoiceNumber} taken, but printing failed: ${sale.printError ?? ''}` })
  }

  /** Space: the whole bill in cash, printed straight away. */
  const takeBill = async () => {
    if (busy.current || modalOpen) {
      return
    }
    const cart = commitEdit()
    if (!cart || !readyToBill(cart)) {
      return
    }
    busy.current = true
    setSaving(true)
    try {
      const priced = await priceOf(cart)
      await sell(cart, [{ mode: 'CASH', amountPaise: priced.grandTotalPaise, tenderedPaise: priced.grandTotalPaise }])
    } catch (failure) {
      setError(messageOfFailure(failure, 'Could not take the bill'))
    } finally {
      busy.current = false
      setSaving(false)
    }
  }

  /** F9: UPI, card, change for a note, or a split. */
  const openPayment = async () => {
    if (busy.current || modalOpen) {
      return
    }
    const cart = commitEdit()
    if (!cart || !readyToBill(cart)) {
      return
    }
    try {
      const priced = await priceOf(cart)
      setPayment({ due: priced.grandTotalPaise, cart })
    } catch (failure) {
      setError(messageOfFailure(failure, 'Could not price the bill'))
    }
  }

  const completePayment = async (tenders: Tender[]) => {
    if (busy.current || !payment) {
      return
    }
    busy.current = true
    setSaving(true)
    try {
      await sell(payment.cart, tenders)
    } catch (failure) {
      setError(messageOfFailure(failure, 'Could not take the bill'))
      requestFocus(linesRef.current.length, 'code')
    } finally {
      setPayment(null)
      busy.current = false
      setSaving(false)
    }
  }

  const reprintLast = async () => {
    if (!lastSale) {
      onToast({ tone: 'warn', text: 'No bill taken on this till yet' })
      return
    }
    try {
      await api.post(`/api/invoices/${lastSale.invoice.id}/receipt`, { copy: 'DUPLICATE', openDrawer: false })
      onToast({ tone: 'good', text: `Reprinted ${lastSale.invoice.invoiceNumber} as a duplicate` })
    } catch (failure) {
      onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not reprint') })
    }
  }

  const askHold = () => {
    const cart = commitEdit()
    if (cart && cart.length > 0) {
      setAsk({ action: 'hold', title: 'Hold this bill', label: 'Name it so you can find it again', initial: customer.name })
    }
  }

  const openHolds = async () => {
    try {
      setHolds(await api.get<HeldBill[]>('/api/holds'))
    } catch (failure) {
      onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not read held bills') })
    }
  }

  const resumeHold = async (held: HeldBill) => {
    try {
      const resumed = await api.delete<HeldBill>(`/api/holds/${held.id}`)
      replaceLines((resumed.cart.lines ?? []).map((line) => ({ ...line, key: nextKey.current++ })))
      setCustomer(resumed.cart.customer ?? NO_CUSTOMER)
      setHolds(null)
      requestFocus(resumed.cart.lines?.length ?? 0, 'code')
    } catch (failure) {
      onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not resume that bill') })
    }
  }

  const runAsk = async (value: string) => {
    const pending = ask
    setAsk(null)
    if (pending?.action === 'hold') {
      try {
        await api.post('/api/holds', {
          label: value.trim() || `Bill ${clockTime(new Date())}`,
          cart: { lines: linesRef.current.map(withoutKey), customer },
          estimatedTotalPaise: quote?.grandTotalPaise ?? 0,
        })
        clearBill()
        onToast({ tone: 'good', text: 'Bill held. Resume it with ' + shortcuts.resumeBill })
      } catch (failure) {
        onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not hold the bill') })
      }
    } else if (pending?.action === 'drawer') {
      try {
        await api.post('/api/hardware/drawer/open', { reason: value.trim() || 'No sale' })
        onToast({ tone: 'good', text: 'Drawer opened' })
      } catch (failure) {
        onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not open the drawer') })
      }
    }
    requestFocus(linesRef.current.length, 'code')
  }

  useShortcuts(shortcuts, {
    takeBill: () => void takeBill(),
    focusSearch: () => move(linesRef.current.length, 'code'),
    setQuantity: () => linesRef.current[edit.row] && move(edit.row, 'qty'),
    setDiscount: () => linesRef.current[edit.row] && move(edit.row, 'disc'),
    removeLine: () => removeRow(edit.row),
    holdBill: askHold,
    resumeBill: () => void openHolds(),
    openDrawer: () => setAsk({ action: 'drawer', title: 'Open drawer', label: 'Reason', initial: '' }),
    takePayment: () => void openPayment(),
    reprintLast: () => void reprintLast(),
    customer: () => setEditingCustomer(true),
    clearBill: () => linesRef.current.length > 0 && setConfirmClear(true),
  }, !modalOpen)

  // ---- Grid keyboard -------------------------------------------------------------------------------
  const onCellKeyDown = (event: ReactKeyboardEvent<HTMLInputElement>, row: number, col: Col) => {
    if (event.ctrlKey || event.altKey || event.metaKey) {
      return
    }
    const input = event.currentTarget
    const value = input.value
    const start = input.selectionStart ?? 0
    const end = input.selectionEnd ?? 0
    const wholeSelected = start === 0 && end === value.length
    const items = listOpen && found ? found.items : []
    const colIndex = COLS.indexOf(col)

    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault()
        if (items.length > 0) {
          setHighlight((current) => Math.min(current + 1, items.length - 1))
        } else if (row === linesRef.current.length && isSearchCol(col) && typed === '') {
          void browse(row, col)
        } else {
          move(row + 1, col)
        }
        return
      case 'ArrowUp':
        event.preventDefault()
        if (items.length > 0) {
          setHighlight((current) => Math.max(current - 1, 0))
        } else {
          move(row - 1, col)
        }
        return
      case 'ArrowLeft':
        if ((start === 0 && end === 0) || wholeSelected) {
          event.preventDefault()
          if (colIndex > 0) {
            move(row, COLS[colIndex - 1])
          }
        }
        return
      case 'ArrowRight':
        if ((start === value.length && end === value.length) || wholeSelected) {
          event.preventDefault()
          const next = COLS[colIndex + 1]
          if (next && !(row === linesRef.current.length && !isSearchCol(next))) {
            move(row, next)
          }
        }
        return
      case 'Enter': {
        event.preventDefault()
        if (isSearchCol(col)) {
          void choose(row, col)
          return
        }
        const committed = commitEdit()
        if (!committed) {
          return
        }
        if (col === 'rate') {
          if (committed[row] && committed[row].unitPricePaise <= 0) {
            refuse(row, 'rate', `Enter the rate for ${committed[row].name}`)
          } else {
            requestFocus(row, 'qty')
          }
        } else {
          // Quantity or discount done: straight on to the next item.
          requestFocus(committed.length, 'code')
        }
        return
      }
      case 'Escape':
        if (listOpen || edit.dirty) {
          event.preventDefault()
          setDropdownOpen(false)
          setEdit({ row, col, text: '', dirty: false })
          setInvalid(null)
          requestAnimationFrame(() => input.select())
        }
        return
      default:
        if (actionFor(shortcuts, event) === 'takeBill' && event.key.length === 1) {
          // Space inside an item name being typed is just a space.
          if (isSearchCol(col) && edit.dirty && edit.text.trim() !== '') {
            return
          }
          event.preventDefault()
          if (!event.repeat) {
            void takeBill()
          }
        }
    }
  }

  const onCellChange = (row: number, col: Col, text: string) => {
    setEdit({ row, col, text, dirty: true })
    setInvalid(null)
    if (isSearchCol(col) && text.trim() === '') {
      setDropdownOpen(false)
    }
  }

  const onCellFocus = (row: number, col: Col) => {
    const current = editRef.current
    if (current.row !== row || current.col !== col) {
      const moved = { row, col, text: '', dirty: false }
      editRef.current = moved
      setEdit(moved)
      setDropdownOpen(false)
    }
  }

  const onCellBlur = (row: number, col: Col) => {
    const current = editRef.current
    if (current.row === row && current.col === col && current.dirty) {
      commitEdit()
    }
  }

  // ---- Rendering helpers ---------------------------------------------------------------------------
  const display = (row: number, col: Col): string => {
    const line = lines[row]
    if (!line) {
      return ''
    }
    switch (col) {
      case 'code':
        return line.sku
      case 'name':
        return line.name
      case 'rate':
        return line.unitPricePaise > 0 ? rupeesForInput(line.unitPricePaise) : ''
      case 'qty':
        return quantity(line.quantityMilli)
      case 'disc':
        return line.discountPaise > 0 ? rupeesForInput(line.discountPaise) : ''
    }
  }

  /** The server's figure for a row, only while it still matches what is on screen. */
  const pricedLine = (index: number) => {
    const line = lines[index]
    const priced = quote?.lines[index]
    return priced && line && priced.productId === line.productId && priced.quantityMilli === line.quantityMilli
      && priced.unitPricePaise === line.unitPricePaise && priced.discountPaise === line.discountPaise
      ? priced
      : null
  }

  const cellInput = (row: number, col: Col, options: { align?: 'left' | 'right'; disabled?: boolean } = {}) => {
    const line = lines[row]
    const active = edit.row === row && edit.col === col
    const needsRate = col === 'rate' && line !== undefined && line.unitPricePaise <= 0
    const bad = invalid?.row === row && invalid.col === col
    const numeric = !isSearchCol(col)
    return (
      <input
        ref={(element) => {
          if (element) {
            cells.current.set(cellId(row, col), element)
          } else {
            cells.current.delete(cellId(row, col))
          }
        }}
        value={active && edit.dirty ? edit.text : display(row, col)}
        disabled={options.disabled}
        inputMode={numeric ? 'decimal' : undefined}
        autoComplete="off"
        spellCheck={false}
        placeholder={needsRate ? 'Enter rate' : row === blankRow && col === 'code' ? 'Code' : row === blankRow && col === 'name' ? 'Type item name…' : ''}
        onChange={(event) => onCellChange(row, col, event.target.value)}
        onFocus={() => onCellFocus(row, col)}
        onBlur={() => onCellBlur(row, col)}
        onKeyDown={(event) => onCellKeyDown(event, row, col)}
        className={`num h-9 w-full rounded border bg-transparent px-2 text-sm outline-none transition
          ${options.align === 'right' ? 'text-right' : 'text-left'}
          ${col === 'name' ? 'font-medium uppercase' : ''} ${col === 'code' ? 'uppercase' : ''}
          ${bad ? 'border-rose-500 bg-rose-50 ring-2 ring-rose-200'
            : needsRate ? 'border-amber-400 bg-amber-50 placeholder:text-amber-700'
              : 'border-transparent focus:border-sky-500 focus:bg-white focus:ring-2 focus:ring-sky-200'}
          disabled:cursor-not-allowed disabled:opacity-40`}
      />
    )
  }

  const totalQuantity = lines.reduce((sum, line) => sum + line.quantityMilli, 0)
  const anchorInput = listOpen ? cells.current.get(cellId(edit.row, edit.col)) : undefined

  return (
    <div className="flex h-full min-h-0 flex-col gap-3">
      {notReady && (
        <Banner tone="warn">
          Billing is blocked until the shop's {settings?.missingForInvoicing.join(', ')} are filled in on the
          Settings screen.
        </Banner>
      )}
      {error && <Banner tone="bad" onDismiss={() => setError(null)}>{error}</Banner>}

      {/* Date, running clock, bill type and customer */}
      <div className="flex flex-wrap items-center gap-x-5 gap-y-2 rounded-lg border border-slate-200 bg-white px-4 py-2 shadow-sm">
        <div className="flex items-baseline gap-3">
          <span className="num text-lg font-semibold text-slate-800">{billDate(now)}</span>
          <span className="num rounded-md bg-slate-900 px-2.5 py-1 font-mono text-lg font-semibold tracking-wide text-emerald-300">
            {clockTime(now)}
          </span>
        </div>
        <span className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${
          gst ? 'bg-sky-100 text-sky-800' : 'bg-slate-200 text-slate-700'}`}>
          {gst ? 'GST tax invoice' : 'Bill without GST'}
        </span>
        <button
          type="button"
          tabIndex={-1}
          onClick={() => setEditingCustomer(true)}
          className="flex items-center gap-2 rounded-md px-2 py-1 text-sm text-slate-600 hover:bg-slate-100"
        >
          <span className="text-slate-400">Customer</span>
          <span className="font-medium text-slate-800">{customer.name || 'Walk-in'}</span>
          {customer.gstin && <span className="num text-xs text-slate-500">{customer.gstin}</span>}
          <kbd>{shortcuts.customer}</kbd>
        </button>
        <div className="ml-auto flex items-center gap-4 text-sm text-slate-500">
          <span>Last bill <span className="num font-medium text-slate-800">{lastSale?.invoice.invoiceNumber ?? '—'}</span></span>
          <span>Last amount <span className="num font-medium text-slate-800">
            {lastSale ? rupees(lastSale.invoice.grandTotalPaise) : '—'}</span></span>
        </div>
      </div>

      {/* The bill */}
      <section className="min-h-64 flex-1 overflow-auto rounded-lg border border-slate-200 bg-white shadow-sm">
        <table className="w-full table-fixed border-collapse text-sm">
          <colgroup>
            <col className="w-12" />
            <col className="w-10" />
            <col className="w-28" />
            <col />
            {gst && <col className="w-16" />}
            <col className="w-28" />
            <col className="w-24" />
            <col className="w-24" />
            <col className="w-32" />
          </colgroup>
          <thead className="sticky top-0 z-10 bg-slate-800 text-xs uppercase tracking-wide text-slate-200">
            <tr>
              <th className="px-2 py-2 text-left font-medium">S.No</th>
              <th className="py-2" aria-label="Delete" />
              <th className="px-2 py-2 text-left font-medium">Code</th>
              <th className="px-2 py-2 text-left font-medium">Name</th>
              {gst && <th className="px-2 py-2 text-right font-medium">GST</th>}
              <th className="px-2 py-2 text-right font-medium">Rate</th>
              <th className="px-2 py-2 text-right font-medium">Qty</th>
              <th className="px-2 py-2 text-right font-medium">Disc</th>
              <th className="px-3 py-2 text-right font-medium">Amount</th>
            </tr>
          </thead>
          <tbody>
            {lines.map((line, index) => {
              const priced = pricedLine(index)
              const activeRow = edit.row === index
              return (
                <tr key={line.key}
                    className={`border-b border-slate-100 ${activeRow ? 'bg-sky-50' : index % 2 ? 'bg-slate-50/60' : ''}`}>
                  <td className="num px-2 text-slate-500">{index + 1}</td>
                  <td className="text-center">
                    <button
                      type="button"
                      tabIndex={-1}
                      title={`Delete row (${shortcuts.removeLine})`}
                      aria-label={`Delete ${line.name}`}
                      onMouseDown={(event) => event.preventDefault()}
                      onClick={() => removeRow(index)}
                      className="rounded p-1 text-rose-500 hover:bg-rose-100 hover:text-rose-700"
                    >
                      <TrashIcon />
                    </button>
                  </td>
                  <td className="px-1 py-0.5">{cellInput(index, 'code')}</td>
                  <td className="px-1 py-0.5">{cellInput(index, 'name')}</td>
                  {gst && (
                    <td className="num px-2 text-right text-xs text-slate-400">
                      {priced ? percent(priced.gstRateBp) : ''}
                    </td>
                  )}
                  <td className="px-1 py-0.5">{cellInput(index, 'rate', { align: 'right' })}</td>
                  <td className="px-1 py-0.5">
                    <div className="flex items-center">
                      {cellInput(index, 'qty', { align: 'right' })}
                      {!WHOLE_UNITS.includes(line.unit) && <span className="pr-1 text-xs text-slate-400">{line.unit}</span>}
                    </div>
                  </td>
                  <td className="px-1 py-0.5">{cellInput(index, 'disc', { align: 'right' })}</td>
                  <td className="num px-3 text-right font-semibold text-slate-900">
                    {priced ? rupees(priced.lineTotalPaise) : line.unitPricePaise > 0 ? '…' : ''}
                  </td>
                </tr>
              )
            })}
            <tr className={`border-b border-emerald-100 ${edit.row === blankRow ? 'bg-emerald-50' : 'bg-emerald-50/40'}`}>
              <td className="num px-2 text-slate-400">{blankRow + 1}</td>
              <td />
              <td className="px-1 py-0.5">{cellInput(blankRow, 'code')}</td>
              <td className="px-1 py-0.5">{cellInput(blankRow, 'name')}</td>
              {gst && <td />}
              <td className="px-1 py-0.5">{cellInput(blankRow, 'rate', { align: 'right', disabled: true })}</td>
              <td className="px-1 py-0.5">{cellInput(blankRow, 'qty', { align: 'right', disabled: true })}</td>
              <td className="px-1 py-0.5">{cellInput(blankRow, 'disc', { align: 'right', disabled: true })}</td>
              <td />
            </tr>
          </tbody>
        </table>
        {lines.length === 0 && (
          <p className="px-4 py-6 text-center text-sm text-slate-400">
            Type an item code or name in the green row. <kbd>↓</kbd> lists every item.
          </p>
        )}
      </section>

      {listOpen && found && anchorInput && (
        <LookupList
          anchor={anchorInput}
          found={found}
          highlight={highlight}
          itemRefs={listItems.current}
          onHover={setHighlight}
          onPick={(product) => pick(edit.row, product)}
        />
      )}

      {/* Shortcuts · bill actions · total */}
      <div className="grid shrink-0 gap-3 lg:grid-cols-[1.3fr_1fr_1.1fr]">
        <section className="rounded-lg border border-slate-200 bg-white px-4 py-3 shadow-sm">
          <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-400">Shortcuts</h2>
          <ul className="grid grid-cols-2 gap-x-5 gap-y-1 text-xs text-slate-600">
            {(Object.keys(SHORTCUT_LABELS) as ShortcutAction[]).map((action) => (
              <li key={action} className="flex items-center justify-between gap-2">
                <span className="truncate">{SHORTCUT_LABELS[action]}</span>
                <kbd>{shortcuts[action]}</kbd>
              </li>
            ))}
            <li className="flex items-center justify-between gap-2">
              <span>Move between cells</span><kbd>← → ↑ ↓</kbd>
            </li>
            <li className="flex items-center justify-between gap-2">
              <span>Pick item / next cell</span><kbd>Enter</kbd>
            </li>
          </ul>
        </section>

        <section className="flex flex-col gap-2 rounded-lg border border-slate-200 bg-white px-4 py-3 shadow-sm">
          <h2 className="text-xs font-semibold uppercase tracking-wide text-slate-400">Bill</h2>
          <div className="grid flex-1 grid-cols-2 gap-2">
            <ActionButton label="Hold" hint={shortcuts.holdBill} disabled={lines.length === 0} onClick={askHold} />
            <ActionButton label="Resume" hint={shortcuts.resumeBill} onClick={() => void openHolds()} />
            <ActionButton label="Reprint" hint={shortcuts.reprintLast} disabled={!lastSale} onClick={() => void reprintLast()} />
            <ActionButton label="Clear" hint={shortcuts.clearBill} tone="danger" disabled={lines.length === 0}
                          onClick={() => setConfirmClear(true)} />
          </div>
          {lastSale && lastSale.invoice.changePaise > 0 && (
            <p className="num text-sm text-emerald-700">
              Change to give on {lastSale.invoice.invoiceNumber}: {rupees(lastSale.invoice.changePaise)}
            </p>
          )}
        </section>

        <section className="flex flex-col gap-2 rounded-lg border border-slate-200 bg-white px-4 py-3 shadow-sm">
          <div className="flex items-baseline justify-between text-sm text-slate-500">
            <span>Items <span className="num font-semibold text-slate-800">{lines.length}</span></span>
            <span>Qty <span className="num font-semibold text-slate-800">{quantity(totalQuantity)}</span></span>
          </div>
          {gst && quote && (
            <div className="num flex flex-wrap justify-between gap-x-3 text-xs text-slate-500">
              <span>Taxable {rupees(quote.taxableValuePaise)}</span>
              {quote.interState
                ? <span>IGST {rupees(quote.igstPaise)}</span>
                : <span>CGST {rupees(quote.cgstPaise)} · SGST {rupees(quote.sgstPaise)}</span>}
              {!!quote.cessPaise && <span>Cess {rupees(quote.cessPaise)}</span>}
            </div>
          )}
          <div className="flex items-baseline justify-between rounded-md bg-slate-900 px-4 py-2 text-white">
            <span className="text-sm text-slate-300">
              Total{quote?.roundOffPaise ? <span className="num ml-2 text-xs">(round off {rupees(quote.roundOffPaise)})</span> : null}
            </span>
            <span className="num text-3xl font-bold">{rupees(quote?.grandTotalPaise ?? 0)}</span>
          </div>
          <div className="flex gap-2">
            <Button
              tone="primary"
              className="flex-[2] justify-center py-3 text-base"
              hint={shortcuts.takeBill}
              disabled={lines.length === 0 || notReady || saving}
              onClick={() => void takeBill()}
            >
              {saving ? 'Taking bill…' : 'Take bill'}
            </Button>
            <Button
              className="flex-1 justify-center"
              hint={shortcuts.takePayment}
              disabled={lines.length === 0 || notReady || saving}
              onClick={() => void openPayment()}
            >
              UPI / Card
            </Button>
          </div>
        </section>
      </div>

      {ask && (
        <AskModal title={ask.title} label={ask.label} initial={ask.initial}
                  onSubmit={runAsk} onClose={() => { setAsk(null); requestFocus(linesRef.current.length, 'code') }} />
      )}

      {payment && (
        <PaymentDialog
          due={payment.due}
          onClose={() => { setPayment(null); requestFocus(linesRef.current.length, 'code') }}
          onConfirm={(tenders) => void completePayment(tenders)}
        />
      )}

      {editingCustomer && (
        <CustomerDialog
          customer={customer}
          gst={gst}
          onClose={() => { setEditingCustomer(false); requestFocus(linesRef.current.length, 'code') }}
          onSave={(saved) => {
            setCustomer(saved)
            setEditingCustomer(false)
            requestFocus(linesRef.current.length, 'code')
          }}
        />
      )}

      {confirmClear && (
        <Modal title="Clear this bill?" onClose={() => { setConfirmClear(false); requestFocus(linesRef.current.length, 'code') }}>
          <p className="text-sm text-slate-600">
            {lines.length} item{lines.length === 1 ? '' : 's'} will be taken off the screen. Nothing is billed.
          </p>
          <div className="mt-4 flex justify-end gap-2">
            <Button onClick={() => { setConfirmClear(false); requestFocus(linesRef.current.length, 'code') }} hint="Esc">
              Keep it
            </Button>
            <AutoFocusButton tone="danger" onClick={clearBill}>Clear bill</AutoFocusButton>
          </div>
        </Modal>
      )}

      {holds !== null && (
        <Modal title="Held bills" onClose={() => { setHolds(null); requestFocus(linesRef.current.length, 'code') }} wide>
          {holds.length === 0 ? (
            <Empty>No bills are held on this till</Empty>
          ) : (
            <ul className="divide-y divide-slate-100">
              {holds.map((held) => (
                <li key={held.id} className="flex items-center justify-between gap-4 py-2">
                  <div>
                    <div className="font-medium text-slate-800">{held.label}</div>
                    <div className="text-xs text-slate-400">
                      {held.cashier} · {new Date(held.heldAt).toLocaleTimeString()} · {held.cart.lines?.length ?? 0} items
                    </div>
                  </div>
                  <div className="flex items-center gap-3">
                    <span className="num text-slate-600">{rupees(held.estimatedTotalPaise)}</span>
                    <Button tone="primary" onClick={() => void resumeHold(held)}>Resume</Button>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Modal>
      )}
    </div>
  )
}

/** The item list under a code or name cell. Fixed to the viewport so the grid's scroll never clips it. */
function LookupList({ anchor, found, highlight, itemRefs, onHover, onPick }: {
  anchor: HTMLInputElement
  found: Found
  highlight: number
  itemRefs: Map<number, HTMLTableRowElement>
  onHover: (index: number) => void
  onPick: (product: Product) => void
}) {
  const [box, setBox] = useState<{ left: number; top?: number; bottom?: number; maxHeight: number } | null>(null)

  useLayoutEffect(() => {
    const place = () => {
      const rect = anchor.getBoundingClientRect()
      const width = Math.min(620, window.innerWidth - 16)
      const left = Math.max(8, Math.min(rect.left, window.innerWidth - width - 8))
      const below = window.innerHeight - rect.bottom - 8
      const above = rect.top - 8
      setBox(below >= 220 || below >= above
        ? { left, top: rect.bottom + 2, maxHeight: Math.min(360, below) }
        : { left, bottom: window.innerHeight - rect.top + 2, maxHeight: Math.min(360, above) })
    }
    place()
    window.addEventListener('resize', place)
    window.addEventListener('scroll', place, true)
    return () => {
      window.removeEventListener('resize', place)
      window.removeEventListener('scroll', place, true)
    }
  }, [anchor])

  if (!box) {
    return null
  }
  return (
    <div
      className="fixed z-40 overflow-auto rounded-md border border-slate-300 bg-white shadow-xl"
      style={{ left: box.left, top: box.top, bottom: box.bottom, maxHeight: box.maxHeight, width: Math.min(620, window.innerWidth - 16) }}
      onMouseDown={(event) => event.preventDefault()}
    >
      {found.items.length === 0 ? (
        <p className="px-3 py-2 text-sm text-slate-500">No item matches "{found.text}"</p>
      ) : (
        <table className="w-full text-sm">
          <thead className="sticky top-0 bg-slate-100 text-xs uppercase tracking-wide text-slate-500">
            <tr>
              <th className="px-3 py-1.5 text-left">Code</th>
              <th className="px-3 py-1.5 text-left">Name</th>
              <th className="px-3 py-1.5 text-right">Rate</th>
              <th className="px-3 py-1.5 text-right">Stock</th>
            </tr>
          </thead>
          <tbody>
            {found.items.map((product, index) => (
              <tr
                key={product.id}
                ref={(element) => {
                  if (element) {
                    itemRefs.set(index, element)
                  } else {
                    itemRefs.delete(index)
                  }
                }}
                onMouseEnter={() => onHover(index)}
                onClick={() => onPick(product)}
                className={`cursor-pointer ${index === highlight ? 'bg-sky-600 text-white' : 'hover:bg-sky-50'}`}
              >
                <td className="num px-3 py-1.5 uppercase">{product.sku}</td>
                <td className="px-3 py-1.5 font-medium uppercase">{product.name}</td>
                <td className="num px-3 py-1.5 text-right">
                  {product.sellingPricePaise > 0 ? rupees(product.sellingPricePaise) : <span className="text-xs opacity-70">enter rate</span>}
                </td>
                <td className={`num px-3 py-1.5 text-right text-xs ${
                  index === highlight ? 'text-sky-100' : product.stockMilli <= 0 ? 'text-rose-500' : 'text-slate-400'}`}>
                  {quantity(product.stockMilli)} {product.unit}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

function ActionButton({ label, hint, onClick, disabled, tone = 'default' }: {
  label: string
  hint: string
  onClick: () => void
  disabled?: boolean
  tone?: 'default' | 'danger'
}) {
  return (
    <button
      type="button"
      tabIndex={-1}
      onClick={onClick}
      disabled={disabled}
      className={`flex items-center justify-between gap-2 rounded-md border px-3 py-2 text-sm font-medium transition
        disabled:cursor-not-allowed disabled:opacity-40 ${tone === 'danger'
          ? 'border-rose-200 bg-rose-50 text-rose-700 hover:bg-rose-100'
          : 'border-slate-300 bg-white text-slate-700 hover:bg-slate-50'}`}
    >
      {label}
      <kbd>{hint}</kbd>
    </button>
  )
}

function AutoFocusButton({ children, onClick, tone }: { children: string; onClick: () => void; tone: 'danger' | 'primary' }) {
  const button = useRef<HTMLButtonElement>(null)
  useEffect(() => button.current?.focus(), [])
  return (
    <button
      ref={button}
      type="button"
      onClick={onClick}
      className={`inline-flex items-center gap-2 rounded-md px-3 py-2 text-sm font-medium text-white ${
        tone === 'danger' ? 'bg-rose-600 hover:bg-rose-500' : 'bg-sky-600 hover:bg-sky-500'}`}
    >
      {children}
      <kbd>Enter</kbd>
    </button>
  )
}

function TrashIcon() {
  return (
    <svg viewBox="0 0 20 20" fill="currentColor" className="h-4 w-4" aria-hidden="true">
      <path fillRule="evenodd" clipRule="evenodd"
            d="M8.75 1A2.75 2.75 0 0 0 6 3.75v.44c-.8.08-1.58.18-2.37.3a.75.75 0 1 0 .23 1.48l.15-.02.84 10.52A2.75 2.75 0 0 0 7.6 19h4.8a2.75 2.75 0 0 0 2.75-2.53l.84-10.52.15.02a.75.75 0 0 0 .23-1.48c-.79-.12-1.58-.22-2.37-.3v-.44A2.75 2.75 0 0 0 11.25 1h-2.5ZM10 4c.84 0 1.67.03 2.5.08v-.33c0-.69-.56-1.25-1.25-1.25h-2.5c-.69 0-1.25.56-1.25 1.25v.33C8.33 4.03 9.16 4 10 4ZM8.58 7.72a.75.75 0 0 0-1.5.06l.3 7.5a.75.75 0 1 0 1.5-.06l-.3-7.5Zm4.34.06a.75.75 0 1 0-1.5-.06l-.3 7.5a.75.75 0 1 0 1.5.06l.3-7.5Z" />
    </svg>
  )
}

/** Name, and for a GST shop the customer's GSTIN and state, for a B2B tax invoice. */
function CustomerDialog({ customer, gst, onClose, onSave }: {
  customer: Customer
  gst: boolean
  onClose: () => void
  onSave: (customer: Customer) => void
}) {
  const [form, setForm] = useState(customer)
  return (
    <Modal title="Customer" onClose={onClose}>
      <form
        className="space-y-3"
        onSubmit={(event) => {
          event.preventDefault()
          onSave({ name: form.name.trim(), gstin: form.gstin.trim(), placeOfSupply: form.placeOfSupply.trim() })
        }}
      >
        <Field label="Name" optional>
          <input className={inputClass} autoFocus value={form.name}
                 onChange={(event) => setForm({ ...form, name: event.target.value })} />
        </Field>
        {gst ? (
          <div className="grid grid-cols-2 gap-3">
            <Field label="GSTIN" optional hint="For a business customer's tax invoice">
              <input className={`${inputClass} num uppercase`} maxLength={15} value={form.gstin}
                     onChange={(event) => setForm({ ...form, gstin: event.target.value.toUpperCase() })} />
            </Field>
            <Field label="Place of supply" optional hint="State code, e.g. 33 for Tamil Nadu">
              <input className={`${inputClass} num`} maxLength={2} value={form.placeOfSupply}
                     onChange={(event) => setForm({ ...form, placeOfSupply: event.target.value.replace(/\D/g, '') })} />
            </Field>
          </div>
        ) : (
          <p className="text-xs text-slate-400">This shop has no GSTIN, so bills carry no customer GSTIN.</p>
        )}
        <div className="flex justify-end gap-2">
          <Button onClick={() => onSave(NO_CUSTOMER)}>Walk-in</Button>
          <Button type="submit" tone="primary" hint="Enter">Save</Button>
        </div>
      </form>
    </Modal>
  )
}

const MODES: PaymentMode[] = ['CASH', 'UPI', 'CARD', 'VOUCHER']
const QUICK_NOTES = [10000, 20000, 50000, 100000, 200000]

/** Cash, UPI, card, or any mix of them; the parts must add up to the bill exactly. */
function PaymentDialog({ due, onClose, onConfirm }: {
  due: number
  onClose: () => void
  onConfirm: (tenders: Tender[]) => void
}) {
  const [mode, setMode] = useState<PaymentMode>('CASH')
  const [tenderedText, setTenderedText] = useState('')
  const [reference, setReference] = useState('')
  const [parts, setParts] = useState<Tender[]>([])
  const submitted = useRef(false)

  const paid = parts.reduce((sum, part) => sum + part.amountPaise, 0)
  const remaining = due - paid
  const tendered = tenderedText ? paiseFromRupees(tenderedText) : remaining
  const change = mode === 'CASH' ? Math.max(0, tendered - remaining) : 0

  const settle = () => {
    if (submitted.current) {
      return
    }
    const amount = Math.min(remaining, mode === 'CASH' ? Math.max(tendered, 0) : tendered || remaining)
    if (amount <= 0) {
      return
    }
    const tender: Tender = {
      mode,
      amountPaise: amount,
      tenderedPaise: mode === 'CASH' ? Math.max(tendered, amount) : amount,
      reference: reference || undefined,
    }
    const all = [...parts, tender]
    if (amount >= remaining) {
      submitted.current = true
      onConfirm(all)
    } else {
      setParts(all)
      setTenderedText('')
      setReference('')
    }
  }

  /** Space in an amount box settles, as it takes the bill on the billing grid. */
  const spaceSettles = (event: ReactKeyboardEvent<HTMLInputElement>) => {
    if (event.key === ' ') {
      event.preventDefault()
      if (!event.repeat) {
        settle()
      }
    }
  }

  return (
    <Modal title="Payment" onClose={onClose}>
      <div className="flex items-baseline justify-between rounded-md bg-slate-900 px-4 py-3 text-white">
        <span className="text-sm text-slate-300">{parts.length ? 'Still to pay' : 'Bill total'}</span>
        <span className="num text-3xl font-semibold">{rupees(remaining)}</span>
      </div>

      {parts.length > 0 && (
        <ul className="mt-3 space-y-1 text-sm">
          {parts.map((part, index) => (
            <li key={index} className="flex justify-between text-slate-600">
              <span>{part.mode}</span>
              <span className="num">{rupees(part.amountPaise)}</span>
            </li>
          ))}
        </ul>
      )}

      <div className="mt-4 grid grid-cols-4 gap-2">
        {MODES.map((option) => (
          <button
            key={option}
            type="button"
            onClick={() => setMode(option)}
            className={`rounded-md border px-2 py-2 text-sm font-medium ${
              mode === option ? 'border-sky-500 bg-sky-50 text-sky-700' : 'border-slate-200 text-slate-600'
            }`}
          >
            {option}
          </button>
        ))}
      </div>

      <form
        className="mt-4 space-y-3"
        onSubmit={(event) => {
          event.preventDefault()
          settle()
        }}
      >
        {mode === 'CASH' ? (
          <>
            <Field label="Cash received" hint="Leave empty for the exact amount">
              <input
                className={`${inputClass} num text-lg`}
                inputMode="decimal"
                autoFocus
                value={tenderedText}
                onKeyDown={spaceSettles}
                onChange={(event) => setTenderedText(event.target.value)}
              />
            </Field>
            <div className="flex flex-wrap gap-2">
              {QUICK_NOTES.filter((note) => note >= remaining).slice(0, 4).map((note) => (
                <Button key={note} onClick={() => setTenderedText((note / 100).toFixed(0))}>
                  {rupees(note)}
                </Button>
              ))}
              <Button onClick={() => setTenderedText((remaining / 100).toFixed(2))}>Exact</Button>
            </div>
            <div className="flex items-baseline justify-between rounded-md bg-emerald-50 px-3 py-2">
              <span className="text-sm text-emerald-800">Change to give</span>
              <span className="num text-xl font-semibold text-emerald-900">{rupees(change)}</span>
            </div>
          </>
        ) : (
          <>
            <Field label="Amount" hint="Less than the total splits the bill">
              <input className={`${inputClass} num text-lg`} inputMode="decimal" autoFocus value={tenderedText}
                     onKeyDown={spaceSettles}
                     onChange={(event) => setTenderedText(event.target.value)} />
            </Field>
            <Field label="Reference" optional hint="UPI reference, card approval code, voucher number">
              <input className={inputClass} value={reference} onChange={(event) => setReference(event.target.value)} />
            </Field>
          </>
        )}

        <div className="flex justify-end gap-2 pt-1">
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" tone="primary" hint="Enter">
            {tendered >= remaining || !tenderedText ? 'Take bill' : 'Add part payment'}
          </Button>
        </div>
      </form>
    </Modal>
  )
}
