import { useCallback, useEffect, useState } from 'react'
import { api, percent, quantity, rupees } from '../api'
import type { AuditEntry, DayReport, GstReport } from '../types'
import { Empty, inputClass, Panel } from '../components/ui'
import type { Toast } from '../App'

type Tab = 'day' | 'gst' | 'audit'

function today(): string {
  return new Date().toISOString().slice(0, 10)
}

function firstOfMonth(): string {
  return today().slice(0, 8) + '01'
}

export default function Reports({ onToast }: { onToast: Toast }) {
  const [tab, setTab] = useState<Tab>('day')
  const [date, setDate] = useState(today())
  const [from, setFrom] = useState(firstOfMonth())
  const [to, setTo] = useState(today())
  const [day, setDay] = useState<DayReport | null>(null)
  const [gst, setGst] = useState<GstReport | null>(null)
  const [audit, setAudit] = useState<AuditEntry[]>([])

  const fail = useCallback((failure: unknown) => {
    onToast({ tone: 'bad', text: failure instanceof Error ? failure.message : 'Could not load the report' })
  }, [onToast])

  useEffect(() => {
    if (tab === 'day') {
      api.get<DayReport>(`/api/reports/day?date=${date}`).then(setDay).catch(fail)
    } else if (tab === 'gst') {
      api.get<GstReport>(`/api/reports/gst?from=${from}&to=${to}`).then(setGst).catch(fail)
    } else {
      api.get<AuditEntry[]>(`/api/reports/audit?from=${from}&to=${to}&limit=300`).then(setAudit).catch(fail)
    }
  }, [tab, date, from, to, fail])

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <div className="flex rounded-md border border-slate-200 bg-white p-1">
          {([['day', "Day's takings"], ['gst', 'GST summary'], ['audit', 'Audit trail']] as [Tab, string][])
            .map(([id, label]) => (
              <button
                key={id}
                onClick={() => setTab(id)}
                className={`rounded px-3 py-1.5 text-sm ${
                  tab === id ? 'bg-slate-800 font-medium text-white' : 'text-slate-600 hover:bg-slate-100'
                }`}
              >
                {label}
              </button>
            ))}
        </div>
        {tab === 'day' ? (
          <input type="date" className={`${inputClass} w-auto`} value={date}
                 onChange={(event) => setDate(event.target.value)} />
        ) : (
          <div className="flex items-center gap-2">
            <input type="date" className={`${inputClass} w-auto`} value={from}
                   onChange={(event) => setFrom(event.target.value)} />
            <span className="text-sm text-slate-400">to</span>
            <input type="date" className={`${inputClass} w-auto`} value={to}
                   onChange={(event) => setTo(event.target.value)} />
          </div>
        )}
      </div>

      {tab === 'day' && day && (
        <div className="grid gap-4 lg:grid-cols-2">
          <Panel title="Takings">
            <div className="mb-3 flex items-baseline justify-between">
              <span className="text-sm text-slate-500">{day.bills} bills
                {day.cancelledBills > 0 && `, ${day.cancelledBills} cancelled`}
                {day.returns > 0 && `, ${day.returns} return${day.returns === 1 ? '' : 's'}`}</span>
              <span className="num text-3xl font-semibold">{rupees(day.totals.grandTotalPaise)}</span>
            </div>
            <dl className="space-y-1 text-sm">
              {day.returns > 0 && (
                <>
                  <Line label="Sales" value={rupees(day.salesTotals.grandTotalPaise)} />
                  <Line label="Less returns" value={`-${rupees(day.returnTotals.grandTotalPaise)}`} />
                </>
              )}
              <Line label="Taxable value" value={rupees(day.totals.taxableValuePaise)} />
              <Line label="CGST" value={rupees(day.totals.cgstPaise)} />
              <Line label="SGST" value={rupees(day.totals.sgstPaise)} />
              {day.totals.igstPaise > 0 && <Line label="IGST" value={rupees(day.totals.igstPaise)} />}
              <Line label="Round off" value={rupees(day.totals.roundOffPaise)} />
            </dl>
          </Panel>

          <Panel title="How it was paid">
            {day.payments.length === 0 ? <Empty>Nothing yet today</Empty> : (
              <dl className="space-y-1 text-sm">
                {day.payments.map((row) => (
                  <Line key={row.mode} label={`${row.mode} (${row.bills})`} value={rupees(row.amountPaise)} />
                ))}
              </dl>
            )}
          </Panel>

          <Panel title="By cashier">
            {day.cashiers.length === 0 ? <Empty>Nothing yet today</Empty> : (
              <dl className="space-y-1 text-sm">
                {day.cashiers.map((row) => (
                  <Line key={row.name} label={`${row.name} (${row.bills})`} value={rupees(row.amountPaise)} />
                ))}
              </dl>
            )}
          </Panel>

          <Panel title="Best sellers">
            {day.bestSellers.length === 0 ? <Empty>Nothing yet today</Empty> : (
              <dl className="space-y-1 text-sm">
                {day.bestSellers.map((row) => (
                  <Line key={row.name} label={`${row.name} · ${quantity(row.quantityMilli)}`}
                        value={rupees(row.amountPaise)} />
                ))}
              </dl>
            )}
          </Panel>

          <Panel title="By till" className="lg:col-span-2">
            {day.terminals.length === 0 ? <Empty>Nothing yet today</Empty> : (
              <dl className="space-y-1 text-sm">
                {day.terminals.map((row) => (
                  <Line key={row.name} label={`${row.name} (${row.bills} bills)`} value={rupees(row.amountPaise)} />
                ))}
              </dl>
            )}
          </Panel>
        </div>
      )}

      {tab === 'gst' && gst && (
        <div className="space-y-4">
          <Panel title="Rate-wise summary" actions={<span className="text-xs text-slate-500">for GSTR-1</span>}>
            <table className="w-full text-sm">
              <thead className="text-xs uppercase tracking-wide text-slate-400">
                <tr>
                  <th className="py-1 text-left">Rate</th>
                  <th className="py-1 text-right">Taxable value</th>
                  <th className="py-1 text-right">CGST</th>
                  <th className="py-1 text-right">SGST</th>
                  <th className="py-1 text-right">IGST</th>
                  <th className="py-1 text-right">Cess</th>
                </tr>
              </thead>
              <tbody>
                {gst.rates.map((row) => (
                  <tr key={row.gstRateBp} className="border-t border-slate-100">
                    <td className="num py-1.5">{percent(row.gstRateBp)}</td>
                    <td className="num py-1.5 text-right">{rupees(row.taxableValuePaise)}</td>
                    <td className="num py-1.5 text-right">{rupees(row.cgstPaise)}</td>
                    <td className="num py-1.5 text-right">{rupees(row.sgstPaise)}</td>
                    <td className="num py-1.5 text-right">{rupees(row.igstPaise)}</td>
                    <td className="num py-1.5 text-right">{rupees(row.cessPaise)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {gst.rates.length === 0 && <Empty>No sales in this period</Empty>}
          </Panel>

          <Panel title="HSN summary" actions={<span className="text-xs text-slate-500">GSTR-1 table 12</span>}>
            <table className="w-full text-sm">
              <thead className="text-xs uppercase tracking-wide text-slate-400">
                <tr>
                  <th className="py-1 text-left">HSN</th>
                  <th className="py-1 text-left">UQC</th>
                  <th className="py-1 text-right">Quantity</th>
                  <th className="py-1 text-right">Taxable value</th>
                  <th className="py-1 text-right">Tax</th>
                </tr>
              </thead>
              <tbody>
                {gst.hsn.map((row) => (
                  <tr key={`${row.hsnCode}-${row.uqc}`} className="border-t border-slate-100">
                    <td className="num py-1.5">{row.hsnCode || <span className="text-slate-400">Not set</span>}</td>
                    <td className="py-1.5 text-slate-500">{row.uqc}</td>
                    <td className="num py-1.5 text-right">{quantity(row.quantityMilli)}</td>
                    <td className="num py-1.5 text-right">{rupees(row.taxableValuePaise)}</td>
                    <td className="num py-1.5 text-right">
                      {rupees(row.cgstPaise + row.sgstPaise + row.igstPaise + row.cessPaise)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {gst.hsn.length === 0 && <Empty>No sales in this period</Empty>}
          </Panel>
        </div>
      )}

      {tab === 'audit' && (
        <Panel title="Audit trail" actions={<span className="text-xs text-slate-500">MCA Rule 11(g)</span>}>
          {audit.length === 0 ? <Empty>No recorded changes in this period</Empty> : (
            <table className="w-full text-sm">
              <thead className="text-xs uppercase tracking-wide text-slate-400">
                <tr>
                  <th className="py-1 text-left">When</th>
                  <th className="py-1 text-left">Who</th>
                  <th className="py-1 text-left">Till</th>
                  <th className="py-1 text-left">What</th>
                  <th className="py-1 text-left">Change</th>
                </tr>
              </thead>
              <tbody>
                {audit.map((entry, index) => (
                  <tr key={index} className="border-t border-slate-100">
                    <td className="num py-1.5 text-slate-500">{new Date(entry.at).toLocaleString()}</td>
                    <td className="py-1.5">{entry.user}</td>
                    <td className="num py-1.5 text-slate-500">{entry.terminal}</td>
                    <td className="py-1.5 text-slate-700">{entry.entity.replace(/_/g, ' ')}</td>
                    <td className="py-1.5">
                      <span className={`rounded px-1.5 py-0.5 text-xs ${
                        entry.change === 'Created' ? 'bg-emerald-100 text-emerald-700'
                          : entry.change === 'Deleted' ? 'bg-rose-100 text-rose-700'
                            : 'bg-amber-100 text-amber-700'
                      }`}>{entry.change}</span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Panel>
      )}
    </div>
  )
}

function Line({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between">
      <dt className="text-slate-500">{label}</dt>
      <dd className="num text-slate-800">{value}</dd>
    </div>
  )
}
