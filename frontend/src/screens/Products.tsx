import { useCallback, useEffect, useState } from 'react'
import { api, fieldErrorsOf, messageOfFailure, paiseFromRupees, percent, quantity, rupees } from '../api'
import type { Product, Unit } from '../types'
import { Banner, Button, Empty, errorFor, Field, inputClass, Modal, Panel } from '../components/ui'
import type { Toast } from '../App'

const UNITS: Unit[] = ['NOS', 'PCS', 'BOX', 'PAC', 'DOZ', 'KGS', 'GMS', 'LTR', 'MLT', 'MTR']
const GST_SLABS = [0, 500, 1200, 1800, 2800]

type Draft = {
  sku: string
  barcode: string
  name: string
  hsnCode: string
  unit: Unit
  sellingPrice: string
  mrp: string
  taxInclusive: boolean
  gstRateBp: number
  cessRateBp: number
}

const EMPTY: Draft = {
  sku: '', barcode: '', name: '', hsnCode: '', unit: 'NOS',
  sellingPrice: '', mrp: '', taxInclusive: true, gstRateBp: 1800, cessRateBp: 0,
}

export default function Products({ onToast }: { onToast: Toast }) {
  const [search, setSearch] = useState('')
  const [products, setProducts] = useState<Product[]>([])
  const [editing, setEditing] = useState<{ id: string | null; draft: Draft } | null>(null)
  const [saveErrors, setSaveErrors] = useState<{ message: string; fields: Record<string, string> } | null>(null)

  const load = useCallback(() => {
    api.get<Product[]>(`/api/products?q=${encodeURIComponent(search)}`)
      .then(setProducts)
      .catch((failure) => onToast({ tone: 'bad', text: failure.message }))
  }, [search, onToast])

  useEffect(() => {
    const timer = setTimeout(load, 150)
    return () => clearTimeout(timer)
  }, [load])

  const save = async (id: string | null, draft: Draft) => {
    const body = {
      sku: draft.sku.trim(),
      barcode: draft.barcode.trim() || null,
      name: draft.name.trim(),
      hsnCode: draft.hsnCode.trim(),
      unit: draft.unit,
      sellingPricePaise: paiseFromRupees(draft.sellingPrice),
      mrpPaise: paiseFromRupees(draft.mrp),
      taxInclusive: draft.taxInclusive,
      gstRateBp: draft.gstRateBp,
      cessRateBp: draft.cessRateBp,
    }
    try {
      if (id) {
        await api.put(`/api/products/${id}`, body)
      } else {
        await api.post('/api/products', body)
      }
      setEditing(null)
      setSaveErrors(null)
      load()
      onToast({ tone: 'good', text: `Saved ${draft.name}` })
    } catch (failure) {
      // Shown inside the dialog, which stays open with what was typed.
      setSaveErrors({ message: messageOfFailure(failure, 'Could not save'), fields: fieldErrorsOf(failure) })
    }
  }

  const openEditor = (id: string | null, draft: Draft) => {
    setSaveErrors(null)
    setEditing({ id, draft })
  }

  const deactivate = async (product: Product) => {
    try {
      await api.post(`/api/products/${product.id}/deactivate`)
      load()
      onToast({ tone: 'good', text: `${product.name} is no longer sold` })
    } catch (failure) {
      onToast({ tone: 'bad', text: failure instanceof Error ? failure.message : 'Could not deactivate' })
    }
  }

  return (
    <Panel
      title="Products"
      actions={<Button tone="primary" onClick={() => openEditor(null, EMPTY)}>Add product</Button>}
    >
      <input
        className={`${inputClass} mb-3`}
        placeholder="Search by name, SKU, barcode or HSN"
        value={search}
        onChange={(event) => setSearch(event.target.value)}
      />
      {products.length === 0 ? (
        <Empty>No products yet. Add the first one to start billing.</Empty>
      ) : (
        <table className="w-full text-sm">
          <thead className="text-xs uppercase tracking-wide text-slate-400">
            <tr>
              <th className="py-1 text-left">Item</th>
              <th className="py-1 text-left">HSN</th>
              <th className="py-1 text-right">GST</th>
              <th className="py-1 text-right">Price</th>
              <th className="py-1 text-right">MRP</th>
              <th className="py-1 text-right">Stock</th>
              <th className="py-1"></th>
            </tr>
          </thead>
          <tbody>
            {products.map((product) => (
              <tr key={product.id} className="border-t border-slate-100">
                <td className="py-2">
                  <div className="font-medium text-slate-800">{product.name}</div>
                  <div className="text-xs text-slate-400">
                    {product.sku}{product.barcode ? ` · ${product.barcode}` : ''}
                  </div>
                </td>
                <td className="num py-2 text-slate-600">{product.hsnCode || <span className="text-slate-300">—</span>}</td>
                <td className="num py-2 text-right">
                  {percent(product.gstRateBp)}
                  <div className="text-xs text-slate-400">{product.taxInclusive ? 'in price' : 'on top'}</div>
                </td>
                <td className="num py-2 text-right">
                  {product.sellingPricePaise > 0
                    ? rupees(product.sellingPricePaise)
                    : <span className="text-xs text-amber-700">typed at billing</span>}
                </td>
                <td className="num py-2 text-right text-slate-500">{product.mrpPaise ? rupees(product.mrpPaise) : '—'}</td>
                <td className={`num py-2 text-right ${product.stockMilli <= 0 ? 'text-rose-600' : ''}`}>
                  {quantity(product.stockMilli)} <span className="text-xs text-slate-400">{product.unit}</span>
                </td>
                <td className="py-2 text-right">
                  <Button tone="ghost" onClick={() => openEditor(product.id, {
                      sku: product.sku,
                      barcode: product.barcode ?? '',
                      name: product.name,
                      hsnCode: product.hsnCode,
                      unit: product.unit,
                      sellingPrice: product.sellingPricePaise ? (product.sellingPricePaise / 100).toFixed(2) : '',
                      mrp: product.mrpPaise ? (product.mrpPaise / 100).toFixed(2) : '',
                      taxInclusive: product.taxInclusive,
                      gstRateBp: product.gstRateBp,
                      cessRateBp: product.cessRateBp,
                  })}>Edit</Button>
                  <Button tone="ghost" onClick={() => deactivate(product)}>Stop</Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {editing && (
        <ProductDialog
          id={editing.id}
          draft={editing.draft}
          errors={saveErrors}
          onClose={() => setEditing(null)}
          onSave={save}
        />
      )}
    </Panel>
  )
}

function ProductDialog({ id, draft, errors, onClose, onSave }: {
  id: string | null
  draft: Draft
  errors: { message: string; fields: Record<string, string> } | null
  onClose: () => void
  onSave: (id: string | null, draft: Draft) => void
}) {
  const [form, setForm] = useState(draft)
  const set = (change: Partial<Draft>) => setForm({ ...form, ...change })
  const err = (field: string) => errorFor(errors?.fields ?? {}, field)

  return (
    <Modal title={id ? 'Edit product' : 'New product'} onClose={onClose} wide>
      <form
        className="grid grid-cols-2 gap-3"
        onSubmit={(event) => {
          event.preventDefault()
          onSave(id, form)
        }}
      >
        {errors && (
          <div className="col-span-2">
            <Banner tone="bad">{errors.message}</Banner>
          </div>
        )}
        <Field label="Name" error={err('name')}>
          <input className={inputClass} value={form.name} autoFocus
                 onChange={(event) => set({ name: event.target.value })} />
        </Field>
        <Field label="Item code" error={err('sku')}
               hint={id ? 'Cannot be changed once used on a bill' : 'Typed in the code column when billing, e.g. 19'}>
          <input className={inputClass} value={form.sku} disabled={Boolean(id)}
                 onChange={(event) => set({ sku: event.target.value })} />
        </Field>
        <Field label="Barcode" optional error={err('barcode')}>
          <input className={`${inputClass} num`} value={form.barcode}
                 onChange={(event) => set({ barcode: event.target.value })} />
        </Field>
        <Field label="HSN / SAC code" optional error={err('hsnCode')}
               hint="4, 6 or 8 digits. Not needed if the shop has no GSTIN">
          <input className={`${inputClass} num`} value={form.hsnCode}
                 onChange={(event) => set({ hsnCode: event.target.value })} />
        </Field>
        <Field label="Sold by" error={err('unit')}>
          <select className={inputClass} value={form.unit}
                  onChange={(event) => set({ unit: event.target.value as Unit })}>
            {UNITS.map((unit) => <option key={unit} value={unit}>{unit}</option>)}
          </select>
        </Field>
        <Field label="GST rate" error={err('gstRateBp')} hint="Ignored when the shop has no GSTIN">
          <select className={inputClass} value={form.gstRateBp}
                  onChange={(event) => set({ gstRateBp: Number(event.target.value) })}>
            {GST_SLABS.map((slab) => <option key={slab} value={slab}>{percent(slab)}</option>)}
          </select>
        </Field>
        <Field label="Selling price" optional error={err('sellingPricePaise')}
               hint={`Leave empty to type the rate on each bill. ${form.taxInclusive ? 'Tax included' : 'Tax added on top'}`}>
          <input className={`${inputClass} num`} inputMode="decimal" value={form.sellingPrice}
                 onChange={(event) => set({ sellingPrice: event.target.value })} />
        </Field>
        <Field label="MRP" optional error={err('mrpPaise')} hint="Printed maximum retail price, if the item has one">
          <input className={`${inputClass} num`} inputMode="decimal" value={form.mrp}
                 onChange={(event) => set({ mrp: event.target.value })} />
        </Field>
        <label className="col-span-2 flex items-center gap-2 text-sm text-slate-600">
          <input type="checkbox" checked={form.taxInclusive}
                 onChange={(event) => set({ taxInclusive: event.target.checked })} />
          Selling price already includes GST
        </label>
        <div className="col-span-2 flex justify-end gap-2">
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" tone="primary">Save</Button>
        </div>
      </form>
    </Modal>
  )
}

