import { useEffect, useState } from 'react'
import { api, fieldErrorsOf, messageOfFailure } from '../api'
import type { PrinterStatus, Role, SettingsView, ShopSettingsForm } from '../types'
import { Banner, Button, Empty, errorFor, Field, inputClass, Panel } from '../components/ui'

const FIELD_LABELS: Record<string, string> = {
  legalName: 'Legal name',
  tradeName: 'Shop name',
  gstin: 'GSTIN',
  address: 'Address',
  phone: 'Phone',
  email: 'Email',
  website: 'Website',
  cin: 'CIN',
  fssaiLicense: 'FSSAI licence',
  upiVpa: 'UPI ID',
  receiptFooter: 'Receipt footer',
  serviceProviderName: 'Service company',
  servicePhone: 'Service phone',
  serviceEmail: 'Service email',
}

/** `address[1]` → "Address line 2", in the same words the form uses. */
function labelOf(field: string): string {
  const [, name, index] = /^([^[.]+)(?:\[(\d+)])?/.exec(field) ?? [field, field]
  const label = FIELD_LABELS[name] ?? name
  return index === undefined ? label : `${label} line ${Number(index) + 1}`
}
import {
  ASSIGNABLE_KEYS, DEFAULT_SHORTCUTS, loadAskCashReceived, loadShortcuts, loadShowShortcuts, saveAskCashReceived,
  saveShortcuts, saveShowShortcuts, SHORTCUT_LABELS,
} from '../shortcuts'
import type { ShortcutAction } from '../shortcuts'
import type { Toast } from '../App'

export default function Settings({ role, settings, onSaved, onToast }: {
  role: Role
  settings: SettingsView | null
  onSaved: (settings: SettingsView) => void
  onToast: Toast
}) {
  const canEdit = role === 'ADMIN'
  const [form, setForm] = useState<ShopSettingsForm | null>(settings?.shop ?? null)
  const [shortcuts, setShortcuts] = useState(loadShortcuts)
  const [showShortcuts, setShowShortcuts] = useState(loadShowShortcuts)
  const [askCashReceived, setAskCashReceived] = useState(loadAskCashReceived)
  const [printer, setPrinter] = useState<PrinterStatus | null>(null)
  const [printerError, setPrinterError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [saveError, setSaveError] = useState<string | null>(null)

  useEffect(() => setForm(settings?.shop ?? null), [settings])

  useEffect(() => {
    api.get<PrinterStatus>('/api/hardware/printer/status')
      .then((status) => {
        setPrinter(status)
        setPrinterError(null)
      })
      .catch((failure) => setPrinterError(failure instanceof Error ? failure.message : 'Printer unreachable'))
  }, [])

  if (!settings || !form) {
    return <Empty>Loading settings…</Empty>
  }

  const set = (change: Partial<ShopSettingsForm>) => setForm({ ...form, ...change })

  const save = async () => {
    setBusy(true)
    try {
      onSaved(await api.put<SettingsView>('/api/settings', form))
      setErrors({})
      setSaveError(null)
    } catch (failure) {
      const fields = fieldErrorsOf(failure)
      setErrors(fields)
      setSaveError(Object.keys(fields).length
        ? 'Nothing was saved. Correct the fields marked in red and save again.'
        : messageOfFailure(failure, 'Could not save the settings'))
    } finally {
      setBusy(false)
    }
  }

  const err = (field: string) => errorFor(errors, field)

  const testPage = async () => {
    try {
      await api.post('/api/hardware/printer/test-page')
      onToast({ tone: 'good', text: 'Test page sent to the printer' })
    } catch (failure) {
      onToast({ tone: 'bad', text: failure instanceof Error ? failure.message : 'Could not print' })
    }
  }

  return (
    <div className="grid gap-4 xl:grid-cols-2">
      <Panel
        title="Shop details"
        className="xl:col-span-2"
        actions={canEdit
          ? <Button tone="primary" onClick={save} disabled={busy}>{busy ? 'Saving…' : 'Save settings'}</Button>
          : <span className="text-xs text-slate-500">Only an admin can change these</span>}
      >
        {saveError && (
          <div className="mb-3">
            <Banner tone="bad" onDismiss={() => setSaveError(null)}>
              <p className="font-medium">{saveError}</p>
              {Object.keys(errors).length > 0 && (
                <ul className="mt-1 list-disc pl-5">
                  {Object.entries(errors).map(([field, message]) => (
                    <li key={field}>{labelOf(field)} {message}</li>
                  ))}
                </ul>
              )}
            </Banner>
          </div>
        )}
        {!settings.readyToInvoice && (
          <div className="mb-3">
            <Banner tone="warn">
              Bills cannot be issued until the {settings.missingForInvoicing.join(', ')} below are filled in.
            </Banner>
          </div>
        )}
        <p className="mb-4 text-sm text-slate-500">
          These go on every bill. Bills already issued keep the details they were issued with, so changing
          anything here never rewrites an old bill.
          {' '}
          {settings.gstRegistered
            ? <span className="font-medium text-slate-700">Registered for GST: bills are tax invoices.</span>
            : <span className="font-medium text-slate-700">No GSTIN: bills charge no GST and are printed as plain bills.</span>}
        </p>
        <fieldset disabled={!canEdit} className="grid gap-3 md:grid-cols-2">
          <Field label="Legal name" hint="Owner or business name; for a GST shop exactly as on the registration"
                 error={err('legalName')}>
            <input className={inputClass} value={form.legalName}
                   onChange={(event) => set({ legalName: event.target.value })} />
          </Field>
          <Field label="Shop name" hint="Printed large at the top of the receipt" optional error={err('tradeName')}>
            <input className={inputClass} value={form.tradeName}
                   onChange={(event) => set({ tradeName: event.target.value })} />
          </Field>
          <Field label="GSTIN" optional error={err('gstin')}
                 hint="Leave empty if the shop is not registered for GST. When set, it is checked character by character">
            <input className={`${inputClass} num uppercase`} maxLength={15} value={form.gstin}
                   onChange={(event) => set({ gstin: event.target.value.toUpperCase() })} />
          </Field>
          <Field label="Address" hint="One line per row" error={err('address')}>
            <textarea className={inputClass} rows={3} value={form.address.join('\n')}
                      onChange={(event) => set({ address: event.target.value.split('\n') })} />
          </Field>
          <Field label="Phone" optional error={err('phone')}>
            <input className={inputClass} value={form.phone} onChange={(event) => set({ phone: event.target.value })} />
          </Field>
          <Field label="Email" optional error={err('email')}>
            <input className={inputClass} value={form.email} onChange={(event) => set({ email: event.target.value })} />
          </Field>
          <Field label="Website" optional error={err('website')}>
            <input className={inputClass} value={form.website}
                   onChange={(event) => set({ website: event.target.value })} />
          </Field>
          <Field label="CIN" optional error={err('cin')}
                 hint="Only for a registered company: 21 characters, e.g. U52100MH2020PTC123456">
            <input className={`${inputClass} num uppercase`} maxLength={21} value={form.cin}
                   onChange={(event) => set({ cin: event.target.value.toUpperCase() })} />
          </Field>
          <Field label="FSSAI licence" optional hint="14 digits, for food businesses" error={err('fssaiLicense')}>
            <input className={`${inputClass} num`} maxLength={14} value={form.fssaiLicense}
                   onChange={(event) => set({ fssaiLicense: event.target.value })} />
          </Field>
          <Field label="UPI ID" optional hint="Prints a scan-to-pay QR code on every bill, e.g. shop@okicici"
                 error={err('upiVpa')}>
            <input className={inputClass} value={form.upiVpa}
                   onChange={(event) => set({ upiVpa: event.target.value })} />
          </Field>
          <Field label="Receipt footer" optional hint="One line per row, e.g. your exchange policy"
                 error={err('receiptFooter')}>
            <textarea className={inputClass} rows={2} value={form.receiptFooter.join('\n')}
                      onChange={(event) => set({ receiptFooter: event.target.value.split('\n') })} />
          </Field>
          <div className="space-y-2">
            <label className="flex items-center gap-2 text-sm text-slate-600">
              <input type="checkbox" checked={form.roundInvoiceTotal}
                     onChange={(event) => set({ roundInvoiceTotal: event.target.checked })} />
              Round every bill to whole rupees
            </label>
            <label className="flex items-center gap-2 text-sm text-slate-600">
              <input type="checkbox" checked={form.blockNegativeStock}
                     onChange={(event) => set({ blockNegativeStock: event.target.checked })} />
              Refuse to sell items the stock says you do not have
            </label>
          </div>
        </fieldset>
      </Panel>

      <Panel title="Service contact" actions={<span className="text-xs text-slate-500">who to call when it breaks</span>}>
        <fieldset disabled={!canEdit} className="grid gap-3">
          <Field label="Service company or engineer" optional error={err('serviceProviderName')}>
            <input className={inputClass} value={form.serviceProviderName}
                   onChange={(event) => set({ serviceProviderName: event.target.value })} />
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label="Service phone" optional error={err('servicePhone')}>
              <input className={inputClass} value={form.servicePhone}
                     onChange={(event) => set({ servicePhone: event.target.value })} />
            </Field>
            <Field label="Service email" optional error={err('serviceEmail')}>
              <input className={inputClass} value={form.serviceEmail}
                     onChange={(event) => set({ serviceEmail: event.target.value })} />
            </Field>
          </div>
          {canEdit && (
            <div className="flex justify-end">
              <Button tone="primary" onClick={save} disabled={busy}>{busy ? 'Saving…' : 'Save settings'}</Button>
            </div>
          )}
        </fieldset>
      </Panel>

      <Panel
        title="Printer"
        actions={<Button onClick={testPage}>Print test page</Button>}
      >
        {printerError ? (
          <Banner tone="bad">{printerError}</Banner>
        ) : printer ? (
          <ul className="space-y-1 text-sm text-slate-600">
            <li>Answering: {printer.responding ? 'yes' : 'no (file mode, or no status support)'}</li>
            {printer.responding && (
              <>
                <li>Online: {printer.online ? 'yes' : 'no'}</li>
                <li>Cover: {printer.coverOpen ? 'open' : 'closed'}</li>
                <li>Paper: {printer.paperOut ? 'out' : printer.paperNearEnd ? 'running low' : 'fine'}</li>
              </>
            )}
          </ul>
        ) : (
          <Empty>Checking the printer…</Empty>
        )}
        <p className="mt-3 text-xs text-slate-400">
          The printer port, paper width and drawer pin belong to this machine, so they are set in
          pos.properties next to the database.
        </p>
      </Panel>

      <Panel title="Keyboard shortcuts" actions={
        <Button onClick={() => {
          setShortcuts({ ...DEFAULT_SHORTCUTS })
          saveShortcuts({ ...DEFAULT_SHORTCUTS })
        }}>Reset</Button>
      }>
        <p className="mb-3 text-xs text-slate-400">Saved on this machine, for whoever bills at this counter.</p>
        <label className="mb-3 flex items-center gap-2 text-sm text-slate-600">
          <input
            type="checkbox"
            checked={showShortcuts}
            onChange={(event) => {
              setShowShortcuts(event.target.checked)
              saveShowShortcuts(event.target.checked)
            }}
          />
          Show the shortcut list at the bottom of the billing screen
        </label>
        <label className="mb-3 flex items-start gap-2 text-sm text-slate-600">
          <input
            type="checkbox"
            className="mt-1"
            checked={askCashReceived}
            onChange={(event) => {
              setAskCashReceived(event.target.checked)
              saveAskCashReceived(event.target.checked)
            }}
          />
          <span>
            Ask for the cash received before taking a cash bill
            <span className="block text-xs text-slate-400">
              Off: pressing Enter with the cash amount empty takes the bill for the exact total.
            </span>
          </span>
        </label>
        <div className="grid gap-2 md:grid-cols-2">
          {(Object.keys(SHORTCUT_LABELS) as ShortcutAction[]).map((action) => (
            <label key={action} className="flex items-center justify-between gap-2 text-sm">
              <span className="text-slate-600">{SHORTCUT_LABELS[action]}</span>
              <select
                className="rounded border border-slate-300 px-2 py-1 text-sm"
                value={shortcuts[action]}
                onChange={(event) => {
                  const updated = { ...shortcuts, [action]: event.target.value }
                  setShortcuts(updated)
                  saveShortcuts(updated)
                }}
              >
                {ASSIGNABLE_KEYS.map((key) => <option key={key} value={key}>{key}</option>)}
              </select>
            </label>
          ))}
        </div>
      </Panel>

      <Panel title="Tills in this shop" className="xl:col-span-2">
        <p className="mb-3 text-xs text-slate-400">
          Each machine registers itself from its own pos.properties. Counters replicate their sales to the master.
        </p>
        <table className="w-full text-sm">
          <thead className="text-xs uppercase tracking-wide text-slate-400">
            <tr>
              <th className="py-1 text-left">Code</th>
              <th className="py-1 text-left">Name</th>
              <th className="py-1 text-left">Floor / area</th>
              <th className="py-1 text-left">Role</th>
              <th className="py-1 text-left">Last seen</th>
            </tr>
          </thead>
          <tbody>
            {settings.terminals.map((terminal) => (
              <tr key={terminal.code} className="border-t border-slate-100">
                <td className="num py-1.5 font-medium">
                  {terminal.code}
                  {terminal.code === settings.thisTerminal.code && (
                    <span className="ml-2 rounded bg-sky-100 px-1.5 py-0.5 text-xs text-sky-700">this machine</span>
                  )}
                </td>
                <td className="py-1.5">{terminal.name}</td>
                <td className="py-1.5 text-slate-500">{terminal.section || '—'}</td>
                <td className="py-1.5 text-slate-500">{terminal.type}</td>
                <td className="py-1.5 text-slate-500">
                  {terminal.lastSeenAt ? new Date(terminal.lastSeenAt).toLocaleString() : '—'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Panel>
    </div>
  )
}
