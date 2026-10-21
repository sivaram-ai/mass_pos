import { useState } from 'react'
import { api, saveToken } from '../api'
import type { Principal } from '../types'
import { Banner, Button, Field, inputClass } from '../components/ui'

/** Sampled from the company logo, so the product name is written in the brand's own colours. */
const BRAND = { blue: '#1a59d3', orange: '#fe6313', green: '#0a8c4b' }

/** Where each piece sits inside the 2000x2000 company logo. */
const WORDMARK = { x: 170, y: 1300, w: 1681, h: 360 }
const ROCKET = { x: 574, y: 293, w: 853, h: 979 }

/**
 * One piece of the company logo, cut out with CSS: the till ships a single image file and the
 * sign-in screen takes the rocket and the wordmark from it.
 */
function LogoPart({ crop, width, label, className = '' }: {
  crop: { x: number; y: number; w: number; h: number }
  width: number
  label: string
  className?: string
}) {
  const scale = width / crop.w
  return (
    <span
      role="img"
      aria-label={label}
      className={`block shrink-0 bg-no-repeat ${className}`}
      style={{
        width: `${width}px`,
        height: `${crop.h * scale}px`,
        backgroundImage: 'url(/gds-logo.webp)',
        backgroundSize: `${2000 * scale}px ${2000 * scale}px`,
        backgroundPosition: `${-crop.x * scale}px ${-crop.y * scale}px`,
      }}
    />
  )
}

/** The dark circuit board both signed-out screens stand on. */
const BACKDROP = "grid min-h-screen place-items-center bg-[#081830] bg-[url('/login-bg.svg')] "
  + 'bg-cover bg-center p-4'

const signInField = 'w-full rounded-lg border border-transparent bg-slate-100 px-4 py-3 text-base '
  + 'outline-none transition focus:border-sky-500 focus:bg-white focus:ring-2 focus:ring-sky-100'

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
    <div className={BACKDROP}>
      <form onSubmit={submit} className="w-full max-w-md rounded-2xl bg-white p-8 shadow-2xl">
        <LogoPart crop={WORDMARK} width={200} label="GoGo Digital Services" className="ml-auto" />

        <div className="mt-8 flex items-start justify-center">
          <h1 className="text-5xl font-bold tracking-tight">
            <span style={{ color: BRAND.blue }}>GO</span>{' '}
            <span style={{ color: BRAND.orange }}>Rock</span>
            <span style={{ color: BRAND.green }}>It</span>
          </h1>
          <LogoPart crop={ROCKET} width={62} label="" className="-mt-7" />
        </div>
        <p className="mb-7 text-center text-base text-slate-500">Sign in to start billing</p>

        {error && <div className="mb-4"><Banner tone="bad">{error}</Banner></div>}
        <div className="space-y-4">
          <label className="block">
            <span className="mb-1.5 block text-sm font-medium text-slate-600">Username</span>
            <input
              className={signInField}
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              autoFocus
              autoComplete="username"
            />
          </label>
          <label className="block">
            <span className="mb-1.5 block text-sm font-medium text-slate-600">PIN</span>
            <input
              className={`${signInField} num tracking-[0.4em]`}
              value={pin}
              onChange={(event) => setPin(event.target.value)}
              type="password"
              inputMode="numeric"
              autoComplete="current-password"
            />
          </label>
        </div>
        <Button type="submit" tone="primary" disabled={busy || !username || !pin}
                className="mt-6 w-full justify-center rounded-lg py-3 text-base">
          {busy ? 'Signing in…' : 'Sign in'}
        </Button>
        <p className="mt-5 text-center text-xs text-slate-400">
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
    <div className={BACKDROP}>
      <form onSubmit={submit} className="w-full max-w-sm rounded-2xl bg-white p-6 shadow-2xl">
        <LogoPart crop={WORDMARK} width={170} label="GoGo Digital Services" className="mb-4 ml-auto" />
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
