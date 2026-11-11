import { useEffect, useMemo, useRef, useState } from 'react'
import { api, messageOfFailure } from '../api'
import type { Role } from '../types'
import { Banner, Button, inputClass, Modal, Panel } from '../components/ui'
import type { Toast } from '../App'
import {
  COLOR_FIELDS, checksOf, contrast, exportJson, grade, isHex, newCustomId, parseImport, previewStyle,
} from '../theme'
import type { ColorKey, Check, ThemeDefinition, ThemeView } from '../theme'

/**
 * The shop's colour scheme: a gallery of designed themes, a builder for the shop's own, a live preview,
 * and a readability report. One theme is worn by every counter, so what is applied here reaches every
 * screen in the shop within seconds.
 *
 * <p>Everyone may look; only an administrator may change anything. The controls are not merely
 * disabled for the rest - they are not drawn - and the server refuses the calls regardless.
 */
export default function ThemeSettings({ role, view, onChanged, onToast }: {
  role: Role
  view: ThemeView | null
  onChanged: (view: ThemeView) => void
  onToast: Toast
}) {
  const isAdmin = role === 'ADMIN'
  const active = view?.active ?? null
  const [draft, setDraft] = useState<ThemeDefinition | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [deleting, setDeleting] = useState<ThemeDefinition | null>(null)
  const picker = useRef<HTMLInputElement>(null)

  // The builder starts on the theme in force, and follows it if another administrator changes it
  // while this one has not touched anything.
  const touched = useRef(false)
  useEffect(() => {
    if (active && (!draft || !touched.current)) {
      setDraft(clone(active))
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active?.id, view?.revision])

  const checks = useMemo(() => (draft ? checksOf(draft.colors) : []), [draft])
  if (!view || !active || !draft) {
    return <p className="py-8 text-center text-sm text-slate-400">Loading the shop's theme…</p>
  }

  const builtIn = view.presets.some((preset) => preset.id === draft.id)
  const failing = builtIn ? [] : checks.filter((check) => !check.ok)
  const inLibrary = view.library.some((theme) => theme.id === draft.id)
  const sameAsActive = draft.id === active.id && sameColors(draft, active)
  const gallery = [...view.presets, ...view.library]

  const choose = (theme: ThemeDefinition) => {
    touched.current = true
    setError('')
    setDraft(clone(theme))
  }

  const setColor = (key: ColorKey, value: string) => {
    touched.current = true
    setDraft((current) => {
      if (!current) {
        return current
      }
      // Changing a colour of a built-in makes it the shop's own theme, under a new id: the built-ins
      // themselves are the server's and never edited.
      const own = current.id.startsWith('custom-')
      return {
        ...current,
        id: own ? current.id : newCustomId(),
        name: own ? current.name : `${current.name} (custom)`,
        description: own ? current.description : '',
        colors: { ...current.colors, [key]: value },
      }
    })
  }

  const run = async (work: () => Promise<ThemeView>, done: string) => {
    setBusy(true)
    setError('')
    try {
      const next = await work()
      onChanged(next)
      onToast({ tone: 'good', text: done })
      return true
    } catch (failure) {
      // Shown beside the buttons that caused it, not as a toast: this page is where it is read.
      setError(messageOfFailure(failure, 'The theme was not saved'))
      return false
    } finally {
      setBusy(false)
    }
  }

  const apply = () => run(
    () => api.put<ThemeView>('/api/theme/active', draft),
    `${draft.name} is now the theme on every counter`,
  )

  const save = async () => {
    const saved = await run(
      () => api.put<ThemeView>('/api/theme/library', draft),
      `${draft.name} saved to the shop's themes`,
    )
    return saved
  }

  const remove = async (theme: ThemeDefinition) => {
    setDeleting(null)
    await run(() => api.delete<ThemeView>(`/api/theme/library/${theme.id}`), `${theme.name} deleted`)
  }

  const download = (themes: ThemeDefinition[], fileName: string) => {
    const url = URL.createObjectURL(new Blob([exportJson(themes)], { type: 'application/json' }))
    const link = document.createElement('a')
    link.href = url
    link.download = fileName
    link.click()
    URL.revokeObjectURL(url)
  }

  const slug = (name: string) => name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'theme'

  const importFile = async (file: File) => {
    setError('')
    try {
      const themes = parseImport(await file.text())
      if (themes.length === 1) {
        // One theme is opened in the builder to be looked at; nothing is saved until it is asked for.
        touched.current = true
        setDraft(themes[0])
        onToast({ tone: 'good', text: `${themes[0].name} loaded. Check the preview, then apply or save it.` })
        return
      }
      let kept = 0
      let refused = ''
      for (const theme of themes) {
        try {
          const next = await api.put<ThemeView>('/api/theme/library', theme)
          onChanged(next)
          kept += 1
        } catch (failure) {
          refused = `${theme.name}: ${messageOfFailure(failure, 'not saved')}`
        }
      }
      onToast({
        tone: refused ? 'warn' : 'good',
        text: `${kept} of ${themes.length} themes added to the shop's themes.${refused ? ` ${refused}` : ''}`,
      })
    } catch (failure) {
      setError(messageOfFailure(failure, 'That file could not be read'))
    }
  }

  return (
    <div className="mx-auto max-w-7xl space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <div>
          <h1 className="text-lg font-semibold text-slate-800">Theme</h1>
          <p className="text-sm text-slate-500">
            One look for the whole shop. What is applied here appears on every counter and device within a few seconds.
          </p>
        </div>
        <div className="flex items-center gap-2 rounded-md border border-slate-200 bg-surface px-3 py-1.5 text-sm">
          <span className="text-slate-500">In use</span>
          <Swatches theme={active} />
          <span className="font-medium text-slate-800">{active.name}</span>
        </div>
      </div>

      {!isAdmin && (
        <Banner tone="warn">
          You can see the shop's theme, but only an administrator can create, change or apply one.
        </Banner>
      )}

      <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_26rem]">
        <div className="min-w-0 space-y-4">
          <Panel title="Themes">
            <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3" aria-label="Themes">
              {gallery.map((theme) => {
                const isActive = theme.id === active.id
                const isChosen = isAdmin && theme.id === draft.id
                const own = theme.id.startsWith('custom-')
                return (
                  <li key={theme.id} className="relative">
                    <button
                      type="button"
                      disabled={!isAdmin}
                      onClick={() => choose(theme)}
                      aria-pressed={isChosen}
                      className={`block w-full overflow-hidden rounded-lg border bg-surface text-left transition ${
                        isChosen ? 'border-sky-600 ring-2 ring-sky-300' : 'border-slate-200'} ${
                        isAdmin ? 'cursor-pointer hover:border-sky-400 hover:shadow-md' : 'cursor-default'}`}
                    >
                      <Thumbnail theme={theme} />
                      <span className="block px-3 py-2">
                        <span className="flex items-center justify-between gap-2">
                          <span className="truncate text-sm font-semibold text-slate-800">{theme.name}</span>
                          {isActive && (
                            <span className="shrink-0 rounded-full bg-emerald-100 px-2 py-0.5 text-[11px] font-semibold text-emerald-800">
                              In use
                            </span>
                          )}
                        </span>
                        <span className="mt-0.5 block truncate text-xs text-slate-500">
                          {theme.description || (own ? "The shop's own" : '')}
                        </span>
                      </span>
                    </button>
                    {isAdmin && own && (
                      <button
                        type="button"
                        onClick={() => setDeleting(theme)}
                        aria-label={`Delete ${theme.name}`}
                        title="Delete this saved theme"
                        className="absolute right-1.5 top-1.5 rounded-full bg-surface/90 px-1.5 py-0.5 text-xs text-slate-500 shadow hover:text-rose-600"
                      >
                        ✕
                      </button>
                    )}
                  </li>
                )
              })}
            </ul>
          </Panel>

          {isAdmin && (
            <Panel
              title="Custom theme builder"
              actions={(
                <>
                  <Button onClick={() => picker.current?.click()} title="Open a theme file saved from this or another shop">
                    Import
                  </Button>
                  <Button onClick={() => download([draft], `${slug(draft.name)}.gorockit-theme.json`)}
                          title="Save this theme as a file you can keep or send to another shop">
                    Export
                  </Button>
                  <Button onClick={() => download(view.library, 'gorockit-themes.json')}
                          disabled={view.library.length === 0}
                          title="Save every theme the shop has made as one backup file">
                    Back up all
                  </Button>
                  <input
                    ref={picker}
                    type="file"
                    accept=".json,application/json"
                    className="hidden"
                    aria-label="Theme file to import"
                    onChange={(event) => {
                      const file = event.target.files?.[0]
                      event.target.value = ''
                      if (file) {
                        void importFile(file)
                      }
                    }}
                  />
                </>
              )}
            >
              <div className="mb-4 max-w-sm">
                <label className="mb-1 block text-xs font-medium text-slate-500" htmlFor="theme-name">Theme name</label>
                <input
                  id="theme-name"
                  className={inputClass}
                  value={draft.name}
                  maxLength={40}
                  disabled={builtIn}
                  onChange={(event) => {
                    touched.current = true
                    setDraft({ ...draft, name: event.target.value })
                  }}
                />
                {builtIn && (
                  <p className="mt-1 text-xs text-slate-400">
                    A built-in theme. Change any colour below to make a theme of your own from it.
                  </p>
                )}
              </div>

              {['Brand', 'Content', 'Navigation'].map((group) => (
                <fieldset key={group} className="mb-4 last:mb-0">
                  <legend className="mb-1 text-xs font-semibold uppercase tracking-wide text-slate-400">{group}</legend>
                  <div className="grid gap-x-6 gap-y-1 md:grid-cols-2">
                    {COLOR_FIELDS.filter((field) => field.group === group).map((field) => (
                      <ColorRow
                        key={field.key}
                        label={field.label}
                        help={field.help}
                        value={draft.colors[field.key]}
                        checks={checks.filter((check) => check.fg === field.key || check.bg === field.key)}
                        onChange={(value) => setColor(field.key, value)}
                      />
                    ))}
                  </div>
                </fieldset>
              ))}
            </Panel>
          )}
        </div>

        <aside className="min-w-0 space-y-4 xl:sticky xl:top-0 xl:self-start">
          <Panel title={isAdmin ? 'Live preview' : 'Preview of the theme in use'}>
            <ThemePreview theme={isAdmin ? draft : active} />
            <p className="mt-2 text-xs text-slate-400">
              {isAdmin
                ? 'This is only a preview. Nothing changes on any screen until you apply it.'
                : 'This is how the shop looks now.'}
            </p>
          </Panel>

          <Panel title="Readability">
            <Report checks={isAdmin ? checks : checksOf(active.colors)} exempt={isAdmin ? builtIn : active.id === 'default'} />
          </Panel>

          {isAdmin && (
            <Panel title="Apply">
              {error && <div className="mb-3"><Banner tone="bad" onDismiss={() => setError('')}>{error}</Banner></div>}
              {failing.length > 0 && !error && (
                <div className="mb-3">
                  <Banner tone="warn">
                    {failing.length === 1 ? 'One colour pair is' : `${failing.length} colour pairs are`} too
                    hard to read. Fix {failing.length === 1 ? 'it' : 'them'} in the builder to apply this theme.
                  </Banner>
                </div>
              )}
              <div className="flex flex-wrap gap-2">
                <Button tone="primary" onClick={() => void apply()} disabled={busy || failing.length > 0 || sameAsActive}
                        title="Make this the theme on every counter, terminal and device">
                  {sameAsActive ? 'In use on all counters' : 'Apply to all counters'}
                </Button>
                {!builtIn && (
                  <Button onClick={() => void save()} disabled={busy || failing.length > 0 || draft.name.trim() === ''}
                          title="Keep this theme in the shop's list to use again">
                    {inLibrary ? 'Update saved theme' : 'Save to my themes'}
                  </Button>
                )}
                <Button tone="ghost" onClick={() => { touched.current = false; setError(''); setDraft(clone(active)) }}
                        disabled={busy}>
                  Reset to the one in use
                </Button>
              </div>
              <p className="mt-3 text-xs text-slate-400">
                Counters pick the change up by themselves within a few seconds, with no restart and no sign-out.
              </p>
            </Panel>
          )}
        </aside>
      </div>

      {deleting && (
        <Modal title={`Delete ${deleting.name}?`} onClose={() => setDeleting(null)}>
          <p className="text-sm text-slate-600">
            It leaves the shop's list of saved themes. If it is the theme in use, the counters keep wearing it.
            Export it first if you may want it back.
          </p>
          <div className="mt-4 flex justify-end gap-2">
            <Button onClick={() => setDeleting(null)}>Keep it</Button>
            <Button tone="danger" onClick={() => void remove(deleting)}>Delete theme</Button>
          </div>
        </Modal>
      )}
    </div>
  )
}

function clone(theme: ThemeDefinition): ThemeDefinition {
  return { ...theme, colors: { ...theme.colors } }
}

function sameColors(a: ThemeDefinition, b: ThemeDefinition): boolean {
  return (Object.keys(a.colors) as ColorKey[]).every((key) => a.colors[key] === b.colors[key])
}

/** A picker, a hex box, and how readable the colour is against what it is put with. */
function ColorRow({ label, help, value, checks, onChange }: {
  label: string
  help: string
  value: string
  checks: Check[]
  onChange: (value: string) => void
}) {
  const [text, setText] = useState(value)
  useEffect(() => setText(value), [value])
  const worst = checks.length ? checks.reduce((a, b) => (a.ratio / a.minimum <= b.ratio / b.minimum ? a : b)) : null

  return (
    <div className="flex items-center gap-3 rounded-md px-1 py-1.5 hover:bg-slate-50">
      <input
        type="color"
        value={isHex(value) ? value : '#000000'}
        onChange={(event) => onChange(event.target.value.toLowerCase())}
        aria-label={`${label} colour`}
        className="h-9 w-11 shrink-0 cursor-pointer rounded-md border border-slate-300 bg-surface p-0.5"
      />
      <div className="min-w-0 flex-1">
        <div className="text-sm font-medium text-slate-800">{label}</div>
        <div className="truncate text-xs text-slate-400" title={help}>{help}</div>
      </div>
      <input
        value={text}
        onChange={(event) => {
          setText(event.target.value)
          const typed = event.target.value.trim()
          if (isHex(typed)) {
            onChange(typed.toLowerCase())
          }
        }}
        onBlur={() => setText(value)}
        spellCheck={false}
        maxLength={7}
        aria-label={`${label} hex value`}
        className="num w-24 shrink-0 rounded-md border border-slate-300 bg-surface px-2 py-1.5 font-mono text-sm uppercase text-slate-800 outline-none focus:border-sky-500 focus:ring-2 focus:ring-sky-100"
      />
      {worst && <Chip check={worst} />}
    </div>
  )
}

function Chip({ check }: { check: Check }) {
  const earned = grade(check.ratio)
  const tone = !check.ok ? 'bg-rose-100 text-rose-800'
    : earned === 'AAA' ? 'bg-emerald-100 text-emerald-800' : 'bg-sky-100 text-sky-800'
  return (
    <span
      className={`w-14 shrink-0 rounded px-1.5 py-0.5 text-center text-[11px] font-semibold ${tone}`}
      title={`${check.meaning}: ${check.ratio.toFixed(1)}:1, needs ${check.minimum}:1`}
    >
      {check.ratio.toFixed(1)}:1
    </span>
  )
}

function Report({ checks, exempt }: { checks: Check[]; exempt: boolean }) {
  return (
    <>
      <ul className="divide-y divide-slate-100 text-sm">
        {checks.map((check) => (
          <li key={check.meaning} className="flex items-center justify-between gap-3 py-1.5">
            <span className="flex min-w-0 items-center gap-2">
              <span aria-hidden="true" className={check.ok ? 'text-emerald-600' : 'text-rose-600'}>
                {check.ok ? '✓' : '✕'}
              </span>
              <span className="truncate text-slate-700" title={check.meaning}>{check.meaning}</span>
            </span>
            <span className="num shrink-0 text-xs text-slate-500">
              {check.ratio.toFixed(1)}:1 <span className="font-semibold">{check.ok ? grade(check.ratio) : 'Fail'}</span>
            </span>
          </li>
        ))}
      </ul>
      <p className="mt-2 text-xs text-slate-400">
        Measured against WCAG 2.1: 4.5:1 is level AA for text, 7:1 is AAA, 3:1 is the floor for controls.
        {exempt && ' The original look is kept exactly as it shipped, so it is not held to these.'}
      </p>
    </>
  )
}

/** The four colours that tell a theme apart at a glance. */
function Swatches({ theme }: { theme: ThemeDefinition }) {
  return (
    <span className="flex -space-x-1" aria-hidden="true">
      {[theme.colors.nav, theme.colors.primary, theme.colors.secondary, theme.colors.background].map((color, i) => (
        <span key={i} className="h-4 w-4 rounded-full border border-slate-300" style={{ background: color }} />
      ))}
    </span>
  )
}

/** A miniature screen in a theme's own colours: header, a row of content, a button. */
function Thumbnail({ theme }: { theme: ThemeDefinition }) {
  const c = theme.colors
  return (
    <span className="block" style={{ background: c.background }} aria-hidden="true">
      <span className="flex h-5 items-center gap-1 px-2" style={{ background: c.nav }}>
        <span className="h-1.5 w-5 rounded-full" style={{ background: c.navText }} />
        <span className="ml-auto h-1.5 w-3 rounded-full" style={{ background: c.accent }} />
      </span>
      <span className="flex gap-2 p-2">
        <span className="block flex-1 space-y-1 rounded p-1.5" style={{ background: c.surface, border: `1px solid ${c.text}22` }}>
          <span className="block h-1.5 w-3/4 rounded-full" style={{ background: c.text }} />
          <span className="block h-1.5 w-1/2 rounded-full" style={{ background: `${c.text}66` }} />
          <span className="block h-1.5 w-2/3 rounded-full" style={{ background: `${c.text}33` }} />
        </span>
        <span className="flex w-10 flex-col justify-end gap-1">
          <span className="block h-3 rounded" style={{ background: c.primary }} />
          <span className="block h-3 rounded" style={{ background: c.secondary }} />
        </span>
      </span>
    </span>
  )
}

/**
 * The real controls, painted in the theme being built. The theme's variables are set on this one box
 * (see `previewStyle`), so the buttons, panels and table below are the same components the till uses
 * and show exactly what applying the theme would do - while the rest of the page stays as it is.
 */
function ThemePreview({ theme }: { theme: ThemeDefinition }) {
  return (
    <div className="themed overflow-hidden rounded-lg border border-slate-300 text-sm" style={previewStyle(theme)}>
      <div className="nav-scope flex h-10 items-center gap-3 bg-slate-900 px-3 text-slate-100">
        <span className="flex items-center gap-1.5 rounded-md px-1.5 py-1 hover:bg-slate-800">☰ Menu <kbd>F9</kbd></span>
        <span className="truncate font-semibold">MASS MART</span>
        <span className="num ml-auto rounded bg-slate-800 px-2 py-0.5 font-mono text-xs font-semibold text-accent">10:42:07</span>
      </div>

      <div className="flex">
        <div className="nav-scope w-28 shrink-0 space-y-1 bg-slate-900 p-1.5 text-xs">
          <div className="rounded-md bg-slate-700 px-2 py-1.5 font-medium text-white">Billing</div>
          <div className="rounded-md px-2 py-1.5 text-slate-300 hover:bg-slate-800">Bills</div>
          <div className="rounded-md px-2 py-1.5 text-slate-300 hover:bg-slate-800">Products</div>
          <div className="rounded-md px-2 py-1.5 text-slate-300 hover:bg-slate-800">Reports</div>
        </div>

        <div className="min-w-0 flex-1 space-y-2.5 p-2.5">
          <div className="rounded-lg border border-slate-200 bg-surface shadow-sm">
            <table className="w-full text-xs">
              <thead className="nav-scope bg-slate-800 text-[10px] uppercase tracking-wide text-slate-200">
                <tr>
                  <th className="px-2 py-1 text-left font-medium">Item</th>
                  <th className="px-2 py-1 text-right font-medium">Qty</th>
                  <th className="px-2 py-1 text-right font-medium">Amount</th>
                </tr>
              </thead>
              <tbody>
                <tr className="bg-sky-600 text-on-primary">
                  <td className="px-2 py-1">Basmati rice 5 kg</td>
                  <td className="num px-2 py-1 text-right">2</td>
                  <td className="num px-2 py-1 text-right">₹ 1,180.00</td>
                </tr>
                <tr className="border-t border-slate-200">
                  <td className="px-2 py-1 text-slate-800">Toor dal 1 kg</td>
                  <td className="num px-2 py-1 text-right text-slate-800">1</td>
                  <td className="num px-2 py-1 text-right text-slate-800">₹ 165.00</td>
                </tr>
                <tr className="border-t border-slate-200 bg-slate-50">
                  <td className="px-2 py-1 text-slate-500">Sunflower oil 1 L</td>
                  <td className="num px-2 py-1 text-right text-slate-500">1</td>
                  <td className="num px-2 py-1 text-right text-slate-500">₹ 148.00</td>
                </tr>
              </tbody>
            </table>
          </div>

          <div className="flex flex-wrap items-center gap-1.5">
            <Button tone="primary" className="py-1.5">Take payment <kbd>Space</kbd></Button>
            <Button className="py-1.5">Hold</Button>
            <span className="rounded-md bg-secondary px-2.5 py-1.5 text-sm font-medium text-on-secondary">Selected</span>
            <Button tone="danger" className="py-1.5">Cancel</Button>
          </div>

          <input
            readOnly
            tabIndex={-1}
            value="Search item or scan"
            className="w-full rounded-md border border-slate-300 bg-surface px-2.5 py-1.5 text-sm text-slate-500 outline-none"
          />

          <div className="space-y-1.5">
            <Banner tone="good">Bill T1-0042 saved</Banner>
            <Banner tone="warn">Low stock: Toor dal</Banner>
            <Banner tone="bad">Printer is offline</Banner>
          </div>

          <p className="text-xs text-slate-500">
            Muted text, <span className="text-sky-700 underline">a link</span> and <kbd>Esc</kbd> follow the theme too.
            Contrast {contrast(theme.colors.text, theme.colors.background).toFixed(1)}:1.
          </p>
        </div>
      </div>
    </div>
  )
}
