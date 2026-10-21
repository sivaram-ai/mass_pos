import { useState } from 'react'
import type { ReactNode } from 'react'
import { api, dayFromIso, fieldErrorsOf, messageOfFailure } from '../api'
import type { LicenseState, LicenseStatus } from '../types'
import { Banner, Button, errorFor, Field, inputClass, Panel } from '../components/ui'
import type { Toast } from '../App'

const STATES: Record<LicenseState, { label: string; badge: string }> = {
  ACTIVE: { label: 'Active', badge: 'bg-emerald-100 text-emerald-800' },
  EXPIRING: { label: 'Ending soon', badge: 'bg-amber-100 text-amber-800' },
  GRACE: { label: 'Grace period', badge: 'bg-amber-100 text-amber-800' },
  EXPIRED: { label: 'Billing locked', badge: 'bg-rose-100 text-rose-800' },
  NOT_ACTIVATED: { label: 'Not activated', badge: 'bg-rose-100 text-rose-800' },
  WRONG_MACHINE: { label: 'Other machine', badge: 'bg-rose-100 text-rose-800' },
  INVALID: { label: 'Not valid', badge: 'bg-rose-100 text-rose-800' },
}

/** Shows the licence, this machine's ID to send the supplier, and takes a new or renewed key. */
export default function License({ status, onChanged, onToast }: {
  status: LicenseStatus | null
  onChanged: (status: LicenseStatus) => void
  onToast: Toast
}) {
  const [key, setKey] = useState('')
  const [saving, setSaving] = useState(false)
  const [failure, setFailure] = useState<{ message: string; fields: Record<string, string> } | null>(null)
  const keyError = errorFor(failure?.fields ?? {}, 'key')

  const activate = async () => {
    if (saving || !key.trim()) {
      return
    }
    setSaving(true)
    try {
      const saved = await api.post<LicenseStatus>('/api/license', { key })
      onChanged(saved)
      setKey('')
      setFailure(null)
      onToast({ tone: 'good', text: saved.message })
    } catch (error) {
      setFailure({ message: messageOfFailure(error, 'Could not activate the licence'), fields: fieldErrorsOf(error) })
    } finally {
      setSaving(false)
    }
  }

  const copyMachineId = async () => {
    if (!status?.machineId) {
      return
    }
    try {
      await navigator.clipboard.writeText(status.machineId)
      onToast({ tone: 'good', text: `Machine ID ${status.machineId} copied` })
    } catch {
      onToast({ tone: 'warn', text: `Could not copy. The machine ID is ${status.machineId}` })
    }
  }

  return (
    <div className="mx-auto max-w-4xl space-y-3">
      <div className="grid gap-3 md:grid-cols-2">
        <Panel title="Licence">
          {!status ? (
            <p className="text-sm text-slate-400">Checking the licence…</p>
          ) : (
            <>
              <div className="flex items-start gap-3">
                <span className={`shrink-0 rounded px-2 py-0.5 text-xs font-semibold ${STATES[status.state].badge}`}>
                  {STATES[status.state].label}
                </span>
                <p className="text-sm text-slate-700">{status.message}</p>
              </div>
              {status.validUntil && (
                <dl className="mt-4 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 text-sm">
                  <Row label="Shop">{status.customer}</Row>
                  <Row label="Customer ID">{status.customerId}</Row>
                  <Row label="Plan">{status.plan === 'SUBSCRIPTION' ? 'Subscription' : status.plan}</Row>
                  <Row label="Valid until"><span className="num">{dayFromIso(status.validUntil)}</span></Row>
                  {status.graceEndsOn && (
                    <Row label="Billing stops after"><span className="num">{dayFromIso(status.graceEndsOn)}</span></Row>
                  )}
                  {status.issuedOn && <Row label="Issued on"><span className="num">{dayFromIso(status.issuedOn)}</span></Row>}
                  <Row label="Licence ID"><span className="font-mono text-xs text-slate-500">{status.licenseId}</span></Row>
                </dl>
              )}
            </>
          )}
        </Panel>

        <Panel title="This machine">
          <div className="flex flex-wrap items-center gap-3">
            <span className="rounded-md bg-slate-100 px-3 py-2 font-mono text-lg font-semibold tracking-wider text-slate-800">
              {status?.machineId ?? 'Cannot be read'}
            </span>
            {status?.machineId && <Button onClick={copyMachineId}>Copy</Button>}
          </div>
          <p className="mt-3 text-xs text-slate-500">
            A licence works only on the machine it was issued for. Send this ID to your GO RockIt supplier to get or
            renew the licence for this till. Each till needs its own licence. Reinstalling Windows or replacing the
            motherboard gives a new ID.
          </p>
        </Panel>
      </div>

      <Panel title="Activate or renew">
        <form
          className="space-y-3"
          onSubmit={(event) => {
            event.preventDefault()
            void activate()
          }}
        >
          {failure && !keyError && <Banner tone="bad">{failure.message}</Banner>}
          <Field label="Licence key" error={keyError}
                 hint="Paste the whole key you received, starting with MPL1. Line breaks from email or chat are fine.">
            <textarea
              className={`${inputClass} font-mono text-xs`}
              rows={4}
              value={key}
              autoFocus
              spellCheck={false}
              onChange={(event) => setKey(event.target.value)}
            />
          </Field>
          <div className="flex justify-end">
            <Button type="submit" tone="primary" disabled={saving || !key.trim()}>
              {saving ? 'Activating…' : 'Activate licence'}
            </Button>
          </div>
        </form>
      </Panel>
    </div>
  )
}

/** The strip under the header while the licence needs attention, on every screen and for every role. */
export function LicenseBanner({ status, onOpen }: { status: LicenseStatus; onOpen?: () => void }) {
  const locked = !status.billingAllowed
  return (
    <div className={`flex shrink-0 items-center gap-3 border-b px-3 py-1.5 text-sm ${
      locked ? 'border-rose-200 bg-rose-50 text-rose-900' : 'border-amber-200 bg-amber-50 text-amber-900'}`}
         role={locked ? 'alert' : 'status'}>
      <span className="shrink-0 font-semibold">{locked ? 'Billing locked' : 'Licence'}</span>
      <span className="min-w-0 flex-1">{status.message}</span>
      {onOpen && (
        <button type="button" onClick={onOpen}
                className="shrink-0 rounded-md border border-current px-2 py-0.5 text-xs font-medium opacity-80 hover:opacity-100">
          Open licence
        </button>
      )}
    </div>
  )
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <>
      <dt className="text-slate-500">{label}</dt>
      <dd className="text-slate-800">{children}</dd>
    </>
  )
}
