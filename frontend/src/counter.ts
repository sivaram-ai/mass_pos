import { api } from './api'

/**
 * What this browser knows about the machine it is running on.
 *
 * <p>The billing screen is served by the shop's server, so the page itself cannot tell whether it
 * is open on the server or on a counter across the room. The counter app says so when it opens
 * the window - `?counter=T2&agent=8767` - and the answer is kept per machine in localStorage, the
 * same place the shortcut map and the other per-machine settings live. Everything the page asks
 * the server carries the counter number, and the server decides once, at sign-in, what it means.
 */

const COUNTER_KEY = 'masspos.counter'
const AGENT_KEY = 'masspos.counter.agent'

/** Reads `?counter=` once when the counter app opens the window, then keeps it. */
export function rememberCounterFromUrl(): void {
  try {
    const params = new URLSearchParams(window.location.search)
    const code = params.get('counter')
    const agent = params.get('agent')
    if (code) {
      localStorage.setItem(COUNTER_KEY, code.toUpperCase())
    }
    if (agent) {
      localStorage.setItem(AGENT_KEY, agent)
    }
    if (code || agent) {
      // Out of the address bar, so a reload or a shared link cannot carry it somewhere else.
      window.history.replaceState({}, '', window.location.pathname)
    }
  } catch {
    // A locked-down profile still bills; it is simply treated as the server's own browser.
  }
}

/** Empty on the server's own browser, which bills as the server. */
export function counterCode(): string {
  try {
    return localStorage.getItem(COUNTER_KEY) ?? ''
  } catch {
    return ''
  }
}

/** True when this machine prints for itself, rather than on the server's printer. */
export function hasPrintAgent(): boolean {
  return agentUrl() !== ''
}

export function agentUrl(): string {
  try {
    const port = localStorage.getItem(AGENT_KEY)
    return port ? `http://127.0.0.1:${port}` : ''
  } catch {
    return ''
  }
}

/** A bill the server laid out, in the two shapes a printer might want it. */
export interface PrintJob {
  escpos: string
  lines: unknown[]
}

/** What the agent says about the printer standing on this counter. */
export interface CounterPrinter {
  counter: string
  printer: string
  columns: number
  codePage: string
  drawerPin: string
  drawerPulseMillis: number
  lastError: string
}

let paper: CounterPrinter | null = null

/**
 * The counter's own paper, asked for once and kept. The server needs it to lay a bill out for
 * this roll, and only the machine the roll is on can say how wide it is.
 */
export async function counterPrinter(): Promise<CounterPrinter> {
  if (paper) {
    return paper
  }
  const response = await fetch(`${agentUrl()}/hello`)
  if (!response.ok) {
    throw new Error('The printer on this counter is not answering')
  }
  paper = await response.json() as CounterPrinter
  return paper
}

/**
 * Hands a bill to the print agent on this machine. Resolves once the agent has taken it, not
 * once the paper is out: a queue at the counter must never wait on a printer, which is the same
 * bargain the server's own printing has always made.
 */
export async function printHere(job: PrintJob): Promise<void> {
  const response = await fetch(`${agentUrl()}/print`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(job),
  })
  if (!response.ok) {
    const said = await response.json().catch(() => null)
    throw new Error(said?.error ?? `The printer on this counter did not take the bill (${response.status})`)
  }
}

/**
 * Prints a bill or a return, wherever this machine's paper is.
 *
 * <p>On the server's own browser the server prints, exactly as it always has. On a counter the
 * server lays the bill out and sends it back, and the agent on this machine puts it on the
 * printer and opens the drawer standing here. One function, so no screen has to know which it is.
 */
export async function printDocument(
  what: 'bill' | 'return',
  id: string,
  copy: 'ORIGINAL' | 'DUPLICATE',
  openDrawer: boolean,
): Promise<void> {
  const onServer = what === 'bill' ? `/api/invoices/${id}/receipt` : `/api/returns/${id}/receipt`
  if (!hasPrintAgent()) {
    await api.post(onServer, { copy, openDrawer })
    return
  }
  const paperHere = await counterPrinter()
  const forCounter = what === 'bill'
    ? `/api/invoices/${id}/receipt-job`
    : `/api/invoices/returns/${id}/receipt-job`
  const job = await api.post<PrintJob>(forCounter, {
    copy,
    openDrawer,
    columns: paperHere.columns,
    codePage: paperHere.codePage,
    drawerPin: paperHere.drawerPin,
    drawerPulseMillis: paperHere.drawerPulseMillis,
  })
  await printHere(job)
}
