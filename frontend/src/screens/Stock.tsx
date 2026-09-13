import { useCallback, useEffect, useState } from 'react'
import { api, milliFromUnits, quantity, rupees } from '../api'
import type { StockRow } from '../types'
import { Button, Empty, Field, inputClass, Modal, Panel } from '../components/ui'
import type { Toast } from '../App'

type Movement = { kind: 'receipts' | 'adjustments' | 'damages'; row: StockRow }

export default function Stock({ onToast }: { onToast: Toast }) {
  const [rows, setRows] = useState<StockRow[]>([])
  const [filter, setFilter] = useState('')
  const [movement, setMovement] = useState<Movement | null>(null)

  const load = useCallback(() => {
    api.get<StockRow[]>('/api/stock')
      .then(setRows)
      .catch((failure) => onToast({ tone: 'bad', text: failure.message }))
  }, [onToast])

  useEffect(load, [load])

  const shown = rows.filter((row) =>
    `${row.name} ${row.sku}`.toLowerCase().includes(filter.trim().toLowerCase()))
  const totalValue = rows.reduce((sum, row) => sum + row.stockValuePaise, 0)
  const outOfStock = rows.filter((row) => row.stockMilli <= 0).length

  return (
    <Panel
      title="Stock on hand"
      actions={
        <span className="text-xs text-slate-500">
          {rows.length} items · <span className="num">{rupees(totalValue)}</span> at selling price
          {outOfStock > 0 && <span className="ml-2 text-rose-600">{outOfStock} out of stock</span>}
        </span>
      }
    >
      <input className={`${inputClass} mb-3`} placeholder="Filter by name or SKU" value={filter}
             onChange={(event) => setFilter(event.target.value)} />

      {shown.length === 0 ? (
        <Empty>Nothing to show. Add products first, then record what arrived.</Empty>
      ) : (
        <table className="w-full text-sm">
          <thead className="text-xs uppercase tracking-wide text-slate-400">
            <tr>
              <th className="py-1 text-left">Item</th>
              <th className="py-1 text-right">On hand</th>
              <th className="py-1 text-right">Price</th>
              <th className="py-1 text-right">Value</th>
              <th className="py-1"></th>
            </tr>
          </thead>
          <tbody>
            {shown.map((row) => (
              <tr key={row.productId} className="border-t border-slate-100">
                <td className="py-2">
                  <div className="font-medium text-slate-800">{row.name}</div>
                  <div className="text-xs text-slate-400">{row.sku}</div>
                </td>
                <td className={`num py-2 text-right ${row.stockMilli <= 0 ? 'text-rose-600' : ''}`}>
                  {quantity(row.stockMilli)} <span className="text-xs text-slate-400">{row.unit}</span>
                </td>
                <td className="num py-2 text-right text-slate-500">{rupees(row.sellingPricePaise)}</td>
                <td className="num py-2 text-right">{rupees(row.stockValuePaise)}</td>
                <td className="py-2 text-right">
                  <Button tone="ghost" onClick={() => setMovement({ kind: 'receipts', row })}>Received</Button>
                  <Button tone="ghost" onClick={() => setMovement({ kind: 'adjustments', row })}>Correct</Button>
                  <Button tone="ghost" onClick={() => setMovement({ kind: 'damages', row })}>Damage</Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {movement && (
        <MovementDialog
          movement={movement}
          onClose={() => setMovement(null)}
          onDone={() => {
            setMovement(null)
            load()
          }}
          onToast={onToast}
        />
      )}
    </Panel>
  )
}

const TITLES = {
  receipts: 'Goods received',
  adjustments: 'Correct the count',
  damages: 'Damaged or expired',
}

const HINTS = {
  receipts: 'What arrived from the supplier',
  adjustments: 'The difference, not the new count. Use -2 if two are missing.',
  damages: 'How many are being written off',
}

function MovementDialog({ movement, onClose, onDone, onToast }: {
  movement: Movement
  onClose: () => void
  onDone: () => void
  onToast: Toast
}) {
  const [amount, setAmount] = useState('')
  const [note, setNote] = useState('')

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    const signed = amount.trim().startsWith('-')
    const milli = milliFromUnits(amount) * (signed ? -1 : 1)
    if (milli === 0) {
      return
    }
    try {
      const body = movement.kind === 'adjustments'
        ? { productId: movement.row.productId, deltaMilli: milli, note }
        : { productId: movement.row.productId, quantityMilli: Math.abs(milli), note }
      await api.post(`/api/stock/${movement.kind}`, body)
      onToast({ tone: 'good', text: `Stock updated for ${movement.row.name}` })
      onDone()
    } catch (failure) {
      onToast({ tone: 'bad', text: failure instanceof Error ? failure.message : 'Could not record that' })
    }
  }

  return (
    <Modal title={TITLES[movement.kind]} onClose={onClose}>
      <form onSubmit={submit} className="space-y-3">
        <p className="text-sm text-slate-500">
          {movement.row.name} · now {quantity(movement.row.stockMilli)} {movement.row.unit}
        </p>
        <Field label="Quantity" hint={HINTS[movement.kind]}>
          <input className={`${inputClass} num text-lg`} inputMode="decimal" autoFocus value={amount}
                 onChange={(event) => setAmount(event.target.value)} />
        </Field>
        <Field label="Note" hint="Supplier, invoice number, reason">
          <input className={inputClass} value={note} onChange={(event) => setNote(event.target.value)} />
        </Field>
        <div className="flex justify-end gap-2">
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" tone="primary">Record</Button>
        </div>
      </form>
    </Modal>
  )
}
