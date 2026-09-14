import { useEffect } from 'react'

/** Every keyboard action on the billing screen. Each till can remap them in Settings. */
export type ShortcutAction =
  | 'takeBill'
  | 'focusSearch'
  | 'setQuantity'
  | 'setDiscount'
  | 'removeLine'
  | 'holdBill'
  | 'resumeBill'
  | 'openDrawer'
  | 'reprintLast'
  | 'customer'
  | 'editBill'
  | 'returnBill'
  | 'clearBill'

export const SHORTCUT_LABELS: Record<ShortcutAction, string> = {
  takeBill: 'Take bill (payment)',
  focusSearch: 'New item row',
  setQuantity: 'Go to quantity',
  setDiscount: 'Go to discount',
  removeLine: 'Delete row',
  holdBill: 'Hold bill',
  resumeBill: 'Resume held bill',
  openDrawer: 'Open drawer (no sale)',
  reprintLast: 'Reprint last bill',
  customer: 'Customer details',
  editBill: "Edit today's bill",
  returnBill: 'Return',
  clearBill: 'Clear bill',
}

/**
 * Function keys, Escape and Space. Space takes the bill only when the cursor is not in the middle of
 * typing an item name, so "chicken rice" can still be typed in the name column.
 */
export const DEFAULT_SHORTCUTS: Record<ShortcutAction, string> = {
  takeBill: 'Space',
  focusSearch: 'F2',
  setQuantity: 'F3',
  setDiscount: 'F4',
  removeLine: 'F5',
  holdBill: 'F6',
  resumeBill: 'F7',
  openDrawer: 'F8',
  reprintLast: 'F10',
  customer: 'F1',
  editBill: 'Insert',
  returnBill: 'F12',
  clearBill: 'Escape',
}

export const ASSIGNABLE_KEYS = [
  'Space', 'F1', 'F2', 'F3', 'F4', 'F5', 'F6', 'F7', 'F8', 'F9', 'F10', 'F11', 'F12', 'Escape', 'Insert', 'Delete',
]

const STORAGE_KEY = 'masspos.shortcuts'

export function loadShortcuts(): Record<ShortcutAction, string> {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    const merged: Record<string, string> = saved ? { ...DEFAULT_SHORTCUTS, ...JSON.parse(saved) } : { ...DEFAULT_SHORTCUTS }
    // Keys saved for actions that no longer exist (such as the old F9 payment key) are dropped.
    return Object.fromEntries((Object.keys(DEFAULT_SHORTCUTS) as ShortcutAction[])
      .map((action) => [action, merged[action]])) as Record<ShortcutAction, string>
  } catch {
    return { ...DEFAULT_SHORTCUTS }
  }
}

export function saveShortcuts(shortcuts: Record<ShortcutAction, string>) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(shortcuts))
  } catch {
    // Not fatal: the till falls back to the defaults next time.
  }
}

const SHOW_LIST_KEY = 'masspos.showShortcuts'

/** Whether the billing screen lists the shortcuts in its footer. On unless turned off in Settings. */
export function loadShowShortcuts(): boolean {
  try {
    return localStorage.getItem(SHOW_LIST_KEY) !== 'false'
  } catch {
    return true
  }
}

export function saveShowShortcuts(show: boolean) {
  try {
    localStorage.setItem(SHOW_LIST_KEY, String(show))
  } catch {
    // Not fatal: the list shows by default.
  }
}

const ASK_CASH_KEY = 'masspos.askCashReceived'

/**
 * Whether a cash bill needs the amount the customer hands over typed in. Off (the default), an empty
 * amount takes the bill for the exact total. Per machine, like the keys.
 */
export function loadAskCashReceived(): boolean {
  try {
    return localStorage.getItem(ASK_CASH_KEY) === 'true'
  } catch {
    return false
  }
}

export function saveAskCashReceived(ask: boolean) {
  try {
    localStorage.setItem(ASK_CASH_KEY, String(ask))
  } catch {
    // Not fatal: the till falls back to taking the exact amount.
  }
}

/** The name a key is stored under: the space bar reports " ", which reads badly on screen. */
export function keyName(event: { key: string }): string {
  return event.key === ' ' ? 'Space' : event.key
}

/** The shortcut bound to this key press, if any. */
export function actionFor(shortcuts: Record<ShortcutAction, string>, event: { key: string }): ShortcutAction | undefined {
  const key = keyName(event)
  return (Object.keys(shortcuts) as ShortcutAction[]).find((action) => shortcuts[action] === key)
}

/** True where a key is typing, not commanding: any text box, list or text area outside the bill grid. */
function isTextEntry(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) {
    return false
  }
  return target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)
}

/** F1 to F24. */
function isFunctionKey(event: { key: string }): boolean {
  return /^F\d{1,2}$/.test(event.key)
}

/** Ctrl letters a text box needs: select all, copy, cut, paste, undo, redo. */
const EDITING_LETTERS = 'ACVXYZ'
/** With Shift: redo, paste as plain text, and the developer tools (Ctrl+Shift+C/I/J) for support. */
const EDITING_SHIFT_LETTERS = 'CIJVZ'

/**
 * True for a key press the browser would act on as a web page: F1 opening help in a new tab, F5 or
 * Ctrl+R reloading away the bill, F11 going full screen, Ctrl+P printing the screen, Ctrl+F finding
 * in the page, Alt+Left going back, Alt+D jumping to the address bar. Letters are matched by key
 * position (`code`), as the browser does. Typing, AltGr characters, editing and zoom are left alone.
 */
function isBrowserKey(event: KeyboardEvent): boolean {
  if (isFunctionKey(event)) {
    return true
  }
  if (event.ctrlKey && event.altKey) {
    // AltGr on Windows reports Ctrl+Alt: it types characters such as ₹.
    return false
  }
  const letter = /^Key([A-Z])$/.exec(event.code)?.[1]
  if (event.ctrlKey || event.metaKey) {
    if (letter) {
      return !(event.shiftKey ? EDITING_SHIFT_LETTERS : EDITING_LETTERS).includes(letter)
    }
    // Ctrl+1 to Ctrl+9 switch browser tabs; Ctrl+0 resets the zoom and stays.
    return /^Digit[1-9]$/.test(event.code)
  }
  if (event.altKey) {
    return letter !== undefined || ['ArrowLeft', 'ArrowRight', 'Home'].includes(event.key)
  }
  return false
}

/**
 * Makes the till behave like an application, not a web page: keys the browser would act on do nothing
 * beyond what the screen itself does with them, and the right-click menu (Back, Reload, Print, Save as)
 * appears only in text boxes, for cut, copy and paste. Installed once, in the capture phase on window,
 * so it runs before every other handler and no stopPropagation can get past it. Handlers still see
 * every key. Ctrl+T, Ctrl+W, Ctrl+N and Alt+F4 are kept by the browser and cannot be cancelled by a page.
 */
export function blockBrowserKeys() {
  window.addEventListener('keydown', (event) => {
    if (isBrowserKey(event)) {
      event.preventDefault()
    }
  }, true)
  window.addEventListener('contextmenu', (event) => {
    if (!isTextEntry(event.target)) {
      event.preventDefault()
    }
  }, true)
}

/**
 * Binds the configured keys for as long as the screen is mounted. Function keys fire wherever the
 * cursor is. Printable keys such as Space fire only outside text boxes: the bill grid handles its own
 * cells, because only it knows whether a cell is being typed in.
 */
export function useShortcuts(
  shortcuts: Record<ShortcutAction, string>,
  handlers: Partial<Record<ShortcutAction, () => void>>,
  enabled = true,
) {
  useEffect(() => {
    if (!enabled) {
      return
    }
    const onKeyDown = (event: KeyboardEvent) => {
      // blockBrowserKeys cancels every function key before any handler runs, so for those
      // defaultPrevented does not mean another handler has already taken the key.
      const taken = event.defaultPrevented && !isFunctionKey(event)
      if (taken || event.ctrlKey || event.altKey || event.metaKey) {
        return
      }
      if (event.key.length === 1 && (isTextEntry(event.target) || event.repeat)) {
        return
      }
      const action = actionFor(shortcuts, event)
      const handler = action && handlers[action]
      if (handler) {
        event.preventDefault()
        handler()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  })
}
