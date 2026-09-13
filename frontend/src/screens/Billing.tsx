import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import { createPortal } from 'react-dom'
import {
  api, clockTime, messageOfFailure, milliFromUnits, paiseFromRupees, percent, quantity, rupees, rupeesForInput,
} from '../api'
import type {
  CartLine, Customer, EditResponse, HeldBill, Invoice, PaymentMode, Product, QuotedCart, Refund, Returnable,
  ReturnResponse, Role, SaleResponse, SettingsView, Tender,
} from '../types'
import { WHOLE_UNITS } from '../types'
import { AskModal, Banner, Button, Empty, Field, inputClass, Modal } from '../components/ui'
import {
  actionFor, loadAskCashReceived, loadShortcuts, loadShowShortcuts, SHORTCUT_LABELS, useShortcuts,
} from '../shortcuts'
import type { ShortcutAction } from '../shortcuts'
import type { Toast } from '../App'

const ONE_UNIT = 1000
const NO_CUSTOMER: Customer = { name: '', gstin: '', placeOfSupply: '' }

/** The editable columns of a bill row, left to right. Amount is worked out by the server. */
type Col = 'code' | 'name' | 'rate' | 'qty' | 'disc'
const COLS: Col[] = ['code', 'name', 'rate', 'qty', 'disc']
/** Typing in these looks an item up; the rest take numbers. */
const SEARCH_COLS: Col[] = ['code', 'name']

/**
 * A bill row. On a return against a bill, `originalLineNo` ties it to the bill's line and `maxMilli`
 * is how much of that line can still come back.
 */
type Row = CartLine & { key: number; originalLineNo?: number; maxMilli?: number }

/** What the grid is building: a new bill, a correction of today's bill, or goods coming back. */
type Mode =
  | { kind: 'sale' }
  | { kind: 'edit'; invoice: Invoice }
  | { kind: 'return'; invoice: Invoice | null }

/** Another screen asking the billing screen to open a bill for editing or a return. */
export type BillingRequest = { kind: 'edit' | 'return'; invoiceId: string | null; at: number }
type Cell = { row: number; col: Col }
/** The cell the cursor is in. `dirty` once something has been typed that is not applied yet. */
type Edit = Cell & { text: string; dirty: boolean }
type Found = Cell & { text: string; items: Product[] }

type Ask = {
  action: 'hold' | 'drawer' | 'edit' | 'return'
  title: string
  label: string
  initial: string
}

const cellId = (row: number, col: Col) => `${row}:${col}`
const isSearchCol = (col: Col) => SEARCH_COLS.includes(col)

function cartBody(lines: Row[]) {
  return lines.map((line) => ({
    productId: line.productId,
    quantityMilli: line.quantityMilli,
    discountPaise: line.discountPaise,
    unitPricePaise: line.unitPricePaise,
    originalLineNo: line.originalLineNo ?? null,
  }))
}

/** Today as the till's calendar has it, the way bill dates are written: 2026-09-13. */
function localToday(): string {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
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

/** Prices the grid by the code that will issue it: a sale (or an edited bill), or a return. */
function quoteFor(cart: Row[], mode: Mode, placeOfSupply: string): Promise<QuotedCart> {
  return mode.kind === 'return'
    ? api.post<QuotedCart>('/api/returns/quote', { againstInvoiceId: mode.invoice?.id ?? null, lines: cartBody(cart) })
    : api.post<QuotedCart>('/api/sales/quote', { lines: cartBody(cart), placeOfSupply })
}

/** What the screen keeps of the latest bill: enough to show it and reprint it. */
type LastBill = Pick<Invoice, 'id' | 'invoiceNumber' | 'grandTotalPaise' | 'changePaise'>

const lastBillOf = (invoice: Invoice): LastBill => ({
  id: invoice.id,
  invoiceNumber: invoice.invoiceNumber,
  grandTotalPaise: invoice.grandTotalPaise,
  changePaise: invoice.changePaise,
})

/**
 * The counter screen. The bill is a grid: every row's code and name cells look an item up as you
 * type, and its rate, quantity and discount cells take numbers directly, so nothing pops up while
 * billing. Arrow keys move between cells, Enter moves on, Space takes the bill.
 *
 * Stays mounted while other screens are open (`active` false), so the bill on screen and the last
 * bill are still there on return. Its keys work only while it is active and nothing covers it.
 *
 * @param blocked    the menu is open over the screen
 * @param headerSlot where the bill type, customer and last bill are shown, in the app header
 * @param request    a bill another screen wants edited or returned here
 */
export default function Billing({ settings, onToast, active, blocked, headerSlot, role, request }: {
  settings: SettingsView | null
  onToast: Toast
  active: boolean
  blocked: boolean
  headerSlot: HTMLElement | null
  role: Role
  request: BillingRequest | null
}) {
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
  const [payment, setPayment] = useState<{ due: number; cart: Row[]; purpose: 'sale' | 'edit' } | null>(null)
  /** A refund to confirm: the whole return, or what an edit gives back. */
  const [settling, setSettling] = useState<{ kind: 'return' | 'edit'; due: number; cart: Row[]; total: number } | null>(null)
  const [mode, setMode] = useState<Mode>({ kind: 'sale' })
  const [holds, setHolds] = useState<HeldBill[] | null>(null)
  const [editingCustomer, setEditingCustomer] = useState(false)
  const [confirmClear, setConfirmClear] = useState(false)
  const [lastBill, setLastBill] = useState<LastBill | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [shortcuts, setShortcuts] = useState(loadShortcuts)
  const [showShortcuts, setShowShortcuts] = useState(loadShowShortcuts)
  const [askCashReceived, setAskCashReceived] = useState(loadAskCashReceived)

  const cells = useRef(new Map<string, HTMLInputElement>())
  const listItems = useRef(new Map<number, HTMLTableRowElement>())
  const nextKey = useRef(1)
  // Handlers read these rather than render-time state: a key press can change the bill and move on
  // before React has rendered the change.
  const linesRef = useRef(lines)
  linesRef.current = lines
  const editRef = useRef(edit)
  editRef.current = edit
  const modeRef = useRef(mode)
  modeRef.current = mode
  /** Set while a bill is being taken, so a second Space press can never take it twice. */
  const busy = useRef(false)

  const gst = settings?.gstRegistered ?? true
  const notReady = Boolean(settings && !settings.readyToInvoice)
  const modalOpen = ask !== null || payment !== null || settling !== null || holds !== null || editingCustomer
    || confirmClear
  const canManage = role === 'MANAGER' || role === 'ADMIN'
  /** A return against a bill: only quantities change, and nothing can be added. */
  const againstBill = mode.kind === 'return' && mode.invoice !== null
  /** In front and uncovered by the menu: the only time the bill's keys may act. */
  const inFront = active && !blocked
  const blankRow = lines.length

  const inSearchCell = isSearchCol(edit.col)
  const typed = edit.dirty ? edit.text.trim() : ''
  const listFresh = found !== null && found.row === edit.row && found.col === edit.col && found.text === typed
  const listOpen = inFront && dropdownOpen && inSearchCell && listFresh

  // ---- Server pricing: the screen never computes tax itself -------------------------------------
  useEffect(() => {
    if (lines.length === 0) {
      setQuote(null)
      return
    }
    let current = true
    quoteFor(lines, mode, customer.placeOfSupply)
      .then((priced) => current && setQuote(priced))
      .catch((failure) => current && setError(messageOfFailure(failure, 'Could not price the bill')))
    return () => {
      current = false
    }
  }, [lines, mode, customer.placeOfSupply])

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

  // Back in front (first open, back from another screen, menu closed): pick up any shortcut
  // changes made in Settings and put the cursor back where it was.
  useEffect(() => {
    if (!inFront) {
      return
    }
    setShortcuts(loadShortcuts())
    setShowShortcuts(loadShowShortcuts())
    setAskCashReceived(loadAskCashReceived())
    const { row, col } = editRef.current
    requestFocus(Math.min(row, linesRef.current.length), col)
  }, [inFront, requestFocus])

  // The till's last bill, so it can be seen and reprinted after a restart or a new sign-in.
  useEffect(() => {
    api.get<Invoice | undefined>('/api/invoices/last')
      .then((invoice) => invoice && setLastBill((current) => current ?? lastBillOf(invoice)))
      .catch(() => undefined)
  }, [])

  // Keep the highlighted item of the lookup list in view.
  useEffect(() => {
    listItems.current.get(highlight)?.scrollIntoView({ block: 'nearest' })
  }, [highlight, listOpen])

  /**
   * Where typing carries on: the green row for a new item, or on a return against a bill (which
   * takes no new items) the quantity of its last row.
   */
  const focusEntry = useCallback(() => {
    const count = linesRef.current.length
    const current = modeRef.current
    if (current.kind === 'return' && current.invoice !== null) {
      requestFocus(Math.max(0, count - 1), 'qty')
    } else {
      requestFocus(count, 'code')
    }
  }, [requestFocus])

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
      if (line.maxMilli !== undefined && milli > line.maxMilli) {
        refuse(row, col, `Only ${quantity(line.maxMilli)} of ${line.name} can come back on this bill`)
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
    const lastRow = modeRef.current.kind === 'return' && modeRef.current.invoice !== null
      ? Math.max(0, committed.length - 1)
      : committed.length
    const target = Math.max(0, Math.min(row, lastRow))
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
    if (modeRef.current.kind === 'return' && modeRef.current.invoice !== null) {
      requestFocus(Math.max(0, Math.min(row, updated.length - 1)), 'qty')
    } else {
      requestFocus(Math.min(row, updated.length), 'code')
    }
  }

  /** Empties the grid. Leaving an edit or a return this way changes nothing already issued. */
  const clearBill = () => {
    replaceLines([])
    setCustomer(NO_CUSTOMER)
    setQuote(null)
    setInvalid(null)
    setConfirmClear(false)
    modeRef.current = { kind: 'sale' }
    setMode({ kind: 'sale' })
    requestFocus(0, 'code')
  }

  /** The checks every way of taking a bill shares. */
  const readyToBill = (cart: Row[]): boolean => {
    if (cart.length === 0) {
      setError(modeRef.current.kind === 'return' ? 'Nothing is being returned' : 'Add an item before taking the bill')
      focusEntry()
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

  const priceOf = (cart: Row[]) => quoteFor(cart, modeRef.current, customer.placeOfSupply)

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
    setLastBill(lastBillOf(sale.invoice))
    clearBill()
    setError(null)
    onToast(sale.printed
      ? { tone: 'good', text: `Bill ${sale.invoice.invoiceNumber} taken and printed` }
      : { tone: 'warn', text: `Bill ${sale.invoice.invoiceNumber} taken, but printing failed: ${sale.printError ?? ''}` })
  }

  /**
   * An edit settles the difference: collected like a payment when the new bill costs more, else
   * confirmed as a refund (or nothing) before the old bill is cancelled.
   */
  const settleEdit = async (cart: Row[], invoice: Invoice) => {
    const priced = await priceOf(cart)
    const difference = priced.grandTotalPaise - invoice.grandTotalPaise
    if (difference > 0) {
      setPayment({ due: difference, cart, purpose: 'edit' })
    } else {
      setSettling({ kind: 'edit', due: -difference, cart, total: priced.grandTotalPaise })
    }
  }

  const settleReturn = async (cart: Row[]) => {
    const priced = await priceOf(cart)
    setSettling({ kind: 'return', due: priced.grandTotalPaise, cart, total: priced.grandTotalPaise })
  }

  /**
   * Space: the payment screen for the bill (cash, UPI, card, voucher, or a split). Editing, it
   * collects or gives back the difference; on a return, it asks how the refund goes back.
   */
  const takeBill = async () => {
    if (busy.current || modalOpen) {
      return
    }
    const cart = commitEdit()
    if (!cart || !readyToBill(cart)) {
      return
    }
    const current = modeRef.current
    try {
      if (current.kind === 'edit') {
        await settleEdit(cart, current.invoice)
      } else if (current.kind === 'return') {
        await settleReturn(cart)
      } else {
        const priced = await priceOf(cart)
        setPayment({ due: priced.grandTotalPaise, cart, purpose: 'sale' })
      }
    } catch (failure) {
      setError(messageOfFailure(failure, 'Could not price the bill'))
    }
  }

  /** Cancels the bill being edited and issues the corrected one; only the difference changes hands. */
  const saveEdit = async (cart: Row[], tenders: Tender[]) => {
    const current = modeRef.current
    if (current.kind !== 'edit') {
      return
    }
    const refundsCash = current.invoice.payments.some((paid) => paid.mode === 'CASH')
    const edited = await api.post<EditResponse>(`/api/invoices/${current.invoice.id}/replace`, {
      lines: cartBody(cart),
      payments: tenders,
      buyerGstin: gst ? customer.gstin || null : null,
      buyerName: customer.name || null,
      placeOfSupply: gst ? customer.placeOfSupply || null : null,
      print: true,
      openDrawer: tenders.some((tender) => tender.mode === 'CASH') || refundsCash,
    })
    setLastBill(lastBillOf(edited.invoice))
    clearBill()
    setError(null)
    const money = edited.refundPaise > 0
      ? `Give back ${rupees(edited.refundPaise)}.`
      : edited.collectedPaise > 0 ? `Collected ${rupees(edited.collectedPaise)} more.` : 'Nothing more to pay.'
    onToast({
      tone: edited.printed ? 'good' : 'warn',
      text: `Bill ${edited.replacedInvoiceNumber} cancelled and replaced by ${edited.invoice.invoiceNumber}. ${money}`
        + (edited.printed ? '' : ` Printing failed: ${edited.printError ?? ''}`),
    })
  }

  /** Issues the credit note: stock back on the shelf, money back to the customer. */
  const issueReturn = async (cart: Row[], refund: Refund, reason: string) => {
    const current = modeRef.current
    if (current.kind !== 'return') {
      return
    }
    const done = await api.post<ReturnResponse>('/api/returns', {
      againstInvoiceId: current.invoice?.id ?? null,
      lines: cartBody(cart),
      refunds: [refund],
      reason: reason.trim() || null,
      print: true,
      openDrawer: refund.mode === 'CASH',
    })
    clearBill()
    setError(null)
    onToast({
      tone: done.printed ? 'good' : 'warn',
      text: `Return ${done.creditNote.creditNoteNumber}: give back ${rupees(done.creditNote.grandTotalPaise)} by ${refund.mode}.`
        + (done.printed ? ' Stock is back on the shelf.' : ` Printing failed: ${done.printError ?? ''}`),
    })
  }

  const completeSettling = async (refund: Refund, reason: string) => {
    if (busy.current || !settling) {
      return
    }
    busy.current = true
    setSaving(true)
    try {
      if (settling.kind === 'edit') {
        await saveEdit(settling.cart, [])
      } else {
        await issueReturn(settling.cart, refund, reason)
      }
    } catch (failure) {
      setError(messageOfFailure(failure, settling.kind === 'edit' ? 'Could not save the edit' : 'Could not take the return'))
    } finally {
      setSettling(null)
      busy.current = false
      setSaving(false)
      focusEntry()
    }
  }

  /**
   * Puts a bill on the grid to edit or to return from. A new bill already on screen is held first,
   * never thrown away.
   */
  const leaveCurrentBill = async (): Promise<boolean> => {
    commitEdit()
    if (modeRef.current.kind !== 'sale' || linesRef.current.length === 0) {
      return true
    }
    try {
      const held = await holdCurrent('')
      onToast({ tone: 'good', text: `The bill that was on screen is held as ${held.label}` })
      return true
    } catch (failure) {
      onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not hold the bill on screen') })
      return false
    }
  }

  const startEdit = async (invoice: Invoice) => {
    if (!canManage) {
      onToast({ tone: 'warn', text: 'Only a manager can edit a bill' })
      return
    }
    if (invoice.status === 'CANCELLED') {
      onToast({ tone: 'bad', text: `Bill ${invoice.invoiceNumber} is cancelled, so it cannot be edited` })
      return
    }
    if (invoice.invoiceDate !== localToday()) {
      onToast({ tone: 'warn', text: `Only today's bills can be edited. For bill ${invoice.invoiceNumber}, take a return with ${shortcuts.returnBill}` })
      return
    }
    if (!(await leaveCurrentBill())) {
      return
    }
    replaceLines(invoice.lines.map((line) => ({
      key: nextKey.current++,
      productId: line.productId,
      sku: line.sku,
      name: line.name,
      unit: line.unit,
      quantityMilli: line.quantityMilli,
      discountPaise: line.discountPaise,
      unitPricePaise: line.unitPricePaise,
    })))
    setCustomer({
      name: invoice.buyerName ?? '',
      gstin: invoice.buyerGstin ?? '',
      placeOfSupply: invoice.buyerGstin ? invoice.placeOfSupply : '',
    })
    const next: Mode = { kind: 'edit', invoice }
    modeRef.current = next
    setMode(next)
    setInvalid(null)
    setError(null)
    requestFocus(invoice.lines.length, 'code')
  }

  const startReturn = async (invoice: Invoice | null) => {
    if (!canManage) {
      onToast({ tone: 'warn', text: 'Only a manager can take a return' })
      return
    }
    let rows: Row[] = []
    if (invoice) {
      if (invoice.status === 'CANCELLED') {
        onToast({ tone: 'bad', text: `Bill ${invoice.invoiceNumber} is cancelled: its stock is already back` })
        return
      }
      const returnable = await api.get<Returnable>(`/api/invoices/${invoice.id}/returnable`)
      rows = returnable.lines
        .filter((line) => line.soldMilli > line.returnedMilli)
        .map((line) => ({
          key: nextKey.current++,
          productId: line.productId,
          sku: line.sku,
          name: line.name,
          unit: line.unit,
          quantityMilli: line.soldMilli - line.returnedMilli,
          discountPaise: 0,
          unitPricePaise: line.unitPricePaise,
          originalLineNo: line.lineNo,
          maxMilli: line.soldMilli - line.returnedMilli,
        }))
      if (rows.length === 0) {
        onToast({ tone: 'warn', text: `Everything on bill ${invoice.invoiceNumber} has already come back` })
        return
      }
    }
    if (!(await leaveCurrentBill())) {
      return
    }
    replaceLines(rows)
    setCustomer(NO_CUSTOMER)
    const next: Mode = { kind: 'return', invoice }
    modeRef.current = next
    setMode(next)
    setInvalid(null)
    setError(null)
    if (invoice) {
      requestFocus(0, 'qty')
    } else {
      requestFocus(0, 'code')
    }
  }

  const findBill = (number: string) =>
    api.get<Invoice>(`/api/invoices/lookup?number=${encodeURIComponent(number.trim())}`)

  // Bills screen: "Edit" or "Return" on a bill lands here.
  useEffect(() => {
    if (!request) {
      return
    }
    const open = async () => {
      try {
        const invoice = request.invoiceId ? await api.get<Invoice>(`/api/invoices/${request.invoiceId}`) : null
        if (request.kind === 'edit' && invoice) {
          await startEdit(invoice)
        } else if (request.kind === 'return') {
          await startReturn(invoice)
        }
      } catch (failure) {
        onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not open that bill') })
      }
    }
    void open()
    // Only a new request should run this; the handlers read the latest state through refs.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [request])

  const completePayment = async (tenders: Tender[]) => {
    if (busy.current || !payment) {
      return
    }
    busy.current = true
    setSaving(true)
    try {
      if (payment.purpose === 'edit') {
        await saveEdit(payment.cart, tenders)
      } else {
        await sell(payment.cart, tenders)
      }
    } catch (failure) {
      setError(messageOfFailure(failure, payment.purpose === 'edit' ? 'Could not save the edit' : 'Could not take the bill'))
      focusEntry()
    } finally {
      setPayment(null)
      busy.current = false
      setSaving(false)
    }
  }

  const reprintLast = async () => {
    if (!lastBill) {
      onToast({ tone: 'warn', text: 'No bill taken on this till yet' })
      return
    }
    try {
      await api.post(`/api/invoices/${lastBill.id}/receipt`, { copy: 'DUPLICATE', openDrawer: false })
      onToast({ tone: 'good', text: `Reprinted ${lastBill.invoiceNumber} as a duplicate` })
    } catch (failure) {
      onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not reprint') })
    }
  }

  const askHold = () => {
    if (modeRef.current.kind !== 'sale') {
      onToast({ tone: 'warn', text: 'Finish or clear the edit or return first' })
      return
    }
    const cart = commitEdit()
    if (cart && cart.length > 0) {
      setAsk({ action: 'hold', title: 'Hold this bill', label: 'Name it so you can find it again', initial: customer.name })
    }
  }

  const openHolds = async () => {
    if (modeRef.current.kind !== 'sale') {
      onToast({ tone: 'warn', text: 'Finish or clear the edit or return first' })
      return
    }
    try {
      setHolds(await api.get<HeldBill[]>('/api/holds'))
    } catch (failure) {
      onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not read held bills') })
    }
  }

  const holdCurrent = (label: string) => api.post<HeldBill>('/api/holds', {
    label: label.trim() || `Bill ${clockTime(new Date())}`,
    cart: { lines: linesRef.current.map(withoutKey), customer },
    estimatedTotalPaise: quote?.grandTotalPaise ?? 0,
  })

  /** Puts a held bill back on screen. A bill already on screen is held first, never thrown away. */
  const resumeHold = async (held: HeldBill) => {
    try {
      const swapped = linesRef.current.length > 0 ? await holdCurrent('') : null
      const resumed = await api.delete<HeldBill>(`/api/holds/${held.id}`)
      replaceLines((resumed.cart.lines ?? []).map((line) => ({ ...line, key: nextKey.current++ })))
      setCustomer(resumed.cart.customer ?? NO_CUSTOMER)
      setInvalid(null)
      setError(null)
      setHolds(null)
      requestFocus(resumed.cart.lines?.length ?? 0, 'code')
      onToast({
        tone: 'good',
        text: swapped
          ? `Resumed ${held.label}. The bill that was on screen is held as ${swapped.label}`
          : `Resumed ${held.label}`,
      })
    } catch (failure) {
      onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not resume that bill') })
    }
  }

  /** Throws a held bill away. It was never a sale, so nothing is cancelled or put back in stock. */
  const deleteHold = async (held: HeldBill) => {
    try {
      await api.delete<HeldBill>(`/api/holds/${held.id}`)
      setHolds((current) => current?.filter((other) => other.id !== held.id) ?? null)
      onToast({ tone: 'good', text: `Deleted held bill ${held.label}` })
    } catch (failure) {
      onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not delete that held bill') })
    }
  }

  const closeHolds = () => {
    setHolds(null)
    requestFocus(linesRef.current.length, 'code')
  }

  const runAsk = async (value: string) => {
    const pending = ask
    setAsk(null)
    if (pending?.action === 'hold') {
      try {
        await holdCurrent(value)
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
    } else if (pending?.action === 'edit' || pending?.action === 'return') {
      try {
        if (pending.action === 'return' && value.trim() === '') {
          await startReturn(null)
        } else if (value.trim() === '') {
          onToast({ tone: 'warn', text: 'Type the number of the bill to edit' })
        } else {
          const invoice = await findBill(value)
          await (pending.action === 'edit' ? startEdit(invoice) : startReturn(invoice))
        }
      } catch (failure) {
        onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not find that bill') })
      }
      return
    }
    focusEntry()
  }

  const askEdit = () => {
    if (!canManage) {
      onToast({ tone: 'warn', text: 'Only a manager can edit a bill' })
      return
    }
    setAsk({
      action: 'edit',
      title: 'Edit a bill',
      label: "Bill number, or just its serial (today's bills only)",
      initial: lastBill?.invoiceNumber ?? '',
    })
  }

  const askReturn = () => {
    if (!canManage) {
      onToast({ tone: 'warn', text: 'Only a manager can take a return' })
      return
    }
    setAsk({
      action: 'return',
      title: 'Return',
      label: 'Bill number or serial the goods were sold on. Leave empty for a return without the bill',
      initial: '',
    })
  }

  const handlers: Record<ShortcutAction, () => void> = {
    takeBill: () => void takeBill(),
    focusSearch: () => move(linesRef.current.length, 'code'),
    setQuantity: () => {
      if (linesRef.current[edit.row]) {
        move(edit.row, 'qty')
      }
    },
    setDiscount: () => {
      if (linesRef.current[edit.row]) {
        move(edit.row, 'disc')
      }
    },
    removeLine: () => removeRow(edit.row),
    holdBill: askHold,
    resumeBill: () => void openHolds(),
    openDrawer: () => setAsk({ action: 'drawer', title: 'Open drawer', label: 'Reason', initial: '' }),
    reprintLast: () => void reprintLast(),
    customer: () => setEditingCustomer(true),
    editBill: askEdit,
    returnBill: askReturn,
    clearBill: () => {
      if (linesRef.current.length > 0 || modeRef.current.kind !== 'sale') {
        setConfirmClear(true)
      }
    },
  }
  useShortcuts(shortcuts, handlers, inFront && !modalOpen)

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
        } else if (againstBill) {
          requestFocus(Math.min(row + 1, committed.length - 1), 'qty')
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
    // A return against a bill takes the items and prices from the bill; only the quantity changes.
    const readOnly = againstBill && col !== 'qty'
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
        readOnly={readOnly}
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
          ${col === 'name' ? 'font-medium uppercase' : ''} ${col === 'code' ? 'uppercase' : ''} ${readOnly ? 'cursor-default' : ''}
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
    <div className="flex h-full min-h-0 flex-col gap-2">
      {/* Bill type, customer and last bill ride in the app header, leaving the height to the bill. */}
      {active && headerSlot && createPortal(
        <>
          {mode.kind === 'edit' ? (
            <span className="rounded-full bg-amber-400 px-2.5 py-0.5 text-xs font-semibold text-amber-950">
              Editing bill {mode.invoice.invoiceNumber}
            </span>
          ) : mode.kind === 'return' ? (
            <span className="rounded-full bg-rose-500 px-2.5 py-0.5 text-xs font-semibold text-white">
              {mode.invoice ? `Return on bill ${mode.invoice.invoiceNumber}` : 'Return without a bill'}
            </span>
          ) : (
            <span className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${
              gst ? 'bg-sky-500/20 text-sky-200' : 'bg-slate-700 text-slate-200'}`}>
              {gst ? 'GST tax invoice' : 'Bill without GST'}
            </span>
          )}
          <button
            type="button"
            tabIndex={-1}
            onMouseDown={(event) => event.preventDefault()}
            onClick={() => setEditingCustomer(true)}
            className="flex min-w-0 items-center gap-2 rounded-md px-2 py-1 text-sm hover:bg-slate-800"
          >
            <span className="text-slate-400">Customer</span>
            <span className="truncate font-medium text-white">{customer.name || 'Walk-in'}</span>
            {customer.gstin && <span className="num hidden text-xs text-slate-400 xl:inline">{customer.gstin}</span>}
            <kbd>{shortcuts.customer}</kbd>
          </button>
          <div className="ml-auto flex items-center gap-4 text-sm text-slate-400">
            <span>Last bill <span className="num font-medium text-white">{lastBill?.invoiceNumber ?? '—'}</span></span>
            <span className="hidden lg:inline">
              Last amount <span className="num font-medium text-white">{lastBill ? rupees(lastBill.grandTotalPaise) : '—'}</span>
              {lastBill && lastBill.changePaise > 0 && (
                <span className="num ml-2 text-emerald-300">change {rupees(lastBill.changePaise)}</span>
              )}
            </span>
          </div>
        </>,
        headerSlot,
      )}

      {notReady && (
        <Banner tone="warn">
          Billing is blocked until the shop's {settings?.missingForInvoicing.join(', ')} are filled in on the
          Settings screen.
        </Banner>
      )}
      {error && <Banner tone="bad" onDismiss={() => setError(null)}>{error}</Banner>}
      {mode.kind === 'edit' && (
        <Banner tone="warn">
          <span className="font-semibold">Editing bill {mode.invoice.invoiceNumber}</span>
          {' '}(paid {rupees(mode.invoice.grandTotalPaise)}). Change, add or delete rows, then press
          {' '}<kbd>{shortcuts.takeBill}</kbd> to save: that bill is cancelled, a new bill is printed in its place,
          and only the difference is collected or given back. <kbd>{shortcuts.clearBill}</kbd> stops editing.
        </Banner>
      )}
      {mode.kind === 'return' && (
        <Banner tone="bad">
          <span className="font-semibold">
            {mode.invoice ? `Return on bill ${mode.invoice.invoiceNumber}.` : 'Return without a bill.'}
          </span>
          {' '}
          {mode.invoice
            ? 'Delete the rows not coming back and lower the quantities.'
            : 'Enter the items coming back and the rate paid.'}
          {' '}Press <kbd>{shortcuts.takeBill}</kbd> to refund: the stock goes back on the shelf.
          {' '}<kbd>{shortcuts.clearBill}</kbd> cancels the return.
        </Banner>
      )}

      {/* The bill */}
      <section className="min-h-64 flex-1 overflow-auto rounded-lg border border-slate-200 bg-white shadow-sm">
        <table className="w-full min-w-[46rem] table-fixed border-collapse text-sm">
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
                      {line.maxMilli !== undefined && (
                        <span className="num whitespace-nowrap pr-1 text-xs text-slate-400">of {quantity(line.maxMilli)}</span>
                      )}
                    </div>
                  </td>
                  <td className="px-1 py-0.5">{cellInput(index, 'disc', { align: 'right' })}</td>
                  <td className="num px-3 text-right font-semibold text-slate-900">
                    {priced ? rupees(priced.lineTotalPaise) : line.unitPricePaise > 0 ? '…' : ''}
                  </td>
                </tr>
              )
            })}
            <tr hidden={againstBill} className={`border-b border-emerald-100 ${edit.row === blankRow ? 'bg-emerald-50' : 'bg-emerald-50/40'}`}>
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
        {lines.length === 0 && mode.kind === 'sale' && (
          <p className="px-4 py-6 text-center text-sm text-slate-400">
            Type an item code or name in the green row and press <kbd>Enter</kbd>. <kbd>↓</kbd> in an empty
            cell lists every item; <kbd>←</kbd> <kbd>→</kbd> <kbd>↑</kbd> <kbd>↓</kbd> move between cells.
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

      {/* Shortcut list (clickable) · items and quantity · total · take bill */}
      <div className="flex shrink-0 flex-wrap items-stretch gap-2">
        {showShortcuts && (
          <section className="min-w-[18rem] flex-1 rounded-lg border border-slate-200 bg-white px-1.5 py-1 shadow-sm">
            <ul className="grid grid-cols-[repeat(auto-fill,minmax(9.5rem,1fr))] gap-x-1 text-[11px] leading-4 text-slate-600 [&_kbd]:py-0 [&_kbd]:text-[10px] [&_kbd]:leading-[14px]">
              {(Object.keys(SHORTCUT_LABELS) as ShortcutAction[]).map((action) => (
                <li key={action}>
                  <button
                    type="button"
                    tabIndex={-1}
                    // Keeps the cursor in its cell, so a click works like the key.
                    onMouseDown={(event) => event.preventDefault()}
                    onClick={handlers[action]}
                    className="flex w-full items-center justify-between gap-1 rounded px-1 py-px text-left hover:bg-sky-50 hover:text-sky-800"
                  >
                    <span className="truncate">{SHORTCUT_LABELS[action]}</span>
                    <kbd>{shortcuts[action]}</kbd>
                  </button>
                </li>
              ))}
            </ul>
          </section>
        )}

        <section className={`flex items-stretch gap-2 rounded-lg border border-slate-200 bg-white px-2 py-1 shadow-sm ${
          showShortcuts ? 'w-[30rem] max-w-full' : 'ml-auto w-full max-w-[36rem]'}`}>
          <dl className="grid shrink-0 grid-cols-[auto_auto] content-center gap-x-2 text-xs leading-4">
            <dt className="text-slate-500">Items</dt>
            <dd className="num text-right font-semibold text-slate-800">{lines.length}</dd>
            <dt className="text-slate-500">Qty</dt>
            <dd className="num text-right font-semibold text-slate-800">{quantity(totalQuantity)}</dd>
          </dl>
          <div className={`flex min-w-0 flex-1 items-center justify-between gap-2 rounded-md px-2.5 py-1 text-white ${
            mode.kind === 'return' ? 'bg-rose-700' : mode.kind === 'edit' ? 'bg-amber-800' : 'bg-slate-900'}`}>
            <div className="min-w-0 leading-4">
              <div className="text-xs text-white/70">
                {mode.kind === 'return' ? 'Refund' : mode.kind === 'edit' ? 'New total' : 'Total'}
              </div>
              {mode.kind === 'edit' ? (
                <EditDifference paid={mode.invoice.grandTotalPaise} total={quote?.grandTotalPaise ?? 0} />
              ) : (
                <div className="num truncate text-[11px] text-white/55">
                  {[
                    gst && quote ? `Taxable ${rupees(quote.taxableValuePaise)}` : '',
                    gst && quote ? (quote.interState ? `IGST ${rupees(quote.igstPaise)}`
                      : `CGST ${rupees(quote.cgstPaise)} · SGST ${rupees(quote.sgstPaise)}`) : '',
                    gst && quote?.cessPaise ? `Cess ${rupees(quote.cessPaise)}` : '',
                    quote?.roundOffPaise ? `Round off ${rupees(quote.roundOffPaise)}` : '',
                  ].filter(Boolean).join(' · ')}
                </div>
              )}
            </div>
            <span className="num shrink-0 text-2xl font-bold leading-none">{rupees(quote?.grandTotalPaise ?? 0)}</span>
          </div>
          <Button
            tone="primary"
            className="shrink-0 justify-between px-3"
            hint={shortcuts.takeBill}
            disabled={lines.length === 0 || notReady || saving}
            onClick={() => void takeBill()}
          >
            {saving ? 'Saving…' : mode.kind === 'edit' ? 'Save edit' : mode.kind === 'return' ? 'Refund' : 'Take bill'}
          </Button>
        </section>
      </div>

      {ask && (
        <AskModal title={ask.title} label={ask.label} initial={ask.initial}
                  onSubmit={runAsk} onClose={() => { setAsk(null); focusEntry() }} />
      )}

      {payment && (
        <PaymentDialog
          due={payment.due}
          title={payment.purpose === 'edit' ? 'Collect the difference' : 'Payment'}
          confirmLabel={payment.purpose === 'edit' ? 'Save edit' : 'Take bill'}
          askCashReceived={askCashReceived}
          onClose={() => { setPayment(null); focusEntry() }}
          onConfirm={(tenders) => void completePayment(tenders)}
        />
      )}

      {settling && (
        <RefundDialog
          kind={settling.kind}
          due={settling.due}
          total={settling.total}
          defaultMode={mode.kind !== 'sale' && mode.invoice ? mode.invoice.payments[0]?.mode ?? 'CASH' : 'CASH'}
          number={mode.kind !== 'sale' ? mode.invoice?.invoiceNumber ?? null : null}
          saving={saving}
          onClose={() => { setSettling(null); focusEntry() }}
          onConfirm={(refund, reason) => void completeSettling(refund, reason)}
        />
      )}

      {editingCustomer && (
        <CustomerDialog
          customer={customer}
          gst={gst}
          onClose={() => { setEditingCustomer(false); focusEntry() }}
          onSave={(saved) => {
            setCustomer(saved)
            setEditingCustomer(false)
            focusEntry()
          }}
        />
      )}

      {confirmClear && (
        <Modal
          title={mode.kind === 'edit' ? `Stop editing ${mode.invoice.invoiceNumber}?`
            : mode.kind === 'return' ? 'Cancel this return?' : 'Clear this bill?'}
          onClose={() => { setConfirmClear(false); focusEntry() }}
        >
          <p className="text-sm text-slate-600">
            {mode.kind === 'edit'
              ? `Your changes are dropped and bill ${mode.invoice.invoiceNumber} stays exactly as it was issued.`
              : mode.kind === 'return'
                ? 'Nothing is refunded and no stock comes back.'
                : `${lines.length} item${lines.length === 1 ? '' : 's'} will be taken off the screen. Nothing is billed.`}
          </p>
          <div className="mt-4 flex justify-end gap-2">
            <Button onClick={() => { setConfirmClear(false); focusEntry() }} hint="Esc">
              {mode.kind === 'sale' ? 'Keep it' : 'Keep going'}
            </Button>
            <AutoFocusButton tone="danger" onClick={clearBill}>
              {mode.kind === 'edit' ? 'Stop editing' : mode.kind === 'return' ? 'Cancel return' : 'Clear bill'}
            </AutoFocusButton>
          </div>
        </Modal>
      )}

      {holds !== null && (
        <HeldBillsDialog holds={holds} onClose={closeHolds} onResume={resumeHold} onDelete={deleteHold} />
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

/**
 * Held bills of this till. Up and down pick one, Enter resumes it, Delete throws it away. Takes the
 * focus when it opens, so those keys never reach the bill behind it.
 */
function HeldBillsDialog({ holds, onClose, onResume, onDelete }: {
  holds: HeldBill[]
  onClose: () => void
  onResume: (held: HeldBill) => Promise<void>
  onDelete: (held: HeldBill) => Promise<void>
}) {
  const [highlight, setHighlight] = useState(0)
  const list = useRef<HTMLUListElement>(null)
  const rows = useRef(new Map<number, HTMLLIElement>())
  const working = useRef(false)
  const at = Math.min(highlight, Math.max(holds.length - 1, 0))

  useEffect(() => {
    list.current?.focus()
  }, [])

  useEffect(() => {
    rows.current.get(at)?.scrollIntoView({ block: 'nearest' })
  }, [at])

  const run = async (action: (held: HeldBill) => Promise<void>, held: HeldBill | undefined) => {
    if (!held || working.current) {
      return
    }
    working.current = true
    try {
      await action(held)
    } finally {
      working.current = false
      list.current?.focus()
    }
  }

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'ArrowDown') {
        event.preventDefault()
        setHighlight(Math.min(at + 1, holds.length - 1))
      } else if (event.key === 'ArrowUp') {
        event.preventDefault()
        setHighlight(Math.max(at - 1, 0))
      } else if (event.key === 'Enter') {
        event.preventDefault()
        if (!event.repeat) {
          void run(onResume, holds[at])
        }
      } else if (event.key === 'Delete') {
        event.preventDefault()
        if (!event.repeat) {
          void run(onDelete, holds[at])
        }
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  return (
    <Modal title="Held bills" onClose={onClose} wide>
      {holds.length === 0 ? (
        <Empty>No bills are held on this till</Empty>
      ) : (
        <>
          <ul ref={list} tabIndex={-1} className="max-h-[60vh] divide-y divide-slate-100 overflow-auto outline-none">
            {holds.map((held, index) => (
              <li
                key={held.id}
                ref={(element) => {
                  if (element) {
                    rows.current.set(index, element)
                  } else {
                    rows.current.delete(index)
                  }
                }}
                onMouseEnter={() => setHighlight(index)}
                className={`flex items-center justify-between gap-4 rounded-md px-3 py-2 ${
                  index === at ? 'bg-sky-600 text-white' : ''}`}
              >
                <div className="min-w-0">
                  <div className="truncate font-medium">{held.label}</div>
                  <div className={`text-xs ${index === at ? 'text-sky-100' : 'text-slate-400'}`}>
                    {held.cashier} · {new Date(held.heldAt).toLocaleTimeString()} · {held.cart.lines?.length ?? 0}
                    {held.cart.lines?.length === 1 ? ' item' : ' items'}
                  </div>
                </div>
                <div className="flex shrink-0 items-center gap-2">
                  <span className="num mr-2">{rupees(held.estimatedTotalPaise)}</span>
                  <button
                    type="button"
                    tabIndex={-1}
                    onClick={() => void run(onResume, held)}
                    className={`rounded-md px-3 py-1.5 text-sm font-medium ${
                      index === at ? 'bg-white text-sky-700' : 'bg-sky-600 text-white hover:bg-sky-500'}`}
                  >
                    Resume
                  </button>
                  <button
                    type="button"
                    tabIndex={-1}
                    aria-label={`Delete held bill ${held.label}`}
                    title="Delete (Delete key)"
                    onClick={() => void run(onDelete, held)}
                    className={`rounded-md p-1.5 ${index === at ? 'text-white hover:bg-sky-500' : 'text-rose-500 hover:bg-rose-50'}`}
                  >
                    <TrashIcon />
                  </button>
                </div>
              </li>
            ))}
          </ul>
          <p className="mt-3 text-xs text-slate-400">
            <kbd>↑</kbd> <kbd>↓</kbd> choose · <kbd>Enter</kbd> resume · <kbd>Delete</kbd> delete · <kbd>Esc</kbd> close
          </p>
        </>
      )}
    </Modal>
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
      onKeyDown={(event) => {
        if (event.key === 'Enter') {
          event.preventDefault()
          onClick()
        }
      }}
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

/** Under the new total of an edited bill: what was paid, and what changes hands now. */
function EditDifference({ paid, total }: { paid: number; total: number }) {
  const difference = total - paid
  return (
    <div className="num flex gap-x-2 text-[11px] font-medium leading-4">
      <span className="text-white/70">Paid {rupees(paid)}</span>
      {difference > 0 && <span className="text-amber-200">Collect {rupees(difference)}</span>}
      {difference < 0 && <span className="text-emerald-200">Give back {rupees(-difference)}</span>}
      {difference === 0 && <span className="text-white/70">No difference</span>}
    </div>
  )
}

/**
 * Money going back: the refund of a return (choose how), or confirming an edit that costs the same or
 * less, where the difference goes back the way the bill was paid.
 */
function RefundDialog({ kind, due, total, defaultMode, number, saving, onClose, onConfirm }: {
  kind: 'return' | 'edit'
  due: number
  total: number
  defaultMode: PaymentMode
  number: string | null
  saving: boolean
  onClose: () => void
  onConfirm: (refund: Refund, reason: string) => void
}) {
  const [mode, setMode] = useState<PaymentMode>(REFUND_MODES.includes(defaultMode) ? defaultMode : 'CASH')
  const [reference, setReference] = useState('')
  const [reason, setReason] = useState('')
  const submitted = useRef(false)
  const confirm = useRef<HTMLButtonElement>(null)

  useEffect(() => confirm.current?.focus(), [])

  const submit = () => {
    if (submitted.current || saving) {
      return
    }
    submitted.current = true
    onConfirm({ mode, amountPaise: due, reference: reference.trim() || undefined }, reason)
  }

  return (
    <Modal title={kind === 'edit' ? `Save the edit of ${number ?? 'the bill'}?` : 'Refund'} onClose={onClose}>
      <form
        className="space-y-3"
        onSubmit={(event) => {
          event.preventDefault()
          submit()
        }}
        // Enter confirms from anywhere in the dialog, on every keyboard, not only via a focused button.
        // Up and down change how the refund goes back.
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault()
            if (!event.repeat) {
              submit()
            }
          } else if (kind === 'return' && (event.key === 'ArrowDown' || event.key === 'ArrowUp')) {
            event.preventDefault()
            setMode(stepMode(REFUND_MODES, mode, event.key))
          }
        }}
      >
        <div className={`flex items-baseline justify-between rounded-md px-4 py-3 text-white ${
          kind === 'edit' ? 'bg-amber-800' : 'bg-rose-700'}`}>
          <span className="text-sm text-white/80">{due > 0 ? 'Give back' : 'Nothing to give back'}</span>
          <span className="num text-3xl font-semibold">{rupees(due)}</span>
        </div>

        {kind === 'edit' ? (
          <p className="text-sm text-slate-600">
            Bill {number} is cancelled and a new bill for {rupees(total)} is printed in its place. Its stock comes
            back and the new items go out.
            {due > 0 && ' The difference goes back the way the bill was paid.'}
          </p>
        ) : (
          <>
            {number && <p className="text-sm text-slate-600">Against bill {number}. The stock goes back on the shelf.</p>}
            <div className="flex gap-4">
              <ModeList modes={REFUND_MODES} value={mode} tone="rose" onChange={setMode} />
              <div className="min-w-0 flex-1 space-y-3">
                {mode !== 'CASH' && (
                  <Field label="Reference" optional hint="UPI reference or card reversal code">
                    <input className={inputClass} value={reference} onChange={(event) => setReference(event.target.value)} />
                  </Field>
                )}
                <Field label="Reason" optional hint="Printed on the return slip">
                  <input className={inputClass} maxLength={200} value={reason}
                         onChange={(event) => setReason(event.target.value)} />
                </Field>
              </div>
            </div>
          </>
        )}

        <div className="flex justify-end gap-2 pt-1">
          <Button onClick={onClose} hint="Esc">Back</Button>
          <button
            ref={confirm}
            type="submit"
            disabled={saving}
            className={`inline-flex items-center gap-2 rounded-md px-3 py-2 text-sm font-medium text-white disabled:opacity-50 ${
              kind === 'edit' ? 'bg-amber-700 hover:bg-amber-600' : 'bg-rose-600 hover:bg-rose-500'}`}
          >
            {saving ? 'Saving…' : kind === 'edit' ? 'Save edit' : `Refund ${rupees(due)}`}
            <kbd>Enter</kbd>
          </button>
        </div>
      </form>
    </Modal>
  )
}

const MODES: PaymentMode[] = ['CASH', 'UPI', 'CARD', 'VOUCHER']
const REFUND_MODES: PaymentMode[] = ['CASH', 'UPI', 'CARD']
const MODE_LABELS: Record<PaymentMode, string> = {
  CASH: 'Cash', UPI: 'UPI', CARD: 'Card', VOUCHER: 'Voucher', ON_ACCOUNT: 'On account',
}
const QUICK_NOTES = [10000, 20000, 50000, 100000, 200000]

/** The mode above or below, stopping at the ends of the list. */
function stepMode(modes: PaymentMode[], current: PaymentMode, key: 'ArrowUp' | 'ArrowDown'): PaymentMode {
  const at = Math.max(0, modes.indexOf(current))
  return modes[Math.max(0, Math.min(modes.length - 1, at + (key === 'ArrowDown' ? 1 : -1)))]
}

/** Payment modes stacked down the left of a dialog. The dialog moves through them with up and down. */
function ModeList({ modes, value, tone, onChange }: {
  modes: PaymentMode[]
  value: PaymentMode
  tone: 'sky' | 'rose'
  onChange: (mode: PaymentMode) => void
}) {
  const chosen = tone === 'rose' ? 'border-rose-600 bg-rose-600 text-white' : 'border-sky-600 bg-sky-600 text-white'
  return (
    <div className="flex w-32 shrink-0 flex-col gap-1.5">
      <ul role="listbox" aria-label="Payment mode" className="flex flex-col gap-1.5">
        {modes.map((option) => (
          <li key={option}>
            <button
              type="button"
              role="option"
              aria-selected={value === option}
              tabIndex={-1}
              onClick={() => onChange(option)}
              className={`flex w-full items-center justify-between rounded-md border px-3 py-2.5 text-left text-sm font-medium transition ${
                value === option ? chosen : 'border-slate-200 text-slate-600 hover:bg-slate-50'}`}
            >
              {MODE_LABELS[option]}
              {value === option && <span aria-hidden="true">›</span>}
            </button>
          </li>
        ))}
      </ul>
      <p className="mt-1 text-center text-xs text-slate-400"><kbd>↑</kbd> <kbd>↓</kbd> change</p>
    </div>
  )
}

/**
 * Cash, UPI, card, voucher, or any mix of them; the parts must add up to the bill exactly. The modes
 * run down the left and up and down move between them from anywhere in the dialog. Enter (or Space
 * in the amount) takes the bill.
 *
 * @param askCashReceived a cash bill needs the amount handed over typed in; otherwise an empty
 *                        amount means the exact total
 */
function PaymentDialog({ due, title = 'Payment', confirmLabel = 'Take bill', askCashReceived, onClose, onConfirm }: {
  due: number
  title?: string
  confirmLabel?: string
  askCashReceived: boolean
  onClose: () => void
  onConfirm: (tenders: Tender[]) => void
}) {
  const [mode, setMode] = useState<PaymentMode>('CASH')
  const [tenderedText, setTenderedText] = useState('')
  const [reference, setReference] = useState('')
  const [parts, setParts] = useState<Tender[]>([])
  const [prompt, setPrompt] = useState<string | null>(null)
  const submitted = useRef(false)
  const amountInput = useRef<HTMLInputElement>(null)

  const paid = parts.reduce((sum, part) => sum + part.amountPaise, 0)
  const remaining = due - paid
  const tendered = tenderedText ? paiseFromRupees(tenderedText) : remaining
  const change = mode === 'CASH' ? Math.max(0, tendered - remaining) : 0

  // The amount box takes the cursor whenever the mode changes or a part is paid.
  useEffect(() => {
    amountInput.current?.focus()
    amountInput.current?.select()
  }, [mode, parts.length])

  const choose = (next: PaymentMode) => {
    if (next === mode) {
      return
    }
    setMode(next)
    setTenderedText('')
    setReference('')
    setPrompt(null)
  }

  const settle = () => {
    if (submitted.current) {
      return
    }
    if (mode === 'CASH' && askCashReceived && tenderedText.trim() === '') {
      setPrompt('is needed: type what the customer hands over')
      amountInput.current?.focus()
      return
    }
    const amount = Math.min(remaining, mode === 'CASH' ? Math.max(tendered, 0) : tendered || remaining)
    if (amount <= 0) {
      setPrompt('must be more than zero')
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
      setPrompt(null)
    }
  }

  /** Space in an amount box settles, as it opened this screen from the billing grid. */
  const spaceSettles = (event: ReactKeyboardEvent<HTMLInputElement>) => {
    if (event.key === ' ') {
      event.preventDefault()
      if (!event.repeat) {
        settle()
      }
    }
  }

  const amountClass = `${inputClass} num text-lg ${prompt ? 'border-rose-500 ring-2 ring-rose-200 focus:border-rose-500 focus:ring-rose-200' : ''}`

  return (
    <Modal title={title} onClose={onClose} size="xl">
      <form
        className="flex gap-4"
        onSubmit={(event) => {
          event.preventDefault()
          settle()
        }}
        onKeyDown={(event) => {
          if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault()
            choose(stepMode(MODES, mode, event.key))
          } else if (event.key === 'Enter') {
            // Handled here so Enter works on every keyboard: a button under the cursor does its
            // own job (a note, Exact, Cancel), anywhere else Enter takes the bill.
            event.preventDefault()
            if (event.repeat) {
              return
            }
            if (event.target instanceof HTMLButtonElement && event.target.type === 'button') {
              event.target.click()
            } else {
              settle()
            }
          }
        }}
      >
        <ModeList modes={MODES} value={mode} tone="sky" onChange={choose} />

        <div className="min-w-0 flex-1 space-y-3">
          <div className="flex items-baseline justify-between rounded-md bg-slate-900 px-4 py-3 text-white">
            <span className="text-sm text-slate-300">{parts.length ? 'Still to pay' : 'Bill total'}</span>
            <span className="num text-3xl font-semibold">{rupees(remaining)}</span>
          </div>

          {parts.length > 0 && (
            <ul className="space-y-1 text-sm">
              {parts.map((part, index) => (
                <li key={index} className="flex justify-between text-slate-600">
                  <span>{MODE_LABELS[part.mode]}</span>
                  <span className="num">{rupees(part.amountPaise)}</span>
                </li>
              ))}
            </ul>
          )}

          {mode === 'CASH' ? (
            <>
              <Field
                label="Cash received"
                error={prompt ?? undefined}
                hint={askCashReceived ? 'Type what the customer hands over' : 'Leave empty for the exact amount'}
              >
                <input
                  ref={amountInput}
                  className={amountClass}
                  inputMode="decimal"
                  autoComplete="off"
                  value={tenderedText}
                  onKeyDown={spaceSettles}
                  onChange={(event) => {
                    setTenderedText(event.target.value)
                    setPrompt(null)
                  }}
                />
              </Field>
              <div className="flex flex-wrap gap-2">
                {QUICK_NOTES.filter((note) => note >= remaining).slice(0, 4).map((note) => (
                  <Button key={note} onClick={() => { setTenderedText((note / 100).toFixed(0)); setPrompt(null) }}>
                    {rupees(note)}
                  </Button>
                ))}
                <Button onClick={() => { setTenderedText((remaining / 100).toFixed(2)); setPrompt(null) }}>Exact</Button>
              </div>
              <div className="flex items-baseline justify-between rounded-md bg-emerald-50 px-3 py-2">
                <span className="text-sm text-emerald-800">Change to give</span>
                <span className="num text-xl font-semibold text-emerald-900">{rupees(change)}</span>
              </div>
            </>
          ) : (
            <>
              <Field label={`${MODE_LABELS[mode]} amount`} error={prompt ?? undefined}
                     hint="Leave empty for the full amount; less splits the bill">
                <input
                  ref={amountInput}
                  className={amountClass}
                  inputMode="decimal"
                  autoComplete="off"
                  value={tenderedText}
                  onKeyDown={spaceSettles}
                  onChange={(event) => {
                    setTenderedText(event.target.value)
                    setPrompt(null)
                  }}
                />
              </Field>
              <Field label="Reference" optional
                     hint={mode === 'VOUCHER' ? 'Voucher number' : 'UPI reference or card approval code'}>
                <input className={inputClass} autoComplete="off" value={reference}
                       onChange={(event) => setReference(event.target.value)} />
              </Field>
            </>
          )}

          <div className="flex justify-end gap-2 pt-1">
            <Button onClick={onClose} hint="Esc">Cancel</Button>
            <Button type="submit" tone="primary" hint="Enter">
              {tendered >= remaining || !tenderedText ? confirmLabel : 'Add part payment'}
            </Button>
          </div>
        </div>
      </form>
    </Modal>
  )
}
