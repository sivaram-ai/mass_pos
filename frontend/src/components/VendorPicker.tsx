import { useEffect, useState } from 'react'
import { api } from '../api'
import type { Vendor } from '../types'
import { Field, inputClass } from './ui'

/** What the dropdown sets: the vendor on the list, or a name typed for somebody not on it. */
export type ChosenVendor = { vendorId: string | null; vendorName: string }

const NOT_ON_THE_LIST = 'other'

/**
 * Who the goods came from, chosen rather than typed.
 *
 * <p>The name still travels with the delivery - it is a snapshot, and a vendor renamed next year
 * must not rewrite what the shop bought this year - so choosing from the list sets both the link
 * and the name. The link is what lets a vendor's own page gather his deliveries without matching
 * on spelling.
 *
 * <p>There is always a way past it. A shop buys from somebody once, off a lorry, and should not
 * have to create a record to write that down: "Somebody not on the list" gives back the plain box,
 * and that delivery carries a name and no link, exactly as every delivery did before this existed.
 */
export function VendorPicker({ value, onChange, error }: {
  value: ChosenVendor
  onChange: (chosen: ChosenVendor) => void
  error?: string
}) {
  const [vendors, setVendors] = useState<Vendor[]>([])
  const [typing, setTyping] = useState(value.vendorId === null && value.vendorName !== '')

  useEffect(() => {
    api.get<Vendor[]>('/api/vendors')
      .then((all) => setVendors(all.filter((one) => one.active)))
      .catch(() => setVendors([]))
  }, [])

  // Nobody on the list yet: the plain box, so a shop is never stopped by a module it has not
  // started using.
  if (vendors.length === 0) {
    return (
      <Field label="Vendor" hint="Who it came from" error={error}>
        <input className={inputClass} value={value.vendorName} autoComplete="off"
               onChange={(event) => onChange({ vendorId: null, vendorName: event.target.value })} />
      </Field>
    )
  }

  return (
    <Field label="Vendor" hint={typing ? 'Somebody not on the list' : 'Who it came from'} error={error}>
      <select
        className={inputClass}
        value={typing ? NOT_ON_THE_LIST : (value.vendorId ?? '')}
        onChange={(event) => {
          const picked = event.target.value
          if (picked === NOT_ON_THE_LIST) {
            setTyping(true)
            onChange({ vendorId: null, vendorName: '' })
            return
          }
          setTyping(false)
          const vendor = vendors.find((one) => one.id === picked)
          onChange({ vendorId: vendor ? vendor.id : null, vendorName: vendor ? vendor.name : '' })
        }}
      >
        <option value="">Not saying</option>
        {vendors.map((vendor) => (
          <option key={vendor.id} value={vendor.id}>{vendor.name}</option>
        ))}
        <option value={NOT_ON_THE_LIST}>Somebody not on the list…</option>
      </select>
      {typing && (
        <input
          className={`${inputClass} mt-2`}
          placeholder="Their name"
          aria-label="Vendor name"
          value={value.vendorName}
          autoComplete="off"
          onChange={(event) => onChange({ vendorId: null, vendorName: event.target.value })}
        />
      )}
    </Field>
  )
}
