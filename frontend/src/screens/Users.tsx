import { useCallback, useEffect, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import { api, fieldErrorsOf, messageOfFailure } from '../api'
import type { Role, Supplier, UserRow } from '../types'
import { Banner, Button, Empty, errorFor, Field, inputClass, Modal, Panel } from '../components/ui'
import { useScreenKeys } from '../shortcuts'
import type { Toast } from '../App'

const ROLES: Role[] = ['CASHIER', 'MANAGER', 'AUDITOR', 'ADMIN']

const ROLE_NOTES: Record<Role, string> = {
  CASHIER: 'Bills, parks and reprints',
  MANAGER: 'Also products, stock, cancellations and reports',
  AUDITOR: 'Reads reports and the audit trail only',
  ADMIN: 'Everything, including staff and settings',
}

const STAFF_KEY = 'F2'
const ADD_PERSON_KEY = 'F3'
const SUPPLIERS_KEY = 'F4'
const ADD_SUPPLIER_KEY = 'F6'

type StaffAction = 'edit' | 'pin' | 'active'
type SupplierAction = 'edit' | 'active'

/** One row of whichever list has the cursor, and what Enter would do to it. */
type Cursor = { at: number; action: string }

const START: Cursor = { at: 0, action: 'edit' }

/**
 * People and suppliers, worked from the keyboard like the products screen: the cursor sits in a
 * list, up and down choose a row, left and right choose what Enter will do, and Enter does it.
 *
 * <p>The two lists are separate because the things in them are: one is accounts that sign in, the
 * other is who carried the food out. {@value STAFF_KEY} and {@value SUPPLIERS_KEY} jump between
 * them, so neither needs the mouse.
 */
export default function Users({ role, onToast }: { role: Role; onToast: Toast }) {
  const canManage = role === 'ADMIN'
  const staffList = useRef<HTMLDivElement>(null)
  const supplierList = useRef<HTMLDivElement>(null)
  const [adding, setAdding] = useState(false)
  const [addingSupplier, setAddingSupplier] = useState(false)
  const [editing, setEditing] = useState<UserRow | null>(null)
  const [editingSupplier, setEditingSupplier] = useState<Supplier | null>(null)
  const [resetting, setResetting] = useState<UserRow | null>(null)
  const [users, setUsers] = useState<UserRow[]>([])
  const [suppliers, setSuppliers] = useState<Supplier[]>([])
  const dialogOpen = adding || addingSupplier || editing !== null || editingSupplier !== null
    || resetting !== null

  const loadUsers = useCallback(() => {
    api.get<UserRow[]>('/api/users')
      .then(setUsers)
      .catch((failure) => onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not load the staff') }))
  }, [onToast])

  const loadSuppliers = useCallback(() => {
    api.get<Supplier[]>('/api/suppliers?all=true')
      .then(setSuppliers)
      .catch((failure) => onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not load the suppliers') }))
  }, [onToast])

  useEffect(loadUsers, [loadUsers])
  useEffect(loadSuppliers, [loadSuppliers])

  // The cursor starts in the staff list, so the arrows work the moment the screen opens.
  useEffect(() => {
    staffList.current?.focus()
  }, [])

  useScreenKeys({
    [STAFF_KEY]: () => staffList.current?.focus(),
    [SUPPLIERS_KEY]: () => supplierList.current?.focus(),
    [ADD_PERSON_KEY]: canManage ? () => setAdding(true) : undefined,
    [ADD_SUPPLIER_KEY]: canManage ? () => setAddingSupplier(true) : undefined,
  }, !dialogOpen)

  /** Back to the list the dialog came from, so the arrows carry on where they were. */
  const closeTo = (list: React.RefObject<HTMLDivElement | null>) => {
    requestAnimationFrame(() => list.current?.focus())
  }

  const saveUser = async (user: UserRow, change: Partial<UserRow>) => {
    await api.put(`/api/users/${user.id}`, {
      username: change.username ?? user.username,
      displayName: change.displayName ?? user.displayName,
      role: change.role ?? user.role,
      active: change.active ?? user.active,
    })
    loadUsers()
  }

  /** Switching someone off is one key press, so its refusal has to be said out loud. */
  const toggleUser = async (user: UserRow) => {
    try {
      await saveUser(user, { active: !user.active })
      onToast({
        tone: 'good',
        text: user.active ? `${user.displayName} can no longer sign in` : `${user.displayName} can sign in again`,
      })
    } catch (failure) {
      onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not change that account') })
    }
  }

  const toggleSupplier = async (supplier: Supplier) => {
    try {
      await api.post(`/api/suppliers/${supplier.id}/${supplier.active ? 'deactivate' : 'activate'}`)
      loadSuppliers()
    } catch (failure) {
      onToast({ tone: 'bad', text: messageOfFailure(failure, 'Could not change that supplier') })
    }
  }

  return (
    <div className="space-y-4">
      <Panel
        title="Staff"
        actions={canManage
          ? (
            <Button tone="primary" hint={ADD_PERSON_KEY} className="whitespace-nowrap px-2 py-1"
                    onClick={() => setAdding(true)}>
              Add person
            </Button>
          )
          : <span className="text-xs text-slate-500">Only an admin can change accounts</span>}
      >
        <KeyHints add={ADD_PERSON_KEY} addLabel="add a person" other={SUPPLIERS_KEY} otherLabel="suppliers" />
        <KeyList<UserRow, StaffAction>
          ref={staffList}
          label="Staff"
          rows={users}
          idOf={(user) => user.id}
          actionsFor={(user) => (canManage
            ? [
              { name: 'edit', label: 'Edit' },
              { name: 'pin', label: 'Reset PIN' },
              { name: 'active', label: user.active ? 'Disable' : 'Enable', tone: user.active ? 'bad' : 'plain' },
            ]
            : [])}
          onRun={(user, action) => {
            if (action === 'edit') {
              setEditing(user)
            } else if (action === 'pin') {
              setResetting(user)
            } else {
              void toggleUser(user)
            }
          }}
          empty="No accounts yet"
          blocked={dialogOpen}
          head={(
            <tr>
              <th className="py-1 pl-2 text-left">Name</th>
              <th className="py-1 text-left">Username</th>
              <th className="py-1 text-left">Role</th>
              <th className="py-1 text-left">Status</th>
              <th className="py-1"></th>
            </tr>
          )}
          cells={(user) => (
            <>
              <td className="py-2 pl-2 font-medium text-slate-800">{user.displayName}</td>
              <td className="num py-2 text-slate-500">{user.username}</td>
              <td className="py-2">
                {user.role}
                <div className="text-xs text-slate-400">{ROLE_NOTES[user.role]}</div>
              </td>
              <td className="py-2">
                {user.active
                  ? <span className="text-emerald-700">active</span>
                  : <span className="text-slate-400">disabled</span>}
                {user.mustChangePin && <div className="text-xs text-amber-600">must choose a PIN</div>}
              </td>
            </>
          )}
        />

        {adding && (
          <UserDialog
            onClose={() => {
              setAdding(false)
              closeTo(staffList)
            }}
            onDone={() => {
              setAdding(false)
              loadUsers()
              closeTo(staffList)
            }}
            onToast={onToast}
          />
        )}
        {editing && (
          <UserDialog
            user={editing}
            onClose={() => {
              setEditing(null)
              closeTo(staffList)
            }}
            onDone={() => {
              setEditing(null)
              loadUsers()
              closeTo(staffList)
            }}
            onToast={onToast}
          />
        )}
        {resetting && (
          <ResetPinDialog
            user={resetting}
            onClose={() => {
              setResetting(null)
              closeTo(staffList)
            }}
            onDone={() => {
              setResetting(null)
              loadUsers()
              closeTo(staffList)
            }}
            onToast={onToast}
          />
        )}
      </Panel>

      <Panel
        title="Suppliers"
        actions={canManage
          ? (
            <Button tone="primary" hint={ADD_SUPPLIER_KEY} className="whitespace-nowrap px-2 py-1"
                    onClick={() => setAddingSupplier(true)}>
              Add supplier
            </Button>
          )
          : <span className="text-xs text-slate-500">Only an admin can change these</span>}
      >
        <p className="mb-2 text-xs text-slate-400">
          Whoever serves the table. Picked at the start of every bill when Settings asks for one, and
          printed beside the table number above the bill number. Stopped rather than deleted, because
          the bills they served carry the ID and have to keep making sense.
        </p>
        <KeyHints add={ADD_SUPPLIER_KEY} addLabel="add a supplier" other={STAFF_KEY} otherLabel="staff" />
        <KeyList<Supplier, SupplierAction>
          ref={supplierList}
          label="Suppliers"
          rows={suppliers}
          idOf={(supplier) => supplier.id}
          actionsFor={(supplier) => (canManage
            ? [
              { name: 'edit', label: 'Edit' },
              {
                name: 'active',
                label: supplier.active ? 'Stop' : 'Use again',
                tone: supplier.active ? 'bad' : 'plain',
              },
            ]
            : [])}
          onRun={(supplier, action) => {
            if (action === 'edit') {
              setEditingSupplier(supplier)
            } else {
              void toggleSupplier(supplier)
            }
          }}
          empty="No suppliers yet"
          blocked={dialogOpen}
          head={(
            <tr>
              <th className="py-1 pl-2 text-left">Supplier ID</th>
              <th className="py-1 text-left">Supplier name</th>
              <th className="py-1 text-left">Status</th>
              <th className="py-1"></th>
            </tr>
          )}
          cells={(supplier) => (
            <>
              <td className="num py-2 pl-2 font-medium">{supplier.supplierId}</td>
              <td className="py-2">{supplier.supplierName}</td>
              <td className="py-2">
                {supplier.active
                  ? <span className="text-emerald-700">active</span>
                  : <span className="text-slate-400">stopped</span>}
              </td>
            </>
          )}
        />

        {(addingSupplier || editingSupplier) && (
          <SupplierDialog
            supplier={editingSupplier}
            onClose={() => {
              setAddingSupplier(false)
              setEditingSupplier(null)
              closeTo(supplierList)
            }}
            onDone={() => {
              setAddingSupplier(false)
              setEditingSupplier(null)
              loadSuppliers()
              closeTo(supplierList)
            }}
            onToast={onToast}
          />
        )}
      </Panel>
    </div>
  )
}

function KeyHints({ add, addLabel, other, otherLabel }: {
  add: string
  addLabel: string
  other: string
  otherLabel: string
}) {
  return (
    <p className="mb-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-400">
      <span><kbd>↑</kbd> <kbd>↓</kbd> choose a row</span>
      <span><kbd>←</kbd> <kbd>→</kbd> choose what to do</span>
      <span><kbd>Enter</kbd> do it</span>
      <span><kbd>{add}</kbd> {addLabel} · <kbd>{other}</kbd> {otherLabel}</span>
    </p>
  )
}

type RowAction<A extends string> = { name: A; label: string; tone?: 'bad' | 'plain' }

/**
 * A list worked with the arrow keys: the same shape the products and bills screens use, pulled
 * out here because this screen needs two of them side by side. The row's actions come from the
 * caller, so a person's and a supplier's differ without the list knowing anything about either.
 */
function KeyList<T, A extends string>({ ref, label, rows, idOf, actionsFor, onRun, head, cells, empty, blocked }: {
  ref: React.RefObject<HTMLDivElement | null>
  label: string
  rows: T[]
  idOf: (row: T) => string
  actionsFor: (row: T) => RowAction<A>[]
  onRun: (row: T, action: A) => void
  head: React.ReactNode
  cells: (row: T) => React.ReactNode
  empty: string
  blocked: boolean
}) {
  const [cursor, setCursor] = useState<Cursor>(START)
  const [focused, setFocused] = useState(false)
  const elements = useRef(new Map<string, HTMLTableRowElement>())

  const at = Math.min(cursor.at, Math.max(rows.length - 1, 0))
  const current = rows[at]
  const actions = current ? actionsFor(current) : []
  const action = actions.find((one) => one.name === cursor.action) ?? actions[0]

  useEffect(() => {
    if (current) {
      elements.current.get(idOf(current))?.scrollIntoView({ block: 'nearest' })
    }
  }, [current, idOf])

  const choose = (index: number) => setCursor({ at: index, action: 'edit' })

  const onKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (blocked || !current) {
      return
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      choose(Math.max(0, Math.min(rows.length - 1, at + (event.key === 'ArrowDown' ? 1 : -1))))
    } else if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
      event.preventDefault()
      const step = event.key === 'ArrowRight' ? 1 : -1
      const was = actions.findIndex((one) => one.name === action?.name)
      const next = actions[Math.max(0, Math.min(actions.length - 1, was + step))]
      if (next) {
        setCursor({ at, action: next.name })
      }
    } else if (event.key === 'Home' || event.key === 'End') {
      event.preventDefault()
      choose(event.key === 'Home' ? 0 : rows.length - 1)
    } else if (event.key === 'Enter' && !event.repeat) {
      event.preventDefault()
      if (action) {
        onRun(current, action.name)
      }
    }
  }

  return (
    <div
      ref={ref}
      tabIndex={0}
      role="grid"
      aria-label={label}
      onKeyDown={onKeyDown}
      onFocus={() => setFocused(true)}
      onBlur={() => setFocused(false)}
      className="overflow-x-auto rounded-md outline-none focus-visible:ring-2 focus-visible:ring-sky-200"
    >
      {rows.length === 0 ? <Empty>{empty}</Empty> : (
        // A minimum width, so a narrow window scrolls sideways rather than crushing the columns
        // into each other the way the old table did.
        <table className="w-full min-w-[42rem] text-sm">
          <thead className="text-xs uppercase tracking-wide text-slate-400">{head}</thead>
          <tbody>
            {rows.map((row, index) => {
              const isHere = index === at
              return (
                <tr
                  key={idOf(row)}
                  ref={(element) => {
                    if (element) {
                      elements.current.set(idOf(row), element)
                    } else {
                      elements.current.delete(idOf(row))
                    }
                  }}
                  onMouseDown={() => choose(index)}
                  aria-selected={isHere}
                  className={`border-t border-slate-100 ${
                    isHere ? 'bg-sky-50 shadow-[inset_3px_0_0_0_var(--color-sky-500)]' : 'hover:bg-slate-50'}`}
                >
                  {cells(row)}
                  <td className="whitespace-nowrap py-1.5 pr-2 text-right">
                    <div className="inline-flex gap-1">
                      {actionsFor(row).map((one) => {
                        const lit = isHere && one.name === action?.name
                        return (
                          <button
                            key={one.name}
                            type="button"
                            tabIndex={-1}
                            onMouseDown={(event) => event.stopPropagation()}
                            onClick={() => {
                              setCursor({ at: index, action: one.name })
                              onRun(row, one.name)
                            }}
                            className={`rounded-md px-2.5 py-1 text-xs font-medium transition ${
                              lit
                                ? `bg-sky-600 text-white ${focused ? 'ring-2 ring-sky-300' : ''}`
                                : one.tone === 'bad'
                                  ? 'text-rose-600 hover:bg-rose-50'
                                  : 'text-slate-600 hover:bg-slate-100'}`}
                          >
                            {one.label}
                          </button>
                        )
                      })}
                    </div>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      )}
    </div>
  )
}

/**
 * Adding a person and changing one, in one dialog: the same four things are being decided either
 * way, and two dialogs would have drifted apart. A PIN is only asked for when the account is new
 * - an existing one is changed with Reset PIN, which signs them out everywhere.
 */
function UserDialog({ user, onClose, onDone, onToast }: {
  user?: UserRow
  onClose: () => void
  onDone: () => void
  onToast: Toast
}) {
  const [username, setUsername] = useState(user?.username ?? '')
  const [displayName, setDisplayName] = useState(user?.displayName ?? '')
  const [role, setRole] = useState<Role>(user?.role ?? 'CASHIER')
  const [active, setActive] = useState(user?.active ?? true)
  const [pin, setPin] = useState('')
  const [errors, setErrors] = useState<{ message: string; fields: Record<string, string> } | null>(null)

  const err = (field: string) => errorFor(errors?.fields ?? {}, field)

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    try {
      if (user) {
        await api.put(`/api/users/${user.id}`, { username, displayName, role, active })
        onToast({ tone: 'good', text: `Saved ${displayName}` })
      } else {
        await api.post('/api/users', { username, displayName, role, pin })
        onToast({ tone: 'good', text: `${displayName} can sign in with the PIN you set, then must change it` })
      }
      onDone()
    } catch (failure) {
      // In the dialog, next to the boxes it is talking about - behind it nobody would see it.
      setErrors({
        message: messageOfFailure(failure, user ? 'Could not save the account' : 'Could not create the account'),
        fields: fieldErrorsOf(failure),
      })
    }
  }

  return (
    <Modal title={user ? `Edit ${user.displayName}` : 'Add a person'} onClose={onClose}>
      <form onSubmit={submit} className="space-y-3">
        {errors && <Banner tone="bad" onDismiss={() => setErrors(null)}>{errors.message}</Banner>}
        <Field label="Full name" error={err('displayName')}>
          <input className={inputClass} autoFocus value={displayName}
                 onChange={(event) => setDisplayName(event.target.value)} />
        </Field>
        <Field
          label="Username"
          hint={user ? 'What they type to sign in. Changing it signs them out everywhere.' : 'What they type to sign in'}
          error={err('username')}
        >
          <input className={inputClass} value={username} onChange={(event) => setUsername(event.target.value)} />
        </Field>
        <Field label="Role" error={err('role')}>
          <select className={inputClass} value={role} onChange={(event) => setRole(event.target.value as Role)}>
            {ROLES.map((option) => (
              <option key={option} value={option}>{option} — {ROLE_NOTES[option]}</option>
            ))}
          </select>
        </Field>
        {user ? (
          <label className="flex items-center gap-2 text-sm text-slate-700">
            <input type="checkbox" checked={active} onChange={(event) => setActive(event.target.checked)} />
            Can sign in
          </label>
        ) : (
          <Field label="Starting PIN" hint="Tell them this once; they must pick their own at first sign-in"
                 error={err('pin')}>
            <input className={`${inputClass} num`} inputMode="numeric" value={pin}
                   onChange={(event) => setPin(event.target.value)} />
          </Field>
        )}
        <div className="flex justify-end gap-2">
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" tone="primary">{user ? 'Save' : 'Create'}</Button>
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

/** Adding a supplier and changing one: both fields either way, because both are on the bill. */
function SupplierDialog({ supplier, onClose, onDone, onToast }: {
  supplier: Supplier | null
  onClose: () => void
  onDone: () => void
  onToast: Toast
}) {
  const [supplierId, setSupplierId] = useState(supplier?.supplierId ?? '')
  const [supplierName, setSupplierName] = useState(supplier?.supplierName ?? '')
  const [errors, setErrors] = useState<{ message: string; fields: Record<string, string> } | null>(null)

  const err = (field: string) => errorFor(errors?.fields ?? {}, field)

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    try {
      if (supplier) {
        await api.put(`/api/suppliers/${supplier.id}`, { supplierId, supplierName })
        onToast({ tone: 'good', text: `Saved ${supplierName}` })
      } else {
        await api.post('/api/suppliers', { supplierId, supplierName })
        onToast({ tone: 'good', text: `${supplierName} can now be picked on a bill` })
      }
      onDone()
    } catch (failure) {
      // Inside the dialog, beside the boxes it is about: behind it nobody would read it.
      setErrors({
        message: messageOfFailure(failure, 'Could not save the supplier'),
        fields: fieldErrorsOf(failure),
      })
    }
  }

  return (
    <Modal title={supplier ? `Edit ${supplier.supplierName}` : 'Add a supplier'} onClose={onClose}>
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
          <Button type="submit" tone="primary">{supplier ? 'Save' : 'Add'}</Button>
        </div>
      </form>
    </Modal>
  )
}
