import { useCallback, useEffect, useState } from 'react'
import { api, saveToken, savedToken } from './api'
import type { Principal, Role, SettingsView } from './types'
import { Login, ChangePin } from './screens/Login'
import Billing from './screens/Billing'
import Products from './screens/Products'
import Stock from './screens/Stock'
import Invoices from './screens/Invoices'
import Reports from './screens/Reports'
import Settings from './screens/Settings'
import Users from './screens/Users'
import { Banner } from './components/ui'

type Screen = 'billing' | 'products' | 'stock' | 'invoices' | 'reports' | 'users' | 'settings'

/** ADMIN reaches everything; the rest see only what their job needs. */
const SCREENS: { id: Screen; label: string; roles: Role[] }[] = [
  { id: 'billing', label: 'Billing', roles: ['CASHIER', 'MANAGER'] },
  { id: 'invoices', label: 'Bills', roles: ['CASHIER', 'MANAGER', 'AUDITOR'] },
  { id: 'products', label: 'Products', roles: ['MANAGER'] },
  { id: 'stock', label: 'Stock', roles: ['MANAGER'] },
  { id: 'reports', label: 'Reports', roles: ['MANAGER', 'AUDITOR'] },
  { id: 'users', label: 'Staff', roles: ['MANAGER', 'AUDITOR'] },
  { id: 'settings', label: 'Settings', roles: [] },
]

export default function App() {
  const [user, setUser] = useState<Principal | null>(null)
  const [settings, setSettings] = useState<SettingsView | null>(null)
  const [screen, setScreen] = useState<Screen>('billing')
  const [booting, setBooting] = useState(true)
  const [toast, setToast] = useState<{ tone: 'good' | 'bad' | 'warn'; text: string } | null>(null)

  const refreshSettings = useCallback(() => {
    api.get<SettingsView>('/api/settings').then(setSettings).catch(() => setSettings(null))
  }, [])

  useEffect(() => {
    if (!savedToken()) {
      setBooting(false)
      return
    }
    api.get<Principal>('/api/auth/me')
      .then(setUser)
      .catch(() => saveToken(null))
      .finally(() => setBooting(false))
  }, [])

  useEffect(() => {
    if (user && !user.mustChangePin) {
      refreshSettings()
    }
  }, [user, refreshSettings])

  useEffect(() => {
    if (!toast) {
      return
    }
    const timer = setTimeout(() => setToast(null), 6000)
    return () => clearTimeout(timer)
  }, [toast])

  const signOut = async () => {
    try {
      await api.post('/api/auth/logout')
    } finally {
      saveToken(null)
      setUser(null)
      setSettings(null)
      setScreen('billing')
    }
  }

  if (booting) {
    return <div className="grid h-screen place-items-center text-slate-400">Starting Mass POS…</div>
  }
  if (!user) {
    return <Login onSignedIn={setUser} />
  }
  if (user.mustChangePin) {
    return <ChangePin user={user} onDone={setUser} onCancel={signOut} />
  }

  const allowed = SCREENS.filter((item) => user.role === 'ADMIN' || item.roles.includes(user.role))
  const current = allowed.some((item) => item.id === screen) ? screen : allowed[0]?.id ?? 'settings'

  return (
    <div className="flex h-screen flex-col">
      <header className="flex items-center gap-4 bg-slate-900 px-4 py-2 text-slate-100">
        <div className="flex items-baseline gap-2">
          <span className="text-base font-semibold">{settings?.shop.tradeName || settings?.shop.legalName || 'Mass POS'}</span>
          <span className="text-xs text-slate-400">
            {settings?.thisTerminal.name} ({settings?.thisTerminal.code})
            {settings?.thisTerminal.section ? ` · ${settings.thisTerminal.section}` : ''}
          </span>
        </div>
        <nav className="flex flex-1 items-center gap-1">
          {allowed.map((item) => (
            <button
              key={item.id}
              onClick={() => setScreen(item.id)}
              className={`rounded px-3 py-1.5 text-sm transition ${
                current === item.id ? 'bg-slate-700 font-medium text-white' : 'text-slate-300 hover:bg-slate-800'
              }`}
            >
              {item.label}
            </button>
          ))}
        </nav>
        <div className="flex items-center gap-3 text-sm">
          <span className="text-slate-300">
            {user.displayName} <span className="text-xs text-slate-500">{user.role.toLowerCase()}</span>
          </span>
          <button onClick={signOut} className="rounded border border-slate-600 px-3 py-1 text-xs hover:bg-slate-800">
            Log out
          </button>
        </div>
      </header>

      {toast && (
        <div className="px-4 pt-3">
          <Banner tone={toast.tone} onDismiss={() => setToast(null)}>{toast.text}</Banner>
        </div>
      )}

      <main className="flex-1 overflow-auto p-4">
        {current === 'billing' && <Billing settings={settings} onToast={setToast} />}
        {current === 'invoices' && <Invoices role={user.role} onToast={setToast} />}
        {current === 'products' && <Products onToast={setToast} />}
        {current === 'stock' && <Stock onToast={setToast} />}
        {current === 'reports' && <Reports onToast={setToast} />}
        {current === 'users' && <Users role={user.role} onToast={setToast} />}
        {current === 'settings' && (
          <Settings
            role={user.role}
            settings={settings}
            onSaved={(saved) => {
              setSettings(saved)
              setToast({ tone: 'good', text: 'Settings saved' })
            }}
            onToast={setToast}
          />
        )}
      </main>
    </div>
  )
}

export type Toast = (toast: { tone: 'good' | 'bad' | 'warn'; text: string }) => void
