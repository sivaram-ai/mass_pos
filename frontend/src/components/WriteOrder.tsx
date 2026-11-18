import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import {
  api, billDate, clockTime, fieldErrorsOf, messageOfFailure, milliFromUnits, paiseFromRupees, quantity,
  rupees, rupeesForInput,
} from '../api'
import type {
  CanGoBack, HeldOrder, OrderLineDraft, PaidPrice, Product, PurchaseOrder, PurchaseReturn, RawMaterial,
  Unit, Vendor, VendorPaymentMode,
} from '../types'
import { WHOLE_UNITS } from '../types'
import { AskModal, Banner, Button, Empty, Field, inputClass, Modal, smallInputClass } from './ui'
import { loadShortcuts, useGridKeys, useShortcuts } from '../shortcuts'
import type { ShortcutAction } from '../shortcuts'
import { forgetOrderDraft, saveOrderDraft, savedOrderDraft } from '../orderDraft'
import type { Toast } from '../App'

const ONE_UNIT = 1000
/** How many matches the item box offers at once. The same cap the billing grid uses. */
const MOST = 12

/** Anything that can go on an order, from either of the shop's two lists. */
export type Orderable = {
  kind: 'p' | 'r'
  id: string
  code: string
  name: string
  unit: Unit
}

/** The editable columns of an order row, left to right. The amount is worked out. */
type Col = 'code' | 'name' | 'rate' | 'qty'
const COLS: Col[] = ['code', 'name', 'rate', 'qty']
const SEARCH_COLS: Col[] = ['code', 'name']
const isSearchCol = (col: Col) => SEARCH_COLS.includes(col)

type Row = {
  key: number
  productId: string | null
  rawMaterialId: string | null
  /** True for something the shop does not keep: a name on the order and nothing else. */
  oneOff: boolean
  code: string
  name: string
  unit: Unit
  quantityMilli: number
  unitCostPaise: number
  /** Sending goods back: how much of this line can still go, so nothing goes back twice. */
  maxMilli?: number
}

type Cell = { row: number; col: Col }
type Edit = Cell & { text: string; dirty: boolean }
type Found = Cell & { text: string; items: Orderable[] }

/** Writing an order, changing one, or sending goods back on one. */
type Mode =
  | { kind: 'order' }
  | { kind: 'edit'; order: PurchaseOrder }
  | { kind: 'return'; against: PurchaseOrder | null }

type Ask = { action: 'hold' | 'return'; title: string; label: string; initial: string }

const cellId = (row: number, col: Col) => `${row}:${col}`

/** Focus without selecting: the cursor goes after what is there, ready to be corrected. */
function focusEnd(box: HTMLInputElement | null) {
  if (!box) {
    return
  }
  box.focus()
  box.setSelectionRange(box.value.length, box.value.length)
}

/**
 * Ranked the way the billing grid ranks items, because it is the same hand doing the same thing:
 * the exact code first, then codes that start with what was typed, then names that start with it,
 * then anything that contains it.
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

function draftOf(row: Row): OrderLineDraft {
  return {
    productId: row.productId,
    rawMaterialId: row.rawMaterialId,
    itemName: row.oneOff ? row.name : '',
    itemCode: row.oneOff ? row.code : '',
    name: row.name,
    unit: row.unit,
    quantityMilli: row.quantityMilli,
    unitCostPaise: row.unitCostPaise,
  }
}

function rowOf(line: OrderLineDraft, key: number): Row {
  const oneOff = !line.productId && !line.rawMaterialId
  return {
    key,
    productId: line.productId,
    rawMaterialId: line.rawMaterialId,
    oneOff,
    code: oneOff ? line.itemCode : '',
    name: line.name,
    unit: line.unit,
    quantityMilli: line.quantityMilli,
    unitCostPaise: line.unitCostPaise,
  }
}

/** Money out, which is the only difference between this payment screen and the billing one. */
const MODES: VendorPaymentMode[] = ['CASH', 'UPI', 'CARD', 'BANK_TRANSFER']
const MODE_LABELS: Record<VendorPaymentMode, string> = {
  CASH: 'Cash', UPI: 'UPI', CARD: 'Card', BANK_TRANSFER: 'Bank account', CHEQUE: 'Cheque',
}
/** The modes the shop has to be able to find the money again by. */
const NEEDS_REFERENCE: VendorPaymentMode[] = ['UPI', 'CARD', 'BANK_TRANSFER', 'CHEQUE']

export type PaidNow = { mode: VendorPaymentMode; amountPaise: number; reference: string }

/**
 * Writing a purchase order, worked exactly like the billing screen - because it is the same hand
 * doing the same job in the other direction, and a manager who can take a bill should not have to
 * learn a second way to type a list of things.
 *
 * <p>The grid is the bill's grid: every row's code and name cells look an item up as you type,
 * the rate and quantity cells take numbers, the arrows move between cells, Enter moves on, and
 * the take-bill key writes the order. What is different is what a purchase is: the questions at
 * the top are the date, the vendor and a note rather than the table and the steward, the money
 * goes out rather than in, and anything the shop does not keep a count of can go on a line as a
 * name, because shops buy things that are not in any catalogue.
 *
 * <p>It is one component for two places: the whole screen on Purchase orders, and a dialog opened
 * from Items or Non billable items with whatever was ticked already on it. Nothing about the order
 * differs between them, so nothing about this does either.
 */
export function WriteOrder({ start = [], framed = false, active = true, blocked = false, startReturn,
                             startEdit, onToast, onClose, onWritten }: {
  /** Items the screen that opened this had already chosen. */
  start?: Orderable[]
  /** True inside a dialog: the grid scrolls in a box rather than filling the screen. */
  framed?: boolean
  active?: boolean
  blocked?: boolean
  /** Open straight into sending goods back on this order. */
  startReturn?: PurchaseOrder | null
  /** Open straight into changing this order, which writes a new one in its place. */
  startEdit?: PurchaseOrder | null
  onToast: Toast
  /** Back to wherever this was opened from, with nothing written. */
  onClose: () => void
  onWritten?: () => void
}) {
  const [restored, setRestored] = useState(false)
  const [lines, setLines] = useState<Row[]>([])
  const [vendors, setVendors] = useState<Vendor[]>([])
  const [catalogue, setCatalogue] = useState<Orderable[]>([])
  const [vendorId, setVendorId] = useState('')
  const [vendorText, setVendorText] = useState('')
  const [vendorOpen, setVendorOpen] = useState(false)
  const [vendorHighlight, setVendorHighlight] = useState(0)
  const [expectedOn, setExpectedOn] = useState('')
  const [note, setNote] = useState('')
  const [edit, setEdit] = useState<Edit>({ row: 0, col: 'code', text: '', dirty: false })
  const [found, setFound] = useState<Found | null>(null)
  const [dropdownOpen, setDropdownOpen] = useState(false)
  const [highlight, setHighlight] = useState(0)
  const [invalid, setInvalid] = useState<Cell | null>(null)
  const [focusRequest, setFocusRequest] = useState<(Cell & { at: number }) | null>(null)
  const [mode, setMode] = useState<Mode>({ kind: 'order' })
  const [paying, setPaying] = useState(false)
  const [sendingBack, setSendingBack] = useState(false)
  const [holds, setHolds] = useState<HeldOrder[] | null>(null)
  const [ask, setAsk] = useState<Ask | null>(null)
  const [confirmClear, setConfirmClear] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [shortcuts] = useState(loadShortcuts)

  const cells = useRef(new Map<string, HTMLInputElement>())
  const listItems = useRef(new Map<number, HTMLTableRowElement>())
  const nextKey = useRef(1)
  const linesRef = useRef(lines)
  linesRef.current = lines
  const editRef = useRef(edit)
  editRef.current = edit
  const modeRef = useRef(mode)
  modeRef.current = mode
  const vendorRef = useRef(vendorId)
  vendorRef.current = vendorId
  const vendorBox = useRef<HTMLInputElement>(null)
  const cancelButton = useRef<HTMLButtonElement>(null)
  const frame = useRef<HTMLDivElement>(null)
  const busy = useRef(false)

  const dialogOpen = paying || sendingBack || holds !== null || ask !== null || confirmClear
  const inFront = active && !blocked && !dialogOpen
  const blankRow = lines.length
  const returning = mode.kind === 'return'
  const editing = mode.kind === 'edit'
  /** Sending back what came in on an order: the items are the order's and only the amount moves. */
  const againstOrder = returning && mode.against !== null

  const typed = edit.dirty ? edit.text.trim() : ''
  const inSearchCell = isSearchCol(edit.col)
  const listFresh = found !== null && found.row === edit.row && found.col === edit.col && found.text === typed
  const listOpen = dropdownOpen && inSearchCell && listFresh && !dialogOpen

  const total = lines.reduce((sum, line) => sum + Math.round(line.quantityMilli * line.unitCostPaise / 1000), 0)
  const totalQuantity = lines.reduce((sum, line) => sum + line.quantityMilli, 0)
  const vendor = vendors.find((one) => one.id === vendorId) ?? null

  // ---- What can go on an order -----------------------------------------------------------------
  useEffect(() => {
    api.get<Vendor[]>('/api/vendors')
      .then((all) => setVendors(all.filter((one) => one.active)))
      .catch(() => setVendors([]))
    Promise.all([
      api.get<Product[]>('/api/products?all=true').catch(() => [] as Product[]),
      api.get<RawMaterial[]>('/api/raw-materials').catch(() => [] as RawMaterial[]),
    ]).then(([products, materials]) => setCatalogue([
      ...products.filter((one) => one.active).map<Orderable>((one) => ({
        kind: 'p', id: one.id, code: one.sku, name: one.name, unit: one.unit,
      })),
      ...materials.filter((one) => one.active).map<Orderable>((one) => ({
        kind: 'r', id: one.id, code: one.code, name: one.name, unit: one.unit,
      })),
    ]))
  }, [])

  // ---- Focus ------------------------------------------------------------------------------------
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

  const focusEntry = useCallback(() => {
    const count = linesRef.current.length
    if (modeRef.current.kind === 'return' && modeRef.current.against !== null) {
      requestFocus(Math.max(0, count - 1), 'qty')
    } else {
      requestFocus(count, 'code')
    }
  }, [requestFocus])

  /** Where an order starts: the vendor if nobody has chosen one, else the first empty row. */
  const focusStart = useCallback(() => {
    if (!vendorRef.current && modeRef.current.kind === 'order') {
      focusEnd(vendorBox.current)
    } else {
      focusEntry()
    }
  }, [focusEntry])

  const replaceLines = (updated: Row[]) => {
    linesRef.current = updated
    setLines(updated)
  }

  const refuse = (row: number, col: Col, message: string) => {
    setInvalid({ row, col })
    setError(message)
    const input = cells.current.get(cellId(row, col))
    if (document.activeElement !== input) {
      requestFocus(row, col)
    }
  }

  // ---- The order this machine had on screen ------------------------------------------------------
  useEffect(() => {
    let gone = false
    savedOrderDraft()
      .then((draft) => {
        if (gone || !draft || linesRef.current.length > 0) {
          return
        }
        replaceLines(draft.lines.map((line, at) => rowOf(line, at + 1)))
        nextKey.current = draft.lines.length + 1
        setVendorId(draft.vendorId)
        setVendorText(draft.vendorText)
        setExpectedOn(draft.expectedOn)
        setNote(draft.note)
      })
      .finally(() => {
        if (!gone) {
          setRestored(true)
        }
      })
    return () => {
      gone = true
    }
    // Once, as the screen opens. Everything it touches is read through a ref or a setter.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /** Whatever the screen that opened this had ticked goes on, once the draft is back. */
  useEffect(() => {
    if (!restored || start.length === 0) {
      return
    }
    const already = new Set(linesRef.current.map((line) => line.productId ?? line.rawMaterialId));
    const added = start
      .filter((item) => !already.has(item.id))
      .map<Row>((item) => ({
        key: nextKey.current++,
        productId: item.kind === 'p' ? item.id : null,
        rawMaterialId: item.kind === 'r' ? item.id : null,
        oneOff: false,
        code: item.code,
        name: item.name,
        unit: item.unit,
        // One of each to start with, as picking an item on a bill gives one of it: the quantity
        // is what the manager is about to type, and a zero would have to be cleared first.
        quantityMilli: ONE_UNIT,
        unitCostPaise: 0,
      }))
    if (added.length > 0) {
      replaceLines([...linesRef.current, ...added])
      added.forEach((row) => void fillInWhatItCostLastTime(row))
    }
    focusStart()
    // Only when the draft lands: start never changes while this is open.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [restored])

  /**
   * The order on screen is written back whenever it changes, so a reload, a walk to another
   * screen or a cable pulled out of a switch all come back to it. Not in return mode: that is a
   * document against an order the server already has, and half of one brought back out of
   * storage - against an order that may since have had goods go back on it - is worse than none.
   */
  useEffect(() => {
    if (!restored) {
      return
    }
    // Only a new order. An edit and a return are documents the server already has, and half of
    // one brought back out of storage - against an order that may since have been received - is
    // worse than none.
    if (mode.kind !== 'order' || lines.length === 0) {
      void forgetOrderDraft()
      return
    }
    void saveOrderDraft({ vendorId, vendorText, expectedOn, note, lines: lines.map(draftOf) })
  }, [restored, lines, vendorId, vendorText, expectedOn, note, mode])

  // The cursor starts where the order does, once the screen has its draft back. Not when this
  // was opened to change an order or to send goods back: those put it on the first row's amount
  // themselves, which is the only cell on them worth typing in.
  useEffect(() => {
    if (restored && start.length === 0 && modeRef.current.kind === 'order') {
      focusStart()
    }
    // Only when the draft lands.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [restored])

  // Opened straight into sending goods back, or into changing an order: the Purchase orders
  // screen asked for one of them.
  useEffect(() => {
    if (startReturn) {
      void openReturn(startReturn)
    } else if (startEdit) {
      openEdit(startEdit)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [startReturn, startEdit])

  useEffect(() => {
    listItems.current.get(highlight)?.scrollIntoView({ block: 'nearest' })
  }, [highlight, listOpen])

  /**
   * A vendor chosen before the list arrived - from an order being sent back, say - has an id and
   * no name to show for it, because there was nothing to look the name up in yet. The box is
   * filled in as soon as there is.
   */
  useEffect(() => {
    if (vendorId && !vendorText && vendors.length > 0) {
      showVendor(vendorId)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [vendors, vendorId])

  // ---- What it cost last time --------------------------------------------------------------------
  /**
   * An item's rate opens on what the shop paid for it last, because that is the number a manager
   * is about to type. Asked for one item at a time, as it is picked: the catalogue does not carry
   * it, and a price is a fact about a delivery rather than about an item.
   */
  const fillInWhatItCostLastTime = async (row: Row) => {
    const asking = row.rawMaterialId ? `rawMaterialId=${row.rawMaterialId}` : `productId=${row.productId}`
    try {
      const paid = await api.get<PaidPrice[]>(`/api/purchase-orders/prices?${asking}`)
      const last = paid.find((one) => one.unitCostPaise > 0)
      if (!last) {
        return
      }
      replaceLines(linesRef.current.map((line) => (line.key === row.key && line.unitCostPaise <= 0
        ? { ...line, unitCostPaise: last.unitCostPaise }
        : line)))
    } catch {
      // Never bought before, or the shop does not track what it pays. The rate is typed in.
    }
  }

  // ---- Cells ---------------------------------------------------------------------------------------
  /** Applies what was typed. Returns the order as it now stands, or null when a value is refused. */
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
    let change: Partial<Row>
    if (col === 'rate') {
      const paise = value === '' ? line.unitCostPaise : paiseFromRupees(value)
      if (paise < 0) {
        refuse(row, col, 'The cost cannot be negative')
        return null
      }
      change = { unitCostPaise: paise }
    } else {
      const milli = value === '' ? line.quantityMilli : milliFromUnits(value)
      if (milli <= 0) {
        refuse(row, col, `Enter how much of ${line.name}`)
        return null
      }
      if (WHOLE_UNITS.includes(line.unit) && milli % ONE_UNIT !== 0) {
        refuse(row, col, `${line.name} is counted in whole ${line.unit}, not ${value}`)
        return null
      }
      if (line.maxMilli !== undefined && milli > line.maxMilli) {
        refuse(row, col, `Only ${quantity(line.maxMilli)} of ${line.name} can go back`)
        return null
      }
      change = { quantityMilli: milli }
    }
    const updated = current.map((existing, at) => (at === row ? { ...existing, ...change } : existing))
    replaceLines(updated)
    settle()
    setInvalid(null)
    return updated
  }

  const move = (to: number, col: Col) => {
    const applied = commitEdit()
    if (!applied) {
      return
    }
    const lastRow = againstOrder ? Math.max(0, applied.length - 1) : applied.length
    const target = Math.max(0, Math.min(to, lastRow))
    let targetCol = col
    if (target === applied.length && !isSearchCol(targetCol)) {
      targetCol = 'code'
    }
    requestFocus(target, targetCol)
  }

  /** Puts an item on a row: a new row at the bottom, or in place of what is already there. */
  const pick = (row: number, item: Orderable) => {
    const current = linesRef.current
    let at = row
    let updated: Row[]
    const put: Omit<Row, 'key'> = {
      productId: item.kind === 'p' ? item.id : null,
      rawMaterialId: item.kind === 'r' ? item.id : null,
      oneOff: false,
      code: item.code,
      name: item.name,
      unit: item.unit,
      quantityMilli: ONE_UNIT,
      unitCostPaise: 0,
    }
    if (row >= current.length) {
      updated = [...current, { key: nextKey.current++, ...put }]
      at = updated.length - 1
    } else {
      updated = current.map((line, index) => (index === row
        ? { ...line, ...put, quantityMilli: line.quantityMilli || ONE_UNIT }
        : line))
    }
    replaceLines(updated)
    setError(null)
    setInvalid(null)
    void fillInWhatItCostLastTime(updated[at])
    requestFocus(at, 'rate')
  }

  /**
   * A line for something that is on neither list. Shops buy things that are in no catalogue - a
   * gas regulator, an hour of a plumber's time - and refusing those would mean inventing a
   * catalogue item to get the order written, which is how catalogues fill up with rubbish.
   */
  const putOneOff = (row: number, col: Col, text: string) => {
    const current = linesRef.current
    const put: Omit<Row, 'key'> = {
      productId: null,
      rawMaterialId: null,
      oneOff: true,
      code: col === 'code' ? text : '',
      name: text,
      unit: 'NOS',
      quantityMilli: ONE_UNIT,
      unitCostPaise: 0,
    }
    const updated = row >= current.length
      ? [...current, { key: nextKey.current++, ...put }]
      : current.map((line, index) => (index === row ? { ...line, ...put } : line))
    replaceLines(updated)
    setError(null)
    setInvalid(null)
    requestFocus(Math.min(row, updated.length - 1), 'rate')
  }

  /** Enter in a code or name cell: the highlighted item, the exact code, or a one-off. */
  const choose = (row: number, col: Col) => {
    const text = editRef.current.dirty ? editRef.current.text.trim() : ''
    if (text === '') {
      const line = linesRef.current[row]
      if (line) {
        move(row, 'rate')
      } else {
        // Nothing typed on the empty row: the order is finished, so the cursor goes to the end.
        cancelButton.current?.focus()
      }
      return
    }
    const items = matching(catalogue, text)
    const highlighted = listOpen && found && found.items.length > 0
      ? found.items[Math.min(highlight, found.items.length - 1)]
      : null
    const exact = items.find((item) => item.code.toLowerCase() === text.toLowerCase())
    const chosen = highlighted ?? exact ?? (items.length === 1 ? items[0] : null)
    if (chosen) {
      pick(row, chosen)
    } else {
      putOneOff(row, col, text)
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
    requestFocus(Math.min(row, updated.length), againstOrder ? 'qty' : 'code')
  }

  /** Empties the screen. Nothing is ordered, nothing is changed and nothing is sent back. */
  const clearOrder = () => {
    replaceLines([])
    setVendorId('')
    setVendorText('')
    setExpectedOn('')
    setNote('')
    setInvalid(null)
    setError(null)
    setConfirmClear(false)
    modeRef.current = { kind: 'order' }
    setMode({ kind: 'order' })
    void forgetOrderDraft()
    if (framed) {
      onClose()
    } else {
      focusStart()
    }
  }

  // ---- The vendor, chosen the way the steward is chosen on a bill ---------------------------------
  const vendorSearch = vendorText.trim().toLowerCase()
  const vendorMatches = vendorSearch
    ? vendors.filter((one) => one.code.toLowerCase().includes(vendorSearch)
      || one.name.toLowerCase().includes(vendorSearch))
    : vendors

  const vendorLabel = (one: Vendor) => `${one.code} - ${one.name}`

  /** Whatever is typed, resolved against the list: an exact code, or the only thing it can mean. */
  const commitVendor = (text: string): string => {
    const looking = text.trim().toLowerCase()
    if (!looking) {
      return ''
    }
    const exact = vendors.find((one) => one.code.toLowerCase() === looking
      || vendorLabel(one).toLowerCase() === looking || one.name.toLowerCase() === looking)
    const narrowed = vendors.filter((one) => one.code.toLowerCase().includes(looking)
      || one.name.toLowerCase().includes(looking))
    const resolved = exact ?? (narrowed.length === 1 ? narrowed[0] : null)
    return resolved ? resolved.id : ''
  }

  const showVendor = (id: string) => {
    const known = vendors.find((one) => one.id === id)
    setVendorText(known ? vendorLabel(known) : '')
  }

  /**
   * Takes a vendor off the list - or, where nothing matched, leaves what was typed exactly where
   * it is. That text is the vendor: the shop buys from a man off a lorry as well as from its own
   * list, and a box that emptied itself would lose the only record of who the goods came from.
   */
  const takeVendor = (id: string) => {
    setVendorId(id)
    vendorRef.current = id
    if (id) {
      showVendor(id)
    }
    setVendorOpen(false)
  }

  // ---- Writing it ---------------------------------------------------------------------------------
  const ready = (cart: Row[]): boolean => {
    if (cart.length === 0) {
      setError(returning ? 'Nothing is going back' : 'Put something on the order first')
      focusEntry()
      return false
    }
    const unpriced = cart.findIndex((line) => line.unitCostPaise <= 0)
    if (unpriced >= 0) {
      refuse(unpriced, 'rate', `Enter what one ${cart[unpriced].name} costs before writing the order`)
      return false
    }
    const unmeasured = cart.findIndex((line) => line.quantityMilli <= 0)
    if (unmeasured >= 0) {
      refuse(unmeasured, 'qty', `Enter how much of ${cart[unmeasured].name}`)
      return false
    }
    return true
  }

  /** The take-bill key: the money screen for an order, or the confirmation for a return. */
  const takeOrder = () => {
    if (busy.current || dialogOpen) {
      return
    }
    const cart = commitEdit()
    if (!cart || !ready(cart)) {
      return
    }
    if (returning) {
      setSendingBack(true)
    } else {
      setPaying(true)
    }
  }

  const write = async (paidNow: PaidNow[]) => {
    if (busy.current) {
      return
    }
    busy.current = true
    setSaving(true)
    try {
      const current = modeRef.current
      const made = await api.post<PurchaseOrder>(
        current.kind === 'edit' ? `/api/purchase-orders/${current.order.id}/replace` : '/api/purchase-orders', {
        vendorId: vendorId || null,
        // Nobody on the list: whatever was typed is the vendor, as it is on a delivery.
        vendorName: vendorId ? null : vendorText.trim(),
        expectedOn: expectedOn || null,
        note: note.trim(),
        lines: linesRef.current.map((line) => ({
          productId: line.productId,
          rawMaterialId: line.rawMaterialId,
          itemName: line.oneOff ? line.name : null,
          itemCode: line.oneOff ? line.code : null,
          quantityMilli: line.quantityMilli,
          unitCostPaise: line.unitCostPaise,
        })),
        payments: paidNow.map((paid) => ({
          mode: paid.mode,
          amountPaise: paid.amountPaise,
          reference: paid.reference,
        })),
      })
      const paid = paidNow.reduce((sum, one) => sum + one.amountPaise, 0)
      const replaced = current.kind === 'edit' ? current.order.number : ''
      clearOrder()
      onToast({
        tone: 'good',
        text: replaced
          ? `${replaced} is cancelled and ${made.number} takes its place. It waits for an administrator.`
          : paid > 0
            ? `${made.number} written, with ${rupees(paid)} paid. Both wait for an administrator.`
            : `${made.number} written. It waits for an administrator.`,
      })
      onWritten?.()
    } catch (failure) {
      setError(messageOfFailure(failure, 'Could not write that order'))
      const fields = fieldErrorsOf(failure)
      if (Object.keys(fields).length > 0) {
        setError(Object.values(fields)[0])
      }
    } finally {
      setPaying(false)
      busy.current = false
      setSaving(false)
    }
  }

  const sendBack = async (reason: string) => {
    if (busy.current || mode.kind !== 'return') {
      return
    }
    busy.current = true
    setSaving(true)
    try {
      const done = await api.post<PurchaseReturn>('/api/purchase-returns', {
        vendorId: vendorId || null,
        vendorName: vendorId ? null : vendorText.trim(),
        againstOrderId: mode.against?.id ?? null,
        reason: reason.trim(),
        lines: linesRef.current.map((line) => ({
          productId: line.productId,
          rawMaterialId: line.rawMaterialId,
          itemName: line.oneOff ? line.name : null,
          itemCode: line.oneOff ? line.code : null,
          quantityMilli: line.quantityMilli,
          unitCostPaise: line.unitCostPaise,
        })),
      })
      clearOrder()
      onToast({
        tone: 'good',
        text: `${done.number}: ${rupees(done.valuePaise)} of goods went back to ${done.vendorName}, `
          + 'and that much comes off what the shop owes them.',
      })
      onWritten?.()
    } catch (failure) {
      setError(messageOfFailure(failure, 'Could not send those goods back'))
    } finally {
      setSendingBack(false)
      busy.current = false
      setSaving(false)
    }
  }

  /**
   * Puts an order on the screen to be changed. Writing it does not edit anything: it cancels this
   * one and writes a new one in its place, exactly as editing a bill reissues it, so what was
   * agreed to and what was ordered can never quietly differ.
   */
  const openEdit = (order: PurchaseOrder) => {
    replaceLines(order.lines.map<Row>((line) => ({
      key: nextKey.current++,
      productId: line.oneOff || line.fromTheStore ? null : line.itemId,
      rawMaterialId: line.fromTheStore ? line.itemId : null,
      oneOff: line.oneOff,
      code: line.itemCode,
      name: line.name,
      unit: line.unit,
      quantityMilli: line.quantityMilli,
      unitCostPaise: line.unitCostPaise,
    })))
    setVendorId(order.vendorId ?? '')
    vendorRef.current = order.vendorId ?? ''
    setVendorText(order.vendorName)
    setExpectedOn(order.expectedOn ?? '')
    setNote(order.note)
    const next: Mode = { kind: 'edit', order }
    modeRef.current = next
    setMode(next)
    setError(null)
    setInvalid(null)
    requestFocus(0, 'qty')
  }

  /** Puts an order's delivery on the screen to send back, as a bill goes on to be returned. */
  const openReturn = async (order: PurchaseOrder | null) => {
    try {
      let rows: Row[] = []
      if (order) {
        const canGoBack = await api.get<CanGoBack[]>(`/api/purchase-returns/can-go-back?orderId=${order.id}`)
        rows = canGoBack
          .filter((line) => line.receivedMilli > line.alreadyBackMilli)
          .map<Row>((line) => ({
            key: nextKey.current++,
            productId: line.fromTheStore || line.oneOff ? null : line.itemId,
            rawMaterialId: line.fromTheStore ? line.itemId : null,
            oneOff: line.oneOff,
            code: line.itemCode,
            name: line.name,
            unit: line.unit,
            quantityMilli: line.receivedMilli - line.alreadyBackMilli,
            unitCostPaise: line.unitCostPaise,
            maxMilli: line.receivedMilli - line.alreadyBackMilli,
          }))
        if (rows.length === 0) {
          onToast({ tone: 'warn', text: `Everything on ${order.number} has already gone back` })
          return
        }
      }
      replaceLines(rows)
      if (order) {
        // The order's own vendor, whether that is somebody on the list or only a name.
        takeVendor(order.vendorId ?? '')
        if (!order.vendorId) {
          setVendorText(order.vendorName)
        }
      }
      setNote('')
      setExpectedOn('')
      const next: Mode = { kind: 'return', against: order }
      modeRef.current = next
      setMode(next)
      setError(null)
      setInvalid(null)
      requestFocus(0, order ? 'qty' : 'code')
    } catch (failure) {
      onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not open that order') })
    }
  }

  // ---- Held orders ---------------------------------------------------------------------------------
  const holdCurrent = (label: string) => api.post<HeldOrder>('/api/purchase-orders/holds', {
    label: label.trim() || `Order ${clockTime(new Date())}`,
    order: {
      vendorId,
      vendorText,
      expectedOn,
      note,
      lines: linesRef.current.map(draftOf),
    },
    estimatedTotalPaise: total,
  })

  const askHold = () => {
    if (returning || editing) {
      onToast({ tone: 'warn', text: 'Finish or put down the change first' })
      return
    }
    const cart = commitEdit()
    if (cart && cart.length > 0) {
      setAsk({ action: 'hold', title: 'Hold this order', label: 'Name it so you can find it again', initial: vendor?.name ?? '' })
    }
  }

  const openHolds = async () => {
    if (returning || editing) {
      onToast({ tone: 'warn', text: 'Finish or put down the change first' })
      return
    }
    try {
      setHolds(await api.get<HeldOrder[]>('/api/purchase-orders/holds'))
    } catch (failure) {
      onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not read the held orders') })
    }
  }

  /** Puts a held order back on screen. One already on screen is held first, never thrown away. */
  const resumeHold = async (held: HeldOrder) => {
    try {
      commitEdit()
      const swapped = linesRef.current.length > 0 ? await holdCurrent('') : null
      const resumed = await api.delete<HeldOrder>(`/api/purchase-orders/holds/${held.id}`)
      replaceLines((resumed.order.lines ?? []).map((line, at) => rowOf(line, nextKey.current + at)))
      nextKey.current += (resumed.order.lines ?? []).length
      setVendorId(resumed.order.vendorId ?? '')
      vendorRef.current = resumed.order.vendorId ?? ''
      setVendorText(resumed.order.vendorText ?? '')
      setExpectedOn(resumed.order.expectedOn ?? '')
      setNote(resumed.order.note ?? '')
      setHolds(null)
      setError(null)
      setInvalid(null)
      focusStart()
      onToast({
        tone: 'good',
        text: swapped
          ? `Resumed ${held.label}. The order that was on screen is held as ${swapped.label}`
          : `Resumed ${held.label}`,
      })
    } catch (failure) {
      onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not resume that order') })
    }
  }

  const deleteHold = async (held: HeldOrder) => {
    try {
      await api.delete<HeldOrder>(`/api/purchase-orders/holds/${held.id}`)
      setHolds((current) => current?.filter((other) => other.id !== held.id) ?? null)
      onToast({ tone: 'good', text: `Deleted the held order ${held.label}` })
    } catch (failure) {
      onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not delete that held order') })
    }
  }

  const runAsk = async (value: string) => {
    const pending = ask
    setAsk(null)
    if (pending?.action === 'hold') {
      try {
        await holdCurrent(value)
        clearOrder()
        onToast({ tone: 'good', text: `Order held. Resume it with ${shortcuts.resumeBill}` })
      } catch (failure) {
        onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not hold the order') })
      }
      return
    }
    if (pending?.action === 'return') {
      const number = value.trim()
      if (!number) {
        await openReturn(null)
        return
      }
      try {
        const orders = await api.get<PurchaseOrder[]>('/api/purchase-orders')
        const order = orders.find((one) => one.number.toLowerCase() === number.toLowerCase()
          || one.number.toLowerCase().endsWith(`-${number.toLowerCase()}`))
        if (!order) {
          onToast({ tone: 'warn', text: `No order here is numbered ${number}` })
          return
        }
        await openReturn(order)
      } catch (failure) {
        onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not find that order') })
      }
      return
    }
    focusEntry()
  }

  // ---- Keys ----------------------------------------------------------------------------------------
  const handlers: Partial<Record<ShortcutAction, () => void>> = {
    takeBill: takeOrder,
    focusSearch: () => move(linesRef.current.length, 'code'),
    setQuantity: () => {
      if (linesRef.current[edit.row]) {
        move(edit.row, 'qty')
      }
    },
    setDiscount: () => {
      if (linesRef.current[edit.row]) {
        move(edit.row, 'rate')
      }
    },
    removeLine: () => removeRow(edit.row),
    holdBill: askHold,
    resumeBill: () => void openHolds(),
    billInfo: () => focusEnd(vendorBox.current),
    returnBill: () => setAsk({
      action: 'return',
      title: 'Send goods back',
      label: 'Order number the goods came in on. Leave it empty to send back without one',
      initial: '',
    }),
    clearBill: () => {
      if (linesRef.current.length > 0 || vendorRef.current || returning || editing) {
        setConfirmClear(true)
      } else {
        onClose()
      }
    },
  }
  useShortcuts(shortcuts, handlers, inFront)
  // While this is open F5 means "delete this row", as it does on a bill, and never "reload".
  useGridKeys(active && !blocked)

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
          setFound({ row, col, text: '', items: matching(catalogue, '') })
          setHighlight(0)
          setDropdownOpen(true)
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
          choose(row, col)
          return
        }
        const committed = commitEdit()
        if (!committed) {
          return
        }
        if (col === 'rate') {
          requestFocus(row, 'qty')
        } else if (againstOrder) {
          requestFocus(Math.min(row + 1, committed.length - 1), 'qty')
        } else {
          move(committed.length, 'code')
        }
        return
      }
      case 'Escape':
        if (listOpen || edit.dirty) {
          // Esc here drops what is being typed, as it does on a bill. Pressed in a cell with
          // nothing typed it means the other Esc, which the shortcut takes.
          event.preventDefault()
          event.stopPropagation()
          event.nativeEvent.stopImmediatePropagation()
          setDropdownOpen(false)
          setEdit({ row, col, text: '', dirty: false })
          setInvalid(null)
          requestAnimationFrame(() => input.select())
        }
        return
      default:
        if (event.key === ' ' && shortcuts.takeBill === 'Space') {
          // Space inside a name being typed is just a space, as it is on a bill.
          if (isSearchCol(col) && edit.dirty && edit.text.trim() !== '') {
            return
          }
          event.preventDefault()
          if (!event.repeat) {
            takeOrder()
          }
        }
    }
  }

  const onCellChange = (row: number, col: Col, text: string) => {
    setEdit({ row, col, text, dirty: true })
    setInvalid(null)
    if (isSearchCol(col)) {
      if (text.trim() === '') {
        setDropdownOpen(false)
      } else {
        setFound({ row, col, text: text.trim(), items: matching(catalogue, text) })
        setHighlight(0)
        setDropdownOpen(true)
      }
    }
  }

  const onCellFocus = (clicked: number, col: Col) => {
    const current = editRef.current
    if (current.row !== clicked || current.col !== col) {
      const moved = { row: clicked, col, text: '', dirty: false }
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

  const display = (row: number, col: Col): string => {
    const line = lines[row]
    if (!line) {
      return ''
    }
    switch (col) {
      case 'code':
        return line.code
      case 'name':
        return line.name
      case 'rate':
        return line.unitCostPaise > 0 ? rupeesForInput(line.unitCostPaise) : ''
      case 'qty':
        return line.quantityMilli > 0 ? quantity(line.quantityMilli) : ''
    }
  }

  const cellInput = (row: number, col: Col, options: { align?: 'left' | 'right'; disabled?: boolean } = {}) => {
    const line = lines[row]
    const readOnly = againstOrder && col !== 'qty'
    const live = edit.row === row && edit.col === col
    const needsRate = col === 'rate' && line !== undefined && line.unitCostPaise <= 0
    const bad = invalid?.row === row && invalid.col === col
    return (
      <input
        ref={(element) => {
          if (element) {
            cells.current.set(cellId(row, col), element)
          } else {
            cells.current.delete(cellId(row, col))
          }
        }}
        value={live && edit.dirty ? edit.text : display(row, col)}
        disabled={options.disabled}
        readOnly={readOnly}
        inputMode={isSearchCol(col) ? undefined : 'decimal'}
        autoComplete="off"
        spellCheck={false}
        aria-label={`${col} of row ${row + 1}`}
        placeholder={needsRate ? 'Cost each' : row === blankRow && col === 'code' ? 'Code'
          : row === blankRow && col === 'name' ? 'Type a name…' : ''}
        onChange={(event) => onCellChange(row, col, event.target.value)}
        onFocus={() => onCellFocus(row, col)}
        onBlur={() => onCellBlur(row, col)}
        onKeyDown={(event) => onCellKeyDown(event, row, col)}
        className={`num h-9 w-full rounded border bg-transparent px-2 text-sm outline-none transition
          ${options.align === 'right' ? 'text-right' : 'text-left'}
          ${col === 'name' ? 'font-medium' : ''} ${col === 'code' ? 'uppercase' : ''}
          ${readOnly ? 'cursor-default' : ''}
          ${bad ? 'border-rose-500 bg-rose-50 ring-2 ring-rose-200'
            : needsRate ? 'border-amber-400 bg-amber-50 placeholder:text-amber-700'
              : 'border-transparent focus:border-sky-500 focus:bg-surface focus:ring-2 focus:ring-sky-200'}
          disabled:cursor-not-allowed disabled:opacity-40`}
      />
    )
  }

  /** Inside a dialog the cursor must not wander out of it while an order is half written. */
  const trapTab = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (!framed || event.key !== 'Tab') {
      return
    }
    const inside = frame.current?.querySelectorAll<HTMLElement>(
      'input:not([disabled]), button:not([disabled]), select, [tabindex]:not([tabindex="-1"])')
    if (!inside || inside.length === 0) {
      return
    }
    const first = inside[0]
    const last = inside[inside.length - 1]
    if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    } else if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last.focus()
    }
  }

  const anchorInput = listOpen ? cells.current.get(cellId(edit.row, edit.col)) : undefined

  /**
   * Esc, the backdrop and the cross all mean the same thing, and it is never "throw the order
   * away without asking". With a dialog of its own open - the confirmation, the money screen, the
   * held list - this does nothing at all: that dialog is what the Esc was for.
   */
  const askToClose = () => {
    if (dialogOpen) {
      return
    }
    if (linesRef.current.length > 0 || vendorRef.current || returning || editing) {
      setConfirmClear(true)
    } else {
      onClose()
    }
  }

  const body = (
    <div ref={frame} onKeyDown={trapTab}
         className={framed ? 'flex max-h-[76vh] flex-col gap-2' : 'flex h-full min-h-0 flex-col gap-2'}>
      {error && <Banner tone="bad" onDismiss={() => setError(null)}>{error}</Banner>}

      {editing && mode.kind === 'edit' && (
        <Banner tone="warn">
          <span className="font-semibold">Changing {mode.order.number}.</span>
          {' '}Add, change or delete rows, then press <kbd>{shortcuts.takeBill}</kbd>: that order is
          cancelled and a new one is written in its place, carrying whatever has been paid against
          it. <kbd>{shortcuts.clearBill}</kbd> leaves it exactly as it is.
        </Banner>
      )}

      {returning && (
        <Banner tone="bad">
          <span className="font-semibold">
            {mode.kind === 'return' && mode.against
              ? `Sending goods back on ${mode.against.number}.`
              : 'Sending goods back without an order.'}
          </span>
          {' '}
          {mode.kind === 'return' && mode.against
            ? 'Delete the rows that are staying and lower the amounts.'
            : 'Enter what is going back and what the shop paid for it.'}
          {' '}Press <kbd>{shortcuts.takeBill}</kbd> to send it: the stock comes off and that much comes
          off what the shop owes. <kbd>{shortcuts.clearBill}</kbd> cancels.
        </Banner>
      )}

      {/* The three questions an order asks, where a bill asks for the table and the steward. */}
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2 rounded-lg border border-slate-200 bg-surface px-3 py-2 shadow-sm">
        <label className="relative flex items-center gap-2 text-sm">
          <span className="font-medium text-slate-500">Vendor</span>
          <input
            ref={vendorBox}
            className={`${smallInputClass} w-60`}
            value={vendorText}
            aria-label="Vendor"
            readOnly={againstOrder}
            placeholder={vendors.length === 0 ? 'A name, or add them on the Vendors screen' : 'Code or name'}
            onChange={(event) => {
              setVendorText(event.target.value)
              setVendorOpen(true)
              setVendorHighlight(0)
            }}
            onFocus={() => setVendorOpen(true)}
            onBlur={() => {
              setVendorOpen(false)
              // Sending goods back carries the order's own vendor and the box cannot be typed in,
              // so there is nothing to resolve - and resolving an empty box would throw it away.
              if (againstOrder) {
                return
              }
              const resolved = commitVendor(vendorText)
              setVendorId(resolved)
              vendorRef.current = resolved
              // Nothing on the list matches: what was typed stays, as the name of somebody the
              // shop does not keep. A box that emptied itself would lose the only record of who
              // the goods are coming from.
              if (resolved) {
                showVendor(resolved)
              }
            }}
            onKeyDown={(event) => {
              if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                event.preventDefault()
                setVendorOpen(true)
                setVendorHighlight((at) => Math.max(0,
                  Math.min(vendorMatches.length - 1, event.key === 'ArrowDown' ? at + 1 : at - 1)))
                return
              }
              if (event.key !== 'Enter' && event.key !== 'Escape') {
                return
              }
              event.preventDefault()
              event.stopPropagation()
              if (event.key === 'Enter') {
                const picked = vendorOpen && vendorMatches[vendorHighlight]
                  ? vendorMatches[vendorHighlight].id
                  : commitVendor(vendorText)
                takeVendor(picked)
                focusEntry()
              } else {
                if (vendorRef.current) {
                  showVendor(vendorRef.current)
                }
                setVendorOpen(false)
                focusEntry()
              }
            }}
          />
          {vendorOpen && vendorMatches.length > 0 && !againstOrder && (
            <ul className="absolute left-16 top-full z-30 mt-1 max-h-52 w-60 overflow-y-auto rounded-md border border-slate-200 bg-surface shadow-lg">
              {vendorMatches.slice(0, 25).map((one, at) => (
                <li key={one.id}>
                  <button
                    type="button"
                    tabIndex={-1}
                    onMouseDown={(event) => {
                      event.preventDefault()
                      takeVendor(one.id)
                      focusEntry()
                    }}
                    className={`block w-full px-3 py-1.5 text-left text-sm ${
                      at === vendorHighlight ? 'bg-sky-50 text-sky-900' : 'hover:bg-slate-50'}`}
                  >
                    <span className="font-medium">{one.code}</span>
                    <span className="text-slate-500"> - {one.name}</span>
                    {one.outstandingPaise > 0 && (
                      <span className="num ml-2 text-xs text-rose-600">owed {rupees(one.outstandingPaise)}</span>
                    )}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </label>

        {!returning && (
          <label className="flex items-center gap-2 text-sm">
            <span className="font-medium text-slate-500">Expected</span>
            <input
              type="date"
              className={`${smallInputClass} w-40`}
              value={expectedOn}
              aria-label="Expected"
              onChange={(event) => setExpectedOn(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' || event.key === 'Escape') {
                  event.preventDefault()
                  event.stopPropagation()
                  focusEntry()
                }
              }}
            />
          </label>
        )}

        <label className="flex min-w-[12rem] flex-1 items-center gap-2 text-sm">
          <span className="font-medium text-slate-500">Note</span>
          <input
            className={`${smallInputClass} w-full`}
            value={note}
            aria-label="Note"
            maxLength={200}
            onChange={(event) => setNote(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' || event.key === 'Escape') {
                event.preventDefault()
                event.stopPropagation()
                focusEntry()
              }
            }}
          />
        </label>

        <span className="text-xs text-slate-400">
          <kbd>Enter</kbd> next · <kbd>{shortcuts.billInfo}</kbd> back to the vendor
        </span>
      </div>

      {/* What is being ordered */}
      <section className={`overflow-auto rounded-lg border border-slate-200 bg-surface shadow-sm ${
        framed ? 'max-h-[38vh] min-h-[12rem]' : 'min-h-48 flex-1'}`}>
        <table className="w-full min-w-[44rem] table-fixed border-collapse text-sm">
          <colgroup>
            <col className="w-12" />
            <col className="w-10" />
            <col className="w-28" />
            <col />
            <col className="w-28" />
            <col className="w-28" />
            <col className="w-32" />
          </colgroup>
          <thead className="nav-scope sticky top-0 z-10 bg-slate-800 text-xs uppercase tracking-wide text-slate-200">
            <tr>
              <th className="px-2 py-2 text-left font-medium">S.No</th>
              <th className="py-2" aria-label="Delete" />
              <th className="px-2 py-2 text-left font-medium">Code</th>
              <th className="px-2 py-2 text-left font-medium">Item</th>
              <th className="px-2 py-2 text-right font-medium">Cost each</th>
              <th className="px-2 py-2 text-right font-medium">{returning ? 'Going back' : 'How much'}</th>
              <th className="px-3 py-2 text-right font-medium">Amount</th>
            </tr>
          </thead>
          <tbody>
            {lines.map((line, index) => (
              <tr key={line.key}
                  className={`border-b border-slate-100 ${
                    edit.row === index ? 'bg-sky-50' : index % 2 ? 'bg-slate-50/60' : ''}`}>
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
                    ✕
                  </button>
                </td>
                <td className="px-1 py-0.5">{cellInput(index, 'code')}</td>
                <td className="px-1 py-0.5">
                  <div className="flex items-center gap-1">
                    <div className="min-w-0 flex-1">{cellInput(index, 'name')}</div>
                    {line.oneOff && (
                      <span title="Not on either list: it goes on the order as a one-off and nothing counts it"
                            className="shrink-0 whitespace-nowrap rounded bg-amber-100 px-1.5 py-0.5 text-xs font-medium text-amber-800">
                        one-off
                      </span>
                    )}
                    {line.rawMaterialId && (
                      <span className="shrink-0 whitespace-nowrap rounded bg-slate-100 px-1.5 py-0.5 text-xs text-slate-500">
                        non billable
                      </span>
                    )}
                  </div>
                </td>
                <td className="px-1 py-0.5">{cellInput(index, 'rate', { align: 'right' })}</td>
                <td className="px-1 py-0.5">
                  <div className="flex items-center">
                    {cellInput(index, 'qty', { align: 'right' })}
                    <span className="pr-1 text-xs text-slate-400">{line.unit}</span>
                    {line.maxMilli !== undefined && (
                      <span className="num whitespace-nowrap pr-1 text-xs text-slate-400">
                        of {quantity(line.maxMilli)}
                      </span>
                    )}
                  </div>
                </td>
                <td className="num px-3 text-right font-semibold text-slate-900">
                  {rupees(Math.round(line.quantityMilli * line.unitCostPaise / 1000))}
                </td>
              </tr>
            ))}
            <tr hidden={againstOrder}
                className={`border-b border-emerald-100 ${
                  edit.row === blankRow ? 'bg-emerald-50' : 'bg-emerald-50/40'}`}>
              <td className="num px-2 text-slate-400">{blankRow + 1}</td>
              <td />
              <td className="px-1 py-0.5">{cellInput(blankRow, 'code')}</td>
              <td className="px-1 py-0.5">{cellInput(blankRow, 'name')}</td>
              <td className="px-1 py-0.5">{cellInput(blankRow, 'rate', { align: 'right', disabled: true })}</td>
              <td className="px-1 py-0.5">{cellInput(blankRow, 'qty', { align: 'right', disabled: true })}</td>
              <td />
            </tr>
          </tbody>
        </table>
        {lines.length === 0 && (
          <p className="px-4 py-6 text-center text-sm text-slate-400">
            Type a code or part of a name in the green row and press <kbd>Enter</kbd>. Items and non
            billable items are both in the list, and anything that is on neither goes on as a one-off.
          </p>
        )}
      </section>

      {listOpen && found && anchorInput && (
        <ItemList
          anchor={anchorInput}
          found={found}
          typedText={typed}
          highlight={highlight}
          itemRefs={listItems.current}
          onHover={setHighlight}
          onPick={(item) => pick(edit.row, item)}
        />
      )}

      {/* Totals and the way out */}
      <div className="flex shrink-0 flex-wrap items-stretch gap-2">
        <section className="ml-auto flex w-full max-w-[42rem] items-stretch gap-2 rounded-lg border border-slate-200 bg-surface px-2 py-1 shadow-sm">
          <dl className="grid shrink-0 grid-cols-[auto_auto] content-center gap-x-2 text-xs leading-4">
            <dt className="text-slate-500">Lines</dt>
            <dd className="num text-right font-semibold text-slate-800">{lines.length}</dd>
            <dt className="text-slate-500">Qty</dt>
            <dd className="num text-right font-semibold text-slate-800">{quantity(totalQuantity)}</dd>
          </dl>
          <div className={`flex min-w-0 flex-1 items-center justify-between gap-2 rounded-md px-2.5 py-1 text-white ${
            returning ? 'bg-[#be123c]' : 'nav-scope bg-slate-900'}`}>
            <div className="min-w-0 leading-4">
              <div className="text-xs text-white/70">{returning ? 'Going back' : 'Comes to'}</div>
              <div className="num truncate text-[11px] text-white/55">
                {vendor ? vendor.name : vendorText.trim() || 'No vendor yet'}
                {vendor && vendor.outstandingPaise > 0 && ` · owed ${rupees(vendor.outstandingPaise)}`}
                {!vendor && vendorText.trim() && ' · not on the list'}
              </div>
            </div>
            <span className="num shrink-0 text-2xl font-bold leading-none">{rupees(total)}</span>
          </div>
          {/* A plain button, because the cursor is sent here when an order is finished and the
              shared one does not carry a ref. */}
          <button
            ref={cancelButton}
            type="button"
            onClick={() => {
              if (lines.length > 0 || vendorId || returning || editing) {
                setConfirmClear(true)
              } else {
                onClose()
              }
            }}
            className="inline-flex shrink-0 items-center gap-2 rounded-md border border-slate-300 px-3 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
          >
            Cancel
            <kbd>Esc</kbd>
          </button>
          <Button
            tone="primary"
            className="shrink-0 justify-between px-3"
            hint={shortcuts.takeBill}
            disabled={lines.length === 0 || saving}
            onClick={takeOrder}
          >
            {saving ? 'Saving…' : returning ? 'Send it back' : editing ? 'Save the change' : 'Write the order'}
          </Button>
        </section>
      </div>

      <p className="flex shrink-0 flex-wrap gap-x-4 gap-y-1 text-xs text-slate-400">
        <span>
          <kbd>{shortcuts.takeBill}</kbd> {returning ? 'send it back' : editing ? 'save the change' : 'write it'}
        </span>
        <span><kbd>{shortcuts.focusSearch}</kbd> new row</span>
        <span><kbd>{shortcuts.setQuantity}</kbd> how much</span>
        <span><kbd>{shortcuts.removeLine}</kbd> delete row</span>
        {!returning && <span><kbd>{shortcuts.holdBill}</kbd> hold · <kbd>{shortcuts.resumeBill}</kbd> held orders</span>}
        {!framed && <span><kbd>{shortcuts.returnBill}</kbd> send goods back</span>}
        <span><kbd>{shortcuts.clearBill}</kbd> clear</span>
      </p>

      {ask && (
        <AskModal title={ask.title} label={ask.label} initial={ask.initial}
                  onSubmit={(value) => void runAsk(value)}
                  onClose={() => { setAsk(null); focusEntry() }} />
      )}

      {paying && (
        <MoneyOutDialog
          due={total}
          editing={editing}
          vendorName={vendor?.name ?? vendorText.trim()}
          saving={saving}
          onClose={() => { setPaying(false); focusEntry() }}
          onConfirm={(paidNow) => void write(paidNow)}
        />
      )}

      {sendingBack && mode.kind === 'return' && (
        <SendBackDialog
          due={total}
          vendorName={vendor?.name ?? ''}
          orderNumber={mode.against?.number ?? null}
          saving={saving}
          onClose={() => { setSendingBack(false); focusEntry() }}
          onConfirm={(reason) => void sendBack(reason)}
        />
      )}

      {holds !== null && (
        <HeldOrdersDialog
          holds={holds}
          onClose={() => { setHolds(null); focusEntry() }}
          onResume={resumeHold}
          onDelete={deleteHold}
        />
      )}

      {confirmClear && (
        <Modal
          title={returning ? 'Cancel this return?'
            : editing && mode.kind === 'edit' ? `Stop changing ${mode.order.number}?` : 'Clear this order?'}
          onClose={() => { setConfirmClear(false); focusEntry() }}
        >
          <p className="text-sm text-slate-600">
            {returning
              ? 'Nothing goes back and no stock moves.'
              : editing && mode.kind === 'edit'
                ? `Your changes are dropped and order ${mode.order.number} stays exactly as it is.`
                : `${lines.length} line${lines.length === 1 ? '' : 's'} will be taken off the screen. `
                  + 'Nothing is ordered and nothing is paid.'}
          </p>
          <div className="mt-4 flex justify-end gap-2">
            <Button onClick={() => { setConfirmClear(false); focusEntry() }} hint="Esc">
              {returning ? 'Keep going' : 'Keep it'}
            </Button>
            <ConfirmButton onClick={clearOrder}>
              {returning ? 'Cancel the return' : editing ? 'Stop changing it' : 'Clear it'}
            </ConfirmButton>
          </div>
        </Modal>
      )}
    </div>
  )

  // In a dialog it brings its own frame, so Esc, the backdrop and the cross all come here rather
  // than to a parent that cannot know whether there is an order half written inside.
  return framed
    ? (
      <Modal
        title={returning ? 'Send goods back'
          : editing && mode.kind === 'edit' ? `Change ${mode.order.number}` : 'Write a purchase order'}
        size="5xl"
        onClose={askToClose}
      >
        {body}
      </Modal>
    )
    : body
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

/** The list under a code or name cell, fixed to the viewport so no scroll box clips it. */
function ItemList({ anchor, found, typedText, highlight, itemRefs, onHover, onPick }: {
  anchor: HTMLInputElement
  found: Found
  typedText: string
  highlight: number
  itemRefs: Map<number, HTMLTableRowElement>
  onHover: (index: number) => void
  onPick: (item: Orderable) => void
}) {
  const [box, setBox] = useState<{ left: number; top?: number; bottom?: number; maxHeight: number } | null>(null)

  useLayoutEffect(() => {
    const place = () => {
      const rect = anchor.getBoundingClientRect()
      const width = Math.min(560, window.innerWidth - 16)
      const left = Math.max(8, Math.min(rect.left, window.innerWidth - width - 8))
      const below = window.innerHeight - rect.bottom - 8
      const above = rect.top - 8
      setBox(below >= 220 || below >= above
        ? { left, top: rect.bottom + 2, maxHeight: Math.min(320, below) }
        : { left, bottom: window.innerHeight - rect.top + 2, maxHeight: Math.min(320, above) })
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
      className="fixed z-50 overflow-auto rounded-md border border-slate-300 bg-surface shadow-xl"
      style={{ left: box.left, top: box.top, bottom: box.bottom, maxHeight: box.maxHeight,
        width: Math.min(560, window.innerWidth - 16) }}
      onMouseDown={(event) => event.preventDefault()}
    >
      {found.items.length === 0 ? (
        <p className="px-3 py-2 text-sm text-slate-500">
          Nothing matches "{typedText}". <kbd>Enter</kbd> puts it on the order as a one-off.
        </p>
      ) : (
        <table className="w-full text-sm">
          <thead className="sticky top-0 bg-slate-100 text-xs uppercase tracking-wide text-slate-500">
            <tr>
              <th className="px-3 py-1.5 text-left">Code</th>
              <th className="px-3 py-1.5 text-left">Item</th>
              <th className="px-3 py-1.5 text-right">Which list</th>
            </tr>
          </thead>
          <tbody>
            {found.items.map((item, index) => (
              <tr
                key={item.kind + item.id}
                ref={(element) => {
                  if (element) {
                    itemRefs.set(index, element)
                  } else {
                    itemRefs.delete(index)
                  }
                }}
                onMouseEnter={() => onHover(index)}
                onClick={() => onPick(item)}
                className={`cursor-pointer ${index === highlight ? 'bg-sky-600 text-on-primary' : 'hover:bg-sky-50'}`}
              >
                <td className="num px-3 py-1.5 uppercase">{item.code}</td>
                <td className="px-3 py-1.5 font-medium">{item.name}</td>
                <td className={`px-3 py-1.5 text-right text-xs ${
                  index === highlight ? 'text-sky-100' : 'text-slate-400'}`}>
                  {item.kind === 'r' ? 'non billable' : 'sold'}
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
 * Money going out, worked exactly like the payment screen of a bill: the modes stack down the
 * left, up and down move between them, Enter pays and writes the order.
 *
 * <p>One mode a bill does not have, and it is the one most orders use: <b>Pay later</b>. A shop
 * usually pays when the goods come, or at the end of the month, and a screen that insisted on
 * money now would have every manager typing a zero to get past it.
 */
function MoneyOutDialog({ due, vendorName, editing = false, saving, onClose, onConfirm }: {
  due: number
  vendorName: string
  /** Changing an order rather than writing a new one, which is all the wording changes. */
  editing?: boolean
  saving: boolean
  onClose: () => void
  onConfirm: (paid: PaidNow[]) => void
}) {
  /** Null is Pay later, which is the one the cursor starts on. */
  const [mode, setMode] = useState<VendorPaymentMode | null>(null)
  const [amountText, setAmountText] = useState('')
  const [reference, setReference] = useState('')
  const [parts, setParts] = useState<PaidNow[]>([])
  const [prompt, setPrompt] = useState<string | null>(null)
  /** The transaction details a shop has to be able to find the money again by. */
  const [referenceNeeded, setReferenceNeeded] = useState(false)
  const submitted = useRef(false)
  const amountInput = useRef<HTMLInputElement>(null)
  const confirm = useRef<HTMLButtonElement>(null)

  const paid = parts.reduce((sum, part) => sum + part.amountPaise, 0)
  const remaining = Math.max(0, due - paid)
  const amount = amountText ? paiseFromRupees(amountText) : remaining

  /**
   * The cursor goes where the typing is: the amount box, or - with nothing being handed over -
   * the button that writes the order. Deliberately not the mode button it came from: Enter on a
   * button presses that button, so the cursor would sit on "Pay later" pressing Pay later.
   */
  useEffect(() => {
    if (mode === null) {
      confirm.current?.focus()
    } else {
      amountInput.current?.focus()
      amountInput.current?.select()
    }
  }, [mode, parts.length])

  const choose = (next: VendorPaymentMode | null) => {
    if (next === mode) {
      return
    }
    setMode(next)
    setAmountText('')
    setReference('')
    setPrompt(null)
  }

  const step = (key: 'ArrowUp' | 'ArrowDown') => {
    const all: (VendorPaymentMode | null)[] = [null, ...MODES]
    const at = Math.max(0, all.indexOf(mode))
    choose(all[Math.max(0, Math.min(all.length - 1, at + (key === 'ArrowDown' ? 1 : -1)))])
  }

  const settle = () => {
    if (submitted.current || saving) {
      return
    }
    if (mode === null) {
      submitted.current = true
      onConfirm(parts)
      return
    }
    if (amount <= 0) {
      setPrompt('must be more than zero')
      return
    }
    if (NEEDS_REFERENCE.includes(mode) && reference.trim() === '') {
      setPrompt(null)
      amountInput.current?.focus()
      setReferenceNeeded(true)
      return
    }
    const part: PaidNow = { mode, amountPaise: Math.min(amount, remaining), reference: reference.trim() }
    const all = [...parts, part]
    if (part.amountPaise >= remaining) {
      submitted.current = true
      onConfirm(all)
    } else {
      setParts(all)
      setAmountText('')
      setReference('')
      setPrompt(null)
      setReferenceNeeded(false)
    }
  }


  return (
    <Modal title="Money out" onClose={onClose} size="xl">
      <form
        className="flex gap-4"
        onSubmit={(event) => {
          event.preventDefault()
          settle()
        }}
        onKeyDown={(event) => {
          if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault()
            step(event.key)
          } else if (event.key === 'Enter') {
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
        <div className="flex w-36 shrink-0 flex-col gap-1.5">
          <ul role="listbox" aria-label="How it is paid" className="flex flex-col gap-1.5">
            {([null, ...MODES] as (VendorPaymentMode | null)[]).map((option) => (
              <li key={option ?? 'later'}>
                <button
                  type="button"
                  role="option"
                  aria-selected={mode === option}
                  tabIndex={-1}
                  onClick={() => choose(option)}
                  className={`flex w-full items-center justify-between rounded-md border px-3 py-2.5 text-left text-sm font-medium transition ${
                    mode === option
                      ? 'border-sky-600 bg-sky-600 text-on-primary'
                      : 'border-slate-200 text-slate-600 hover:bg-slate-50'}`}
                >
                  {option === null ? 'Pay later' : MODE_LABELS[option]}
                  {mode === option && <span aria-hidden="true">›</span>}
                </button>
              </li>
            ))}
          </ul>
          <p className="mt-1 text-center text-xs text-slate-400"><kbd>↑</kbd> <kbd>↓</kbd> change</p>
        </div>

        <div className="min-w-0 flex-1 space-y-3">
          <div className="nav-scope flex items-baseline justify-between rounded-md bg-slate-900 px-4 py-3 text-white">
            <span className="text-sm text-slate-300">
              {parts.length ? 'Still unpaid' : 'The order comes to'}
              {vendorName && <span className="ml-2 text-slate-400">{vendorName}</span>}
            </span>
            <span className="num text-3xl font-semibold">{rupees(remaining)}</span>
          </div>

          {parts.length > 0 && (
            <ul className="space-y-1 text-sm">
              {parts.map((part, index) => (
                <li key={index} className="flex justify-between text-slate-600">
                  <span>{MODE_LABELS[part.mode]}{part.reference && ` · ${part.reference}`}</span>
                  <span className="num">{rupees(part.amountPaise)}</span>
                </li>
              ))}
            </ul>
          )}

          {mode === null ? (
            <p className="rounded-md border border-slate-200 bg-slate-50 px-3 py-4 text-sm text-slate-600">
              Nothing is handed over now. The order is written, and what the shop owes moves when the
              goods actually arrive — pay it from the order itself, or from the vendor's page, whenever
              it is paid.
            </p>
          ) : (
            <>
              <Field label={`${MODE_LABELS[mode]} paid`} error={prompt ?? undefined}
                     hint="Leave it empty for the whole amount; less splits it">
                <input
                  ref={amountInput}
                  className={`${inputClass} num text-lg ${prompt ? 'border-rose-500 ring-2 ring-rose-200' : ''}`}
                  inputMode="decimal"
                  autoComplete="off"
                  value={amountText}
                  onChange={(event) => {
                    setAmountText(event.target.value)
                    setPrompt(null)
                  }}
                />
              </Field>
              <Field
                label={mode === 'CASH' ? 'Reference' : 'Transaction details'}
                optional={mode === 'CASH'}
                error={referenceNeeded ? 'is needed: the shop has to be able to find this payment again' : undefined}
                hint={mode === 'UPI' ? 'The UPI reference number'
                  : mode === 'CARD' ? 'The card and the approval code'
                    : mode === 'BANK_TRANSFER' ? 'The transfer reference' : 'Anything worth keeping'}
              >
                <input
                  className={`${inputClass} ${referenceNeeded ? 'border-rose-500 ring-2 ring-rose-200' : ''}`}
                  autoComplete="off"
                  value={reference}
                  onChange={(event) => {
                    setReference(event.target.value)
                    setReferenceNeeded(false)
                  }}
                />
              </Field>
              <p className="text-xs text-slate-400">
                Recorded against this order and waiting for an administrator, as every payment to a
                vendor is.
              </p>
            </>
          )}

          <div className="flex justify-end gap-2 pt-1">
            <Button onClick={onClose} hint="Esc">Back</Button>
            <button
              ref={confirm}
              type="submit"
              disabled={saving}
              className="inline-flex items-center gap-2 rounded-md bg-sky-600 px-3 py-2 text-sm font-medium text-on-primary hover:bg-sky-500 disabled:opacity-50"
            >
              {saving ? 'Saving…' : mode === null ? (editing ? 'Save the change' : 'Write the order')
                : amount >= remaining
                  ? `Pay ${rupees(remaining)} and ${editing ? 'save it' : 'write it'}`
                  : 'Add part payment'}
              <kbd>Enter</kbd>
            </button>
          </div>
        </div>
      </form>
    </Modal>
  )
}

/** Sending goods back: what it comes to, and why they are going. */
function SendBackDialog({ due, vendorName, orderNumber, saving, onClose, onConfirm }: {
  due: number
  vendorName: string
  orderNumber: string | null
  saving: boolean
  onClose: () => void
  onConfirm: (reason: string) => void
}) {
  const [reason, setReason] = useState('')
  const submitted = useRef(false)
  const confirm = useRef<HTMLButtonElement>(null)

  useEffect(() => confirm.current?.focus(), [])

  const submit = () => {
    if (submitted.current || saving) {
      return
    }
    submitted.current = true
    onConfirm(reason)
  }

  return (
    <Modal title="Send these goods back?" onClose={onClose}>
      <form
        className="space-y-3"
        onSubmit={(event) => {
          event.preventDefault()
          submit()
        }}
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault()
            if (!event.repeat) {
              submit()
            }
          }
        }}
      >
        <div className="flex items-baseline justify-between rounded-md bg-[#be123c] px-4 py-3 text-white">
          <span className="text-sm text-white/80">Going back to {vendorName || 'the vendor'}</span>
          <span className="num text-3xl font-semibold">{rupees(due)}</span>
        </div>
        <p className="text-sm text-slate-600">
          {orderNumber ? `Against ${orderNumber}. ` : ''}
          The stock comes off now — the goods are leaving — and {rupees(due)} comes off what the shop
          owes {vendorName || 'them'}. No money changes hands here: if they hand it back, record it as
          a payment on their page.
        </p>
        <Field label="Why they are going back" optional hint="Spoiled, wrong item, too many">
          <input className={inputClass} maxLength={200} value={reason}
                 onChange={(event) => setReason(event.target.value)} />
        </Field>
        <div className="flex justify-end gap-2 pt-1">
          <Button onClick={onClose} hint="Esc">Back</Button>
          <button
            ref={confirm}
            type="submit"
            disabled={saving}
            className="inline-flex items-center gap-2 rounded-md bg-rose-600 px-3 py-2 text-sm font-medium text-white hover:bg-rose-500 disabled:opacity-50"
          >
            {saving ? 'Saving…' : `Send back ${rupees(due)}`}
            <kbd>Enter</kbd>
          </button>
        </div>
      </form>
    </Modal>
  )
}

/** Orders put aside, worked exactly like held bills: ↑ ↓ choose, Enter resumes, Delete throws away. */
function HeldOrdersDialog({ holds, onClose, onResume, onDelete }: {
  holds: HeldOrder[]
  onClose: () => void
  onResume: (held: HeldOrder) => Promise<void>
  onDelete: (held: HeldOrder) => Promise<void>
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

  const run = async (action: (held: HeldOrder) => Promise<void>, held: HeldOrder | undefined) => {
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
    <Modal title="Held orders" onClose={onClose} wide>
      {holds.length === 0 ? (
        <Empty>No orders are held on this till</Empty>
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
                  index === at ? 'bg-sky-600 text-on-primary' : ''}`}
              >
                <div className="min-w-0">
                  <div className="truncate font-medium">{held.label}</div>
                  <div className={`text-xs ${index === at ? 'text-sky-100' : 'text-slate-400'}`}>
                    {held.by} · {billDate(new Date(held.heldAt))} {clockTime(new Date(held.heldAt))} ·{' '}
                    {held.order.lines?.length ?? 0}
                    {held.order.lines?.length === 1 ? ' line' : ' lines'}
                  </div>
                </div>
                <div className="flex shrink-0 items-center gap-2">
                  <span className="num mr-2">{rupees(held.estimatedTotalPaise)}</span>
                  <button
                    type="button"
                    tabIndex={-1}
                    onClick={() => void run(onResume, held)}
                    className={`rounded-md px-3 py-1.5 text-sm font-medium ${
                      index === at ? 'bg-surface text-sky-700' : 'bg-sky-600 text-on-primary hover:bg-sky-500'}`}
                  >
                    Resume
                  </button>
                  <button
                    type="button"
                    tabIndex={-1}
                    aria-label={`Delete held order ${held.label}`}
                    title="Delete (Delete key)"
                    onClick={() => void run(onDelete, held)}
                    className={`rounded-md px-2 py-1.5 ${
                      index === at ? 'text-on-primary hover:bg-sky-500' : 'text-rose-500 hover:bg-rose-50'}`}
                  >
                    ✕
                  </button>
                </div>
              </li>
            ))}
          </ul>
          <p className="mt-3 text-xs text-slate-400">
            <kbd>↑</kbd> <kbd>↓</kbd> choose · <kbd>Enter</kbd> resume · <kbd>Delete</kbd> delete ·{' '}
            <kbd>Esc</kbd> close
          </p>
        </>
      )}
    </Modal>
  )
}
