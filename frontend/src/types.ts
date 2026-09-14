/** Mirrors the JSON the backend returns. Money is always paise, quantity always milli-units. */

export type Role = 'CASHIER' | 'MANAGER' | 'AUDITOR' | 'ADMIN'

export type PaymentMode = 'CASH' | 'UPI' | 'CARD' | 'VOUCHER' | 'ON_ACCOUNT'

export type Unit = 'NOS' | 'PCS' | 'BOX' | 'PAC' | 'DOZ' | 'KGS' | 'GMS' | 'LTR' | 'MLT' | 'MTR'

/** Units sold in whole pieces; the rest can be weighed or measured. */
export const WHOLE_UNITS: Unit[] = ['NOS', 'PCS', 'BOX', 'PAC', 'DOZ']

/** What each GST unit code stands for, shown beside the code wherever a unit is picked. */
export const UNIT_NAMES: Record<Unit, string> = {
  NOS: 'Numbers',
  PCS: 'Pieces',
  BOX: 'Boxes',
  PAC: 'Packs',
  DOZ: 'Dozens',
  KGS: 'Kilograms',
  GMS: 'Grams',
  LTR: 'Litres',
  MLT: 'Millilitres',
  MTR: 'Metres',
}

export interface Principal {
  userId: string
  username: string
  displayName: string
  role: Role
  tokenId: string
  mustChangePin: boolean
}

export interface Product {
  id: string
  sku: string
  barcode: string | null
  name: string
  /** Empty when the item has none. */
  hsnCode: string
  unit: Unit
  sellingPricePaise: number
  mrpPaise: number
  taxInclusive: boolean
  gstRateBp: number
  cessRateBp: number
  active: boolean
  stockMilli: number
}

export interface StockRow {
  productId: string
  sku: string
  name: string
  unit: Unit
  stockMilli: number
  sellingPricePaise: number
  stockValuePaise: number
}

export interface CartLine {
  productId: string
  sku: string
  name: string
  unit: Unit
  quantityMilli: number
  discountPaise: number
  /** 0 until the cashier types a rate for an item the catalogue has no price for. */
  unitPricePaise: number
}

export interface QuotedLine {
  productId: string
  sku: string
  name: string
  hsnCode: string
  unit: Unit
  quantityMilli: number
  unitPricePaise: number
  discountPaise: number
  gstRateBp: number
  taxableValuePaise: number
  taxPaise: number
  lineTotalPaise: number
}

export interface QuotedCart {
  lines: QuotedLine[]
  interState: boolean
  taxableValuePaise: number
  cgstPaise: number
  sgstPaise: number
  igstPaise: number
  cessPaise: number
  roundOffPaise: number
  grandTotalPaise: number
}

export interface Tender {
  mode: PaymentMode
  amountPaise: number
  tenderedPaise: number
  reference?: string
}

export interface InvoiceLine {
  lineNo: number
  productId: string
  sku: string
  name: string
  hsnCode: string
  unit: Unit
  quantityMilli: number
  unitPricePaise: number
  discountPaise: number
  gstRateBp: number
  taxableValuePaise: number
  cgstPaise: number
  sgstPaise: number
  igstPaise: number
  cessPaise: number
  lineTotalPaise: number
}

export interface Invoice {
  id: string
  invoiceNumber: string
  invoiceDate: string
  issuedAt: string
  status: 'ISSUED' | 'CANCELLED'
  /** False for the bill of a shop without a GSTIN: no tax on it. */
  taxInvoice: boolean
  terminalCode: string
  cashier: string
  buyerName: string | null
  buyerGstin: string | null
  placeOfSupply: string
  taxableValuePaise: number
  cgstPaise: number
  sgstPaise: number
  igstPaise: number
  cessPaise: number
  roundOffPaise: number
  grandTotalPaise: number
  changePaise: number
  cancelReason: string | null
  /** Set on a bill issued in place of an edited one. */
  replacesInvoiceNumber: string | null
  lines: InvoiceLine[]
  payments: Tender[]
}

export interface SaleResponse {
  invoice: Invoice
  printed: boolean
  printError: string | null
}

export interface EditResponse extends SaleResponse {
  replacedInvoiceNumber: string
  previousTotalPaise: number
  refundPaise: number
  collectedPaise: number
}

/** Each line of a bill, with how much has already come back on returns. */
export interface Returnable {
  invoice: Invoice
  lines: {
    lineNo: number
    productId: string
    sku: string
    name: string
    unit: Unit
    soldMilli: number
    returnedMilli: number
    unitPricePaise: number
    discountPaise: number
    lineTotalPaise: number
  }[]
}

export interface Refund {
  mode: PaymentMode
  amountPaise: number
  reference?: string
}

/** A return: goods back, money back. */
export interface CreditNote {
  id: string
  creditNoteNumber: string
  noteDate: string
  issuedAt: string
  taxInvoice: boolean
  terminalCode: string
  cashier: string
  originalInvoiceId: string | null
  originalInvoiceNumber: string | null
  originalInvoiceDate: string | null
  buyerName: string | null
  buyerGstin: string | null
  placeOfSupply: string
  reason: string | null
  taxableValuePaise: number
  cgstPaise: number
  sgstPaise: number
  igstPaise: number
  cessPaise: number
  roundOffPaise: number
  grandTotalPaise: number
  lines: (InvoiceLine & { originalLineNo: number | null })[]
  refunds: Refund[]
}

export interface ReturnResponse {
  creditNote: CreditNote
  printed: boolean
  printError: string | null
}

export interface HeldBill {
  id: string
  label: string
  cashier: string
  heldAt: string
  estimatedTotalPaise: number
  cart: { lines: CartLine[]; customer?: Customer }
}

export interface Customer {
  name: string
  gstin: string
  placeOfSupply: string
}

export interface ShopSettingsForm {
  legalName: string
  tradeName: string
  gstin: string
  address: string[]
  phone: string
  email: string
  website: string
  cin: string
  fssaiLicense: string
  upiVpa: string
  receiptFooter: string[]
  serviceProviderName: string
  servicePhone: string
  serviceEmail: string
  roundInvoiceTotal: boolean
  blockNegativeStock: boolean
}

export interface TerminalView {
  code: string
  name: string
  section: string
  type: 'MASTER' | 'COUNTER'
  active: boolean
  lastSeenAt: string | null
}

export interface SettingsView {
  shop: ShopSettingsForm
  readyToInvoice: boolean
  missingForInvoicing: string[]
  /** False without a GSTIN: bills charge no tax. */
  gstRegistered: boolean
  thisTerminal: TerminalView
  terminals: TerminalView[]
}

export interface UserRow {
  id: string
  username: string
  displayName: string
  role: Role
  active: boolean
  mustChangePin: boolean
}

export interface PrinterStatus {
  responding: boolean
  online: boolean
  coverOpen: boolean
  paperNearEnd: boolean
  paperOut: boolean
  error: boolean
}

export interface DayReport {
  date: string
  bills: number
  cancelledBills: number
  returns: number
  salesTotals: ReportTotals
  returnTotals: ReportTotals
  /** Sales less returns. */
  totals: ReportTotals
  payments: { mode: PaymentMode; bills: number; amountPaise: number }[]
  cashiers: { name: string; bills: number; amountPaise: number }[]
  terminals: { name: string; bills: number; amountPaise: number }[]
  bestSellers: { name: string; quantityMilli: number; amountPaise: number; lines: number }[]
}

export interface ReportTotals {
  taxableValuePaise: number
  cgstPaise: number
  sgstPaise: number
  igstPaise: number
  cessPaise: number
  roundOffPaise: number
  grandTotalPaise: number
}

export interface GstReport {
  from: string
  to: string
  totals: ReportTotals
  rates: {
    gstRateBp: number
    taxableValuePaise: number
    cgstPaise: number
    sgstPaise: number
    igstPaise: number
    cessPaise: number
    lines: number
  }[]
  hsn: {
    hsnCode: string
    uqc: Unit
    quantityMilli: number
    taxableValuePaise: number
    cgstPaise: number
    sgstPaise: number
    igstPaise: number
    cessPaise: number
  }[]
}

export interface AuditEntry {
  at: string
  user: string
  terminal: string
  entity: string
  change: string
  entityId: string
}
