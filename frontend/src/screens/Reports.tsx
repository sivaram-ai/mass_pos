import { useCallback, useEffect, useRef, useState } from 'react'
import { api, downloadCsv, messageOfFailure, percent, quantity, rupees } from '../api'
import type { AuditEntry, DayReport, GstReport, SoldItem } from '../types'
import { Button, Empty, inputClass, Modal, Panel } from '../components/ui'
import type { Toast } from '../App'
import { useScreenKeys } from '../shortcuts'

type Tab = 'day' | 'gst' | 'audit'

const AUDIT_ROWS = 300
/** How many of the sold items the panel calls the best sellers; View All shows the rest. */
const BEST_SELLERS = 10

function today(): string {
  return new Date().toISOString().slice(0, 10)
}

function firstOfMonth(): string {
  return today().slice(0, 8) + '01'
}

/**
 * The takings report goes by the moment a bill was issued, so its boxes carry a time. Built from
 * the local parts rather than an ISO string, which is UTC and names yesterday after midnight here.
 */
function pad(value: number): string {
  return String(value).padStart(2, '0')
}

function localDay(): string {
  const now = new Date()
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}

function endOfDay(moment: string): string {
  return `${moment.slice(0, 10)}T23:59`
}

export default function Reports({ onToast }: { onToast: Toast }) {
  const [tab, setTab] = useState<Tab>('day')
  const [date, setDate] = useState(`${localDay()}T00:00`)
  const [dayTo, setDayTo] = useState(`${localDay()}T23:59`)
  const [dayRange, setDayRange] = useState(false)
  const [from, setFrom] = useState(firstOfMonth())
  const [to, setTo] = useState(today())
  const [day, setDay] = useState<DayReport | null>(null)
  const [gst, setGst] = useState<GstReport | null>(null)
  const [audit, setAudit] = useState<AuditEntry[]>([])
  const [showAllSold, setShowAllSold] = useState(false)
  const [printing, setPrinting] = useState<'asking' | 'busy' | null>(null)

  const fail = useCallback((failure: unknown) => {
    onToast({ tone: 'bad', text: failure instanceof Error ? failure.message : 'Could not load the report' })
  }, [onToast])

  /** The window the open tab is showing, which is also the window its sheet is exported for. */
  const period = tab === 'day'
    ? (dayRange ? `from=${date}&to=${dayTo}` : `from=${date}`)
    : `from=${from}&to=${to}`

  useEffect(() => {
    if (tab === 'day') {
      // Without a range the end is left off, and the server runs to the end of that day.
      api.get<DayReport>(`/api/reports/day?${period}`).then(setDay).catch(fail)
    } else if (tab === 'gst') {
      api.get<GstReport>(`/api/reports/gst?${period}`).then(setGst).catch(fail)
    } else {
      api.get<AuditEntry[]>(`/api/reports/audit?${period}&limit=${AUDIT_ROWS}`).then(setAudit).catch(fail)
    }
  }, [tab, period, fail])

  /**
   * The open tab as a sheet, for the window on screen. The server builds it with the same method
   * that answered the screen, so the figures cannot differ; the name carries the window and the
   * moment it was taken, because a file in a folder has nothing else to say which report it is.
   */
  const exportSheet = async () => {
    const stamp = (moment: string) => moment.replace(/:/g, '-')
    const range = tab === 'day'
      ? (dayRange ? `${stamp(date)}_to_${stamp(dayTo)}` : stamp(date))
      : `${from}_to_${to}`
    const now = new Date()
    const exportedAt = `${localDay()}_${pad(now.getHours())}-${pad(now.getMinutes())}`
    try {
      await downloadCsv(`/api/reports/export?report=${tab}&${period}&limit=${AUDIT_ROWS}`,
        `billing_reports_${tab}_${range}_exported_${exportedAt}.csv`)
    } catch (failure) {
      onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not make the file') })
    }
  }

  /** The day's takings on the till's printer, with as much of what sold as was asked for. */
  const printReport = async (items: 'ALL' | 'BEST_SELLERS') => {
    setPrinting('busy')
    try {
      await api.post(`/api/reports/print?${period}&items=${items}`)
      onToast({ tone: 'good', text: 'Report sent to the printer' })
    } catch (failure) {
      onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not print the report') })
    } finally {
      setPrinting(null)
    }
  }

  const toggleRange = () => {
    if (!dayRange) {
      setDayTo(endOfDay(date))
    }
    setDayRange(!dayRange)
  }

  /** A dialog is open: the screen's own keys stand down so they never act behind it. */
  const dialogOpen = printing !== null || showAllSold

  // Screen keys: F2-F4 the three reports, F6 print, F7 export, F8 single day / date range.
  useScreenKeys({
    F2: () => setTab('day'),
    F3: () => setTab('gst'),
    F4: () => setTab('audit'),
    F6: tab === 'day' && printing === null ? () => setPrinting('asking') : undefined,
    F7: () => void exportSheet(),
    F8: tab === 'day' ? toggleRange : undefined,
  }, !dialogOpen)

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <div className="flex rounded-md border border-slate-200 bg-white p-1">
          {([['day', "Day's takings", 'F2'], ['gst', 'GST summary', 'F3'], ['audit', 'Audit trail', 'F4']] as [Tab, string, string][])
            .map(([id, label, key]) => (
              <button
                key={id}
                onClick={() => setTab(id)}
                className={`rounded px-3 py-1.5 text-sm ${
                  tab === id ? 'bg-slate-800 font-medium text-white' : 'text-slate-600 hover:bg-slate-100'
                }`}
              >
                {label} <kbd>{key}</kbd>
              </button>
            ))}
        </div>
        {tab === 'day' ? (
          <div className="flex items-center gap-2">
            <input type="datetime-local" className={`${inputClass} w-auto`} value={date}
                   onChange={(event) => setDate(event.target.value)} />
            {dayRange && (
              <>
                <span className="text-sm text-slate-400">to</span>
                <input type="datetime-local" className={`${inputClass} w-auto`} value={dayTo}
                       onChange={(event) => setDayTo(event.target.value)} />
              </>
            )}
            <Button
              onClick={toggleRange}
              hint="F8"
              title={dayRange ? 'Back to a single day' : 'Add the takings up over a stretch of days'}
            >
              {dayRange ? 'Single day' : 'Choose date range'}
            </Button>
          </div>
        ) : (
          <div className="flex items-center gap-2">
            <input type="date" className={`${inputClass} w-auto`} value={from}
                   onChange={(event) => setFrom(event.target.value)} />
            <span className="text-sm text-slate-400">to</span>
            <input type="date" className={`${inputClass} w-auto`} value={to}
                   onChange={(event) => setTo(event.target.value)} />
          </div>
        )}
        <div className="ml-auto flex items-center gap-2">
          {tab === 'day' && (
            <Button className="whitespace-nowrap" onClick={() => setPrinting('asking')} hint="F6"
                    disabled={printing === 'busy'}
                    title="Print the day's takings on this till's printer">
              {printing === 'busy' ? 'Printing…' : 'Print report'}
            </Button>
          )}
          <Button className="whitespace-nowrap" onClick={() => void exportSheet()} hint="F7"
                  title="Save what is on screen as a sheet you can open in Excel">
            Export
          </Button>
        </div>
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

          {/* The best sellers are the head of the sold list, so the two can never disagree. */}
          <Panel title="Best sellers"
                 actions={day.soldItems.length > 0
                   ? <span className="text-xs text-slate-500">most sold first</span>
                   : undefined}>
            {day.soldItems.length === 0 ? <Empty>Nothing yet today</Empty> : (
              <>
                <dl className="space-y-1 text-sm">
                  {day.soldItems.slice(0, BEST_SELLERS).map((row) => (
                    <Line key={row.name} label={`${row.name} · ${quantity(row.quantityMilli)}`}
                          value={rupees(row.amountPaise)} />
                  ))}
                </dl>
                <div className="mt-3 border-t border-slate-100 pt-2">
                  <Button className="px-2 py-1" onClick={() => setShowAllSold(true)}
                          title="Every item that sold in this window, not just the top ten">
                    View all
                    <span className="text-xs font-normal text-slate-400">{day.soldItems.length} items</span>
                  </Button>
                </div>
              </>
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

      {showAllSold && day && (
        <AllSoldDialog items={day.soldItems} onClose={() => setShowAllSold(false)} />
      )}

      {printing === 'asking' && (
        <PrintChoice day={day} onChoose={(items) => void printReport(items)} onClose={() => setPrinting(null)} />
      )}
    </div>
  )
}

/** Everything that sold in the window, in the order the best sellers panel starts with. */
function AllSoldDialog({ items, onClose }: { items: SoldItem[]; onClose: () => void }) {
  const totalQuantity = items.reduce((sum, row) => sum + row.quantityMilli, 0)
  const totalAmount = items.reduce((sum, row) => sum + row.amountPaise, 0)

  return (
    <Modal title={`All sold items (${items.length})`} onClose={onClose} size="3xl">
      <div className="max-h-[60vh] overflow-y-auto">
        <table className="w-full text-sm">
          <thead className="sticky top-0 bg-white text-xs uppercase tracking-wide text-slate-400">
            <tr>
              <th className="py-1 text-left">#</th>
              <th className="py-1 text-left">Item</th>
              <th className="py-1 text-right">Quantity</th>
              <th className="py-1 text-right">Bill lines</th>
              <th className="py-1 text-right">Amount</th>
            </tr>
          </thead>
          <tbody>
            {items.map((row, index) => (
              <tr key={row.name} className={`border-t border-slate-100 ${
                index < BEST_SELLERS ? 'bg-sky-50/50' : ''}`}>
                <td className="num py-1.5 text-slate-400">{index + 1}</td>
                <td className="py-1.5 text-slate-800">{row.name}</td>
                <td className="num py-1.5 text-right">{quantity(row.quantityMilli)}</td>
                <td className="num py-1.5 text-right text-slate-500">{row.lines}</td>
                <td className="num py-1.5 text-right">{rupees(row.amountPaise)}</td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr className="border-t-2 border-slate-200 font-medium">
              <td></td>
              <td className="py-1.5 text-slate-600">Total</td>
              <td className="num py-1.5 text-right">{quantity(totalQuantity)}</td>
              <td></td>
              <td className="num py-1.5 text-right">{rupees(totalAmount)}</td>
            </tr>
          </tfoot>
        </table>
      </div>
      <p className="mt-2 text-xs text-slate-400">
        The shaded rows are the ones the Best sellers panel shows.
      </p>
      <div className="mt-4 flex justify-end">
        <Button onClick={onClose} hint="Esc">Close</Button>
      </div>
    </Modal>
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

/**
 * "Print the day's takings?": the first option takes focus the moment it opens, ↑↓ move between the
 * options and Enter runs the one in focus, so the whole choice is made without the mouse. Focus has
 * to land in here, or a button behind the dialog would keep receiving the keys.
 */
function PrintChoice({ day, onChoose, onClose }: {
  day: DayReport | null
  onChoose: (items: 'ALL' | 'BEST_SELLERS') => void
  onClose: () => void
}) {
  const box = useRef<HTMLDivElement>(null)
  useEffect(() => {
    box.current?.querySelector('button')?.focus()
  }, [])

  const onKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    const buttons = Array.from(box.current?.querySelectorAll('button') ?? [])
    const at = buttons.indexOf(document.activeElement as HTMLButtonElement)
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      const step = event.key === 'ArrowDown' ? 1 : -1
      buttons[(at + step + buttons.length) % buttons.length]?.focus()
    } else if (event.key === 'Enter' && at >= 0) {
      // The in-app browser does not activate a focused button on Enter, so do it here.
      event.preventDefault()
      buttons[at].click()
    }
  }

  return (
    <Modal title="Print the day's takings?" onClose={onClose}>
      <p className="text-sm text-slate-600">
        The figures go on the paper either way. Choose how much of what sold goes with them.
      </p>
      <div ref={box} onKeyDown={onKeyDown} className="mt-4 flex flex-col gap-2">
        <Button tone="primary" onClick={() => onChoose('ALL')}>
          Print all sold items{day && ` (${day.soldItems.length})`}
        </Button>
        <Button onClick={() => onChoose('BEST_SELLERS')}>
          Print best sellers only{day && ` (top ${Math.min(BEST_SELLERS, day.soldItems.length)})`}
        </Button>
        <Button tone="ghost" onClick={onClose} hint="Esc">Cancel</Button>
      </div>
    </Modal>
  )
}
