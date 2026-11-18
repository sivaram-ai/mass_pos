/** Mirrors the JSON the backend returns. Money is always paise, quantity always milli-units. */

export type Role = 'CASHIER' | 'MANAGER' | 'AUDITOR' | 'ADMIN'

/** Whoever served the food on a bill: an ID and a name. Not somebody who signs in. */
export interface Supplier {
  id: string
  supplierId: string
  supplierName: string
  active: boolean
}

export type PaymentMode = 'CASH' | 'UPI' | 'CARD' | 'VOUCHER' | 'ON_ACCOUNT'

export type LicenseState = 'ACTIVE' | 'EXPIRING' | 'GRACE' | 'EXPIRED' | 'NOT_ACTIVATED' | 'WRONG_MACHINE' | 'INVALID'

/** This till's licence. The licence fields are null until a trustworthy licence is installed. */
export interface LicenseStatus {
  state: LicenseState
  billingAllowed: boolean
  /** Written for the banner: what is wrong and what to do. */
  message: string
  machineId: string | null
  clockBehind: boolean
  licenseId: string | null
  customerId: string | null
  customer: string | null
  plan: string | null
  issuedOn: string | null
  validUntil: string | null
  graceEndsOn: string | null
}

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
  /** Sell it whether or not the stock count says any is left. */
  billWithoutStock: boolean
  active: boolean
  stockMilli: number
}

/** Every kind of movement the stock ledger records. The shop's own words are in StockHistory. */
export type StockMovementType =
  | 'OPENING_STOCK' | 'PURCHASE_RECEIPT' | 'PURCHASE_RETURN' | 'SALE' | 'SALE_RETURN'
  | 'SALE_CANCELLED' | 'DAMAGE' | 'TRANSFER_IN' | 'TRANSFER_OUT' | 'ADJUSTMENT'

/** And the store behind the kitchen, which is never sold from and does get cooked with. */
export type RawMovementType =
  | 'OPENING_STOCK' | 'PURCHASE' | 'CONSUMED' | 'WASTAGE' | 'RETURNED_TO_VENDOR' | 'ADJUSTMENT'

export type MovementType = StockMovementType | RawMovementType

/**
 * One movement in an item's history, on the shelf or in the store. Both ends send the same shape
 * on purpose: the question being asked of them - why does it say four - is the same question, so
 * one screen answers it.
 */
export interface StockMovement {
  id: string
  occurredAt: string
  type: MovementType
  quantityDeltaMilli: number
  /** What was held once this was applied, or null over a window across every item. */
  balanceAfterMilli: number | null
  itemId: string
  itemCode: string
  name: string
  unit: Unit
  counterCode: string
  by: string
  note: string
  /** What one unit cost, on goods arriving; 0 on everything else. */
  buyingPricePaise: number
  /** Who the goods came from, as it was typed when they arrived. */
  vendorName: string
  /** The order the goods came in against, or empty where there was none. */
  orderNumber: string
}

/**
 * Why a count was put right by hand. The labels come from the server (`/api/stock/
 * correction-reasons`) rather than being written out here: the server builds the note the history
 * will show from the same label, and two copies of a word drift.
 */
export type CorrectionReasonName =
  | 'STOCK_TAKE' | 'OPENING_STOCK' | 'DAMAGED' | 'EXPIRED' | 'WASTED' | 'TAKEN' | 'SENT_BACK'
  | 'ENTERED_WRONGLY'

export interface CorrectionReason {
  name: CorrectionReasonName
  label: string
  /** -1 can only take stock off, +1 can only put it on, 0 either way. */
  sign: number
}

/** One delivery of one thing, at the price it came in at. */
export interface PaidPrice {
  when: string
  vendorName: string
  quantityMilli: number
  unit: Unit
  unitCostPaise: number
  valuePaise: number
  /** Empty for a delivery taken in by hand before an order was the only way in. */
  orderNumber: string
  approvedBy: string
  by: string
}

export type PurchaseOrderStatus =
  'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED' | 'RECEIVED' | 'CANCELLED'

/** Whether the money has followed the goods, worked out from the payments against the order. */
export type Settled = 'UNPAID' | 'PART_PAID' | 'PAID' | 'NOT_DUE'

export interface PurchaseOrderLine {
  id: string
  /** True when it is a raw material rather than something off the shelf. */
  fromTheStore: boolean
  /** True for something the shop does not keep: a name on the order and nothing else. */
  oneOff: boolean
  /** Null on a one-off: there is no item to open. */
  itemId: string | null
  itemCode: string
  name: string
  unit: Unit
  quantityMilli: number
  unitCostPaise: number
  valuePaise: number
  /** What actually turned up. Nothing until the order is received. */
  receivedMilli: number
}

export interface PurchaseOrder {
  id: string
  number: string
  status: PurchaseOrderStatus
  vendorId: string
  vendorName: string
  orderedOn: string
  expectedOn: string | null
  note: string
  valuePaise: number
  createdBy: string
  decidedBy: string
  decidedAt: string | null
  /** Why it was turned down or called off. */
  decision: string
  receivedBy: string
  receivedAt: string | null
  /** What the vendor can ask for: what arrived once received, what was asked for before that. */
  owedPaise: number
  /** What an administrator has agreed was paid against this order. */
  paidPaise: number
  settled: Settled
  lines: PurchaseOrderLine[]
}

/** An order put aside half written, the way a bill is held. */
export interface HeldOrder {
  id: string
  label: string
  by: string
  heldAt: string
  estimatedTotalPaise: number
  /** The screen's own shape, written back as it was held. */
  order: HeldOrderBody
}

/** What the writing screen keeps of an order it has not written yet. */
export interface HeldOrderBody {
  vendorId: string
  vendorText: string
  expectedOn: string
  note: string
  lines: OrderLineDraft[]
}

/** One line of an order being written: an item from either list, or a name typed in. */
export interface OrderLineDraft {
  productId: string | null
  rawMaterialId: string | null
  /** Set on a one-off, which is a line for something the shop does not keep. */
  itemName: string
  itemCode: string
  name: string
  unit: Unit
  quantityMilli: number
  unitCostPaise: number
}

export interface PurchaseReturnLine {
  id: string
  fromTheStore: boolean
  oneOff: boolean
  itemId: string | null
  itemCode: string
  name: string
  unit: Unit
  quantityMilli: number
  unitCostPaise: number
  valuePaise: number
}

/** Goods that went back to a vendor. */
export interface PurchaseReturn {
  id: string
  number: string
  vendorId: string
  vendorName: string
  againstOrderId: string | null
  againstOrderNumber: string
  returnedOn: string
  reason: string
  by: string
  valuePaise: number
  lines: PurchaseReturnLine[]
}

/** What can still go back off a received order. */
export interface CanGoBack {
  lineId: string
  fromTheStore: boolean
  oneOff: boolean
  itemId: string | null
  itemCode: string
  name: string
  unit: Unit
  receivedMilli: number
  alreadyBackMilli: number
  unitCostPaise: number
}

/** Somebody the shop buys from. Not a Supplier: that word means the steward on a hotel bill. */
export interface Vendor {
  id: string
  code: string
  name: string
  phone: string
  gstin: string
  address: string
  note: string
  active: boolean
  /** Everything ever bought from them, at what it was bought for. */
  boughtPaise: number
  /** What an administrator has agreed was paid. */
  paidPaise: number
  /** Recorded but not yet agreed to, and deliberately not counted in the balance. */
  waitingPaise: number
  /** Bought less paid. Negative means the shop has paid ahead. */
  outstandingPaise: number
}

export type VendorPaymentMode = 'CASH' | 'UPI' | 'BANK_TRANSFER' | 'CHEQUE' | 'CARD'

export interface VendorPurchase {
  id: string
  occurredAt: string
  /** True when it was a raw material rather than something off the shelf. */
  fromTheStore: boolean
  itemCode: string
  name: string
  unit: Unit
  quantityMilli: number
  buyingPricePaise: number
  valuePaise: number
  note: string
  by: string
}

export interface VendorPaymentRow {
  id: string
  paidOn: string
  amountPaise: number
  mode: VendorPaymentMode
  reference: string
  note: string
  by: string
  approved: boolean
  approvedBy: string
  approvedAt: string | null
  /** The order it was against, or null where it was against the running account. */
  purchaseOrderId: string | null
}

export interface VendorProfile {
  vendor: Vendor
  purchases: VendorPurchase[]
  payments: VendorPaymentRow[]
}

/** Something the shop buys and uses but never sells: butter, onions, oil, carrier bags. */
export interface RawMaterial {
  id: string
  code: string
  name: string
  unit: Unit
  inStoreMilli: number
  /** Tell me before it runs below this; 0 means nobody is watching it. */
  reorderLevelMilli: number
  active: boolean
  /** At or below the level the shop asked to be told about. */
  lowOnIt: boolean
}

export interface StockRow {
  productId: string
  sku: string
  name: string
  unit: Unit
  stockMilli: number
  sellingPricePaise: number
  mrpPaise: number
  stockValuePaise: number
  /** False for a stopped item, which the screen still shows, marked and dimmed. */
  active: boolean
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
  /** Taken away rather than eaten in: printed with a large P beside the UPI code. */
  parcel: boolean
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

/** Everything that has happened to one bill: the versions it went through, and what came back. */
export interface BillHistory {
  /** Oldest first; just the bill itself when it was never edited. */
  versions: Invoice[]
  /** Returns taken against any version, oldest first. */
  returns: CreditNote[]
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
  /** Ask which table the bill is for before anything is rung up. */
  askForTable: boolean
  /** Ask who is serving the table before anything is rung up. */
  askForSupplier: boolean
}

export interface TerminalView {
  /** The shop server's own id for this till: what a counter machine is told it is. */
  id: string
  code: string
  name: string
  section: string
  type: 'MASTER' | 'COUNTER'
  /** False for a till an admin has blocked; it stays listed because its code is on bills. */
  active: boolean
  /** The shop has more counters than its licence covers and this is one of the extras. */
  overLimit: boolean
  lastSeenAt: string | null
  /** Somebody is signed in on it and its screen has checked in within the last few minutes. */
  inUse: boolean
  /** Somebody is signed in, answering or not. This is what holds the till's place in the shop. */
  signedIn: boolean
  /** Who that is, or null when nobody is signed in. */
  staff: string | null
}

/** The shop's tills and how much room is left for another counter. */
export interface TillsView {
  /** What pos.clients.max allows; 0 means this till keeps to itself. */
  places: number
  /** Places a counter is signed in on. */
  used: number
  /** Places another counter could still take. */
  free: number
  /** How many counters the shop has, besides this server. */
  counters: number
  /** How many more it may create before its licence is at its limit. */
  roomForMore: number
  tills: TerminalView[]
}

export interface SettingsView {
  shop: ShopSettingsForm
  readyToInvoice: boolean
  missingForInvoicing: string[]
  /** False without a GSTIN: bills charge no tax. */
  gstRegistered: boolean
  thisTerminal: TerminalView
  terminals: TerminalView[]
  /** The build this till is running, e.g. "0.4.6", or "dev" when run from source. */
  version: string
}

export interface UserRow {
  id: string
  username: string
  displayName: string
  role: Role
  active: boolean
  mustChangePin: boolean
}

export type PrinterType = 'SERIAL' | 'TCP' | 'WINDOWS' | 'FILE'
export type FlowControl = 'NONE' | 'RTS_CTS' | 'DTR_DSR' | 'XON_XOFF'
export type CodePage = 'PC437' | 'PC850' | 'PC858' | 'WPC1252'
export type DrawerPin = 'PIN_2' | 'PIN_5'

/** This till's printer, as the printer panel edits it. One shape covering every type. */
export interface PrinterSettingsForm {
  type: PrinterType
  serialPort: string
  serialBaudRate: number
  serialFlowControl: FlowControl
  tcpHost: string
  tcpPort: number
  windowsPrinter: string
  windowsWidthMm: number
  columns: number
  codePage: CodePage
  drawerPin: DrawerPin
  drawerPulseMillis: number
  timeoutSeconds: number
}

export interface SerialPortInfo {
  systemName: string
  description: string
  details: string
}

export interface PrinterSetupView {
  printer: PrinterSettingsForm
  /** Where bills are going now, e.g. "WINDOWS Canon G3080 series". */
  target: string
  serialPorts: SerialPortInfo[]
  installedPrinters: string[]
}

/** One copy of the shop's database, sitting in the backups folder. */
export interface BackupFile {
  name: string
  sizeBytes: number
  takenAt: string
  /** True for the one this till will put back when it next starts. */
  restorePending: boolean
}

export interface DatabaseView {
  backups: BackupFile[]
  folder: string
  restoreFrom: string | null
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
  /** The same date twice for a single day; a stretch of them when a range was asked for. */
  from: string
  to: string
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
  /** Every item that sold, most sold first. The best sellers are the first few of these. */
  soldItems: SoldItem[]
}

export interface SoldItem {
  name: string
  quantityMilli: number
  amountPaise: number
  /** How many bill lines it appeared on. */
  lines: number
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

/** What the sign-in screen may still offer: a backup put back before anyone has signed in. */
export interface SetupView {
  /** False once a bill has been issued or the factory admin has picked its own PIN. */
  fresh: boolean
  candidates: BackupCandidate[]
  /** Already staged and waiting for the next start, if any. */
  restoreFrom: string | null
  lookedIn: string
}

export interface BackupCandidate {
  name: string
  sizeBytes: number
  takenAt: string
  folder: string
}