import { useEffect, useState } from 'react'
import { api, fieldErrorsOf, messageOfFailure } from '../api'
import type { PrinterSettingsForm, PrinterSetupView, PrinterStatus, PrinterType } from '../types'
import { Banner, Button, Empty, errorFor, Field, inputClass, Panel } from './ui'
import type { Toast } from '../App'

const TYPE_LABELS: Record<PrinterType, string> = {
  WINDOWS: 'Printer installed in Windows (inkjet, laser, any driver)',
  SERIAL: 'Thermal printer on a COM port (USB or RS-232)',
  TCP: 'Thermal printer on the network',
  FILE: 'No printer - save each bill as a file',
}

const TYPE_HINTS: Record<PrinterType, string> = {
  WINDOWS: 'Prints through the Windows driver, so anything that prints from Notepad works. The cash '
    + 'drawer and the paper warning need a thermal printer.',
  SERIAL: 'Needs the printer to appear as a COM port. A USB printer usually needs its maker\'s virtual '
    + 'COM port driver first.',
  TCP: 'The printer\'s own address, from its self-test page. Raw printing, usually port 9100.',
  FILE: 'Nothing is printed. Bills are still taken and saved, and each one is written to the '
    + 'printer-out folder so you can print it later.',
}

const BAUD_RATES = [9600, 19200, 38400, 57600, 115200]

/**
 * This till's printer, set here rather than in a file on the machine. Saving takes effect on the
 * next bill: nothing restarts, and a printer that is switched off or unplugged never stops billing.
 */
export default function PrinterPanel({ canEdit, onToast }: { canEdit: boolean; onToast: Toast }) {
  const [setup, setSetup] = useState<PrinterSetupView | null>(null)
  const [form, setForm] = useState<PrinterSettingsForm | null>(null)
  const [status, setStatus] = useState<PrinterStatus | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [saveError, setSaveError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const load = () => {
    api.get<PrinterSetupView>('/api/hardware/printer/settings')
      .then((view) => {
        setSetup(view)
        setForm(view.printer)
        setLoadError(null)
      })
      .catch((failure) => setLoadError(messageOfFailure(failure, 'Could not read the printer settings')))
    api.get<PrinterStatus>('/api/hardware/printer/status')
      .then(setStatus)
      .catch(() => setStatus(null))
  }

  useEffect(load, [])

  if (loadError) {
    return <Panel title="Printer"><Banner tone="bad">{loadError}</Banner></Panel>
  }
  if (!setup || !form) {
    return <Panel title="Printer"><Empty>Checking the printer…</Empty></Panel>
  }

  const set = (change: Partial<PrinterSettingsForm>) => setForm({ ...form, ...change })
  const err = (field: string) => errorFor(errors, field)
  const thermal = form.type === 'SERIAL' || form.type === 'TCP'

  const save = async () => {
    setBusy(true)
    try {
      const saved = await api.put<PrinterSetupView>('/api/hardware/printer/settings', form)
      setSetup(saved)
      setForm(saved.printer)
      setErrors({})
      setSaveError(null)
      onToast({ tone: 'good', text: `Bills now print on ${saved.target}` })
    } catch (failure) {
      const fields = fieldErrorsOf(failure)
      setErrors(fields)
      setSaveError(Object.keys(fields).length
        ? 'Nothing was saved. Correct the fields marked in red and save again.'
        : messageOfFailure(failure, 'Could not save the printer'))
    } finally {
      setBusy(false)
    }
  }

  const testPage = async () => {
    try {
      await api.post('/api/hardware/printer/test-page')
      onToast({ tone: 'good', text: 'Test page sent to the printer' })
    } catch (failure) {
      onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not print') })
    }
  }

  /** Keeps a port or printer that is switched off right now from vanishing out of its own list. */
  const withCurrent = (options: string[], current: string) =>
    current && !options.includes(current) ? [...options, current] : options

  return (
    <Panel title="Printer" actions={<Button onClick={testPage}>Print test page</Button>}>
      <p className="mb-3 text-sm text-slate-600">
        Bills are going to <span className="font-medium text-slate-800">{setup.target}</span>.
      </p>

      {saveError && (
        <div className="mb-3">
          <Banner tone="bad" onDismiss={() => setSaveError(null)}>{saveError}</Banner>
        </div>
      )}

      <fieldset disabled={!canEdit} className="grid gap-3">
        <Field label="What is this till printing on?" hint={TYPE_HINTS[form.type]} error={err('type')}>
          <select
            className={inputClass}
            value={form.type}
            onChange={(event) => set({ type: event.target.value as PrinterType })}
          >
            {(Object.keys(TYPE_LABELS) as PrinterType[]).map((type) => (
              <option key={type} value={type}>{TYPE_LABELS[type]}</option>
            ))}
          </select>
        </Field>

        {form.type === 'WINDOWS' && (
          <>
            <Field label="Which printer" error={err('windowsPrinter')}>
              <select
                className={inputClass}
                value={form.windowsPrinter}
                onChange={(event) => set({ windowsPrinter: event.target.value })}
              >
                <option value="">Whichever Windows uses by default</option>
                {withCurrent(setup.installedPrinters, form.windowsPrinter).map((name) => (
                  <option key={name} value={name}>{name}</option>
                ))}
              </select>
            </Field>
            <Field
              label="Bill width on the paper (mm)"
              hint="80 keeps a bill the shape of a receipt on an A4 sheet. 0 spreads it across the page."
              error={err('windowsWidthMm')}
            >
              <input
                type="number" min={0} max={500} className={inputClass}
                value={form.windowsWidthMm}
                onChange={(event) => set({ windowsWidthMm: Number(event.target.value) })}
              />
            </Field>
          </>
        )}

        {form.type === 'SERIAL' && (
          <>
            <Field label="COM port" error={err('serialPort')}>
              <select
                className={inputClass}
                value={form.serialPort}
                onChange={(event) => set({ serialPort: event.target.value })}
              >
                <option value="">Not chosen</option>
                {withCurrent(setup.serialPorts.map((port) => port.systemName), form.serialPort).map((name) => {
                  const found = setup.serialPorts.find((port) => port.systemName === name)
                  return (
                    <option key={name} value={name}>
                      {found ? `${name} - ${found.description}` : `${name} (not plugged in now)`}
                    </option>
                  )
                })}
              </select>
            </Field>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="Baud rate" hint="Must match the printer's self-test page" error={err('serialBaudRate')}>
                <select
                  className={inputClass}
                  value={form.serialBaudRate}
                  onChange={(event) => set({ serialBaudRate: Number(event.target.value) })}
                >
                  {BAUD_RATES.map((rate) => <option key={rate} value={rate}>{rate}</option>)}
                </select>
              </Field>
              <Field label="Flow control" error={err('serialFlowControl')}>
                <select
                  className={inputClass}
                  value={form.serialFlowControl}
                  onChange={(event) => set({ serialFlowControl: event.target.value as PrinterSettingsForm['serialFlowControl'] })}
                >
                  <option value="NONE">None</option>
                  <option value="RTS_CTS">RTS/CTS</option>
                  <option value="DTR_DSR">DTR/DSR</option>
                </select>
              </Field>
            </div>
          </>
        )}

        {form.type === 'TCP' && (
          <div className="grid gap-3 sm:grid-cols-[1fr_8rem]">
            <Field label="Printer address" hint="From the printer's self-test page" error={err('tcpHost')}>
              <input
                className={inputClass} placeholder="192.168.1.50"
                value={form.tcpHost}
                onChange={(event) => set({ tcpHost: event.target.value })}
              />
            </Field>
            <Field label="Port" error={err('tcpPort')}>
              <input
                type="number" min={1} max={65535} className={inputClass}
                value={form.tcpPort}
                onChange={(event) => set({ tcpPort: Number(event.target.value) })}
              />
            </Field>
          </div>
        )}

        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Paper width" error={err('columns')}>
            <select
              className={inputClass}
              value={form.columns}
              onChange={(event) => set({ columns: Number(event.target.value) })}
            >
              <option value={48}>80 mm roll - 48 characters a line</option>
              <option value={32}>58 mm roll - 32 characters a line</option>
            </select>
          </Field>
          <Field label="Give up after" hint="A slow printer may need longer" error={err('timeoutSeconds')}>
            <select
              className={inputClass}
              value={form.timeoutSeconds}
              onChange={(event) => set({ timeoutSeconds: Number(event.target.value) })}
            >
              {[5, 10, 20, 30, 60].map((seconds) => (
                <option key={seconds} value={seconds}>{seconds} seconds</option>
              ))}
            </select>
          </Field>
        </div>

        {thermal ? (
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="Cash drawer pin" hint="Try the other one if the drawer stays shut" error={err('drawerPin')}>
              <select
                className={inputClass}
                value={form.drawerPin}
                onChange={(event) => set({ drawerPin: event.target.value as PrinterSettingsForm['drawerPin'] })}
              >
                <option value="PIN_2">Pin 2</option>
                <option value="PIN_5">Pin 5</option>
              </select>
            </Field>
            <Field label="Drawer pulse (ms)" hint="Lengthen it if the drawer does not open" error={err('drawerPulseMillis')}>
              <input
                type="number" min={1} max={510} className={inputClass}
                value={form.drawerPulseMillis}
                onChange={(event) => set({ drawerPulseMillis: Number(event.target.value) })}
              />
            </Field>
          </div>
        ) : (
          <p className="text-xs text-slate-400">
            The cash drawer opens from a thermal printer's own drawer socket, so it cannot be opened on
            this kind of printer.
          </p>
        )}

        {canEdit && (
          <div className="flex items-center justify-between gap-3">
            <Button onClick={load}>Look again for printers</Button>
            <Button tone="primary" onClick={save} disabled={busy}>
              {busy ? 'Saving…' : 'Save printer'}
            </Button>
          </div>
        )}
      </fieldset>

      <ul className="mt-4 space-y-1 border-t border-slate-100 pt-3 text-sm text-slate-600">
        <li>Answering: {status?.responding ? 'yes' : 'no (no printer, or a kind that cannot answer)'}</li>
        {status?.responding && (
          <>
            <li>Online: {status.online ? 'yes' : 'no'}</li>
            <li>Cover: {status.coverOpen ? 'open' : 'closed'}</li>
            <li>Paper: {status.paperOut ? 'out' : status.paperNearEnd ? 'running low' : 'fine'}</li>
          </>
        )}
      </ul>
      <p className="mt-2 text-xs text-slate-400">
        This printer belongs to this machine alone. Every counter has its own.
      </p>
    </Panel>
  )
}
