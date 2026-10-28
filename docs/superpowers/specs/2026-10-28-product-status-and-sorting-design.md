# Product status, delete and sorting

Design, 2026-10-28. Covers the Products and Stock screens and the catalogue API behind them.

## The problem

Stop on the Products screen calls `POST /api/products/{id}/deactivate`, which sets `active = false`.
Both lists then filter the item out - `ProductRepository.findByActiveTrueOrderByNameAsc` for the
catalogue, `Product::isActive` in `InventoryController.stock()` - so stopping an item hides it from
the two screens a manager would use to find it again. There is no way back from the screen, no way
to remove an item that was added by mistake, and no way to reuse the code it took.

Neither list can be sorted. Both are name-ordered by the server, and the catalogue is capped at 25
rows, so a shop with four hundred items cannot answer "what is out of stock" or "what is dearest"
from the screen at all.

## What Stop, Start and Delete mean

| Action | Effect | Role |
|---|---|---|
| Stop | `active = false`. Stays on Products and Stock, styled as inactive. Out of billing search. | MANAGER |
| Start | `active = true`. Normal styling, billable again. | MANAGER |
| Delete | `deleted = true`, `active = false`, `sku` rewritten to a tombstone, `barcode` cleared. Gone from Products, Stock, billing search and the catalogue CSV. The code and barcode are free to use again. | MANAGER |

### Why Delete does not delete the row

`InvoiceItem.product`, `CreditNoteItem.product` and `InventoryLedgerEvent.product` are all foreign
keys to `product`, and all three tables are append-only: `AuditTrailGuard` recreates triggers on
every start that refuse UPDATE and DELETE on them. The URL pragmas set `foreign_keys=true`. So for
an item that has ever been sold, returned or stock-counted:

- `DELETE FROM product` fails on the foreign key, because the referencing rows cannot be removed;
- nulling `invoice_item.product_id` to release it fails on the no-UPDATE trigger.

Deleting for real would mean dropping the triggers that make the audit trail worth having, and
losing the stock history and the link from a bill line to its item. The tombstone gets every
outcome asked for - gone from every screen, code immediately reusable, not undoable - while the
bill that sold it still prints the code it was sold under, because invoice lines carry their own
copy of the code, name, rate and tax.

The tombstone sku is `~` followed by the product's UUID: 37 characters, inside the `@Size(max = 40)`
limit, unique by construction, `@NotBlank` satisfied, and it sorts to the end of any list it leaks
into. `name` is left alone so the audit trail and `product_aud` still read as the item they were.

## Data model

One new column on `product`:

```java
/** Removed by a manager. The row stays for the bills and stock movements that point at it. */
@Column(nullable = false, columnDefinition = "boolean default 0")
private boolean deleted;
```

The `columnDefinition` default is required: SQLite refuses `ALTER TABLE ... ADD COLUMN ... NOT NULL`
on a populated table, which is every till in the field.
`RestartOnExistingDatabaseTest.aNewColumnReachesATableThatAlreadyHasRows` is the guard.

No new enum value, no new unique constraint and no CHECK change, so an existing database takes the
upgrade as it stands. `Product` gains `activate()` and `delete()` beside the existing `deactivate()`.

## API

| Endpoint | Change |
|---|---|
| `GET /api/products?q=` | Unchanged for billing: ranked, capped at 25, active only. Now also excludes deleted. |
| `GET /api/products?all=true` | New. The whole non-deleted catalogue, active and inactive, code order, no cap. |
| `POST /api/products/{id}/activate` | New. MANAGER. |
| `DELETE /api/products/{id}` | New. MANAGER. Tombstones. |
| `GET /api/stock` | Filters on `!deleted` rather than `active`. `StockRow` gains `active` and `mrpPaise`. |
| `GET /api/products/export` | Unchanged: active only. |

`findBySkuIgnoreCase` and `findByBarcode` gain `and deleted = false`. That is what frees a deleted
code: `ProductController.checkCode` stops seeing it, and a CSV import creates a fresh item under it
rather than resurrecting the old one.

## Products screen

### Columns

Code becomes its own first column. Today the first cell stacks the name over `sku . barcode` in
small grey text, which is why there is nothing to sort or scan by code.

| Column | Width | Holds |
|---|---|---|
| Code | fixed, 8rem | `sku`, and `barcode` beneath it in small text when present |
| Item | flexible - takes whatever is left | `name`, truncated with the full name on hover |
| HSN, GST, Price, MRP, Stock, actions | fixed, as now | unchanged |

The barcode moves under the code rather than under the name: it is the other code that bills the
item, so it belongs with the code.

The table stays `table-fixed`. Its `<col>` for Item carries no width, so Item absorbs all the space
the fixed columns leave - that is the "auto adjust" - and it keeps `truncate` + `title`, so a
150-character name still cannot push the columns off screen. A genuinely auto-sized table
(`table-auto`) would let one long name do exactly that, which is the problem the fixed layout was
put there to solve. If the intent was instead that the column grow to fit the longest name, say so
and it can be revisited, but the truncation should stay.

### Rows and actions

Row actions become three - Edit, Stop/Start, Delete - with the left and right arrows walking them,
the way Stock's three already work. The middle one reads Stop and is rose for an active item, Start
and sky for an inactive one.

An inactive row is `bg-slate-50` with muted text and an amber `Inactive` chip beside the name. It is
dimmed, not hidden, and still fully keyboard-reachable.

### Loading and filtering

The screen loads `?all=true` once and filters client-side on name, code, barcode and HSN - the same
fields the server matches, minus the closest-match ranking. The ranking is for a cashier typing two
characters at the billing grid; a manager browsing an explicitly sorted catalogue does not need it,
and sorting a server-capped 25 rows would sort the wrong 25. Billing's ranked search is untouched.

### Delete confirmation

```
Are you sure you want to delete this product?

This action cannot be undone.
Bills already taken keep it. The code PG-100 becomes free to use again.

                                           [Cancel]  [Delete]
```

The first two lines are the agreed wording. The third is there because every other confirm dialog on
these screens says what survives, and a manager deleting an item that has been sold needs to know
the bills are safe. Delete is the focused button, so Enter alone finishes, like `ConfirmButton` today.

## Stock screen

Same inactive styling and chip. Inactive items stay in the item count and the stock value: the goods
are still on the shelf, and leaving them out would make the figure wrong. Stop, Start and Delete are
not offered here - they belong to the catalogue.

## Sorting

### Fields and default

Code, Name, Stock availability, Selling price, MRP, Status. Each sorts ascending or descending.

**The default is Code ascending**, on both screens, for a till that has never chosen. Code order is
how the shop knows its catalogue, and it is stable - two items can share a name, never a code.

Code and Name compare through `Intl.Collator('en-IN', { numeric: true, sensitivity: 'base' })`, so
`2` comes before `10` and `PG-9` before `PG-100`. Comparing as plain strings would put `10` before
`2`, which reads as broken to anyone whose codes are numbers. Status sorts active first ascending.

### Where it is remembered

`localStorage`, per machine, under `masspos.productSort` and `masspos.stockSort`, each holding
`{ field, dir }` - alongside `masspos.shortcuts`, `masspos.screen` and `masspos.askCashReceived`.
Reads and writes are wrapped in try/catch like the rest, and a saved field that no longer exists
falls back to the default, the way `loadShortcuts` drops unknown keys.

### The control

A new `SortMenu` component: a popover that takes focus when it opens. It has to - a grid cell that
kept focus behind it would still receive the arrows.

- **F4** opens it on both screens. F2 is search, F3 is add on Products, F5 reloads, F9 is the menu.
- Up and Down move between fields.
- Left sets Ascending, Right sets Descending.
- Enter closes it and returns focus to the list.
- Esc closes it and returns focus to the list.

Every keypress re-sorts the list immediately and saves, so there is no pending state: Enter and Esc
differ in nothing but which key the person reached for. That is deliberate - a sort that previewed
and then reverted on Esc would be the only control on these screens that did.

F4 is a screen key through `useScreenKeys`, not one of the billing `ASSIGNABLE_KEYS`, so it is not
remappable in Settings and does not collide with the bill actions.

### Where the code lives

`frontend/src/sorting.ts` holds the fields, their labels, the comparator and the load/save helpers,
used by both screens. `frontend/src/components/SortMenu.tsx` holds the popover. Products.tsx is
already 557 lines and Stock.tsx 334; putting the shared parts in their own module keeps one
definition of what sorting means rather than two that drift.

## Testing

A new `ProductLifecycleApiIntegrationTest`, beside the other API tests:

- stopping an item keeps it in `?all=true` and in `/api/stock`, and takes it out of `?q=`;
- starting it puts it back into `?q=`;
- deleting it takes it out of all three and out of the CSV export;
- a new product can be created under a deleted item's code and barcode;
- the bill that sold a deleted item still reads its original code;
- the ledger sum for a deleted item is unchanged, and stock for the reused code starts at zero.

`RestartOnExistingDatabaseTest` is run to prove `deleted` reaches a populated table.

The frontend has no test runner - `build` is `tsc --noEmit && vite build` - so the screens get the
type-check, the full Maven build with the `frontend` profile, and a drive in the browser: stop,
start, delete, the code column, and the sort menu's keys on both screens.

## Not in scope

- Sorting the billing grid's item lookup. It is ranked by closeness on purpose.
- Including inactive items in the catalogue CSV export, or reactivating through import.
- A shop-wide sort shared by every till. The choice is per machine, like the keyboard map.
