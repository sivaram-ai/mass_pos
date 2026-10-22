import { useCallback, useEffect, useState } from 'react'
import { api, fieldErrorsOf, messageOfFailure } from '../api'
import type { Role, Supplier, UserRow } from '../types'
import { Banner, Button, Empty, errorFor, Field, inputClass, Modal, Panel } from '../components/ui'
import type { Toast } from '../App'

const ROLES: Role[] = ['CASHIER', 'MANAGER', 'AUDITOR', 'ADMIN']

const ROLE_NOTES: Record<Role, string> = {
  CASHIER: 'Bills, parks and reprints',
  MANAGER: 'Also products, stock, cancellations and reports',
  AUDITOR: 'Reads reports and the audit trail only',
  ADMIN: 'Everything, including staff and settings',
}

export default function Users({ role, onToast }: { role: Role; onToast: Toast }) {
  const canManage = role === 'ADMIN'
  const [users, setUsers] = useState<UserRow[]>([])
  const [adding, setAdding] = useState(false)
  const [resetting, setResetting] = useState<UserRow | null>(null)

  const load = useCallback(() => {
    api.get<UserRow[]>('/api/users')
      .then(setUsers)
      .catch((failure) => onToast({ tone: 'bad', text: failure.message }))
  }, [onToast])

  useEffect(load, [load])

  const update = async (user: UserRow, change: Partial<UserRow>) => {
    try {
      await api.put(`/api/users/${user.id}`, {
        displayName: change.displayName ?? user.displayName,
        role: change.role ?? user.role,
        active: change.active ?? user.active,
      })
      load()
    } catch (failure) {
      onToast({ tone: 'bad', text: failure instanceof Error ? failure.message : 'Could not update' })
    }
  }

  return (
    <div className="space-y-4">
      <Panel
        title="Staff"
        actions={canManage
          ? <Button tone="primary" onClick={() => setAdding(true)}>Add person</Button>
          : <span className="text-xs text-slate-500">Only an admin can change accounts</span>}
      >
        {users.length === 0 ? <Empty>No accounts yet</Empty> : (
          <table className="w-full text-sm">
            <thead className="text-xs uppercase tracking-wide text-slate-400">
              <tr>
                <th className="py-1 text-left">Name</th>
                <th className="py-1 text-left">Username</th>
                <th className="py-1 text-left">Role</th>
                <th className="py-1 text-left">Status</th>
                <th className="py-1"></th>
              </tr>
            </thead>
            <tbody>
              {users.map((user) => (
                <tr key={user.id} className="border-t border-slate-100">
                  <td className="py-2 font-medium text-slate-800">{user.displayName}</td>
                  <td className="py-2 text-slate-500">{user.username}</td>
                  <td className="py-2">
                    {canManage ? (
                      <select
                        className="rounded border border-slate-300 px-2 py-1 text-sm"
                        value={user.role}
                        onChange={(event) => update(user, { role: event.target.value as Role })}
                      >
                        {ROLES.map((option) => <option key={option} value={option}>{option}</option>)}
                      </select>
                    ) : user.role}
                    <div className="text-xs text-slate-400">{ROLE_NOTES[user.role]}</div>
                  </td>
                  <td className="py-2">
                    {user.active
                      ? <span className="text-emerald-700">active</span>
                      : <span className="text-slate-400">disabled</span>}
                    {user.mustChangePin && (
                      <div className="text-xs text-amber-600">must choose a PIN</div>
                    )}
                  </td>
                  <td className="py-2 text-right">
                    {canManage && (
                      <>
                        <Button tone="ghost" onClick={() => setResetting(user)}>Reset PIN</Button>
                        <Button tone="ghost" onClick={() => update(user, { active: !user.active })}>
                          {user.active ? 'Disable' : 'Enable'}
                        </Button>
                      </>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {adding && (
          <AddUserDialog
            onClose={() => setAdding(false)}
            onDone={() => {
              setAdding(false)
              load()
            }}
            onToast={onToast}
          />
        )}
        {resetting && (
          <ResetPinDialog
            user={resetting}
            onClose={() => setResetting(null)}
            onDone={() => {
              setResetting(null)
              load()
            }}
            onToast={onToast}
          />
        )}
      </Panel>

      <SupplierPanel canManage={canManage} onToast={onToast} />
    </div>
  )
}

function AddUserDialog({ onClose, onDone, onToast }: { onClose: () => void; onDone: () => void; onToast: Toast }) {
  const [username, setUsername] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [role, setRole] = useState<Role>('CASHIER')
  const [pin, setPin] = useState('')
  const [errors, setErrors] = useState<{ message: string; fields: Record<string, string> } | null>(null)

  const err = (field: string) => errorFor(errors?.fields ?? {}, field)

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    try {
      await api.post('/api/users', { username, displayName, role, pin })
      onToast({ tone: 'good', text: `${displayName} can sign in with the PIN you set, then must change it` })
      onDone()
    } catch (failure) {
      // In the dialog, next to the boxes it is talking about - behind it nobody would see it.
      setErrors({
        message: messageOfFailure(failure, 'Could not create the account'),
        fields: fieldErrorsOf(failure),
      })
    }
  }

  return (
    <Modal title="Add a person" onClose={onClose}>
      <form onSubmit={submit} className="space-y-3">
        {errors && <Banner tone="bad" onDismiss={() => setErrors(null)}>{errors.message}</Banner>}
        <Field label="Full name" error={err('displayName')}>
          <input className={inputClass} autoFocus value={displayName}
                 onChange={(event) => setDisplayName(event.target.value)} />
        </Field>
        <Field label="Username" hint="What they type to sign in" error={err('username')}>
          <input className={inputClass} value={username} onChange={(event) => setUsername(event.target.value)} />
        </Field>
        <Field label="Role" error={err('role')}>
          <select className={inputClass} value={role} onChange={(event) => setRole(event.target.value as Role)}>
            {ROLES.map((option) => (
              <option key={option} value={option}>{option} — {ROLE_NOTES[option]}</option>
            ))}
          </select>
        </Field>
        <Field label="Starting PIN" hint="Tell them this once; they must pick their own at first sign-in"
               error={err('pin')}>
          <input className={`${inputClass} num`} inputMode="numeric" value={pin}
                 onChange={(event) => setPin(event.target.value)} />
        </Field>
        <div className="flex justify-end gap-2">
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" tone="primary">Create</Button>
        </div>
      </form>
    </Modal>
  )
}

function ResetPinDialog({ user, onClose, onDone, onToast }: {
  user: UserRow
  onClose: () => void
  onDone: () => void
  onToast: Toast
}) {
  const [pin, setPin] = useState('')
  const [errors, setErrors] = useState<{ message: string; fields: Record<string, string> } | null>(null)

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    try {
      await api.post(`/api/users/${user.id}/pin`, { newPin: pin })
      onToast({ tone: 'good', text: `${user.displayName} is signed out everywhere and must pick a new PIN` })
      onDone()
    } catch (failure) {
      setErrors({ message: messageOfFailure(failure, 'Could not reset the PIN'), fields: fieldErrorsOf(failure) })
    }
  }

  return (
    <Modal title={`Reset PIN for ${user.displayName}`} onClose={onClose}>
      <form onSubmit={submit} className="space-y-3">
        {errors && <Banner tone="bad" onDismiss={() => setErrors(null)}>{errors.message}</Banner>}
        <p className="text-sm text-slate-600">
          This signs them out of every till at once. They will have to choose their own PIN at the next sign-in.
        </p>
        <Field label="Temporary PIN" error={errorFor(errors?.fields ?? {}, 'newPin')}>
          <input className={`${inputClass} num`} inputMode="numeric" autoFocus value={pin}
                 onChange={(event) => setPin(event.target.value)} />
        </Field>
        <div className="flex justify-end gap-2">
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" tone="danger">Reset</Button>
        </div>
      </form>
    </Modal>
  )
}

/**
 * Whoever carries the food to the table. They sit beside the staff because that is where someone
 * goes to add one, but they are not people who use the till: an ID and a name, no PIN and no
 * sign-in. Stopped rather than deleted, because the bills they served carry the code and have to
 * keep making sense long after they have left.
 */
function SupplierPanel({ canManage, onToast }: { canManage: boolean; onToast: Toast }) {
  const [suppliers, setSuppliers] = useState<Supplier[]>([])
  const [adding, setAdding] = useState(false)

  const load = useCallback(() => {
    api.get<Supplier[]>('/api/suppliers?all=true')
      .then(setSuppliers)
      .catch((failure) => onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not load the suppliers') }))
  }, [onToast])

  useEffect(load, [load])

  const setActive = async (supplier: Supplier, active: boolean) => {
    try {
      await api.post(`/api/suppliers/${supplier.id}/${active ? 'activate' : 'deactivate'}`)
      load()
    } catch (failure) {
      onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not change that supplier') })
    }
  }

  return (
    <Panel
      title="Suppliers"
      actions={canManage
        ? <Button tone="primary" onClick={() => setAdding(true)}>Add supplier</Button>
        : <span className="text-xs text-slate-500">Only an admin can change these</span>}
    >
      <p className="mb-2 text-xs text-slate-400">
        Whoever serves the table. Picked at the start of every bill when Settings asks for one, and
        printed beside the table number above the bill number.
      </p>
      {suppliers.length === 0 ? <Empty>No suppliers yet</Empty> : (
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-slate-200 text-xs uppercase text-slate-400">
              <th className="py-1 text-left">Supplier ID</th>
              <th className="py-1 text-left">Supplier name</th>
              <th className="py-1 text-left">Status</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {suppliers.map((supplier) => (
              <tr key={supplier.id} className="border-b border-slate-100">
                <td className="py-2 font-medium">{supplier.supplierId}</td>
                <td className="py-2">{supplier.supplierName}</td>
                <td className="py-2">
                  {supplier.active
                    ? <span className="text-emerald-700">active</span>
                    : <span className="text-slate-400">stopped</span>}
                </td>
                <td className="py-2 text-right">
                  {canManage && (
                    <Button tone="ghost" onClick={() => void setActive(supplier, !supplier.active)}>
                      {supplier.active ? 'Stop' : 'Use again'}
                    </Button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {adding && (
        <AddSupplierDialog
          onClose={() => setAdding(false)}
          onDone={() => {
            setAdding(false)
            load()
          }}
          onToast={onToast}
        />
      )}
    </Panel>
  )
}

function AddSupplierDialog({ onClose, onDone, onToast }: {
  onClose: () => void
  onDone: () => void
  onToast: Toast
}) {
  const [supplierId, setSupplierId] = useState('')
  const [supplierName, setSupplierName] = useState('')
  const [errors, setErrors] = useState<{ message: string; fields: Record<string, string> } | null>(null)

  const err = (field: string) => errorFor(errors?.fields ?? {}, field)

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    try {
      await api.post('/api/suppliers', { supplierId, supplierName })
      onToast({ tone: 'good', text: `${supplierName} can now be picked on a bill` })
      onDone()
    } catch (failure) {
      // Inside the dialog, beside the boxes it is about: behind it nobody would read it.
      setErrors({
        message: messageOfFailure(failure, 'Could not add the supplier'),
        fields: fieldErrorsOf(failure),
      })
    }
  }

  return (
    <Modal title="Add a supplier" onClose={onClose}>
      <form onSubmit={submit} className="space-y-3">
        {errors && <Banner tone="bad" onDismiss={() => setErrors(null)}>{errors.message}</Banner>}
        <Field label="Supplier name" error={err('supplierName')}>
          <input className={inputClass} autoFocus value={supplierName}
                 onChange={(event) => setSupplierName(event.target.value)} />
        </Field>
        <Field label="Supplier ID" hint="Printed on every bill they serve" error={err('supplierId')}>
          <input className={inputClass} value={supplierId}
                 onChange={(event) => setSupplierId(event.target.value)} />
        </Field>
        <div className="flex justify-end gap-2">
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" tone="primary">Add</Button>
        </div>
      </form>
    </Modal>
  )
}