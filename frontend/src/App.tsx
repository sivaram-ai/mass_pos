import { useCallback, useEffect, useRef, useState } from 'react'
import { api, billDate, clockTime, saveToken, savedToken } from './api'
import type { Principal, Role, SettingsView } from './types'
import { Login, ChangePin } from './screens/Login'
import Billing from './screens/Billing'
import type { BillingRequest } from './screens/Billing'
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
  const [menuOpen, setMenuOpen] = useState(false)
  const [billingRequest, setBillingRequest] = useState<BillingRequest | null>(null)
  const [toast, setToast] = useState<{ tone: 'good' | 'bad' | 'warn'; text: string } | null>(null)
  const lastEscape = useRef(0)
  // The billing screen puts its bill details into the header through this slot.
  const [headerSlot, setHeaderSlot] = useState<HTMLDivElement | null>(null)

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

  // Esc twice in quick succession on any other screen goes back to billing. A single Esc keeps
  // its usual job, and an Esc a dialog or the menu used to close itself never counts.
  const onBilling = screen === 'billing'
  useEffect(() => {
    if (onBilling || menuOpen || !user) {
      return
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.repeat) {
        return
      }
      const now = performance.now()
      if (now - lastEscape.current < 600) {
        lastEscape.current = 0
        setScreen('billing')
      } else {
        lastEscape.current = now
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onBilling, menuOpen, user])

  useEffect(() => {
    if (!toast) {
      return
    }
    const timer = setTimeout(() => setToast(null), 6000)
    return () => clearTimeout(timer)
  }, [toast])

  const signOut = async () => {
    setMenuOpen(false)
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
  const canBill = allowed.some((item) => item.id === 'billing')
  const shopName = settings?.shop.tradeName || settings?.shop.legalName || 'Mass POS'

  return (
    <div className="flex h-screen flex-col">
      <header className="flex h-12 shrink-0 items-center gap-4 overflow-hidden whitespace-nowrap bg-slate-900 px-3 text-slate-100">
        <button
          type="button"
          onClick={() => setMenuOpen(true)}
          aria-label="Open menu"
          aria-expanded={menuOpen}
          className="flex items-center gap-2 rounded-md px-2 py-1.5 text-sm hover:bg-slate-800"
        >
          <MenuIcon />
          Menu
        </button>
        <div className="flex min-w-0 items-baseline gap-2">
          <span className="truncate text-base font-semibold">{shopName}</span>
          {settings && (
            <span className="text-xs text-slate-400">
              {settings.thisTerminal.name} ({settings.thisTerminal.code})
              {settings.thisTerminal.section ? ` · ${settings.thisTerminal.section}` : ''}
            </span>
          )}
        </div>
        <Clock />
        <div ref={setHeaderSlot} className="flex min-w-0 flex-1 items-center gap-4" />
        {current !== 'billing' && (
          <span className="ml-auto flex items-center gap-4 text-sm font-medium text-slate-300">
            {allowed.find((item) => item.id === current)?.label}
            {canBill && (
              <button
                type="button"
                onClick={() => setScreen('billing')}
                className="flex items-center gap-2 rounded-md border border-slate-700 px-2 py-1 text-xs text-slate-300 hover:bg-slate-800"
              >
                Back to billing <kbd>Esc</kbd><kbd>Esc</kbd>
              </button>
            )}
          </span>
        )}
      </header>

      <MenuDrawer
        open={menuOpen}
        shopName={shopName}
        user={user}
        items={allowed}
        current={current}
        onPick={(id) => {
          setScreen(id)
          setMenuOpen(false)
        }}
        onClose={() => setMenuOpen(false)}
        onSignOut={signOut}
      />

      {toast && (
        <div className="fixed right-4 top-14 z-40 w-[28rem] max-w-[calc(100vw-2rem)] shadow-lg">
          <Banner tone={toast.tone} onDismiss={() => setToast(null)}>{toast.text}</Banner>
        </div>
      )}

      <main className="min-h-0 flex-1 overflow-auto p-3">
        {/* Billing stays mounted while other screens are open, so the bill on screen and the last
            bill survive a look at Bills or Products. */}
        {canBill && (
          <div hidden={current !== 'billing'} className="h-full">
            <Billing
              settings={settings}
              onToast={setToast}
              active={current === 'billing'}
              blocked={menuOpen}
              headerSlot={headerSlot}
              role={user.role}
              request={billingRequest}
            />
          </div>
        )}
        {current === 'invoices' && (
          <Invoices
            role={user.role}
            onToast={setToast}
            onOpenInBilling={canBill ? (kind, invoiceId) => {
              setBillingRequest({ kind, invoiceId, at: performance.now() })
              setScreen('billing')
            } : undefined}
          />
        )}
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

/** Its own component, so the once-a-second tick re-renders only the clock, not the bill. */
function Clock() {
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), 1000)
    return () => clearInterval(timer)
  }, [])
  return (
    <div className="flex items-center gap-2">
      <span className="num text-sm font-medium text-slate-200">{billDate(now)}</span>
      <span className="num rounded bg-slate-800 px-2 py-0.5 font-mono text-sm font-semibold text-emerald-300">
        {clockTime(now)}
      </span>
    </div>
  )
}

/** The screens, the signed-in user and Log out, sliding in from the left. */
function MenuDrawer({ open, shopName, user, items, current, onPick, onClose, onSignOut }: {
  open: boolean
  shopName: string
  user: Principal
  items: { id: Screen; label: string }[]
  current: Screen
  onPick: (screen: Screen) => void
  onClose: () => void
  onSignOut: () => void
}) {
  const panel = useRef<HTMLElement>(null)

  useEffect(() => {
    if (!open) {
      return
    }
    // Focus leaves the bill, so keys typed now drive the menu and never reach a bill cell.
    panel.current?.querySelector<HTMLButtonElement>('[data-current="true"]')?.focus()
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.stopPropagation()
        onClose()
        return
      }
      if (event.key === 'Enter' && document.activeElement instanceof HTMLButtonElement
        && panel.current?.contains(document.activeElement)) {
        event.preventDefault()
        document.activeElement.click()
        return
      }
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        const buttons = [...(panel.current?.querySelectorAll<HTMLButtonElement>('button[data-menu-item]') ?? [])]
        const at = buttons.indexOf(document.activeElement as HTMLButtonElement)
        const next = event.key === 'ArrowDown' ? Math.min(at + 1, buttons.length - 1) : Math.max(at - 1, 0)
        buttons[next]?.focus()
        event.preventDefault()
      }
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [open, onClose])

  return (
    <div className={`fixed inset-0 z-50 ${open ? '' : 'pointer-events-none'}`} aria-hidden={!open}>
      <div
        className={`absolute inset-0 bg-slate-900/50 transition-opacity duration-200 ${open ? 'opacity-100' : 'opacity-0'}`}
        onMouseDown={onClose}
      />
      <nav
        ref={panel}
        inert={!open}
        className={`absolute inset-y-0 left-0 flex w-72 max-w-[85vw] flex-col bg-slate-900 text-slate-100 shadow-2xl transition-transform duration-200 ${
          open ? 'translate-x-0' : '-translate-x-full'}`}
      >
        <div className="flex items-center justify-between border-b border-slate-800 px-4 py-3">
          <div className="min-w-0">
            <div className="truncate font-semibold">{shopName}</div>
            <div className="truncate text-xs text-slate-400">
              {user.displayName} · {user.role.toLowerCase()}
            </div>
          </div>
          <button type="button" onClick={onClose} aria-label="Close menu"
                  className="rounded p-1.5 text-slate-400 hover:bg-slate-800 hover:text-white">✕</button>
        </div>
        <ul className="flex-1 space-y-1 overflow-auto p-2">
          {items.map((item) => (
            <li key={item.id}>
              <button
                type="button"
                data-menu-item
                data-current={item.id === current}
                onClick={() => onPick(item.id)}
                className={`w-full rounded-md px-3 py-2.5 text-left text-sm outline-none transition focus-visible:ring-2 focus-visible:ring-sky-400 ${
                  item.id === current ? 'bg-slate-700 font-medium text-white' : 'text-slate-300 hover:bg-slate-800'}`}
              >
                {item.label}
              </button>
            </li>
          ))}
        </ul>
        <div className="border-t border-slate-800 p-2">
          <button
            type="button"
            data-menu-item
            onClick={onSignOut}
            className="w-full rounded-md px-3 py-2.5 text-left text-sm text-rose-300 outline-none hover:bg-slate-800 focus-visible:ring-2 focus-visible:ring-sky-400"
          >
            Log out
          </button>
        </div>
      </nav>
    </div>
  )
}

function MenuIcon() {
  return (
    <svg viewBox="0 0 20 20" fill="currentColor" className="h-5 w-5" aria-hidden="true">
      <path fillRule="evenodd" clipRule="evenodd"
            d="M2 4.75A.75.75 0 0 1 2.75 4h14.5a.75.75 0 0 1 0 1.5H2.75A.75.75 0 0 1 2 4.75ZM2 10a.75.75 0 0 1 .75-.75h14.5a.75.75 0 0 1 0 1.5H2.75A.75.75 0 0 1 2 10Zm0 5.25a.75.75 0 0 1 .75-.75h14.5a.75.75 0 0 1 0 1.5H2.75a.75.75 0 0 1-.75-.75Z" />
    </svg>
  )
}

export type Toast = (toast: { tone: 'good' | 'bad' | 'warn'; text: string }) => void
