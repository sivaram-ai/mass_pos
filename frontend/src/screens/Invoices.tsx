import { useCallback, useEffect, useState } from 'react'
import { api, percent, quantity, rupees } from '../api'
import type { Invoice, Role } from '../types'
import { Button, Empty, Field, inputClass, Modal, Panel } from '../components/ui'
import type { Toast } from '../App'

function today(): string {
  return new Date().toISOString().slice(0, 10)
}

export default function Invoices({ role, onToast }: { role: Role; onToast: Toast }) {
  const [date, setDate] = useState(today())
  const [invoices, setInvoices] = useState<Invoice[]>([])
  const [open, setOpen] = useState<Invoice | null>(null)
  const [cancelling, setCancelling] = useState<Invoice | null>(null)
  const canCancel = role === 'MANAGER' || role === 'ADMIN'

  const load = useCallback(() => {
    api.get<Invoice[]>(`/api/invoices?from=${date}&to=${date}`)
      .then(setInvoices)
      .catch((failure) => onToast({ tone: 'bad', text: failure.message }))
  }, [date, onToast])

  useEffect(load, [load])

  const reprint = async (invoice: Invoice) => {
    try {
      await api.post(`/api/invoices/${invoice.id}/receipt`, { copy: 'DUPLICATE', openDrawer: false })
      onToast({ tone: 'good', text: `Reprinted ${invoice.invoiceNumber}` })
    } catch (failure) {
      onToast({ tone: 'bad', text: failure instanceof Error ? failure.message : 'Could not reprint' })
    }
  }

  const takings = invoices
    .filter((invoice) => invoice.status === 'ISSUED')
    .reduce((sum, invoice) => sum + invoice.grandTotalPaise, 0)

  return (
    <Panel
      title="Bills"
      actions={
        <div className="flex items-center gap-3">
          <span className="text-xs text-slate-500">
            {invoices.length} bills · <span className="num">{rupees(takings)}</span>
          </span>
          <input type="date" className={inputClass} value={date} onChange={(event) => setDate(event.target.value)} />
        </div>
      }
    >
      {invoices.length === 0 ? (
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
            {invoices.map((invoice) => (
              <tr key={invoice.id} className="border-t border-slate-100">
                <td className="num py-2">
                  <button className="font-medium text-sky-700 hover:underline" onClick={() => setOpen(invoice)}>
                    {invoice.invoiceNumber}
                  </button>
                  {invoice.status === 'CANCELLED' && (
                    <span className="ml-2 rounded bg-rose-100 px-1.5 py-0.5 text-xs text-rose-700">cancelled</span>
                  )}
                </td>
                <td className="num py-2 text-slate-500">
                  {new Date(invoice.issuedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                </td>
                <td className="py-2 text-slate-600">{invoice.cashier}</td>
                <td className="py-2 text-slate-600">
                  {invoice.buyerName || <span className="text-slate-400">Walk-in</span>}
                  {invoice.buyerGstin && <div className="num text-xs text-slate-400">{invoice.buyerGstin}</div>}
                </td>
                <td className="num py-2 text-right font-medium">{rupees(invoice.grandTotalPaise)}</td>
                <td className="py-2 text-right">
                  <Button tone="ghost" onClick={() => reprint(invoice)}>Reprint</Button>
                  {canCancel && invoice.status === 'ISSUED' && (
                    <Button tone="ghost" onClick={() => setCancelling(invoice)}>Cancel</Button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {open && <InvoiceDialog invoice={open} onClose={() => setOpen(null)} />}
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
        {invoice.status === 'CANCELLED' && <span className="text-rose-600">Cancelled: {invoice.cancelReason}</span>}
      </div>
      <table className="w-full text-sm">
        <thead className="text-xs uppercase tracking-wide text-slate-400">
          <tr>
            <th className="py-1 text-left">Item</th>
            <th className="py-1 text-left">HSN</th>
            <th className="py-1 text-right">Rate</th>
            <th className="py-1 text-right">Qty</th>
            {invoice.taxInvoice && <th className="py-1 text-right">GST</th>}
            <th className="py-1 text-right">Amount</th>
          </tr>
        </thead>
        <tbody>
          {invoice.lines.map((line) => (
            <tr key={line.lineNo} className="border-t border-slate-100">
              <td className="py-1.5">{line.name}</td>
              <td className="num py-1.5 text-slate-500">{line.hsnCode || '—'}</td>
              <td className="num py-1.5 text-right">{rupees(line.unitPricePaise)}</td>
              <td className="num py-1.5 text-right">{quantity(line.quantityMilli)} {line.unit}</td>
              {invoice.taxInvoice && <td className="num py-1.5 text-right text-slate-500">{percent(line.gstRateBp)}</td>}
              <td className="num py-1.5 text-right">{rupees(line.lineTotalPaise)}</td>
            </tr>
          ))}
        </tbody>
      </table>
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
        <div>
          {invoice.taxInvoice && (<>
          <div className="flex justify-between text-slate-600">
            <span>Taxable value</span><span className="num">{rupees(invoice.taxableValuePaise)}</span>
          </div>
          {invoice.igstPaise > 0 ? (
            <div className="flex justify-between text-slate-600">
              <span>IGST</span><span className="num">{rupees(invoice.igstPaise)}</span>
            </div>
          ) : (
            <>
              <div className="flex justify-between text-slate-600">
                <span>CGST</span><span className="num">{rupees(invoice.cgstPaise)}</span>
              </div>
              <div className="flex justify-between text-slate-600">
                <span>SGST</span><span className="num">{rupees(invoice.sgstPaise)}</span>
              </div>
            </>
          )}
          </>)}
          {invoice.roundOffPaise !== 0 && (
            <div className="flex justify-between text-slate-600">
              <span>Round off</span><span className="num">{rupees(invoice.roundOffPaise)}</span>
            </div>
          )}
          <div className="mt-1 flex justify-between border-t border-slate-200 pt-1 text-base font-semibold">
            <span>Total</span><span className="num">{rupees(invoice.grandTotalPaise)}</span>
          </div>
        </div>
      </div>
    </Modal>
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
      onToast({ tone: 'bad', text: failure instanceof Error ? failure.message : 'Could not cancel' })
    }
  }

  return (
    <Modal title={`Cancel ${invoice.invoiceNumber}`} onClose={onClose}>
      <form onSubmit={submit} className="space-y-3">
        <p className="text-sm text-slate-600">
          The bill stays in the books with its number, marked cancelled, and the stock goes back on the shelf.
          Nothing is deleted.
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
