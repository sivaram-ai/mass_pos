import { counterCode, forgetOrderHere, hasPrintAgent, keepOrderHere, orderHere } from './counter'
import type { OrderLineDraft } from './types'

/**
 * The purchase order on screen, kept so nothing throws it away.
 *
 * <p>The same note the billing screen keeps of its bill, and kept in the same two places for the
 * same reasons: on a counter the print agent on that machine holds it, because `localStorage`
 * belongs to the address a page came from and a counter that moves from the cable to Wi-Fi
 * reaches its shop at a different one; on the server's own browser, `localStorage`, which has no
 * agent to ask and an address that never changes.
 *
 * <p>An order outlives a bill, though, and that is the one difference. A bill left on a screen
 * overnight is yesterday's customer and is dropped; an order written on Friday for the Monday
 * lorry is still that order on Monday, so it is kept until it is written, held or cleared and no
 * day is stamped on it. Nothing has been ordered and nobody has been billed either way.
 */

const DRAFT_KEY = 'masspos.order.draft'

/** A week. Past that it is not an order anybody is still writing, it is one they forgot. */
const WHILE_IT_IS_STILL_THIS_ORDER_MS = 7 * 24 * 60 * 60 * 1000

export interface OrderDraft {
  /** When it was written, as the machine's own clock had it. */
  at: number
  /** Which counter wrote it: only read back where the browser is the one keeping it. */
  counter: string
  vendorId: string
  vendorText: string
  expectedOn: string
  note: string
  lines: OrderLineDraft[]
}

type Writing = Pick<OrderDraft, 'vendorId' | 'vendorText' | 'expectedOn' | 'note' | 'lines'>

export async function saveOrderDraft(order: Writing): Promise<void> {
  const draft: OrderDraft = { at: Date.now(), counter: counterCode(), ...order }
  if (hasPrintAgent()) {
    try {
      await keepOrderHere(draft)
    } catch {
      // The agent on this machine is not answering, which is its own trouble and already on
      // screen. The order itself is untouched; it simply will not survive a reload.
    }
    return
  }
  try {
    localStorage.setItem(DRAFT_KEY, JSON.stringify(draft))
  } catch {
    // A locked-down browser profile, or a quota that is full. Same bargain as above.
  }
}

export async function savedOrderDraft(): Promise<OrderDraft | null> {
  if (hasPrintAgent()) {
    // Anything this browser kept under an address this counter used to reach its shop at.
    forgetLocalDraft()
    try {
      return stillThisOrder(await orderHere<OrderDraft>(), true)
    } catch {
      return null
    }
  }
  try {
    const saved = localStorage.getItem(DRAFT_KEY)
    const draft = saved ? JSON.parse(saved) as OrderDraft | null : null
    const keep = stillThisOrder(draft, false)
    if (!keep && saved) {
      forgetLocalDraft()
    }
    return keep
  } catch {
    // Anything unreadable is treated as nothing: an empty screen is always safe to open on.
    forgetLocalDraft()
    return null
  }
}

function stillThisOrder(draft: OrderDraft | null, thisMachineKeptIt: boolean): OrderDraft | null {
  if (!draft || !Array.isArray(draft.lines) || draft.lines.length === 0) {
    return null
  }
  if (Date.now() - draft.at > WHILE_IT_IS_STILL_THIS_ORDER_MS) {
    return null
  }
  if (!thisMachineKeptIt && draft.counter !== counterCode()) {
    return null
  }
  return draft
}

export async function forgetOrderDraft(): Promise<void> {
  forgetLocalDraft()
  if (!hasPrintAgent()) {
    return
  }
  try {
    await forgetOrderHere()
  } catch {
    // Nothing to do, and nothing that depends on it: the next order overwrites it anyway.
  }
}

function forgetLocalDraft(): void {
  try {
    localStorage.removeItem(DRAFT_KEY)
  } catch {
    // There is no screen to tell and nothing that depends on it.
  }
}
