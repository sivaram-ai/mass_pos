import { counterCode } from './counter'

const TOKEN_KEY = 'masspos.token'

/** Window event fired when the server refuses something because of the licence. */
export const LICENSE_CHANGED = 'masspos:license'

/**
 * Window events for the one failure a counter cannot read its way out of: the shop stopped
 * answering. A pulled Ethernet cable, a switch off at the wall, the server restarting.
 *
 * <p>They are events rather than a return value because the whole screen has to know, not the one
 * call that happened to be in flight. A cashier halfway through a bill should be told the shop has
 * gone - once, plainly, without the bill moving - and told again when it comes back, and neither
 * of those belongs to whichever request found out.
 *
 * <p>Only a request that never reached the server counts. A refusal is not this: a 401, a 409, a
 * 500 all mean the shop answered, and the screen that asked has a sentence of its own to show.
 */
export const SHOP_UNREACHABLE = 'masspos:unreachable'
export const SHOP_ANSWERED = 'masspos:answered'

let answering = true

/** True until a request fails to reach the shop at all, and again as soon as one gets through. */
export function shopIsAnswering(): boolean {
  return answering
}

function reached() {
  if (!answering) {
    answering = true
    window.dispatchEvent(new Event(SHOP_ANSWERED))
  }
}

function couldNotReach() {
  if (answering) {
    answering = false
    window.dispatchEvent(new Event(SHOP_UNREACHABLE))
  }
}

/**
 * Asks the shop whether it is there, within a bounded wait, and says so through the two events
 * above. `/api/clients/places` needs no sign-in, so this works on the sign-in screen too.
 *
 * <p>Bounded, because the answer is wanted now and the browser's own is far too slow. A cable
 * pulled out of a switch does not refuse a connection, it swallows it: nothing comes back and the
 * browser waits twenty seconds or more before admitting it - `ERR_CONNECTION_TIMED_OUT`, which is
 * exactly the page this is here to keep a till off. Refresh cannot stand there that long with a
 * bill on screen, and a till asking every three seconds must not stack up twenty-second waits.
 *
 * @return true when the shop answered at all; a refusal is an answer, and proves the cable is in
 */
export async function askTheShop(withinMs = 2500): Promise<boolean> {
  const giveUp = new AbortController()
  const timer = setTimeout(() => giveUp.abort(), withinMs)
  try {
    await fetch('/api/clients/places', { headers: authHeaders(), signal: giveUp.signal })
    reached()
    return true
  } catch {
    // Refused, unreachable, or too slow to be any use: all the same thing at a counter.
    couldNotReach()
    return false
  } finally {
    clearTimeout(timer)
  }
}

/** The till an account is already signed in on, as the server describes it. */
export interface ActiveSessionDetails {
  counterCode: string
  counterName: string
  where: string
  since: string | null
}

/**
 * Thrown for any non-2xx reply; `message` is the server's own wording, fit to show on screen.
 * `fieldErrors` names each refused form field (`gstin`, `address[1]`) so a form can mark it.
 */
export class ApiError extends Error {
  status: number
  fieldErrors: Record<string, string>
  /** True when the server refused this machine rather than the person: see ApiExceptionHandler. */
  counterSetup: boolean
  /** Where this account is already signed in, when that is what the refusal was about. */
  activeSession: ActiveSessionDetails | null

  constructor(status: number, message: string, fieldErrors: Record<string, string> = {},
              counterSetup = false, activeSession: ActiveSessionDetails | null = null) {
    super(message)
    this.status = status
    this.fieldErrors = fieldErrors
    this.counterSetup = counterSetup
    this.activeSession = activeSession
  }
}

/** The per-field messages of a failed save, or none for any other kind of failure. */
export function fieldErrorsOf(failure: unknown): Record<string, string> {
  return failure instanceof ApiError ? failure.fieldErrors : {}
}

export function messageOfFailure(failure: unknown, fallback: string): string {
  return failure instanceof Error && failure.message ? failure.message : fallback
}

export function savedToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY)
  } catch {
    return null
  }
}

export function saveToken(token: string | null) {
  try {
    if (token) {
      localStorage.setItem(TOKEN_KEY, token)
    } else {
      localStorage.removeItem(TOKEN_KEY)
    }
  } catch {
    // A locked-down browser profile still works, it just forgets the sign-in on reload.
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {
    // Required by the server on every state-changing call: a cross-site page cannot send it.
    'X-POS-Client': 'mass-pos-ui',
  }
  // Which machine is asking. The server acts on it once, at sign-in, and ignores it afterwards -
  // a session's counter lives on its token, where a request cannot reach it.
  const counter = counterCode()
  if (counter) {
    headers['X-POS-Counter'] = counter
  }
  const token = savedToken()
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }

  let response: Response
  try {
    response = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    // The browser's own wording here is "Failed to fetch" or "NetworkError when attempting to
    // fetch resource": true, and no use to anybody at a counter. Status 0 is never a reply.
    couldNotReach()
    throw new ApiError(0, 'The till could not be reached. Check that it is switched on and on this'
      + ' network, then try again.')
  }
  // Answered, whatever it said: a refusal still proves the shop is there and the cable is in.
  reached()

  if (response.status === 204) {
    return undefined as T
  }
  const text = await response.text()
  const payload = text ? safeParse(text) : null
  if (!response.ok) {
    if ((payload as { license?: boolean } | null)?.license) {
      // A bill refused by the licence: the banner re-reads the licence rather than waiting for its timer.
      window.dispatchEvent(new Event(LICENSE_CHANGED))
    }
    const errors = (payload as { errors?: Record<string, string> } | null)?.errors
    const said = payload as {
      counterSetup?: boolean
      sessionConflict?: boolean
      activeSession?: ActiveSessionDetails
    } | null
    throw new ApiError(response.status, messageOf(payload, response.status),
      errors && typeof errors === 'object' ? errors : {},
      Boolean(said?.counterSetup),
      said?.sessionConflict && said.activeSession ? said.activeSession : null)
  }
  return payload as T
}

/**
 * Every refusal this server makes is JSON. Anything else is a page the web server wrote on its
 * own - a stack trace, an HTML error page - and showing it whole is how a cashier ends up reading
 * the inside of the program. It is dropped, and the status decides the sentence instead.
 */
function safeParse(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

function messageOf(payload: unknown, status: number): string {
  const problem = payload as { detail?: string; message?: string; title?: string } | null
  // Spring's own "Bad Request" / "Internal Server Error" titles tell a cashier nothing; the detail does.
  const detail = problem?.detail ?? problem?.message
  if (detail && !looksInternal(detail)) {
    return detail
  }
  // No status numbers and no framework wording: whoever reads this is at a till with a customer
  // in front of them, and a number they cannot act on is worse than a sentence they can.
  if (status === 401) {
    return 'You have been signed out. Sign in again to carry on.'
  }
  if (status === 403) {
    return 'Your account is not allowed to do this. Ask a manager.'
  }
  return status >= 500
    ? 'Something went wrong on this till. Try again, and if it keeps happening call service.'
    : 'That could not be done. Reload the screen and try again.'
}

/**
 * A last guard on wording nobody wrote. Every message this server sends is written for a shop, so
 * anything carrying a package name, a stack frame, SQL or a URL came from a layer underneath it -
 * a filter, the servlet container, a library - and the status sentence below is better than it.
 */
function looksInternal(detail: string): boolean {
  return /com\.masspos|java\.|jakarta\.|org\.springframework|SQLITE|jdbc:|\bat [a-z]+\.[a-z]+\./.test(detail)
}

export const api = {
  get: <T,>(path: string) => request<T>('GET', path),
  post: <T,>(path: string, body?: unknown) => request<T>('POST', path, body ?? {}),
  put: <T,>(path: string, body: unknown) => request<T>('PUT', path, body),
  delete: <T,>(path: string) => request<T>('DELETE', path),
}

function authHeaders(): Record<string, string> {
  const headers: Record<string, string> = { 'X-POS-Client': 'mass-pos-ui' }
  const counter = counterCode()
  if (counter) {
    headers['X-POS-Counter'] = counter
  }
  const token = savedToken()
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }
  return headers
}

/**
 * Saves a CSV the server built. A plain link cannot carry the bearer token, so the file is
 * fetched like any other call and handed to the browser as a blob under a name of our choosing.
 */
export async function downloadCsv(path: string, filename: string): Promise<void> {
  const response = await fetch(path, { headers: authHeaders() })
  const text = await response.text()
  if (!response.ok) {
    throw new ApiError(response.status, messageOf(text ? safeParse(text) : null, response.status), {})
  }
  const url = URL.createObjectURL(new Blob([text], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  URL.revokeObjectURL(url)
}

/** Sends a file exactly as it is, for a backup database the setup screen was handed. */
export async function postBinary<T>(path: string, bytes: Uint8Array): Promise<T> {
  const headers = authHeaders()
  headers['Content-Type'] = 'application/octet-stream'
  const response = await fetch(path, { method: 'POST', headers, body: bytes as BodyInit })
  const text = await response.text()
  const payload = text ? safeParse(text) : null
  if (!response.ok) {
    throw new ApiError(response.status, messageOf(payload, response.status), {})
  }
  return payload as T
}

/** Sends a sheet the shop picked. Field errors come back the same way a form's do. */
export async function uploadCsv<T>(path: string, csv: string): Promise<T> {
  const headers = authHeaders()
  headers['Content-Type'] = 'text/csv'
  const response = await fetch(path, { method: 'POST', headers, body: csv })
  const text = await response.text()
  const payload = text ? safeParse(text) : null
  if (!response.ok) {
    const errors = (payload as { errors?: Record<string, string> } | null)?.errors
    const said = payload as {
      counterSetup?: boolean
      sessionConflict?: boolean
      activeSession?: ActiveSessionDetails
    } | null
    throw new ApiError(response.status, messageOf(payload, response.status),
      errors && typeof errors === 'object' ? errors : {},
      Boolean(said?.counterSetup),
      said?.sessionConflict && said.activeSession ? said.activeSession : null)
  }
  return payload as T
}

/**
 * "T1-2627-00001" → "00001": the running number on its own, which is what a person reads, says
 * and searches for. The counter and the financial year are shown beside it where they are worth
 * knowing; the whole string stays the number of record, stored and filed. Anything not in that
 * shape comes back unchanged.
 */
export function billNo(number: string): string {
  const parts = number.split('-')
  return parts.length === 3 ? parts[2] : number
}

/** "T1-2627-00001" → "T1": which counter took it, empty for a number from somewhere else. */
export function counterOf(number: string): string {
  const parts = number.split('-')
  return parts.length === 3 ? parts[0] : ''
}

/** 1234567 paise → "₹12,345.67", grouped the Indian way (lakh, crore). */
export function rupees(paise: number): string {
  return (paise < 0 ? '-₹' : '₹') + plainRupees(Math.abs(paise))
}

export function plainRupees(paise: number): string {
  const abs = Math.abs(Math.round(paise))
  const whole = Math.floor(abs / 100).toString()
  const paisePart = (abs % 100).toString().padStart(2, '0')
  const last3 = whole.slice(-3)
  const rest = whole.slice(0, -3)
  const grouped = rest ? `${rest.replace(/\B(?=(\d{2})+(?!\d))/g, ',')},${last3}` : last3
  return `${grouped}.${paisePart}`
}

/** 12000 → "120", 12050 → "120.50": a rate the way a cashier types it into a cell. */
export function rupeesForInput(paise: number): string {
  return paise % 100 === 0 ? String(paise / 100) : (paise / 100).toFixed(2)
}

const INDIA = 'Asia/Kolkata'

/** "13-09-2026", in shop time whatever the machine's clock zone. */
export function billDate(at: Date): string {
  return at.toLocaleDateString('en-GB', { timeZone: INDIA }).replaceAll('/', '-')
}

/** "2027-09-30", a date the server sends without a time → "30-09-2027". */
export function dayFromIso(isoDate: string): string {
  const [year, month, day] = isoDate.split('-')
  return `${day}-${month}-${year}`
}

/** "07:16:27 PM" */
export function clockTime(at: Date): string {
  return at.toLocaleTimeString('en-US', {
    timeZone: INDIA, hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: true,
  })
}

/** 1250 → "1.25", 2000 → "2" */
export function quantity(milli: number): string {
  const value = milli / 1000
  return Number.isInteger(value) ? value.toString() : value.toFixed(3).replace(/0+$/, '')
}

/** 1800 → "18%" */
export function percent(basisPoints: number): string {
  return `${(basisPoints / 100).toString()}%`
}

export function paiseFromRupees(text: string): number {
  const value = Number.parseFloat(text.replace(/[^0-9.-]/g, ''))
  return Number.isFinite(value) ? Math.round(value * 100) : 0
}

export function milliFromUnits(text: string): number {
  const value = Number.parseFloat(text.replace(/[^0-9.]/g, ''))
  return Number.isFinite(value) ? Math.round(value * 1000) : 0
}
