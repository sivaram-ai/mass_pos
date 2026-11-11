/**
 * The shop's colour scheme, applied to every screen.
 *
 * <p>The screens are painted with Tailwind's slate, sky, rose, amber and emerald scales, and Tailwind 4
 * reads every colour through a CSS variable (`bg-sky-600` is `var(--color-sky-600)`). So a theme is
 * applied by <em>redefining those variables</em>, on the page or on any element, and no screen has to
 * know a theme exists. A theme itself is only eleven colours; this file derives the scales from them.
 *
 * <p>Three places are deliberately not driven by the neutral scale and have their own tokens, because
 * "dark chrome on a light page" is a different thing from "light text on a dark page":
 * the header and menu (`.nav-scope`, see index.css), the primary button text (`text-on-primary`) and
 * the secondary selected state (`bg-secondary`).
 *
 * <p>The default theme applies nothing at all: the page keeps the colours it was built with.
 */
import type { CSSProperties } from 'react'

export const COLOR_KEYS = [
  'primary', 'onPrimary', 'secondary', 'onSecondary', 'accent',
  'background', 'surface', 'text', 'nav', 'navText', 'navActive',
] as const

export type ColorKey = (typeof COLOR_KEYS)[number]
export type ThemeColors = Record<ColorKey, string>

export interface ThemeDefinition {
  id: string
  name: string
  description: string
  colors: ThemeColors
}

/** What `GET /api/theme` answers. */
export interface ThemeView {
  revision: number
  active: ThemeDefinition
  presets: ThemeDefinition[]
  library: ThemeDefinition[]
}

/** How each colour is labelled in the builder, and what it paints. */
export const COLOR_FIELDS: { key: ColorKey; label: string; help: string; group: string }[] = [
  { key: 'primary', label: 'Primary', help: 'Main buttons, links, focus and selected rows', group: 'Brand' },
  { key: 'onPrimary', label: 'Text on primary', help: 'Words on primary buttons and selected rows', group: 'Brand' },
  { key: 'secondary', label: 'Secondary', help: 'Selected tabs, sort buttons, secondary actions', group: 'Brand' },
  { key: 'onSecondary', label: 'Text on secondary', help: 'Words on secondary buttons', group: 'Brand' },
  { key: 'accent', label: 'Accent / highlight', help: 'The clock, badges and highlights on the header', group: 'Brand' },
  { key: 'background', label: 'Page background', help: 'Behind everything', group: 'Content' },
  { key: 'surface', label: 'Cards & dialogs', help: 'Panels, tables, pop-ups', group: 'Content' },
  { key: 'text', label: 'Text', help: 'Main text; muted text and borders are derived from it', group: 'Content' },
  { key: 'nav', label: 'Header & menu', help: 'The bar across the top and the slide-in menu', group: 'Navigation' },
  { key: 'navText', label: 'Header & menu text', help: 'Words and icons on the header and menu', group: 'Navigation' },
  { key: 'navActive', label: 'Open menu item', help: 'The highlighted menu entry', group: 'Navigation' },
]

// ---------------------------------------------------------------------------------------------
// Colour arithmetic
// ---------------------------------------------------------------------------------------------

type Rgb = [number, number, number]

const HEX = /^#[0-9a-f]{6}$/i

export function isHex(value: string): boolean {
  return HEX.test(value)
}

function toRgb(hex: string): Rgb {
  const n = parseInt(hex.slice(1), 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}

function toHex([r, g, b]: Rgb): string {
  return '#' + [r, g, b].map((c) => Math.round(Math.min(255, Math.max(0, c))).toString(16).padStart(2, '0')).join('')
}

/** `amount` of `b` in `a`: 0 is a, 1 is b. */
export function mix(a: string, b: string, amount: number): string {
  const x = toRgb(a)
  const y = toRgb(b)
  return toHex([0, 1, 2].map((i) => x[i] + (y[i] - x[i]) * amount) as Rgb)
}

function channel(value: number): number {
  const c = value / 255
  return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
}

export function luminance(hex: string): number {
  const [r, g, b] = toRgb(hex)
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
}

/** WCAG 2.1 contrast ratio, 1 to 21. */
export function contrast(a: string, b: string): number {
  const la = luminance(a)
  const lb = luminance(b)
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05)
}

/** Below this background luminance a theme is dark: tints run toward the page, not toward white. Same as the server. */
const DARK_BELOW = 0.2

export function isDark(colors: ThemeColors): boolean {
  return luminance(colors.background) < DARK_BELOW
}

/** Moves `color` toward black or white, whichever helps, until it reads on `against`. */
function readableOn(color: string, against: string, minimum: number): string {
  if (contrast(color, against) >= minimum) {
    return color
  }
  const target = luminance(against) < 0.4 ? '#ffffff' : '#000000'
  for (let step = 1; step <= 20; step += 1) {
    const candidate = mix(color, target, step / 20)
    if (contrast(candidate, against) >= minimum) {
      return candidate
    }
  }
  return target
}

/** The grade a ratio earns for ordinary text. */
export function grade(ratio: number): 'AAA' | 'AA' | 'Low' | 'Fail' {
  if (ratio >= 7) {
    return 'AAA'
  }
  if (ratio >= 4.5) {
    return 'AA'
  }
  return ratio >= 3 ? 'Low' : 'Fail'
}

/** Mirrors ThemeRules.PAIRS on the server, which is what actually enforces them. */
export const PAIRS: { fg: ColorKey; bg: ColorKey; minimum: number; meaning: string }[] = [
  { fg: 'text', bg: 'background', minimum: 4.5, meaning: 'Text on the page background' },
  { fg: 'text', bg: 'surface', minimum: 4.5, meaning: 'Text on cards and dialogs' },
  { fg: 'onPrimary', bg: 'primary', minimum: 4.5, meaning: 'Text on primary buttons' },
  { fg: 'onSecondary', bg: 'secondary', minimum: 4.5, meaning: 'Text on secondary buttons and selected tabs' },
  { fg: 'navText', bg: 'nav', minimum: 4.5, meaning: 'Text on the header and menu' },
  { fg: 'navText', bg: 'navActive', minimum: 4.5, meaning: 'Text on the open menu item' },
  { fg: 'primary', bg: 'surface', minimum: 3, meaning: 'Primary colour as links and controls on cards' },
  { fg: 'accent', bg: 'nav', minimum: 3, meaning: 'Highlights on the header and menu' },
]

export interface Check {
  meaning: string
  ratio: number
  minimum: number
  ok: boolean
  fg: ColorKey
  bg: ColorKey
}

export function checksOf(colors: ThemeColors): Check[] {
  return PAIRS.map((pair) => {
    const ratio = contrast(colors[pair.fg], colors[pair.bg])
    return { meaning: pair.meaning, ratio, minimum: pair.minimum, ok: ratio >= pair.minimum, fg: pair.fg, bg: pair.bg }
  })
}

// ---------------------------------------------------------------------------------------------
// The scales
// ---------------------------------------------------------------------------------------------

const STEPS = ['50', '100', '200', '300', '400', '500', '600', '700', '800', '900', '950'] as const

/** The colours the screens were built with (Tailwind's own), used to show the default theme faithfully. */
const STOCK: Record<string, string[]> = {
  slate: ['#f8fafc', '#f1f5f9', '#e2e8f0', '#cbd5e1', '#94a3b8', '#64748b', '#475569', '#334155', '#1e293b', '#0f172a', '#020617'],
  sky: ['#f0f9ff', '#e0f2fe', '#bae6fd', '#7dd3fc', '#38bdf8', '#0ea5e9', '#0284c7', '#0369a1', '#075985', '#0c4a6e', '#082f49'],
  rose: ['#fff1f2', '#ffe4e6', '#fecdd3', '#fda4af', '#fb7185', '#f43f5e', '#e11d48', '#be123c', '#9f1239', '#881337', '#4c0519'],
  amber: ['#fffbeb', '#fef3c7', '#fde68a', '#fcd34d', '#fbbf24', '#f59e0b', '#d97706', '#b45309', '#92400e', '#78350f', '#451a03'],
  emerald: ['#ecfdf5', '#d1fae5', '#a7f3d0', '#6ee7b7', '#34d399', '#10b981', '#059669', '#047857', '#065f46', '#064e3b', '#022c22'],
}

function scaleVars(name: string, values: string[]): Record<string, string> {
  const vars: Record<string, string> = {}
  STEPS.forEach((step, i) => {
    vars[`--color-${name}-${step}`] = values[i]
  })
  return vars
}

/**
 * A colour's eleven steps, `base` being the 600 step. Light themes tint toward white and shade toward
 * black; dark themes tint toward the page, so a `bg-sky-50` is a faint glow and not a white slab,
 * and the dark steps become the light ones used for text.
 */
function accentScale(base: string, dark: boolean, page: string): string[] {
  if (!dark) {
    return [
      mix(base, '#ffffff', 0.93), mix(base, '#ffffff', 0.86), mix(base, '#ffffff', 0.72),
      mix(base, '#ffffff', 0.52), mix(base, '#ffffff', 0.3), mix(base, '#ffffff', 0.14), base,
      mix(base, '#000000', 0.15), mix(base, '#000000', 0.3), mix(base, '#000000', 0.45), mix(base, '#000000', 0.6),
    ]
  }
  return [
    mix(page, base, 0.1), mix(page, base, 0.16), mix(page, base, 0.26), mix(page, base, 0.5),
    mix(base, '#ffffff', 0.25), mix(base, '#ffffff', 0.12), base,
    mix(base, '#ffffff', 0.35), mix(base, '#ffffff', 0.5), mix(base, '#ffffff', 0.65), mix(base, '#ffffff', 0.8),
  ]
}

/**
 * The greys: step 100 is the page, step 900 the text, and everything between is a blend of the two,
 * so muted text, borders and hover fills always belong to the theme. Very high contrast themes get
 * firmer borders, because a hairline that vanishes is the first thing low vision loses.
 */
function neutralScale(c: ThemeColors): string[] {
  const firm = contrast(c.text, c.background) >= 15
  const edge = firm ? [0.4, 0.6] : [0.1, 0.22]
  return [
    mix(c.background, c.surface, 0.55), c.background,
    mix(c.background, c.text, edge[0]), mix(c.background, c.text, edge[1]),
    mix(c.background, c.text, 0.45), mix(c.background, c.text, 0.62), mix(c.background, c.text, 0.75),
    mix(c.background, c.text, 0.86), mix(c.background, c.text, 0.94), c.text, c.text,
  ]
}

/**
 * The header and menu read the same slate classes as everything else, but their greys run from the
 * navigation colour (900, the bar) up to its text (50): `.nav-scope` swaps these in for its subtree.
 */
function navVars(c: ThemeColors): Record<string, string> {
  const ramp = [c.navText, c.navText, mix(c.nav, c.navText, 0.9), mix(c.nav, c.navText, 0.8),
    mix(c.nav, c.navText, 0.62), mix(c.nav, c.navText, 0.45), mix(c.nav, c.navText, 0.3), c.navActive,
    mix(c.nav, c.navText, 0.12), c.nav, c.nav]
  const vars: Record<string, string> = {}
  STEPS.forEach((step, i) => {
    vars[`--nav-s${step}`] = ramp[i]
  })
  vars['--nav-white'] = c.navText
  vars['--nav-accent'] = c.accent
  // A warning-red that reads on whatever the bar is.
  vars['--nav-danger'] = readableOn('#fda4af', c.nav, 4.5)
  return vars
}

/** Every CSS variable a theme sets. The default theme is built from the stock colours. */
export function varsFor(theme: ThemeDefinition): Record<string, string> {
  const c = theme.colors
  const dark = isDark(c)
  const vars: Record<string, string> = {}

  if (theme.id === 'default') {
    for (const [name, values] of Object.entries(STOCK)) {
      Object.assign(vars, scaleVars(name, values))
    }
  } else {
    Object.assign(vars, scaleVars('slate', neutralScale(c)))
    Object.assign(vars, scaleVars('sky', accentScale(c.primary, dark, c.background)))
    if (dark) {
      // Status colours turn the same way, or their pale backgrounds would glare on a dark page.
      Object.assign(vars, scaleVars('rose', accentScale(STOCK.rose[6], true, c.background)))
      Object.assign(vars, scaleVars('amber', accentScale(STOCK.amber[6], true, c.background)))
      Object.assign(vars, scaleVars('emerald', accentScale(STOCK.emerald[6], true, c.background)))
    } else {
      for (const name of ['rose', 'amber', 'emerald']) {
        Object.assign(vars, scaleVars(name, STOCK[name]))
      }
    }
  }

  vars['--color-surface'] = c.surface
  vars['--color-secondary'] = c.secondary
  vars['--color-on-secondary'] = c.onSecondary
  vars['--color-on-primary'] = c.onPrimary
  vars['--color-accent'] = c.accent
  vars['--page-bg'] = c.background
  vars['--page-text'] = c.text
  const neutral = theme.id === 'default' ? STOCK.slate : neutralScale(c)
  vars['--kbd-bg'] = neutral[0]
  vars['--kbd-border'] = neutral[3]
  vars['--kbd-text'] = neutral[6]
  Object.assign(vars, navVars(c))
  return vars
}

/** Style for a preview box: the theme's variables and its page colours, without touching the page. */
export function previewStyle(theme: ThemeDefinition): CSSProperties {
  return {
    ...varsFor(theme),
    background: theme.colors.background,
    color: theme.colors.text,
    colorScheme: isDark(theme.colors) ? 'dark' : 'light',
  } as CSSProperties
}

// ---------------------------------------------------------------------------------------------
// Applying it
// ---------------------------------------------------------------------------------------------

const CACHE_KEY = 'masspos.theme'
let applied: string[] = []

/**
 * Puts a theme on the whole page, or takes every theme off with `null` / the default. Only the
 * variables set are ever removed, so nothing the page itself defines is disturbed.
 */
export function applyTheme(theme: ThemeDefinition | null) {
  const root = document.documentElement
  for (const name of applied) {
    root.style.removeProperty(name)
  }
  applied = []
  root.classList.remove('themed')
  root.style.removeProperty('color-scheme')
  if (!theme || theme.id === 'default') {
    return
  }
  const vars = varsFor(theme)
  for (const [name, value] of Object.entries(vars)) {
    root.style.setProperty(name, value)
    applied.push(name)
  }
  root.classList.add('themed')
  root.style.setProperty('color-scheme', isDark(theme.colors) ? 'dark' : 'light')
}

/** Remembered so the next page load is painted right away, before the server has answered. */
export function cacheTheme(theme: ThemeDefinition | null) {
  try {
    if (theme && theme.id !== 'default') {
      localStorage.setItem(CACHE_KEY, JSON.stringify(theme))
    } else {
      localStorage.removeItem(CACHE_KEY)
    }
  } catch {
    // Without storage the first paint is the default and the server's answer corrects it.
  }
}

export function cachedTheme(): ThemeDefinition | null {
  try {
    const text = localStorage.getItem(CACHE_KEY)
    const theme = text ? (JSON.parse(text) as ThemeDefinition) : null
    return theme && theme.colors && COLOR_KEYS.every((key) => isHex(theme.colors[key])) ? theme : null
  } catch {
    return null
  }
}

// ---------------------------------------------------------------------------------------------
// Import and export
// ---------------------------------------------------------------------------------------------

const FILE_FORMAT = 'gorockit-theme'

/** A theme as a file someone can keep or send to another shop. */
export function exportJson(themes: ThemeDefinition[]): string {
  return JSON.stringify({
    format: FILE_FORMAT,
    version: 1,
    themes: themes.map(({ name, description, colors }) => ({ name, description, colors })),
  }, null, 2)
}

/** A fresh id for a theme of the shop's own. */
export function newCustomId(): string {
  return 'custom-' + Math.random().toString(36).slice(2, 10).padEnd(8, '0')
}

/**
 * Reads a theme file into themes ready to edit, each with a new id: an imported file never overwrites
 * a theme already in the shop, whatever id it carries. Throws a sentence for a file that is not one.
 */
export function parseImport(text: string): ThemeDefinition[] {
  let data: unknown
  try {
    data = JSON.parse(text)
  } catch {
    throw new Error('That file is not a theme file: it is not valid JSON.')
  }
  const file = data as { format?: string; themes?: unknown[] } | null
  const entries = file?.format === FILE_FORMAT && Array.isArray(file.themes) ? file.themes : null
  if (!entries || entries.length === 0) {
    throw new Error('That file is not a GO RockIt theme file.')
  }
  return entries.map((entry, index) => {
    const raw = entry as { name?: unknown; description?: unknown; colors?: Record<string, unknown> }
    const colors = {} as ThemeColors
    for (const key of COLOR_KEYS) {
      const value = raw.colors?.[key]
      if (typeof value !== 'string' || !isHex(value.trim())) {
        throw new Error(`Theme ${index + 1} in that file is missing a valid "${key}" colour (a value like #1a59d3).`)
      }
      colors[key] = value.trim().toLowerCase()
    }
    const name = typeof raw.name === 'string' && raw.name.trim() ? raw.name.trim().slice(0, 40) : `Imported ${index + 1}`
    return {
      id: newCustomId(),
      name,
      description: typeof raw.description === 'string' ? raw.description.slice(0, 200) : '',
      colors,
    }
  })
}
