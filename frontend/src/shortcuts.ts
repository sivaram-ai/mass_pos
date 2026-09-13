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
  | 'takePayment'
  | 'reprintLast'
  | 'customer'
  | 'editBill'
  | 'returnBill'
  | 'clearBill'

export const SHORTCUT_LABELS: Record<ShortcutAction, string> = {
  takeBill: 'Take bill (cash)',
  focusSearch: 'New item row',
  setQuantity: 'Go to quantity',
  setDiscount: 'Go to discount',
  removeLine: 'Delete row',
  holdBill: 'Hold bill',
  resumeBill: 'Resume held bill',
  openDrawer: 'Open drawer (no sale)',
  takePayment: 'UPI, card or split',
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
  takePayment: 'F9',
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
    return saved ? { ...DEFAULT_SHORTCUTS, ...JSON.parse(saved) } : { ...DEFAULT_SHORTCUTS }
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
      if (event.defaultPrevented || event.ctrlKey || event.altKey || event.metaKey) {
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
