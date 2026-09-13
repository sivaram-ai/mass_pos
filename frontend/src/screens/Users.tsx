import { useCallback, useEffect, useState } from 'react'
import { api } from '../api'
import type { Role, UserRow } from '../types'
import { Button, Empty, Field, inputClass, Modal, Panel } from '../components/ui'
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
  )
}

function AddUserDialog({ onClose, onDone, onToast }: { onClose: () => void; onDone: () => void; onToast: Toast }) {
  const [username, setUsername] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [role, setRole] = useState<Role>('CASHIER')
  const [pin, setPin] = useState('')

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    try {
      await api.post('/api/users', { username, displayName, role, pin })
      onToast({ tone: 'good', text: `${displayName} can sign in with the PIN you set, then must change it` })
      onDone()
    } catch (failure) {
      onToast({ tone: 'bad', text: failure instanceof Error ? failure.message : 'Could not create the account' })
    }
  }

  return (
    <Modal title="Add a person" onClose={onClose}>
      <form onSubmit={submit} className="space-y-3">
        <Field label="Full name">
          <input className={inputClass} autoFocus value={displayName}
                 onChange={(event) => setDisplayName(event.target.value)} />
        </Field>
        <Field label="Username" hint="What they type to sign in">
          <input className={inputClass} value={username} onChange={(event) => setUsername(event.target.value)} />
        </Field>
        <Field label="Role">
          <select className={inputClass} value={role} onChange={(event) => setRole(event.target.value as Role)}>
            {ROLES.map((option) => (
              <option key={option} value={option}>{option} — {ROLE_NOTES[option]}</option>
            ))}
          </select>
        </Field>
        <Field label="Starting PIN" hint="Tell them this once; they must pick their own at first sign-in">
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

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    try {
      await api.post(`/api/users/${user.id}/pin`, { newPin: pin })
      onToast({ tone: 'good', text: `${user.displayName} is signed out everywhere and must pick a new PIN` })
      onDone()
    } catch (failure) {
      onToast({ tone: 'bad', text: failure instanceof Error ? failure.message : 'Could not reset the PIN' })
    }
  }

  return (
    <Modal title={`Reset PIN for ${user.displayName}`} onClose={onClose}>
      <form onSubmit={submit} className="space-y-3">
        <p className="text-sm text-slate-600">
          This signs them out of every till at once. They will have to choose their own PIN at the next sign-in.
        </p>
        <Field label="Temporary PIN">
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
