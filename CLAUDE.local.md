# Mass POS: local engineering notes

This file holds engineering context: invariants, gotchas and tuning. The user-facing overview,
**configuration reference**, printer setup, the API and the **installer build commands** are in
[README.md](README.md).

## Status

| Phase | Scope | State |
|---|---|---|
| 1 | pom, core entities (UUIDv7, Envers), SQLite/WAL config, append-only guards | Done |
| 2 | ESC/POS over jSerialComm / TCP / file, cash drawer, printer status, Rule 46 receipt, local API guard, per-till seller settings (`pos.company`) with an invoice snapshot | Done: 72 tests green |
| 3A | RBAC (no-expiry tokens, forced first PIN change, roles, user admin), terminal/floor registry, GST calculator | Done: 110 tests green |
| 3B | Products + stock APIs, sale transaction (split payments, round-off, stock movements), held bills, cancellation | Done: 110 tests green |
| 3C | React UI: login, billing with configurable shortcuts, products, stock, bills, reports, staff, settings | Done: driven end to end in the browser |
| 3D | Reports: day takings, GST rate-wise + HSN (GSTR-1 prep), audit-trail viewer | Done |
| 3E | Shop settings moved into the database (`shop_settings`), editable on screen, seeded once from `pos.company.*` | Done |
| 3F | Readable field errors (`errors` map), non-GST shops, optional HSN/CIN, grid billing screen with Space bill, one-line header with slide-in menu, compact footer, held-bill keys | Done: 127 tests green, driven in the browser |
| 3G | Edit today's bill (cancel + reissue), returns as credit notes with refunds, reports net of returns, held bills newest first, Esc Esc back to billing | Done: 139 tests green, driven in the browser |
| 4 | Counter → master sync over LAN (see [docs/multi-terminal-and-rbac.md](docs/multi-terminal-and-rbac.md)) | Designed, not built |
| Later | Flyway migrations, cloud sync, composition scheme / bill of supply, desktop shell | Not started |

## UI notes (frontend/)

- Vite + React 19 + TypeScript + Tailwind 4, built by the `frontend` Maven profile straight into
  `src/main/resources/static`. The profile self-activates once `frontend/package.json` exists and
  installs its own Node 22 under `target/`, so a build box needs only a JDK.
- **The `node` on PATH here is v16, too old for Vite 6.** Use the Maven build, or `npm run dev`
  with a newer Node.
- No router: the shell keeps a `screen` state. One page is served, so no SPA fallback is needed.
- **Billing stays mounted** (inside a `hidden` div) while other screens show, so the bill on screen
  and the last bill survive navigation. Its keys must therefore be gated: `active` (screen shown)
  and `blocked` (menu open) → `inFront`. Anything global it adds must respect `inFront`.
- **Header slot**: Billing portals its bill type, customer and last bill into a `<div>` in the App
  header (`headerSlot`). The clock is its own component so its tick never re-renders the bill.
- Shortcut map and the "show shortcut list" flag (`masspos.showShortcuts`) are per machine in
  `localStorage`; Billing re-reads both each time it comes back in front.
- Every modal must take focus when it opens (autofocus input, button or list): a grid cell that
  keeps focus behind a modal would still receive arrows, Enter and Space.
- **The screen never computes tax.** `POST /api/sales/quote` prices the cart with the same code
  that issues the bill; duplicating the rounding rules in TypeScript would drift.
- Keyboard map lives in `localStorage` (`masspos.shortcuts`), editable on the Settings screen.
  Function keys, Insert, Escape and Space are bound; Space never fires inside a name being typed.
- Billing has a `mode` (sale / edit / return). In a return against a bill only the qty cell is
  editable (others `readOnly`, still focusable for arrows) and the green row is hidden: use
  `focusEntry()`, never `requestFocus(lines.length, 'code')`. Quotes go to `/api/returns/quote` then.
- The in-app browser pane does not activate a focused button on Enter: dialogs handle Enter in
  `onKeyDown` themselves (AskModal, RefundDialog, AutoFocusButton, the menu drawer).
- Money is formatted in `api.ts` (`rupees`, `quantity`, `percent`, `rupeesForInput`) with Indian
  grouping; never format money inside a component.
- **Repeats fold by code + rate, neighbours only** (`foldIntoRowAbove`). Only the newest row folds,
  and only when the cursor leaves it (`move`, `onCellFocus`) or the bill is taken/held
  (`foldNewest` after `commitEdit`), never while it is being filled in. Never key on name or code
  alone; rows from a bill being returned (`originalLineNo`) never fold. Discounts add up.
- **Billing is a grid** (`Billing.tsx`): each row's code/name cells look items up (list rendered
  `fixed` so the grid's scroll never clips it), rate/qty/disc cells take numbers. Handlers read
  `linesRef`/`editRef`, not render state: a key can change the bill and move on before React
  renders. `commitEdit()` returns the committed bill or null when a value is refused.
- **Space opens the payment screen** (`takeBill`; the separate F9 action is gone, and unknown saved
  shortcut keys are dropped on load) unless a code/name is being typed. `PaymentDialog` stacks
  the modes with arrow keys; `masspos.askCashReceived` (Settings) makes an empty cash amount prompt
  instead of meaning the exact total. `busy` ref guards every
  sale path: without it a second Space issues a second invoice. The total is re-quoted just before
  selling, never taken from a possibly stale `quote`.
- The global shortcut hook ignores printable keys inside text fields; the grid handles Space itself
  and calls `preventDefault`, which the global hook respects.
- **Bills screen is keyboard-first** (`Invoices.tsx`): the list `div` holds focus (`role=grid`) and
  handles arrows/Enter; row actions come from `actionsFor`, which gives each action its blocked
  reason, and ←/→ skip blocked ones. F2 (window listener, off while a dialog is open) focuses the
  search; Esc there calls `stopPropagation` so it never counts toward Esc Esc. Dialogs return focus to
  the list on close. Edit chains come from `GET /api/invoices/{id}/history` (walks
  `replacesInvoiceNumber` back and `findFirstByReplacesInvoiceNumber` forward).
- **Forms show server field errors**: `ApiError.fieldErrors` + `errorFor()` + `<Field error>`.
  Keep new forms on that pattern rather than toasting the message.
- The in-app browser pane cannot send a real Space keydown or edit text with Backspace; test those
  by dispatching a `KeyboardEvent` and with `form_input`.

## Slice 3A/3B notes

- **Auth**: opaque token (SHA-256 stored), never expires, revoked on logout; `AuthFilter` (order 20,
  after `LocalApiGuard`) binds both `AuthContext` and `AuditContext`. A user with `mustChangePin`
  can reach only change-pin, logout and me. `@RequiresRole` + `RoleInterceptor`; ADMIN always passes.
- **Sale**: one transaction covers sequence, invoice, payments and SALE ledger events; printing is
  after commit and its failure is reported in `SaleResponse.printed`, never rolled back.
- **Services return views, not entities.** `SaleService` maps to `InvoiceView` inside the
  transaction: returning a detached `Invoice` blew up on the lazy payments collection (caught by
  `SaleApiIntegrationTest`). Keep that rule for every new service.
- **Held bills** are not invoices: no number, no stock effect, deleted on resume.
- **Test accounts**: `TestAccounts.tokenFor(auth, username, role)` creates a user and completes the
  forced PIN change, returning a usable bearer token.

## Stack (pinned)

| Component | Version | Notes |
|---|---|---|
| Java | 21 LTS | Build JDK and bundled runtime. JDK 21 is installed here at `C:\Program Files\Java\jdk-21.0.12`: set `JAVA_HOME` to it and build normally (verified 2026-09-13: 126 tests, BUILD SUCCESS). For a backend-only run, skip the UI build with `-P '!frontend'`. The `java` on PATH is 18; only that fallback needs `-Djava.version=17`. |
| Spring Boot | 3.5.16 | Last 3.x line. Plan the Boot 4 / Hibernate 7 move. |
| Hibernate ORM + Envers + community dialects | 6.6.53.Final | Managed by Boot |
| sqlite-jdbc | 3.53.4.0 | Overrides Boot's 3.49.1.0 |
| jSerialComm | 2.11.4 | |
| Maven | 3.9.16 | Through `mvnw` / `mvnw.cmd` |

## Project structure

```
src/main/java/com/masspos/
├── PosApplication.java
├── config/              PosProperties (pos.data-dir, pos.terminal), SqliteDataSourceConfig,
│                        FirstRunSetup (writes pos.properties template, warns on missing seller data)
├── common/              IndiaTime (Asia/Kolkata)
├── common/persistence/  BaseEntity, UuidV7 + @GeneratedUuidV7 + UuidV7IdGenerator, PosSqliteDialect, LocalDateIsoConverter
├── audit/               AuditRevision, AuditRevisionListener, AuditActor / AuditContext, AuditTrailGuard (triggers)
├── user/ catalog/       User, UserRole / Product, UnitOfMeasure
├── billing/             Invoice, InvoiceItem, InvoiceSequence, InvoiceNumbers, InvoiceStatus, GstStates,
│                        CompanyProperties (pos.company), SellerDetails (@Embeddable snapshot), Gstin + @ValidGstin
├── inventory/           InventoryLedgerEvent, LedgerEventType
├── hardware/            EscPos, CodePage, PrinterConnection (+ Serial / Tcp / File), PrinterConfig,
│                        PrinterProperties (pos.printer), PrinterStatus, ThermalPrinter, HardwareController
├── receipt/             ReceiptFormatter, ReceiptProperties (pos.receipt), ReceiptService, ReceiptController, ReceiptCopy, Amounts
└── web/                 LocalApiGuard (Host + X-POS-Client), ApiExceptionHandler (ProblemDetail)
src/main/resources/      application.properties (defaults), pos-template.properties (copied to <data-dir>/pos.properties)
src/test/java/com/masspos/  TestFixtures, PersistenceIntegrationTest, HardwareApiIntegrationTest, + unit tests per package
```

Runtime data lives in `pos.data-dir`: `%LOCALAPPDATA%\MassPOS` on Windows, `~/MassPOS` elsewhere,
overridable with `POS_DATA_DIR`. It holds:

- `pos.db` and its WAL files
- `pos.properties`: per-till settings, loaded through `spring.config.import`; a template is written
  on first start
- `logs/`
- `printer-out/`: FILE printer output

## Configuration model

- **Layers**, where later wins: `application.properties` in the jar, then
  `<data-dir>/pos.properties`, then environment variables.
- **Groups:**

  | Prefix | Holds | Bound by |
  |---|---|---|
  | `pos.terminal` | terminal code | `PosProperties` |
  | `pos.company` | seller | `CompanyProperties` |
  | `pos.printer` | printer settings | `PrinterProperties` |
  | `pos.receipt` | footer | `ReceiptProperties` |

- **A new setting** needs three things:
  - a record component
  - an empty or default entry in `application.properties`
  - a commented, explained entry in `pos-template.properties`
- **Validation policy:**
  - Anything that is set must be valid; `@Validated` fails startup and names the property.
  - Anything the till needs only for some operations may be missing at startup. Check it when it
    is used, e.g. `SellerDetails.from()` refuses to snapshot an incomplete seller, and
    `FirstRunSetup` warns.
  - Hardware settings never fail startup.
- **`.properties` files are read as ISO-8859-1** (Boot's `OriginTrackedPropertiesLoader`). The
  template tells store owners to stay ASCII.
- **Lists** (`address[n]`, `footer[n]`) bind from the highest-precedence source that defines them.
  So `pos.receipt.footer[0]` in `pos.properties` replaces the default footer entirely.

## Invariants: do not break these

### Data and compliance

- **Money** is `long` paise, **quantity** is `long` milli-units, and **rates** are `int` basis
  points. Never use `double`/`float`/`BigDecimal` columns.
- **Ids.** Every entity extends `BaseEntity`, so its key is a UUIDv7 stored as text. A
  pre-assigned id (from sync) is kept. New vs existing is decided by a null `@Version`.
- **Audit.** Every entity is `@Audited`. Envers can never be disabled: `AuditTrailGuard` refuses to
  start. Never use `ValidityAuditStrategy`.
- **Append-only**, enforced by SQLite triggers recreated on every start:
  - `*_aud`, `audit_revision`, `inventory_ledger_event`, `invoice_item`, `invoice_payment`,
    `credit_note`, `credit_note_item` and `credit_note_refund`: no UPDATE, no DELETE.
  - `invoice`: no DELETE; after issue only cancellation.
  - `invoice_sequence` and `credit_note_sequence`: can only move forward.
- **Editing a bill = cancel + reissue** (`SaleService.replace`), today's bills only, manager only.
  Reserve the new number *before* cancelling: a cancelled row is frozen by the trigger, so its
  reason ("Edited, replaced by …") cannot be written afterwards. Paid amounts carry over in order;
  only the difference is collected (request `payments`) or refunded (`refundPaise`).
- **Returns are credit notes** (`CreditNote*`, `ReturnService`), own series `T1-2627-R0001`, manager
  only. Against a bill, each line's refund is a pro-rata share of that bill line, and the line's
  last return takes the exact remainder, so returns never exceed the bill. A bill with any credit
  note against it can no longer be edited or cancelled (`SaleService.requireNoReturns`).
- **Reports net returns**: day totals, payments per mode, cashiers and tills subtract credit notes;
  the GST summary nets them off rates and HSN. `DayReport.totals` is the net figure.
- `Invoice` and `CreditNote` implement `TaxDocument` (`InvoiceItem`/`CreditNoteItem`: `TaxLine`), which
  is what `ReceiptFormatter` lays out.
  - A new immutable table, or a new fiscal or seller column on `Invoice`, must also be added to
    `AuditTrailGuard.FROZEN_INVOICE_COLUMNS`.
- **Stock** is `SUM(quantity_delta_milli)` from the ledger. Never add a stock column.
- **Invoices.** Build the invoice fully, then persist it once, in the same transaction as
  `InvoiceSequence.next()` and the SALE ledger events. There are no drafts.
- **The seller is a snapshot.** Invoices get `SellerDetails.from(companyProperties)` at issue time.
  Receipts print legal identity from `invoice.getSeller()`, never from `CompanyProperties`. Only
  contact lines, UPI ID and footer come from current settings at print time.
- **GSTINs** (seller, and buyer on invoices) must pass `@ValidGstin`, which checks format plus the
  mod-36 check character. The seller's state code is derived from the GSTIN and is never
  configured separately.
- **The seller GSTIN is optional.** No GSTIN = not GST-registered: every line is priced at 0% GST
  and 0 cess whatever the product says, place of supply is empty, a buyer GSTIN is refused, and
  the receipt prints "BILL" without GSTIN/tax/rate summary/place of supply. Stored as empty strings
  (`seller_gstin`, `seller_state_code`, `place_of_supply`), never null, so the NOT NULL columns of
  existing databases still accept them. `Invoice.isTaxInvoice()` is the switch.
- **HSN is optional**, stored as `""`. Receipts omit "HSN" when blank; the HSN report shows "Not set".
- **Validation errors** go through `ApiExceptionHandler`: every refusal carries a sentence-style
  `detail` and, for forms, `errors` (field path → message). Default constraint messages live in
  `ValidationMessages.properties` and read after a label from `FieldLabels`. Checks a validator
  cannot make (duplicate barcode, price above MRP) throw `FieldRejectedException(field, message)`.
- **Controllers that modify a loaded entity need `@Transactional`**: open-in-view is off, so an
  entity from `findById` is detached and edits to it are silently dropped (product edit and
  deactivate were broken this way).
- **Invoice numbers** follow `{terminal}-{FY}-{seq:05d}` and are ≤16 chars, so the terminal code is
  ≤3 chars.
- **Dates.** Business dates and times use `IndiaTime.ZONE`, never the JVM default.
- **Sync bookkeeping stays off audited entities.** Use an unaudited outbox table.

### Hardware

- **No transaction spans printer or network I/O.** Render in a short read transaction, then submit
  to `ThermalPrinter` (`ReceiptService` is the pattern).
- **All printer traffic goes through `ThermalPrinter`'s single worker thread.**
- **Hardware can never block startup or a sale.** A print failure is an HTTP 503 after the commit.
- **No automatic retry of a failed job.** The user reprints as DUPLICATE.
- **All text goes through `EscPos.text()`**, which blanks control characters and maps ₹ to "Rs.".
- **The drawer opens only with the ORIGINAL receipt** (`ReceiptController` 400 + `ReceiptService`).
- **Every state-changing `/api` call needs the `X-POS-Client` header**, and Host must be a loopback
  name.
- **Serial status reads must not call `flushIOBuffers()`**, which purges the unsent tail of the
  previous job.

## SQLite notes

- The URL pragmas (`WAL`, `synchronous=FULL`, `transaction_mode=IMMEDIATE`, `foreign_keys=true`,
  `busy_timeout`) are explained in `application.properties` and tested.
- `synchronous=FULL` is a deliberate durability choice: power cuts are routine in Indian retail.
- `transaction_mode=IMMEDIATE` makes gapless numbering and concurrent writers safe.
- Stock `SQLiteDialect` silently drops all foreign keys. `PosSqliteDialect` declares them inline,
  but only for tables Hibernate creates.
- **`jdbc_metadata_extraction_strategy=individually` is required.** The default (grouped) makes
  sqlite-jdbc's `getColumns` one UNION ALL term per column of every table; SQLite allows 500. Past
  500 columns (the credit note tables took it to 600) the app starts once on a new database and
  never again. `RestartOnExistingDatabaseTest` starts the app twice on one data dir to guard it.
- `ddl-auto=update` is for development only. Delete a local dev `pos.db` after entity changes, such
  as the new `seller_*` columns: SQLite cannot add NOT NULL columns to a table that has rows. Move
  to Flyway + `validate` before the first real install.
- The startup warning `HHH90006001 ... preferred_uuid_jdbc_type` is expected.

## Hardware notes

- **jSerialComm native library.** It is extracted to `java.io.tmpdir/jSerialComm/<version>`, or
  `~/.jSerialComm/...` as a fallback. `-DjSerialComm.library.path` only loads a library already in
  that directory; it does not extract there. If antivirus interferes, unpack the library at
  package time into the app image and pass `-DjSerialComm.library.path=$APPDIR/native`.
- **USB printer-class devices** (Windows `USB001`) need the vendor's virtual COM driver or a LAN
  printer.
- **Printer status contract:** a port or host that cannot be opened throws `PrinterException`
  (HTTP 503 with the reason). Open but silent returns `responding: false`.
- **`TcpPrinterConnection`** connects per job. On Windows a refused localhost connect takes about
  2 s.

## JVM tuning (G1GC, low latency)

The flags themselves are in the README's jpackage command.

| Flag | Why |
|---|---|
| `-XX:+UseG1GC` | Set explicitly: on <2 CPUs or <1792 MB RAM the JVM silently picks SerialGC. |
| `-Xms512m -Xmx512m` | A fixed heap means no resize pauses. |
| `-XX:+AlwaysPreTouch` | Faults the heap in at startup instead of during the first bills. |
| `-XX:MaxGCPauseMillis=20` | Achievable on a 512 MB heap. |
| `-XX:+UseStringDeduplication` | Product names, SKUs and HSN codes repeat heavily. |
| `-XX:+ExitOnOutOfMemoryError` | Fail fast and restart rather than limp on mid-sale. |

Low-memory tills (2 GB RAM): use `-Xms256m -Xmx256m` and drop `AlwaysPreTouch`.

Measured on the dev box (JDK 18, jlinked runtime):

| Metric | Value |
|---|---|
| Runtime image | 45 MB |
| App image | 110 MB |
| Startup | about 6 s |
| Heap used after start | 35 MB |
| Metaspace | 73 MB |
| RSS | 716 MB |

Re-measure under a billing load test.

## Open decisions and known gaps

- **JSON old/new state**: Envers snapshots plus `_mod` flags. A JSON projection is planned in the
  audit-report API. Pending sign-off.
- **Unregistered sellers** now bill without tax as a plain "BILL". **Composition-scheme** sellers
  (registered, but may not collect tax) still need a *bill of supply* with its mandatory
  declaration; add a GST scheme setting for that.
- **"No sale" drawer openings are only logged.** Persist them once login exists.
- **The drawer kick is chosen by the caller.** Derive it from a cash tender in Phase 3.
- **No status pre-check before printing.**
- **Indic scripts** need raster printing.
- **FK enforcement** exists only on tables Hibernate created. Fixed by the Flyway move.
- **Not yet exercised on real hardware**: serial and TCP printing (fakes and error paths only). Also
  untested: the `frontend` profile and the WiX/DMG installers.
