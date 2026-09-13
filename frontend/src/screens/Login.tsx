import { useState } from 'react'
import { api, saveToken } from '../api'
import type { Principal } from '../types'
import { Banner, Button, Field, inputClass } from '../components/ui'

/**
 * Signing in is per shift, not per hour: the token that comes back never expires, so a cashier
 * stays signed in until they press Log out.
 */
export function Login({ onSignedIn }: { onSignedIn: (user: Principal) => void }) {
  const [username, setUsername] = useState('')
  const [pin, setPin] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const result = await api.post<{ token: string; user: Principal }>('/api/auth/login', { username, pin })
      saveToken(result.token)
      onSignedIn(result.user)
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not sign in')
      setPin('')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="grid h-screen place-items-center bg-slate-900 p-4">
      <form onSubmit={submit} className="w-full max-w-sm rounded-xl bg-white p-6 shadow-xl">
        <h1 className="text-xl font-semibold text-slate-800">Mass POS</h1>
        <p className="mb-5 text-sm text-slate-500">Sign in to start billing</p>
        {error && <div className="mb-4"><Banner tone="bad">{error}</Banner></div>}
        <div className="space-y-3">
          <Field label="Username">
            <input
              className={inputClass}
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              autoFocus
              autoComplete="username"
            />
          </Field>
          <Field label="PIN">
            <input
              className={`${inputClass} num tracking-[0.4em]`}
              value={pin}
              onChange={(event) => setPin(event.target.value)}
              type="password"
              inputMode="numeric"
              autoComplete="current-password"
            />
          </Field>
        </div>
        <Button type="submit" tone="primary" disabled={busy || !username || !pin} className="mt-5 w-full justify-center">
          {busy ? 'Signing in…' : 'Sign in'}
        </Button>
        <p className="mt-4 text-center text-xs text-slate-400">
          First start? The admin PIN is in logs\pos.log
        </p>
      </form>
    </div>
  )
}

/** A new or reset account has to choose its own PIN before it can bill in that person's name. */
export function ChangePin({ user, onDone, onCancel }: {
  user: Principal
  onDone: (user: Principal) => void
  onCancel: () => void
}) {
  const [currentPin, setCurrentPin] = useState('')
  const [newPin, setNewPin] = useState('')
  const [repeat, setRepeat] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (newPin !== repeat) {
      setError('The two new PINs do not match')
      return
    }
    setBusy(true)
    setError(null)
    try {
      await api.post('/api/auth/change-pin', { currentPin, newPin })
      onDone(await api.get<Principal>('/api/auth/me'))
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Could not change the PIN')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="grid h-screen place-items-center bg-slate-900 p-4">
      <form onSubmit={submit} className="w-full max-w-sm rounded-xl bg-white p-6 shadow-xl">
        <h1 className="text-lg font-semibold text-slate-800">Choose your PIN</h1>
        <p className="mb-5 text-sm text-slate-500">
          Signed in as {user.displayName}. Pick a PIN only you know: every bill you take is recorded under this account.
        </p>
        {error && <div className="mb-4"><Banner tone="bad">{error}</Banner></div>}
        <div className="space-y-3">
          <Field label="Current PIN">
            <input className={`${inputClass} num`} type="password" inputMode="numeric" value={currentPin}
                   onChange={(event) => setCurrentPin(event.target.value)} autoFocus />
          </Field>
          <Field label="New PIN" hint="4 to 8 digits, and not something like 1234">
            <input className={`${inputClass} num`} type="password" inputMode="numeric" value={newPin}
                   onChange={(event) => setNewPin(event.target.value)} />
          </Field>
          <Field label="New PIN again">
            <input className={`${inputClass} num`} type="password" inputMode="numeric" value={repeat}
                   onChange={(event) => setRepeat(event.target.value)} />
          </Field>
        </div>
        <div className="mt-5 flex gap-2">
          <Button onClick={onCancel} className="flex-1 justify-center">Sign out</Button>
          <Button type="submit" tone="primary" disabled={busy} className="flex-1 justify-center">Save PIN</Button>
        </div>
      </form>
    </div>
  )
}
