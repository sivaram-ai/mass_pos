import { useCallback, useEffect, useRef, useState } from 'react'
import {
  api, ApiError, askTheShop, billDate, clockTime, LICENSE_CHANGED, saveToken, savedToken,
  SHOP_ANSWERED, SHOP_UNREACHABLE, shopIsAnswering,
} from './api'
import { forgetBillDraft } from './billDraft'
import { hasPrintAgent, openCounterSetup } from './counter'
import type { LicenseStatus, Principal, Role, SettingsView } from './types'
import { Login, ChangePin } from './screens/Login'
import Billing from './screens/Billing'
import type { BillingRequest } from './screens/Billing'
import Products from './screens/Products'
import Stock from './screens/Stock'
import Invoices from './screens/Invoices'
import Reports from './screens/Reports'
import Settings from './screens/Settings'
import Users from './screens/Users'
import ThemeSettings from './screens/ThemeSettings'
import { useThemeSync } from './themeSync'
import License, { LicenseBanner } from './screens/License'
import { Banner, isModalOpen } from './components/ui'
import { MENU_KEY, useScreenKeys } from './shortcuts'

type Screen = 'billing' | 'products' | 'stock' | 'invoices' | 'reports' | 'users' | 'theme' | 'settings' | 'license'

const SCREEN_KEY = 'masspos.screen'

/**
 * Refreshing is a page reload, so the screen has to be remembered: a manager checking a report
 * should land back on the report, not on the billing grid.
 */
function remembered(): Screen {
  try {
    const saved = localStorage.getItem(SCREEN_KEY) as Screen | null
    return saved ?? 'billing'
  } catch {
    return 'billing'
  }
}

const rememberedScreen = remembered()

/** Often enough that a till left on overnight shows the lapse soon after midnight. */
const LICENSE_REFRESH_MS = 10 * 60 * 1000

/**
 * How often a signed-in screen tells the server it is still there, which is how the shop's list of
 * tills knows which ones somebody is standing at. A minute is cheap - the server usually answers it
 * out of memory without touching the database - and three missed words is what marks a till idle.
 */
const STILL_HERE_MS = 60 * 1000

/**
 * How often a till that has lost the shop asks whether it is back. Often enough that somebody who
 * has just pushed the cable in sees the screen clear itself while they are still standing there,
 * and cheap enough to leave running all evening.
 */
const ASK_AGAIN_MS = 3000

/** ADMIN reaches everything; the rest see only what their job needs. */
const SCREENS: { id: Screen; label: string; roles: Role[] }[] = [
  { id: 'billing', label: 'Billing', roles: ['CASHIER', 'MANAGER'] },
  { id: 'invoices', label: 'Bills', roles: ['CASHIER', 'MANAGER', 'AUDITOR'] },
  { id: 'products', label: 'Products', roles: ['MANAGER'] },
  { id: 'stock', label: 'Stock', roles: ['MANAGER'] },
  { id: 'reports', label: 'Reports', roles: ['MANAGER', 'AUDITOR'] },
  { id: 'users', label: 'Staff', roles: ['MANAGER', 'AUDITOR'] },
  // Everyone may look at the shop's theme; only an administrator may change it (the screen enforces
  // that, and so does the server).
  { id: 'theme', label: 'Theme', roles: ['CASHIER', 'MANAGER', 'AUDITOR'] },
  { id: 'settings', label: 'Settings', roles: [] },
  { id: 'license', label: 'Licence', roles: [] },
]

export default function App() {
  const [user, setUser] = useState<Principal | null>(null)
  const [settings, setSettings] = useState<SettingsView | null>(null)
  const [license, setLicense] = useState<LicenseStatus | null>(null)
  const [screen, setScreen] = useState<Screen>(rememberedScreen)

  /** Moves screen and remembers it, so a reload comes back to the same place. */
  const goTo = useCallback((next: Screen) => {
    setScreen(next)
    try {
      localStorage.setItem(SCREEN_KEY, next)
    } catch {
      // A browser with storage locked down simply forgets; billing is a safe place to land.
    }
  }, [])
  const [booting, setBooting] = useState(true)
  const [menuOpen, setMenuOpen] = useState(false)
  const [billingRequest, setBillingRequest] = useState<BillingRequest | null>(null)
  const [toast, setToast] = useState<{ tone: 'good' | 'bad' | 'warn'; text: string } | null>(null)
  /** How many items the billing screen has on the bill, which is what Log out asks about. */
  const [billItems, setBillItems] = useState(0)
  const [leaving, setLeaving] = useState(false)
  const [holdRequest, setHoldRequest] = useState(0)
  const [holding, setHolding] = useState(false)
  const [holdFailed, setHoldFailed] = useState<string | null>(null)
  /** True while a button is waiting on the shop's answer, so it can say so rather than sit dead. */
  const [checking, setChecking] = useState(false)
  const onCartChanged = useCallback((items: number) => setBillItems(items), [])
  const lastEscape = useRef(0)
  // The billing screen puts its bill details into the header through this slot.
  const [headerSlot, setHeaderSlot] = useState<HTMLDivElement | null>(null)

  const refreshSettings = useCallback(() => {
    api.get<SettingsView>('/api/settings').then(setSettings).catch(() => {
      // Keep what is on screen. A till that cannot be reached has not renamed the shop, and
      // blanking the header on a dropped cable only makes a bad minute look worse.
    })
  }, [])

  useEffect(() => {
    if (!savedToken()) {
      setBooting(false)
      return
    }
    api.get<Principal>('/api/auth/me')
      .then(setUser)
      .catch((failure) => {
        // A till that could not be reached has not signed anybody out. Throwing the token away
        // here is how a pulled cable turned into "sign in again" - with the shop unreachable, so
        // nobody could. The token is kept and tried again when the shop answers.
        if (!(failure instanceof ApiError) || failure.status !== 0) {
          saveToken(null)
        }
      })
      .finally(() => setBooting(false))
  }, [])

  useEffect(() => {
    if (user && !user.mustChangePin) {
      refreshSettings()
    }
  }, [user, refreshSettings])

  // Whether the shop is answering at all. Every request says so, so the whole screen knows rather
  // than only the call that happened to find out.
  const [reachable, setReachable] = useState(shopIsAnswering)
  useEffect(() => {
    const lost = () => setReachable(false)
    const back = () => setReachable(true)
    window.addEventListener(SHOP_UNREACHABLE, lost)
    window.addEventListener(SHOP_ANSWERED, back)
    return () => {
      window.removeEventListener(SHOP_UNREACHABLE, lost)
      window.removeEventListener(SHOP_ANSWERED, back)
    }
  }, [])

  /** The same ask, with the button that asked saying so while it waits. */
  const askNow = useCallback(async () => {
    setChecking(true)
    try {
      return await askTheShop()
    } finally {
      setChecking(false)
    }
  }, [])

  // While the shop is out, keep asking. Nothing on screen moves and nothing is cleared: the first
  // ask that gets through puts the bar away by itself, mid-bill, with the bill still there. The
  // quiet one, so the buttons do not blink every three seconds at somebody who is not watching.
  useEffect(() => {
    if (reachable) {
      return
    }
    const keepAsking = () => { void askTheShop() }
    const timer = setInterval(keepAsking, ASK_AGAIN_MS)
    window.addEventListener('focus', keepAsking)
    return () => {
      clearInterval(timer)
      window.removeEventListener('focus', keepAsking)
    }
  }, [reachable])

  // Back again. The sign-in this browser still holds a token for is picked up without anybody
  // typing a PIN: being unable to reach the shop for a minute is not a reason to sign out.
  useEffect(() => {
    if (!reachable || booting || user || !savedToken()) {
      return
    }
    api.get<Principal>('/api/auth/me').then(setUser).catch(() => {
      // A token the shop no longer knows: the sign-in screen is already up, which is the answer.
    })
  }, [reachable, booting, user])

  const signedIn = Boolean(user && !user.mustChangePin)
  const theme = useThemeSync(signedIn)
  useEffect(() => {
    if (!signedIn) {
      return
    }
    const refresh = () => {
      api.get<LicenseStatus>('/api/license').then(setLicense).catch(() => {
        // Keep the last known state; the next refresh tries again.
      })
    }
    refresh()
    const timer = setInterval(refresh, LICENSE_REFRESH_MS)
    window.addEventListener(LICENSE_CHANGED, refresh)
    return () => {
      clearInterval(timer)
      window.removeEventListener(LICENSE_CHANGED, refresh)
    }
  }, [signedIn])

  // "This till is being used, by me." Also on coming back to the window, so a machine that was
  // asleep or in the background says so at once rather than up to a minute later.
  useEffect(() => {
    if (!signedIn) {
      return
    }
    const sayStillHere = () => {
      // A till that cannot reach the server has nothing to report and nothing to show for it:
      // whatever is wrong is already on screen from the call the cashier made themselves.
      api.post('/api/clients/here').catch(() => {})
    }
    sayStillHere()
    const timer = setInterval(sayStillHere, STILL_HERE_MS)
    window.addEventListener('focus', sayStillHere)
    return () => {
      clearInterval(timer)
      window.removeEventListener('focus', sayStillHere)
    }
  }, [signedIn])

  /**
   * Refresh asks the shop before it reloads anything, and reloads only if it answers.
   *
   * <p>Reloading while the shop is unreachable is how somebody ends up on the browser's own error
   * page: the screen they were on is gone, the bill with it, and there is no way back to this
   * counter's settings from a cloud and a Refresh button. Asking first is what makes that
   * impossible rather than merely unlikely - the bar going up is not enough on its own, because
   * the cable can come out between two requests and the first thing anybody does when a screen
   * stops responding is press Refresh, before anything has had the chance to fail.
   *
   * <p>If it does not answer, the ask that just failed has raised the bar, and the screen - with
   * the bill on it, and Counter settings on the bar - stays exactly where it was.
   */
  const refresh = useCallback(async () => {
    if (await askNow()) {
      window.location.reload()
    }
  }, [askNow])

  // F5 is the key everyone reaches for to refresh, so it reloads - except on the billing grid,
  // where it has long been "remove this line" and the cart is live anyway.
  useEffect(() => {
    if (screen === 'billing' || !user) {
      return
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'F5' && !event.repeat) {
        event.preventDefault()
        void refresh()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [screen, user, refresh])

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
        goTo('billing')
      } else {
        lastEscape.current = now
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onBilling, menuOpen, user])

  // F9 opens the menu from any screen, and closes it again; never from behind a dialog.
  useScreenKeys({
    [MENU_KEY]: () => {
      if (!isModalOpen()) {
        setMenuOpen((wasOpen) => !wasOpen)
      }
    },
  }, Boolean(user) && !user?.mustChangePin && !leaving)

  useEffect(() => {
    if (!toast) {
      return
    }
    const timer = setTimeout(() => setToast(null), 6000)
    return () => clearTimeout(timer)
  }, [toast])

  const signOut = async () => {
    setMenuOpen(false)
    setLeaving(false)
    try {
      await api.post('/api/auth/logout')
    } catch {
      // Signing out locally matters more than telling the server: a till nobody can reach is
      // exactly where somebody needs to get off this account and on with their evening. The
      // session is settled at the server's end by the next sign-in on this counter.
    } finally {
      forgetBillDraft()
      saveToken(null)
      setUser(null)
      setSettings(null)
      setLicense(null)
      setBillItems(0)
      setHoldRequest(0)
      goTo('billing')
    }
  }

  /**
   * Log out is never one click. It sits under Menu, a keystroke away from the screens beside it,
   * and the bill on screen has been typed by hand and sent nowhere - so it is asked about first,
   * and a bill that is part way through is held rather than lost.
   */
  const askToSignOut = () => {
    setHoldFailed(null)
    // The menu goes with it. It is not only tidier: the drawer listens for keys on the window in
    // the capture phase, before anything else sees them, so left open behind this dialog it took
    // the arrows and moved the focus ring down its own list instead.
    setMenuOpen(false)
    setLeaving(true)
  }

  const holdThenSignOut = () => {
    setHoldFailed(null)
    setHolding(true)
    setHoldRequest((asked) => asked + 1)
  }

  const heldForSignOut = (outcome: { held: boolean; says: string }) => {
    setHolding(false)
    if (!outcome.held) {
      // The bill is still on screen and still theirs. Said inside this dialog, where the person
      // who pressed the button is looking - a toast would render behind it.
      setHoldFailed(outcome.says)
      return
    }
    void signOut()
  }

  // At the foot of a signed-out screen: the sign-in screen's own Counter settings button is in
  // the top-left corner, and the one screen that can put this right must not be covered by the
  // message telling somebody to go to it.
  const troubleAtTheFoot = !reachable && (
    <div className="fixed inset-x-0 bottom-0 z-40">
      <ShopUnreachable busy={checking} onTryAgain={() => void askNow()} />
    </div>
  )

  if (booting) {
    return <div className="grid h-screen place-items-center text-slate-400">Starting GO…</div>
  }
  if (!user) {
    return <><Login onSignedIn={setUser} />{troubleAtTheFoot}</>
  }
  if (user.mustChangePin) {
    return (
      <>
        <ChangePin user={user} onDone={setUser} onCancel={() => void signOut()} />
        {troubleAtTheFoot}
      </>
    )
  }

  const allowed = SCREENS.filter((item) => user.role === 'ADMIN' || item.roles.includes(user.role))
  const current = allowed.some((item) => item.id === screen) ? screen : allowed[0]?.id ?? 'settings'
  const canBill = allowed.some((item) => item.id === 'billing')
  const shopName = settings?.shop.tradeName || settings?.shop.legalName || 'GO RockIt'

  return (
    <div className="flex h-screen flex-col">
      <header className="nav-scope flex h-12 shrink-0 items-center gap-4 overflow-hidden whitespace-nowrap bg-slate-900 px-3 text-slate-100">
        <button
          type="button"
          onClick={() => setMenuOpen(true)}
          aria-label="Open menu"
          aria-expanded={menuOpen}
          className="flex shrink-0 items-center gap-2 rounded-md px-2 py-1.5 text-sm hover:bg-slate-800"
        >
          <MenuIcon />
          Menu
          <kbd>{MENU_KEY}</kbd>
        </button>
        <button
          type="button"
          onClick={() => void refresh()}
          disabled={checking}
          title={!reachable
            ? 'Asks the shop again. It will not reload while the shop cannot be reached, because'
              + ' that would lose this screen.'
            : current === 'billing'
              ? 'Reload the till'
              : 'Reload this screen, and come back to it'}
          className="flex shrink-0 items-center gap-2 rounded-md px-2 py-1.5 text-sm hover:bg-slate-800 disabled:opacity-60"
        >
          <RefreshIcon />
          <span className="hidden sm:inline">{checking ? 'Checking…' : 'Refresh'}</span>
          {current !== 'billing' && !checking && <kbd>F5</kbd>}
        </button>
        {/* A long shop name gives way first: it is cut, rather than pushing the rest off the row. */}
        <div className="flex min-w-0 shrink items-baseline gap-2">
          <span className="max-w-[20rem] truncate text-base font-semibold">{shopName}</span>
          {settings && (
            <span className="hidden shrink-0 text-xs text-slate-400 lg:inline">
              {settings.thisTerminal.name} ({settings.thisTerminal.code})
              {settings.thisTerminal.section ? ` · ${settings.thisTerminal.section}` : ''}
            </span>
          )}
        </div>
        <Clock />
        <div ref={setHeaderSlot} className="flex min-w-0 flex-1 items-center gap-4 overflow-hidden" />
        {current !== 'billing' && (
          <span className="ml-auto flex items-center gap-4 text-sm font-medium text-slate-300">
            {allowed.find((item) => item.id === current)?.label}
            {canBill && (
              <button
                type="button"
                onClick={() => goTo('billing')}
                className="flex items-center gap-2 rounded-md border border-slate-700 px-2 py-1 text-xs text-slate-300 hover:bg-slate-800"
              >
                Back to billing <kbd>Esc</kbd><kbd>Esc</kbd>
              </button>
            )}
          </span>
        )}
      </header>

      {!reachable && <ShopUnreachable busy={checking} onTryAgain={() => void askNow()} />}

      {license && (license.state !== 'ACTIVE' || license.clockBehind) && (
        <LicenseBanner
          status={license}
          onOpen={allowed.some((item) => item.id === 'license') && current !== 'license'
            ? () => goTo('license')
            : undefined}
        />
      )}

      <MenuDrawer
        open={menuOpen}
        shopName={shopName}
        user={user}
        items={allowed}
        current={current}
        onPick={(id) => {
          goTo(id)
          setMenuOpen(false)
        }}
        onClose={() => setMenuOpen(false)}
        onSignOut={askToSignOut}
      />

      {leaving && (
        <ConfirmSignOut
          items={billItems}
          busy={holding}
          failed={holdFailed}
          onHold={holdThenSignOut}
          onSignOut={() => void signOut()}
          onBack={() => {
            setLeaving(false)
            if (billItems > 0 && canBill) {
              goTo('billing')
            }
          }}
        />
      )}

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
              // Blocked by the log-out question as well as by the menu, so the grid behind it
              // neither answers the arrows nor keeps the cursor - and so that closing it puts the
              // cursor back in the cell it was in, which is what `blocked` going false already
              // does for the menu.
              blocked={menuOpen || leaving}
              headerSlot={headerSlot}
              role={user.role}
              request={billingRequest}
              onCartChanged={onCartChanged}
              holdRequest={holdRequest}
              onHeldForSignOut={heldForSignOut}
            />
          </div>
        )}
        {current === 'invoices' && (
          <Invoices
            role={user.role}
            onToast={setToast}
            onOpenInBilling={canBill ? (kind, invoiceId) => {
              setBillingRequest({ kind, invoiceId, at: performance.now() })
              goTo('billing')
            } : undefined}
          />
        )}
        {current === 'products' && <Products onToast={setToast} />}
        {current === 'stock' && <Stock onToast={setToast} />}
        {current === 'reports' && <Reports onToast={setToast} />}
        {current === 'users' && <Users role={user.role} onToast={setToast} />}
        {current === 'theme' && (
          <ThemeSettings role={user.role} view={theme.view} onChanged={theme.take} onToast={setToast} />
        )}
        {current === 'license' && <License status={license} onChanged={setLicense} onToast={setToast} />}
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

const TROUBLE_BUTTON = 'rounded-md border border-[#be123c] bg-white px-3 py-1.5 text-sm '
  + 'font-medium text-[#9f1239] transition hover:bg-[#ffe4e6]'

/**
 * The shop has stopped answering: a cable out of its socket, a switch off at the wall, the server
 * being restarted in the back office.
 *
 * <p>It is a bar and not a dialog on purpose. Nothing is taken off the screen, the cursor stays
 * in the bill, and the twenty items already rung up out of a trolley that is now packed are still
 * there to carry on with. The till keeps asking in the background, so the bar goes away by itself
 * the moment somebody pushes the cable back in - nobody has to find a button to get going again.
 *
 * <p>The way out is on it. A counter's own settings - how it finds the shop, over a cable or over
 * Wi-Fi, and at what address - are served by the print agent on this very machine, which is still
 * running and still answering whatever the network is doing. So the one screen that can put this
 * right is a button away rather than something a shop has to close the program to reach.
 *
 * <p>Fixed colours: the status scales flip on a dark theme, and a warning that turns into pale
 * text on a pale bar is not a warning.
 */
function ShopUnreachable({ busy, onTryAgain }: { busy: boolean; onTryAgain: () => void }) {
  return (
    <div className="shrink-0 border-y-2 border-[#be123c] bg-[#fff1f2] px-4 py-3 text-[#881337] shadow-lg">
      <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-x-4 gap-y-2">
        <div className="min-w-0 flex-1">
          <p className="font-semibold">This till cannot reach the shop at the moment.</p>
          <p className="text-sm">
            Nothing on this screen has been lost. Check the network cable, or the Wi-Fi, and
            billing carries on the moment the shop answers — this machine keeps asking.
          </p>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <button type="button" onClick={onTryAgain} disabled={busy}
                  className={`${TROUBLE_BUTTON} disabled:opacity-60`}>
            {busy ? 'Asking…' : 'Try now'}
          </button>
          {hasPrintAgent() && (
            <button
              type="button"
              onClick={() => { openCounterSetup() }}
              title="Change how this counter finds the shop, or the address it looks for"
              className={TROUBLE_BUTTON}
            >
              Counter settings
            </button>
          )}
        </div>
      </div>
    </div>
  )
}

/**
 * Log out, asked rather than done.
 *
 * <p>It sits at the foot of the menu, one keystroke from the screens above it, and the bill on
 * screen has been typed in by hand and sent nowhere - so a slip costs a trolley-load of items
 * that no server has and no undo can reach. With a bill in progress there is deliberately no
 * "log out anyway": the bill is held first, which puts it on Held bills for whoever comes back to
 * this till, and that is a better answer than either losing it or refusing to let somebody leave.
 *
 * <p>Both answers are safe to pick by mistake, and the harmless one is where the cursor starts.
 * The keys are the ones a cashier's hands already use: up and down to choose, Enter to do it.
 */
function ConfirmSignOut({ items, busy, failed, onHold, onSignOut, onBack }: {
  items: number
  busy: boolean
  failed: string | null
  onHold: () => void
  onSignOut: () => void
  onBack: () => void
}) {
  const hasBill = items > 0
  const choices = hasBill
    ? [
      { label: 'Back to the bill', hint: 'Leave it on screen and carry on with it.', run: onBack },
      {
        label: 'Hold the bill, then log out',
        hint: 'Keeps it on Held bills, for whoever comes back to this till.',
        run: onHold,
      },
    ]
    : [
      { label: 'Stay signed in', hint: 'Nothing changes.', run: onBack },
      { label: 'Log out', hint: "Ends this sign-in and frees this counter's place.", run: onSignOut },
    ]
  const [at, setAt] = useState(0)
  const list = useRef<HTMLDivElement>(null)

  // Takes the focus off the bill behind, or the arrows would go on editing it.
  useEffect(() => list.current?.focus(), [])

  const onKeyDown = (event: React.KeyboardEvent) => {
    if (busy) {
      return
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      setAt((now) => (now + (event.key === 'ArrowDown' ? 1 : choices.length - 1)) % choices.length)
      return
    }
    // Nothing here is reached by tabbing to it, so Tab only ever takes the cursor somewhere it
    // cannot be used - the bill behind, or the browser's own chrome. It stays on the list.
    if (event.key === 'Tab') {
      event.preventDefault()
      return
    }
    // The in-app browser pane does not activate a focused button on Enter, and this list is a div
    // in any case, so Enter is handled here rather than left to the browser.
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      choices[at].run()
      return
    }
    if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      onBack()
    }
  }

  return (
    <div className="fixed inset-0 z-[60] grid place-items-center bg-slate-900/60 p-4">
      <div className="w-full max-w-lg rounded-2xl bg-surface p-6 shadow-2xl">
        <h2 className="text-lg font-semibold text-slate-900">
          {hasBill ? 'There is a bill on this screen' : 'Log out?'}
        </h2>
        <p className="mt-1 text-sm text-slate-600">
          {hasBill
            ? `${items} ${items === 1 ? 'item has' : 'items have'} been rung up and the bill has not`
              + ' been taken. Logging out now would lose it, so it is held first.'
            : 'You will be signed out of this till. Anybody can sign in again on it.'}
        </p>

        {failed && <div className="mt-4"><Banner tone="bad">{failed}</Banner></div>}

        <div
          ref={list}
          tabIndex={-1}
          role="listbox"
          aria-label="What would you like to do?"
          onKeyDown={onKeyDown}
          className="mt-4 space-y-2 outline-none"
        >
          {choices.map((choice, index) => (
            <div
              key={choice.label}
              role="option"
              aria-selected={index === at}
              onMouseEnter={() => setAt(index)}
              onClick={() => !busy && choice.run()}
              className={`rounded-lg border px-4 py-3 transition ${busy ? 'opacity-60' : 'cursor-pointer'} ${
                index === at
                  ? 'border-sky-500 bg-sky-50 ring-2 ring-sky-100'
                  : 'border-slate-200 hover:bg-slate-50'}`}
            >
              <div className={`font-medium ${index === at ? 'text-sky-900' : 'text-slate-800'}`}>
                {choice.label}
              </div>
              <div className="text-sm text-slate-500">{choice.hint}</div>
            </div>
          ))}
        </div>

        <p className="mt-3 text-center text-xs text-slate-400">
          {busy ? 'Holding the bill…' : <><kbd>↑</kbd> <kbd>↓</kbd> to choose, <kbd>Enter</kbd> to go ahead</>}
        </p>
      </div>
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
    <div className="flex shrink-0 items-center gap-2">
      <span className="num hidden text-sm font-medium text-slate-200 md:inline">{billDate(now)}</span>
      <span className="num rounded bg-slate-800 px-2 py-0.5 font-mono text-sm font-semibold text-accent">
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
        className={`absolute inset-0 bg-black/50 transition-opacity duration-200 ${open ? 'opacity-100' : 'opacity-0'}`}
        onMouseDown={onClose}
      />
      <nav
        ref={panel}
        inert={!open}
        className={`nav-scope absolute inset-y-0 left-0 flex w-72 max-w-[85vw] flex-col bg-slate-900 text-slate-100 shadow-2xl transition-transform duration-200 ${
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

function RefreshIcon() {
  return (
    <svg viewBox="0 0 20 20" fill="currentColor" className="h-5 w-5" aria-hidden="true">
      <path fillRule="evenodd" clipRule="evenodd"
            d="M15.312 11.424a5.5 5.5 0 0 1-9.201 2.466l-.312-.311h2.433a.75.75 0 0 0 0-1.5H3.989a.75.75 0 0 0-.75.75v4.242a.75.75 0 0 0 1.5 0v-2.43l.31.31a7 7 0 0 0 11.712-3.138.75.75 0 0 0-1.449-.389Zm1.23-3.723a.75.75 0 0 0 .219-.53V2.929a.75.75 0 0 0-1.5 0V5.36l-.31-.31A7 7 0 0 0 3.239 8.188a.75.75 0 1 0 1.448.389A5.5 5.5 0 0 1 13.89 6.11l.311.31h-2.432a.75.75 0 0 0 0 1.5h4.243a.75.75 0 0 0 .53-.219Z" />
    </svg>
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
