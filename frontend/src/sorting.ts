/**
 * How the Products and Stock lists are ordered. One definition for both screens: the fields, the
 * comparator and where the choice is remembered, so the two cannot drift apart.
 */

export type SortField = 'sku' | 'name' | 'stock' | 'price' | 'mrp' | 'status'
export type SortDir = 'asc' | 'desc'
export type Sort = { field: SortField; dir: SortDir }

/** The order the arrows walk, so Code is where the cursor starts. */
export const SORT_FIELDS: SortField[] = ['sku', 'name', 'stock', 'price', 'mrp', 'status']

export const SORT_LABELS: Record<SortField, string> = {
  sku: 'Code',
  name: 'Name',
  stock: 'Stock availability',
  price: 'Selling price',
  mrp: 'MRP',
  status: 'Status',
}

/**
 * Code order. It is how a shop knows its own catalogue, and it is the one field no two items can
 * share, so the list never changes under the cursor between two loads.
 */
export const DEFAULT_SORT: Sort = { field: 'sku', dir: 'asc' }

/** Per machine, beside masspos.shortcuts and masspos.screen. */
export const PRODUCT_SORT_KEY = 'masspos.productSort'
export const STOCK_SORT_KEY = 'masspos.stockSort'

/** What a row has to offer to be sortable, which both the catalogue and the stock list satisfy. */
export type Sortable = {
  sku: string
  name: string
  stockMilli: number
  sellingPricePaise: number
  mrpPaise: number
  active: boolean
}

/**
 * Numeric-aware, so 2 comes before 10 and PG-9 before PG-100. Plain string order puts "10" ahead
 * of "2", which reads as broken in a shop whose item codes are numbers.
 */
const collator = new Intl.Collator('en-IN', { numeric: true, sensitivity: 'base' })

function compareBy(field: SortField, left: Sortable, right: Sortable): number {
  switch (field) {
    case 'sku':
      return collator.compare(left.sku, right.sku)
    case 'name':
      return collator.compare(left.name, right.name)
    case 'stock':
      return left.stockMilli - right.stockMilli
    case 'price':
      return left.sellingPricePaise - right.sellingPricePaise
    case 'mrp':
      return left.mrpPaise - right.mrpPaise
    case 'status':
      // Ascending puts what is still being sold first.
      return Number(right.active) - Number(left.active)
  }
}

/**
 * A sorted copy. Ties fall back to the code, so two items at the same price keep a settled order
 * instead of swapping places every time the list reloads.
 */
export function sorted<T extends Sortable>(rows: T[], sort: Sort): T[] {
  const sign = sort.dir === 'asc' ? 1 : -1
  return [...rows].sort((left, right) => {
    const by = compareBy(sort.field, left, right) * sign
    return by !== 0 ? by : collator.compare(left.sku, right.sku)
  })
}

/** A field saved by an older build that no longer exists falls back to the default, never crashes. */
export function loadSort(key: string): Sort {
  try {
    const saved = localStorage.getItem(key)
    if (!saved) {
      return DEFAULT_SORT
    }
    const { field, dir } = JSON.parse(saved) as Partial<Sort>
    return {
      field: field && SORT_FIELDS.includes(field) ? field : DEFAULT_SORT.field,
      dir: dir === 'desc' ? 'desc' : 'asc',
    }
  } catch {
    return DEFAULT_SORT
  }
}

export function saveSort(key: string, sort: Sort) {
  try {
    localStorage.setItem(key, JSON.stringify(sort))
  } catch {
    // Not fatal: the screen falls back to code order next time.
  }
}
