import { useCallback, useEffect, useState } from 'react'
import { api, messageOfFailure, percent, quantity, rupees } from '../api'
import type { CreditNote, Invoice, Role } from '../types'
import { Button, Empty, Field, inputClass, Modal, Panel } from '../components/ui'
import type { Toast } from '../App'

/** The till's own calendar date, as bill dates are written: 2026-09-13. */
function today(): string {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
}

type Entry = { kind: 'bill'; at: string; bill: Invoice } | { kind: 'return'; at: string; note: CreditNote }

/**
 * The day's bills and returns, newest first. A manager edits today's bill or takes a return against
 * any bill from here; both open on the billing screen.
 */
export default function Invoices({ role, onToast, onOpenInBilling }: {
  role: Role
  onToast: Toast
  onOpenInBilling?: (kind: 'edit' | 'return', invoiceId: string | null) => void
}) {
  const [date, setDate] = useState(today())
  const [entries, setEntries] = useState<Entry[]>([])
  const [open, setOpen] = useState<Invoice | null>(null)
  const [openNote, setOpenNote] = useState<CreditNote | null>(null)
  const [cancelling, setCancelling] = useState<Invoice | null>(null)
  const canManage = role === 'MANAGER' || role === 'ADMIN'

  const load = useCallback(() => {
    Promise.all([
      api.get<Invoice[]>(`/api/invoices?from=${date}&to=${date}`),
      api.get<CreditNote[]>(`/api/returns?from=${date}&to=${date}`),
    ])
      .then(([bills, notes]) => setEntries([
        ...bills.map((bill): Entry => ({ kind: 'bill', at: bill.issuedAt, bill })),
        ...notes.map((note): Entry => ({ kind: 'return', at: note.issuedAt, note })),
      ].sort((a, b) => b.at.localeCompare(a.at))))
      .catch((failure) => onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not load the bills') }))
  }, [date, onToast])

  useEffect(load, [load])

  const reprint = async (entry: Entry) => {
    const [path, number] = entry.kind === 'bill'
      ? [`/api/invoices/${entry.bill.id}/receipt`, entry.bill.invoiceNumber]
      : [`/api/returns/${entry.note.id}/receipt`, entry.note.creditNoteNumber]
    try {
      await api.post(path, { copy: 'DUPLICATE', openDrawer: false })
      onToast({ tone: 'good', text: `Reprinted ${number}` })
    } catch (failure) {
      onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not reprint') })
    }
  }

  const bills = entries.flatMap((entry) => (entry.kind === 'bill' ? [entry.bill] : []))
  const notes = entries.flatMap((entry) => (entry.kind === 'return' ? [entry.note] : []))
  const takings = bills.filter((bill) => bill.status === 'ISSUED').reduce((sum, bill) => sum + bill.grandTotalPaise, 0)
  const refunded = notes.reduce((sum, note) => sum + note.grandTotalPaise, 0)
  // A bill with a return against it has had stock and money put back once; the server refuses to
  // cancel or edit it, so the buttons are not offered for the returns in view.
  const returnedBills = new Set(notes.map((note) => note.originalInvoiceId).filter(Boolean))

  return (
    <Panel
      title="Bills and returns"
      actions={
        <div className="flex items-center gap-3">
          <span className="text-xs text-slate-500">
            {bills.length} bills
            {notes.length > 0 && <> · {notes.length} return{notes.length === 1 ? '' : 's'} (<span className="num">-{rupees(refunded)}</span>)</>}
            {' · net '}<span className="num font-medium text-slate-700">{rupees(takings - refunded)}</span>
          </span>
          {canManage && onOpenInBilling && (
            <Button onClick={() => onOpenInBilling('return', null)}>Return without a bill</Button>
          )}
          <input type="date" className={inputClass} value={date} onChange={(event) => setDate(event.target.value)} />
        </div>
      }
    >
      {entries.length === 0 ? (
        <Empty>No bills on {date}</Empty>
      ) : (
        <table className="w-full text-sm">
          <thead className="text-xs uppercase tracking-wide text-slate-400">
            <tr>
              <th className="py-1 text-left">Number</th>
              <th className="py-1 text-left">Time</th>
              <th className="py-1 text-left">Cashier</th>
              <th className="py-1 text-left">Customer</th>
              <th className="py-1 text-right">Total</th>
              <th className="py-1"></th>
            </tr>
          </thead>
          <tbody>
            {entries.map((entry) => entry.kind === 'bill' ? (
              <tr key={entry.bill.id} className="border-t border-slate-100">
                <td className="num py-2">
                  <button className="font-medium text-sky-700 hover:underline" onClick={() => setOpen(entry.bill)}>
                    {entry.bill.invoiceNumber}
                  </button>
                  {entry.bill.status === 'CANCELLED' && (
                    <span className="ml-2 rounded bg-rose-100 px-1.5 py-0.5 text-xs text-rose-700">cancelled</span>
                  )}
                  {entry.bill.replacesInvoiceNumber && (
                    <span className="ml-2 rounded bg-amber-100 px-1.5 py-0.5 text-xs text-amber-800">
                      replaces {entry.bill.replacesInvoiceNumber}
                    </span>
                  )}
                </td>
                <td className="num py-2 text-slate-500">{timeOf(entry.bill.issuedAt)}</td>
                <td className="py-2 text-slate-600">{entry.bill.cashier}</td>
                <td className="py-2 text-slate-600">
                  {entry.bill.buyerName || <span className="text-slate-400">Walk-in</span>}
                  {entry.bill.buyerGstin && <div className="num text-xs text-slate-400">{entry.bill.buyerGstin}</div>}
                </td>
                <td className={`num py-2 text-right font-medium ${entry.bill.status === 'CANCELLED' ? 'text-slate-400 line-through' : ''}`}>
                  {rupees(entry.bill.grandTotalPaise)}
                </td>
                <td className="whitespace-nowrap py-2 text-right">
                  <Button tone="ghost" onClick={() => reprint(entry)}>Reprint</Button>
                  {canManage && onOpenInBilling && entry.bill.status === 'ISSUED' && (
                    <>
                      {entry.bill.invoiceDate === today() && !returnedBills.has(entry.bill.id) && (
                        <Button tone="ghost" onClick={() => onOpenInBilling('edit', entry.bill.id)}>Edit</Button>
                      )}
                      <Button tone="ghost" onClick={() => onOpenInBilling('return', entry.bill.id)}>Return</Button>
                    </>
                  )}
                  {canManage && entry.bill.status === 'ISSUED' && !returnedBills.has(entry.bill.id) && (
                    <Button tone="ghost" onClick={() => setCancelling(entry.bill)}>Cancel</Button>
                  )}
                </td>
              </tr>
            ) : (
              <tr key={entry.note.id} className="border-t border-slate-100 bg-rose-50/40">
                <td className="num py-2">
                  <button className="font-medium text-rose-700 hover:underline" onClick={() => setOpenNote(entry.note)}>
                    {entry.note.creditNoteNumber}
                  </button>
                  <span className="ml-2 rounded bg-rose-100 px-1.5 py-0.5 text-xs text-rose-700">
                    return{entry.note.originalInvoiceNumber ? ` on ${entry.note.originalInvoiceNumber}` : ''}
                  </span>
                </td>
                <td className="num py-2 text-slate-500">{timeOf(entry.note.issuedAt)}</td>
                <td className="py-2 text-slate-600">{entry.note.cashier}</td>
                <td className="py-2 text-slate-600">
                  {entry.note.buyerName || <span className="text-slate-400">Walk-in</span>}
                </td>
                <td className="num py-2 text-right font-medium text-rose-700">-{rupees(entry.note.grandTotalPaise)}</td>
                <td className="py-2 text-right">
                  <Button tone="ghost" onClick={() => reprint(entry)}>Reprint</Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {open && <InvoiceDialog invoice={open} onClose={() => setOpen(null)} />}
      {openNote && <CreditNoteDialog note={openNote} onClose={() => setOpenNote(null)} />}
      {cancelling && (
        <CancelDialog
          invoice={cancelling}
          onClose={() => setCancelling(null)}
          onDone={() => {
            setCancelling(null)
            load()
          }}
          onToast={onToast}
        />
      )}
    </Panel>
  )
}

function timeOf(instant: string): string {
  return new Date(instant).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

function InvoiceDialog({ invoice, onClose }: { invoice: Invoice; onClose: () => void }) {
  return (
    <Modal title={`Bill ${invoice.invoiceNumber}`} onClose={onClose} wide>
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
          <h3 className="mb-1 text-xs uppercase tracking-wide text-slate-400">Paid by</h3>
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
    </Modal>
  )
}

function CreditNoteDialog({ note, onClose }: { note: CreditNote; onClose: () => void }) {
  return (
    <Modal title={`Return ${note.creditNoteNumber}`} onClose={onClose} wide>
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
          <h3 className="mb-1 text-xs uppercase tracking-wide text-slate-400">Paid back by</h3>
          {note.refunds.map((refund, index) => (
            <div key={index} className="flex justify-between text-slate-600">
              <span>{refund.mode}{refund.reference ? ` · ${refund.reference}` : ''}</span>
              <span className="num">{rupees(refund.amountPaise)}</span>
            </div>
          ))}
        </div>
        <Totals document={note} label="Refund" />
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

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
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
      <form onSubmit={submit} className="space-y-3">
        <p className="text-sm text-slate-600">
          The bill stays in the books with its number, marked cancelled, and the stock goes back on the shelf.
          Nothing is deleted. To change only some items, use Edit (today's bills) or Return instead.
        </p>
        <Field label="Reason" hint="Recorded in the audit trail against your name">
          <input className={inputClass} autoFocus value={reason} onChange={(event) => setReason(event.target.value)} />
        </Field>
        <div className="flex justify-end gap-2">
          <Button onClick={onClose}>Keep it</Button>
          <Button type="submit" tone="danger" disabled={!reason.trim()}>Cancel the bill</Button>
        </div>
      </form>
    </Modal>
  )
}
