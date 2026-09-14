import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import { api, ApiError, messageOfFailure, percent, quantity, rupees } from '../api'
import type { CreditNote, Invoice, Role } from '../types'
import { Button, Empty, Field, inputClass, Modal, Panel } from '../components/ui'
import type { Toast } from '../App'

/** The till's own calendar date, as bill dates are written: 2026-09-13. */
function today(): string {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
}

type Entry = { kind: 'bill'; id: string; number: string; at: string; bill: Invoice }
  | { kind: 'return'; id: string; number: string; at: string; note: CreditNote }

type Action = 'open' | 'reprint' | 'edit' | 'return' | 'cancel'

const ACTION_LABELS: Record<Action, string> = {
  open: 'Open', reprint: 'Reprint', edit: 'Edit', return: 'Return', cancel: 'Cancel',
}

const SEARCH_KEY = 'F2'

/**
 * The day's bills and returns, newest first, worked from the keyboard. The cursor starts in the list:
 * up and down choose a row, left and right choose what to do with it (Open by default), Enter does it.
 * F2 jumps to the bill number search; Enter there goes back to the list at the first match, fetching
 * the bill from another day when it is not in this one. A manager edits today's bill or takes a return
 * from here; both open on the billing screen.
 */
export default function Invoices({ role, onToast, onOpenInBilling }: {
  role: Role
  onToast: Toast
  onOpenInBilling?: (kind: 'edit' | 'return', invoiceId: string | null) => void
}) {
  const [date, setDate] = useState(today())
  const [entries, setEntries] = useState<Entry[]>([])
  const [search, setSearch] = useState('')
  const [searchError, setSearchError] = useState<string | null>(null)
  const [selected, setSelected] = useState(0)
  const [action, setAction] = useState<Action>('open')
  const [listFocused, setListFocused] = useState(false)
  const [open, setOpen] = useState<Invoice | null>(null)
  const [openNote, setOpenNote] = useState<CreditNote | null>(null)
  const [cancelling, setCancelling] = useState<Invoice | null>(null)
  const canManage = role === 'MANAGER' || role === 'ADMIN'
  const canPrint = role !== 'AUDITOR'
  const list = useRef<HTMLDivElement>(null)
  const searchBox = useRef<HTMLInputElement>(null)
  const rows = useRef(new Map<string, HTMLTableRowElement>())
  /** A bill found on another day: selected once that day's list has loaded. */
  const pendingSelect = useRef<string | null>(null)
  const dialogOpen = open !== null || openNote !== null || cancelling !== null

  const load = useCallback(() => {
    Promise.all([
      api.get<Invoice[]>(`/api/invoices?from=${date}&to=${date}`),
      api.get<CreditNote[]>(`/api/returns?from=${date}&to=${date}`),
    ])
      .then(([bills, notes]) => setEntries([
        ...bills.map((bill): Entry => ({ kind: 'bill', id: bill.id, number: bill.invoiceNumber, at: bill.issuedAt, bill })),
        ...notes.map((note): Entry => ({ kind: 'return', id: note.id, number: note.creditNoteNumber, at: note.issuedAt, note })),
      ].sort((a, b) => b.at.localeCompare(a.at))))
      .catch((failure) => onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not load the bills') }))
  }, [date, onToast])

  useEffect(load, [load])

  // The cursor starts in the list, so arrows work the moment the screen opens.
  useEffect(() => {
    list.current?.focus()
  }, [])

  const text = search.trim().toUpperCase()
  const shown = useMemo(
    () => (text ? entries.filter((entry) => entry.number.toUpperCase().includes(text)) : entries),
    [entries, text],
  )
  const at = Math.min(selected, Math.max(shown.length - 1, 0))
  const current = shown[at]

  // Select a bill fetched from another day as soon as that day is on screen.
  useEffect(() => {
    if (!pendingSelect.current) {
      return
    }
    const index = shown.findIndex((entry) => entry.id === pendingSelect.current)
    if (index >= 0) {
      pendingSelect.current = null
      setSelected(index)
      setAction('open')
      list.current?.focus()
    }
  }, [shown])

  useEffect(() => {
    if (current) {
      rows.current.get(current.id)?.scrollIntoView({ block: 'nearest' })
    }
  }, [current])

  // F2 from anywhere on the screen (but not over a dialog) goes to the search box.
  useEffect(() => {
    if (dialogOpen) {
      return
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === SEARCH_KEY) {
        event.preventDefault()
        searchBox.current?.focus()
        searchBox.current?.select()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [dialogOpen])

  const bills = entries.flatMap((entry) => (entry.kind === 'bill' ? [entry.bill] : []))
  const notes = entries.flatMap((entry) => (entry.kind === 'return' ? [entry.note] : []))
  const takings = bills.filter((bill) => bill.status === 'ISSUED').reduce((sum, bill) => sum + bill.grandTotalPaise, 0)
  const refunded = notes.reduce((sum, note) => sum + note.grandTotalPaise, 0)
  // A bill with a return against it has had stock and money put back once; the server refuses to
  // cancel or edit it, so those are not offered for the returns in view.
  const returnedBills = useMemo(() => new Set(notes.map((note) => note.originalInvoiceId).filter(Boolean)), [notes])

  /** The actions a row offers, each with the reason it cannot be used right now (null when it can). */
  const actionsFor = (entry: Entry): [Action, string | null][] => {
    const printing: string | null = canPrint ? null : 'An auditor cannot print'
    if (entry.kind === 'return') {
      return [['open', null], ['reprint', printing]]
    }
    const bill = entry.bill
    const manager = canManage ? null : 'Only a manager can do this'
    const cancelled = bill.status === 'CANCELLED' ? 'This bill is cancelled' : null
    const returned = returnedBills.has(bill.id) ? 'This bill has a return against it' : null
    const billing = onOpenInBilling ? null : 'Billing is not open to your role'
    return [
      ['open', null],
      ['reprint', printing],
      ['edit', manager ?? billing ?? cancelled ?? returned
        ?? (bill.invoiceDate === today() ? null : "Only today's bills can be edited")],
      ['return', manager ?? billing ?? cancelled],
      ['cancel', manager ?? cancelled ?? returned],
    ]
  }

  const run = async (entry: Entry, chosen: Action) => {
    const blocked = actionsFor(entry).find(([name]) => name === chosen)?.[1]
    if (blocked) {
      onToast({ tone: 'warn', text: blocked })
      return
    }
    if (chosen === 'open') {
      if (entry.kind === 'bill') {
        setOpen(entry.bill)
      } else {
        setOpenNote(entry.note)
      }
    } else if (chosen === 'reprint') {
      const path = entry.kind === 'bill' ? `/api/invoices/${entry.id}/receipt` : `/api/returns/${entry.id}/receipt`
      try {
        await api.post(path, { copy: 'DUPLICATE', openDrawer: false })
        onToast({ tone: 'good', text: `Reprinted ${entry.number}` })
      } catch (failure) {
        onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not reprint') })
      }
    } else if (entry.kind === 'bill') {
      if (chosen === 'cancel') {
        setCancelling(entry.bill)
      } else {
        onOpenInBilling?.(chosen === 'edit' ? 'edit' : 'return', entry.id)
      }
    }
  }

  const choose = (index: number) => {
    setSelected(index)
    setAction('open')
  }

  const onListKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (dialogOpen || !current) {
      return
    }
    const available = actionsFor(current).filter(([, blocked]) => !blocked).map(([name]) => name)
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      choose(Math.max(0, Math.min(shown.length - 1, at + (event.key === 'ArrowDown' ? 1 : -1))))
    } else if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
      event.preventDefault()
      const from = Math.max(0, available.indexOf(action))
      const to = Math.max(0, Math.min(available.length - 1, from + (event.key === 'ArrowRight' ? 1 : -1)))
      setAction(available[to] ?? 'open')
    } else if (event.key === 'Home' || event.key === 'End') {
      event.preventDefault()
      choose(event.key === 'Home' ? 0 : shown.length - 1)
    } else if (event.key === 'Enter') {
      event.preventDefault()
      if (!event.repeat) {
        void run(current, available.includes(action) ? action : 'open')
      }
    }
  }

  /**
   * Enter in the search box: back to the list at the first match. A number not in this day's list is
   * looked up (a bill, then a return), and the list moves to that day.
   */
  const applySearch = async () => {
    setSearchError(null)
    if (!text || shown.length > 0) {
      choose(0)
      list.current?.focus()
      return
    }
    const found = await findAnywhere(text)
    if (!found) {
      setSearchError(`No bill or return numbered ${search.trim()}`)
      return
    }
    pendingSelect.current = found.id
    setSearch(found.number)
    if (found.date === date) {
      load()
    } else {
      setDate(found.date)
    }
  }

  const onSearchKeyDown = (event: ReactKeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'Enter') {
      event.preventDefault()
      void applySearch()
    } else if (event.key === 'ArrowDown' || event.key === 'Escape') {
      // Esc here only leaves the search box: it must not count toward Esc Esc back to billing.
      event.preventDefault()
      event.stopPropagation()
      event.nativeEvent.stopImmediatePropagation()
      list.current?.focus()
    }
  }

  const closeDialog = (reload = false) => {
    setOpen(null)
    setOpenNote(null)
    setCancelling(null)
    if (reload) {
      load()
    }
    // Back to the list, so the arrows carry on where they were.
    requestAnimationFrame(() => list.current?.focus())
  }

  return (
    <Panel
      title="Bills and returns"
      actions={
        <div className="flex flex-wrap items-center justify-end gap-3">
          <span className="text-xs text-slate-500">
            {bills.length} bills
            {notes.length > 0 && <> · {notes.length} return{notes.length === 1 ? '' : 's'} (<span className="num">-{rupees(refunded)}</span>)</>}
            {' · net '}<span className="num font-medium text-slate-700">{rupees(takings - refunded)}</span>
          </span>
          <div className="relative">
            <input
              ref={searchBox}
              className={`${inputClass} num w-56 pr-10 uppercase ${searchError ? 'border-rose-400' : ''}`}
              placeholder="Bill or return number"
              aria-label="Search by bill number"
              value={search}
              onChange={(event) => {
                setSearch(event.target.value)
                setSearchError(null)
                choose(0)
              }}
              onKeyDown={onSearchKeyDown}
            />
            <kbd className="pointer-events-none absolute right-2 top-1/2 -translate-y-1/2">{SEARCH_KEY}</kbd>
          </div>
          {canManage && onOpenInBilling && (
            <Button onClick={() => onOpenInBilling('return', null)}>Return without a bill</Button>
          )}
          <input type="date" className={`${inputClass} w-auto`} value={date}
                 onChange={(event) => setDate(event.target.value)} />
        </div>
      }
    >
      <p className="mb-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-400">
        <span><kbd>↑</kbd> <kbd>↓</kbd> choose a bill</span>
        <span><kbd>←</kbd> <kbd>→</kbd> choose Open, Reprint, Edit, Return or Cancel</span>
        <span><kbd>Enter</kbd> do it</span>
        <span><kbd>{SEARCH_KEY}</kbd> search a number, <kbd>Enter</kbd> or <kbd>Esc</kbd> back to the list</span>
        <span><kbd>Esc</kbd> <kbd>Esc</kbd> billing</span>
      </p>
      {searchError && <p className="mb-2 text-sm font-medium text-rose-600">{searchError}</p>}

      <div
        ref={list}
        tabIndex={0}
        role="grid"
        aria-label="Bills and returns"
        onKeyDown={onListKeyDown}
        onFocus={() => setListFocused(true)}
        onBlur={() => setListFocused(false)}
        className="rounded-md outline-none focus-visible:ring-2 focus-visible:ring-sky-200"
      >
        {shown.length === 0 ? (
          <Empty>{text ? `Nothing on ${date} matches "${search.trim()}". Press Enter to look further.` : `No bills on ${date}`}</Empty>
        ) : (
          <table className="w-full text-sm">
            <thead className="text-xs uppercase tracking-wide text-slate-400">
              <tr>
                <th className="py-1 pl-2 text-left">Number</th>
                <th className="py-1 text-left">Time</th>
                <th className="py-1 text-left">Cashier</th>
                <th className="py-1 text-left">Customer</th>
                <th className="py-1 text-right">Total</th>
                <th className="py-1"></th>
              </tr>
            </thead>
            <tbody>
              {shown.map((entry, index) => {
                const isSelected = index === at
                return (
                  <tr
                    key={entry.id}
                    ref={(element) => {
                      if (element) {
                        rows.current.set(entry.id, element)
                      } else {
                        rows.current.delete(entry.id)
                      }
                    }}
                    onMouseDown={() => choose(index)}
                    onClick={() => void run(entry, 'open')}
                    aria-selected={isSelected}
                    className={`cursor-pointer border-t border-slate-100 ${
                      isSelected ? 'bg-sky-50 shadow-[inset_3px_0_0_0_var(--color-sky-500)]'
                        : entry.kind === 'return' ? 'bg-rose-50/40 hover:bg-slate-50' : 'hover:bg-slate-50'}`}
                  >
                    <td className="num py-2 pl-2">
                      <span className={`font-medium ${entry.kind === 'return' ? 'text-rose-700' : 'text-sky-700'}`}>
                        {entry.number}
                      </span>
                      {entry.kind === 'bill' && entry.bill.status === 'CANCELLED' && (
                        <span className="ml-2 rounded bg-rose-100 px-1.5 py-0.5 text-xs text-rose-700">cancelled</span>
                      )}
                      {entry.kind === 'bill' && entry.bill.replacesInvoiceNumber && (
                        <span className="ml-2 rounded bg-amber-100 px-1.5 py-0.5 text-xs text-amber-800">
                          replaces {entry.bill.replacesInvoiceNumber}
                        </span>
                      )}
                      {entry.kind === 'return' && (
                        <span className="ml-2 rounded bg-rose-100 px-1.5 py-0.5 text-xs text-rose-700">
                          return{entry.note.originalInvoiceNumber ? ` on ${entry.note.originalInvoiceNumber}` : ''}
                        </span>
                      )}
                    </td>
                    <td className="num py-2 text-slate-500">{timeOf(entry.at)}</td>
                    <td className="py-2 text-slate-600">{entry.kind === 'bill' ? entry.bill.cashier : entry.note.cashier}</td>
                    <td className="py-2 text-slate-600">
                      {(entry.kind === 'bill' ? entry.bill.buyerName : entry.note.buyerName)
                        || <span className="text-slate-400">Walk-in</span>}
                      {entry.kind === 'bill' && entry.bill.buyerGstin && (
                        <div className="num text-xs text-slate-400">{entry.bill.buyerGstin}</div>
                      )}
                    </td>
                    <td className={`num py-2 text-right font-medium ${
                      entry.kind === 'return' ? 'text-rose-700'
                        : entry.bill.status === 'CANCELLED' ? 'text-slate-400 line-through' : ''}`}>
                      {entry.kind === 'return' ? `-${rupees(entry.note.grandTotalPaise)}` : rupees(entry.bill.grandTotalPaise)}
                    </td>
                    <td className="whitespace-nowrap py-1.5 pr-2 text-right">
                      <div className="inline-flex gap-1">
                        {actionsFor(entry).map(([name, blocked]) => {
                          const focused = isSelected && action === name
                          return (
                            <button
                              key={name}
                              type="button"
                              tabIndex={-1}
                              disabled={Boolean(blocked)}
                              title={blocked ?? undefined}
                              onMouseDown={(event) => event.stopPropagation()}
                              onClick={(event) => {
                                event.stopPropagation()
                                choose(index)
                                setAction(name)
                                void run(entry, name)
                              }}
                              className={`rounded-md px-2.5 py-1 text-xs font-medium transition disabled:cursor-not-allowed disabled:text-slate-300 ${
                                focused
                                  ? `bg-sky-600 text-white ${listFocused ? 'ring-2 ring-sky-300' : ''}`
                                  : name === 'cancel' ? 'text-rose-600 hover:bg-rose-50' : 'text-slate-600 hover:bg-slate-100'}`}
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

      {open && <BillDialog opened={open} onClose={() => closeDialog()} onToast={onToast} />}
      {openNote && <CreditNoteDialog note={openNote} onClose={() => closeDialog()} />}
      {cancelling && (
        <CancelDialog
          invoice={cancelling}
          onClose={() => closeDialog()}
          onDone={() => closeDialog(true)}
          onToast={onToast}
        />
      )}
    </Panel>
  )
}

/** A bill or a return found by number anywhere, with the day it belongs to. */
async function findAnywhere(number: string): Promise<{ id: string; number: string; date: string } | null> {
  const query = encodeURIComponent(number)
  try {
    const bill = await api.get<Invoice>(`/api/invoices/lookup?number=${query}`)
    return { id: bill.id, number: bill.invoiceNumber, date: bill.invoiceDate }
  } catch (failure) {
    if (!(failure instanceof ApiError && failure.status === 404)) {
      throw failure
    }
  }
  try {
    const note = await api.get<CreditNote>(`/api/returns/lookup?number=${query}`)
    return { id: note.id, number: note.creditNoteNumber, date: note.noteDate }
  } catch (failure) {
    if (failure instanceof ApiError && (failure.status === 404 || failure.status === 400)) {
      return null
    }
    throw failure
  }
}

function timeOf(instant: string): string {
  return new Date(instant).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

/**
 * A bill with every version it went through when it was edited, oldest first, so the cancelled
 * original and its replacement read side by side. The bill that was opened is marked.
 */
function BillDialog({ opened, onClose, onToast }: { opened: Invoice; onClose: () => void; onToast: Toast }) {
  const [versions, setVersions] = useState<Invoice[] | null>(null)
  const openedSection = useRef<HTMLElement>(null)
  const body = useRef<HTMLDivElement>(null)

  useEffect(() => {
    api.get<Invoice[]>(`/api/invoices/${opened.id}/history`)
      .then(setVersions)
      .catch((failure) => {
        onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not load the earlier versions of this bill') })
        setVersions([opened])
      })
  }, [opened, onToast])

  // Take the focus off the list behind, and show the opened version.
  useEffect(() => {
    body.current?.focus()
    openedSection.current?.scrollIntoView({ block: 'nearest' })
  }, [versions])

  const chain = versions ?? [opened]
  return (
    <Modal title={`Bill ${opened.invoiceNumber}`} onClose={onClose} wide>
      <div ref={body} tabIndex={-1} className="max-h-[70vh] space-y-4 overflow-auto outline-none">
        {chain.length > 1 && (
          <p className="rounded-md bg-amber-50 px-3 py-2 text-sm text-amber-900">
            This bill was edited, so all {chain.length} versions are shown, oldest first.
          </p>
        )}
        {chain.map((invoice) => {
          const isOpened = invoice.id === opened.id
          return (
            <section
              key={invoice.id}
              ref={isOpened ? openedSection : undefined}
              className={`rounded-lg border p-3 ${isOpened ? 'border-sky-400 ring-2 ring-sky-100' : 'border-slate-200'}`}
            >
              {chain.length > 1 && (
                <h3 className="mb-2 flex flex-wrap items-baseline gap-2 font-semibold text-slate-800">
                  Bill {invoice.invoiceNumber}
                  {isOpened && <span className="text-sky-700">(Opened)</span>}
                  {invoice.status === 'CANCELLED' && (
                    <span className="rounded bg-rose-100 px-1.5 py-0.5 text-xs font-medium text-rose-700">cancelled</span>
                  )}
                </h3>
              )}
              <BillDetails invoice={invoice} />
            </section>
          )
        })}
      </div>
    </Modal>
  )
}

function BillDetails({ invoice }: { invoice: Invoice }) {
  return (
    <>
      <div className="mb-3 flex flex-wrap gap-x-6 gap-y-1 text-sm text-slate-500">
        <span>{new Date(invoice.issuedAt).toLocaleString()}</span>
        <span>Cashier {invoice.cashier}</span>
        <span>Till {invoice.terminalCode}</span>
        {invoice.taxInvoice
          ? <span>Place of supply {invoice.placeOfSupply}</span>
          : <span className="rounded bg-slate-100 px-1.5 text-slate-600">Bill without GST</span>}
        {invoice.replacesInvoiceNumber && <span className="text-amber-700">Replaces {invoice.replacesInvoiceNumber}</span>}
        {invoice.status === 'CANCELLED' && <span className="text-rose-600">Cancelled: {invoice.cancelReason}</span>}
      </div>
      <LinesTable lines={invoice.lines} taxInvoice={invoice.taxInvoice} />
      <div className="mt-4 grid grid-cols-2 gap-6 text-sm">
        <div>
          <h4 className="mb-1 text-xs uppercase tracking-wide text-slate-400">Paid by</h4>
          {invoice.payments.map((payment, index) => (
            <div key={index} className="flex justify-between text-slate-600">
              <span>{payment.mode}{payment.reference ? ` · ${payment.reference}` : ''}</span>
              <span className="num">{rupees(payment.amountPaise)}</span>
            </div>
          ))}
          {invoice.changePaise > 0 && (
            <div className="flex justify-between text-emerald-700">
              <span>Change given</span><span className="num">{rupees(invoice.changePaise)}</span>
            </div>
          )}
        </div>
        <Totals document={invoice} label="Total" />
      </div>
    </>
  )
}

function CreditNoteDialog({ note, onClose }: { note: CreditNote; onClose: () => void }) {
  const body = useRef<HTMLDivElement>(null)
  useEffect(() => body.current?.focus(), [])
  return (
    <Modal title={`Return ${note.creditNoteNumber}`} onClose={onClose} wide>
      <div ref={body} tabIndex={-1} className="outline-none">
        <div className="mb-3 flex flex-wrap gap-x-6 gap-y-1 text-sm text-slate-500">
          <span>{new Date(note.issuedAt).toLocaleString()}</span>
          <span>By {note.cashier}</span>
          <span>Till {note.terminalCode}</span>
          <span>{note.originalInvoiceNumber ? `Against bill ${note.originalInvoiceNumber}` : 'Without a bill'}</span>
          {note.reason && <span>Reason: {note.reason}</span>}
        </div>
        <LinesTable lines={note.lines} taxInvoice={note.taxInvoice} />
        <div className="mt-4 grid grid-cols-2 gap-6 text-sm">
          <div>
            <h4 className="mb-1 text-xs uppercase tracking-wide text-slate-400">Paid back by</h4>
            {note.refunds.map((refund, index) => (
              <div key={index} className="flex justify-between text-slate-600">
                <span>{refund.mode}{refund.reference ? ` · ${refund.reference}` : ''}</span>
                <span className="num">{rupees(refund.amountPaise)}</span>
              </div>
            ))}
          </div>
          <Totals document={note} label="Refund" />
        </div>
      </div>
    </Modal>
  )
}

function LinesTable({ lines, taxInvoice }: { lines: Invoice['lines']; taxInvoice: boolean }) {
  return (
    <table className="w-full text-sm">
      <thead className="text-xs uppercase tracking-wide text-slate-400">
        <tr>
          <th className="py-1 text-left">Item</th>
          <th className="py-1 text-left">HSN</th>
          <th className="py-1 text-right">Rate</th>
          <th className="py-1 text-right">Qty</th>
          {taxInvoice && <th className="py-1 text-right">GST</th>}
          <th className="py-1 text-right">Amount</th>
        </tr>
      </thead>
      <tbody>
        {lines.map((line) => (
          <tr key={line.lineNo} className="border-t border-slate-100">
            <td className="py-1.5">{line.name}</td>
            <td className="num py-1.5 text-slate-500">{line.hsnCode || '—'}</td>
            <td className="num py-1.5 text-right">{rupees(line.unitPricePaise)}</td>
            <td className="num py-1.5 text-right">{quantity(line.quantityMilli)} {line.unit}</td>
            {taxInvoice && <td className="num py-1.5 text-right text-slate-500">{percent(line.gstRateBp)}</td>}
            <td className="num py-1.5 text-right">{rupees(line.lineTotalPaise)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function Totals({ document, label }: { document: Invoice | CreditNote; label: string }) {
  return (
    <div>
      {document.taxInvoice && (
        <>
          <Row label="Taxable value" value={document.taxableValuePaise} />
          {document.igstPaise > 0
            ? <Row label="IGST" value={document.igstPaise} />
            : (
              <>
                <Row label="CGST" value={document.cgstPaise} />
                <Row label="SGST" value={document.sgstPaise} />
              </>
            )}
        </>
      )}
      {document.roundOffPaise !== 0 && <Row label="Round off" value={document.roundOffPaise} />}
      <div className="mt-1 flex justify-between border-t border-slate-200 pt-1 text-base font-semibold">
        <span>{label}</span><span className="num">{rupees(document.grandTotalPaise)}</span>
      </div>
    </div>
  )
}

function Row({ label, value }: { label: string; value: number }) {
  return (
    <div className="flex justify-between text-slate-600">
      <span>{label}</span><span className="num">{rupees(value)}</span>
    </div>
  )
}

function CancelDialog({ invoice, onClose, onDone, onToast }: {
  invoice: Invoice
  onClose: () => void
  onDone: () => void
  onToast: Toast
}) {
  const [reason, setReason] = useState('')

  const submit = async () => {
    if (!reason.trim()) {
      return
    }
    try {
      await api.post(`/api/invoices/${invoice.id}/cancel`, { reason })
      onToast({ tone: 'good', text: `${invoice.invoiceNumber} cancelled and stock returned` })
      onDone()
    } catch (failure) {
      onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not cancel') })
    }
  }

  return (
    <Modal title={`Cancel ${invoice.invoiceNumber}`} onClose={onClose}>
      <form
        className="space-y-3"
        onSubmit={(event) => {
          event.preventDefault()
          void submit()
        }}
      >
        <p className="text-sm text-slate-600">
          The bill stays in the books with its number, marked cancelled, and the stock goes back on the shelf.
          Nothing is deleted. To change only some items, use Edit (today's bills) or Return instead.
        </p>
        <Field label="Reason" hint="Recorded in the audit trail against your name">
          <input
            className={inputClass}
            autoFocus
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            // Enter confirms on every keyboard, not only through implicit form submission.
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                event.preventDefault()
                void submit()
              }
            }}
          />
        </Field>
        <div className="flex justify-end gap-2">
          <Button onClick={onClose} hint="Esc">Keep it</Button>
          <Button type="submit" tone="danger" disabled={!reason.trim()}>Cancel the bill</Button>
        </div>
      </form>
    </Modal>
  )
}
