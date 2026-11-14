import { billHere, counterCode, forgetBillHere, hasPrintAgent, keepBillHere } from './counter'
import type { Customer } from './types'

/**
 * The bill on screen, kept so a reload does not throw it away.
 *
 * <p>Refreshing is a page reload, and a page reload used to empty the grid. That is a worse
 * accident here than almost anywhere else: the cashier has rung up twenty items from a trolley
 * that has already been packed, and there is no way back to them - the items were never sent
 * anywhere, so no server has them and no undo can reach them. The browser's own error page counts
 * as a reload too, which is how a pulled network cable came to cost a bill.
 *
 * <p>This is <em>not</em> a held bill: a held bill is the shop's, kept on the server, named, and
 * listed for any till to resume. This is one machine's note of what was on its own screen a moment
 * ago, and it is thrown away as soon as the bill is taken, held, cleared or signed out from.
 *
 * <p><b>Where it is kept matters.</b> On a counter it goes to the print agent on that machine, and
 * on the server's own browser to `localStorage`. The obvious thing - `localStorage` everywhere -
 * is what was here first, and it was wrong: that storage belongs to the *address* the page came
 * from. A counter moved from the cable to Wi-Fi reaches its shop at a different address, so the
 * browser hands it a different, empty store, and gives the first one back when it moves to the
 * cable again. The cashier watched the bill they were ringing up disappear and an older one they
 * had abandoned take its place. The machine is the thing that does not change when the network
 * does; the server's own browser has no agent, and its address never changes either.
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
  /** Which counter wrote it: only read back where the browser is the one keeping it. */
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

export async function saveBillDraft<Line>(day: string, bill: Writing<Line>): Promise<void> {
  const draft: BillDraft<Line> = { at: Date.now(), day, counter: counterCode(), ...bill }
  if (hasPrintAgent()) {
    try {
      await keepBillHere(draft)
    } catch {
      // The agent on this machine is not answering, which is its own trouble and already on
      // screen. The bill itself is untouched; it simply will not survive a reload.
    }
    return
  }
  try {
    localStorage.setItem(DRAFT_KEY, JSON.stringify(draft))
  } catch {
    // A locked-down browser profile, or a quota that is full. Same bargain as above.
  }
}

/**
 * The bill this machine was ringing up, or null when there is nothing worth bringing back.
 *
 * @param day the till's calendar day, as the screen writes bill dates
 */
export async function savedBillDraft<Line>(day: string): Promise<BillDraft<Line> | null> {
  if (hasPrintAgent()) {
    // Anything this browser kept under an address this counter used to reach its shop at. It is
    // the wrong bill by definition now, and leaving it would let it come back.
    forgetLocalDraft()
    try {
      // No counter check: the agent answering is this counter, which is the whole point of it.
      return stillThisBill(await billHere<BillDraft<Line>>(), day, true)
    } catch {
      return null
    }
  }
  try {
    const saved = localStorage.getItem(DRAFT_KEY)
    const draft = saved ? JSON.parse(saved) as BillDraft<Line> | null : null
    const keep = stillThisBill(draft, day, false)
    if (!keep && saved) {
      forgetLocalDraft()
    }
    return keep
  } catch {
    // Anything unreadable is treated as nothing: an empty grid is always safe to open on.
    forgetLocalDraft()
    return null
  }
}

function stillThisBill<Line>(draft: BillDraft<Line> | null, day: string,
                             thisMachineKeptIt: boolean): BillDraft<Line> | null {
  if (!draft || !Array.isArray(draft.lines) || draft.lines.length === 0) {
    return null
  }
  if (draft.day !== day || Date.now() - draft.at > WHILE_IT_IS_STILL_THIS_BILL_MS) {
    return null
  }
  if (!thisMachineKeptIt && draft.counter !== counterCode()) {
    return null
  }
  return draft
}

export async function forgetBillDraft(): Promise<void> {
  forgetLocalDraft()
  if (!hasPrintAgent()) {
    return
  }
  try {
    await forgetBillHere()
  } catch {
    // Nothing to do, and nothing that depends on it: the next bill overwrites it anyway.
  }
}

function forgetLocalDraft(): void {
  try {
    localStorage.removeItem(DRAFT_KEY)
  } catch {
    // There is no screen to tell and nothing that depends on it.
  }
}
