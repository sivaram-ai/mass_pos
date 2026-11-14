import { counterCode } from './counter'
import type { Customer } from './types'

/**
 * The bill on screen, kept on this machine so a reload does not throw it away.
 *
 * <p>Refreshing is a page reload, and a page reload used to empty the grid. That is a worse
 * accident here than almost anywhere else: the cashier has rung up twenty items from a trolley
 * that has already been packed, and there is no way back to them - the items were never sent
 * anywhere, so no server has them and no undo can reach them. The browser's own error page counts
 * as a reload too, which is how a pulled network cable came to cost a bill.
 *
 * <p>So the grid is written here on every change, and read back when the screen next opens. This
 * is <em>not</em> a held bill: a held bill is the shop's, kept on the server, named, and listed
 * for any till to resume. This is one machine's note of what was on its own screen a moment ago,
 * and it is thrown away as soon as the bill is taken, held, cleared or signed out from.
 */

const DRAFT_KEY = 'masspos.bill.draft'

/**
 * Older than this and it is not the bill somebody was ringing up, it is the bill somebody
 * abandoned. Brought back in front of the next customer it would be billed to them, so a draft
 * that has sat overnight is dropped rather than offered - the day is checked as well, because a
 * shop that bills past midnight should not carry yesterday's screen into today's takings.
 */
const WHILE_IT_IS_STILL_THIS_BILL_MS = 12 * 60 * 60 * 1000

export interface BillDraft<Line> {
  /** When it was written, as the machine's own clock had it. */
  at: number
  /** The till's calendar day, so a bill never crosses one. */
  day: string
  /** Which counter wrote it: a machine that has since become another till starts clean. */
  counter: string
  lines: Line[]
  customer: Customer
  tableNo: string
  supplierId: string
  /** How the steward reads in his box, so the box is not left empty beside a chosen steward. */
  supplierText: string
}

/** What a caller hands over; the rest is stamped here. */
type Writing<Line> = Pick<BillDraft<Line>,
  'lines' | 'customer' | 'tableNo' | 'supplierId' | 'supplierText'>

export function saveBillDraft<Line>(day: string, bill: Writing<Line>): void {
  try {
    const draft: BillDraft<Line> = { at: Date.now(), day, counter: counterCode(), ...bill }
    localStorage.setItem(DRAFT_KEY, JSON.stringify(draft))
  } catch {
    // A locked-down browser profile, or a quota that is full. The bill on screen is unaffected;
    // it simply will not survive a reload, which is where this started.
  }
}

/**
 * The bill this machine was ringing up, or null when there is nothing worth bringing back.
 *
 * @param day the till's calendar day, as the screen writes bill dates
 */
export function savedBillDraft<Line>(day: string): BillDraft<Line> | null {
  try {
    const saved = localStorage.getItem(DRAFT_KEY)
    if (!saved) {
      return null
    }
    const draft = JSON.parse(saved) as BillDraft<Line> | null
    if (!draft || !Array.isArray(draft.lines) || draft.lines.length === 0) {
      return null
    }
    if (draft.day !== day || Date.now() - draft.at > WHILE_IT_IS_STILL_THIS_BILL_MS) {
      forgetBillDraft()
      return null
    }
    if (draft.counter !== counterCode()) {
      forgetBillDraft()
      return null
    }
    return draft
  } catch {
    // Anything unreadable is treated as nothing: an empty grid is always a safe thing to open on.
    forgetBillDraft()
    return null
  }
}

export function forgetBillDraft(): void {
  try {
    localStorage.removeItem(DRAFT_KEY)
  } catch {
    // Nothing to do: there is no screen to tell and nothing that depends on it.
  }
}
