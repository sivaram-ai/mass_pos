import { useEffect, useState } from 'react'
import { api, clockTime, fieldErrorsOf, messageOfFailure, milliFromUnits, quantity } from '../api'
import type { CorrectionReason, CorrectionReasonName, Unit } from '../types'
import { Banner, Button, errorFor, Field, inputClass, Modal } from './ui'
import type { Toast } from '../App'

/** The item a count is being put right on, whichever of the two lists it came from. */
export type Correcting = {
  itemId: string
  itemCode: string
  name: string
  unit: Unit
  haveMilli: number
}

/** Which of the two stores it is, which decides where the correction is posted. */
export type CorrectionSource = 'stock' | 'raw'

/**
 * A count put right by hand.
 *
 * <p>It replaces three buttons - Received, Correct, Damage - with the question somebody at a
 * counter is actually asked, which is why the figure is wrong. Goods arriving are no longer one of
 * the answers: they come in against a purchase order an administrator agreed to, and nowhere else.
 * What a shop already had on its shelves the day it started is a correction with Opening stock as
 * its reason, because that is the shop saying what is on the floor rather than buying anything.
 *
 * <p>The reason decides which movement is written - a stock take is a correction, something broken
 * is damage, goods going back are a return - and the labels come from the server, which writes the
 * same words into the history. Who did it and when are not asked: the till knows both, and a
 * person typing their own name into a record of what they did is not an audit trail.
 */
export function CorrectionDialog({ of, source, by, onClose, onDone, onToast }: {
  of: Correcting
  source: CorrectionSource
  by: string
  onClose: () => void
  onDone: () => void
  onToast: Toast
}) {
  const [reasons, setReasons] = useState<CorrectionReason[]>([])
  const [reason, setReason] = useState<CorrectionReasonName>('STOCK_TAKE')
  const [amount, setAmount] = useState('')
  const [notes, setNotes] = useState('')
  const [errors, setErrors] = useState<{ message: string; fields: Record<string, string> } | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    api.get<CorrectionReason[]>('/api/stock/correction-reasons').then(setReasons).catch(() => {})
  }, [])

  const chosen = reasons.find((one) => one.name === reason)
  const typed = milliFromUnits(amount)
  const signed = amount.trim().startsWith('-') ? -typed : typed
  // The reason settles which way it can go, so a figure typed the wrong way round is turned round
  // rather than refused: nobody means "damaged minus five".
  const moving = chosen && chosen.sign !== 0 ? chosen.sign * typed : signed
  const after = Math.max(0, of.haveMilli + moving)

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (typed === 0) {
      setErrors({ message: 'Say how far out the count was.', fields: {} })
      return
    }
    setBusy(true)
    try {
      const body = { deltaMilli: moving, reason, notes: notes.trim() }
      if (source === 'stock') {
        await api.post('/api/stock/corrections', { productId: of.itemId, ...body })
      } else {
        await api.post(`/api/raw-materials/${of.itemId}/corrections`, body)
      }
      onToast({ tone: 'good', text: `${of.name} corrected` })
      onDone()
    } catch (failure) {
      // Inside the dialog, which stays open with what was typed.
      setErrors({ message: messageOfFailure(failure, 'Could not record that'), fields: fieldErrorsOf(failure) })
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal title={`Correct the count · ${of.name}`} onClose={onClose}>
      <form onSubmit={submit} className="space-y-3">
        {errors && <Banner tone="bad" onDismiss={() => setErrors(null)}>{errors.message}</Banner>}

        <p className="text-sm text-slate-500">
          <span className="num">{of.itemCode}</span> · now{' '}
          <span className="num font-medium text-slate-700">{quantity(of.haveMilli)} {of.unit}</span>
        </p>

        <Field label="Why" error={errorFor(errors?.fields ?? {}, 'reason')}
               hint="This is what the history will show against it">
          <select className={inputClass} value={reason} autoFocus
                  onChange={(event) => setReason(event.target.value as CorrectionReasonName)}>
            {reasons.map((one) => <option key={one.name} value={one.name}>{one.label}</option>)}
          </select>
        </Field>

        <Field
          label={chosen && chosen.sign < 0 ? 'How many are gone' : chosen && chosen.sign > 0 ? 'How many there are' : 'How far out'}
          error={errorFor(errors?.fields ?? {}, 'deltaMilli')}
          hint={chosen && chosen.sign === 0
            ? 'The difference, not the new count. Use -2 if two are missing.'
            : typed > 0
              ? `Leaves ${quantity(after)} ${of.unit} on the count`
              : 'How much it moves by'}
        >
          <input className={`${inputClass} num text-lg`} inputMode="decimal" value={amount}
                 onChange={(event) => setAmount(event.target.value)} />
        </Field>

        <Field label="Notes" optional error={errorFor(errors?.fields ?? {}, 'notes')}
               hint="Anything worth keeping: which batch, who found it, the bin it was in">
          <input className={inputClass} value={notes} onChange={(event) => setNotes(event.target.value)} />
        </Field>

        <p className="text-xs text-slate-400">
          Recorded as {by || 'you'} at {clockTime(new Date())}, on this till, as its own line in the
          item's history. Nothing is rewritten.
        </p>

        <div className="flex justify-end gap-2">
          <Button onClick={onClose} hint="Esc">Cancel</Button>
          <Button type="submit" tone="primary" hint="Enter" disabled={busy}>Correct it</Button>
        </div>
      </form>
    </Modal>
  )
}
