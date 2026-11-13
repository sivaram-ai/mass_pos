# GO RockIt: local engineering notes

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
| 3H | `WINDOWS` printer type: tills with no thermal printer bill through any installed driver printer, receipt-width on the sheet, QR drawn by the till | Done: 199 tests green, printed on a real Canon G3080 |
| 3I | Printer chosen on screen (Settings > Printer): per-till `printer_settings` row seeded from `pos.properties`, device lists from the machine, connection swapped on the next job | Done |
| 3J | A stopped item stays on Products and Stock, marked; Delete tombstones it and frees its code; both lists sort by code, name, stock, price, MRP or status, remembered per machine | Done: 270 tests green, driven in the browser |
| 3K | Shop-wide theme: `theme_settings` row (active theme + saved library), 8 designed presets + the original as Default, admin-only builder with live preview, WCAG checks enforced on the server, import/export, counters follow within ~4 s | Done: driven in the browser |
| 3L | Tills in this shop say who is billing on them and whether anyone is; an item the shop will not sell without stock is marked on the bill row as it is rung up; a bill and a return each open on the same story - the bill, its versions, and everything that came back off it, with the opened one named and ringed | Done: 347 tests green, driven in the browser |
| 3M | A counter's place is held by a live sign-in, not by having once said hello: the cap is checked at every sign-in, logging out frees a place at once, the settings window will not let anybody press "Save and start billing" until the shop has a place for this counter, the counter that loses the race for the last one is handed back to its own settings window, and clicking the shortcut twice no longer leaves two counter processes running. Logging out frees the place there and then: a counter signing in ends whatever session was open on it, an admin can sign a till out from the tills panel, and a counter turned away watches for a place to come free | Done: 365 tests green, server side driven over the LAN; the Swing settings window itself is covered by tests, not driven on screen |
| 3N | The shop owns its counters: an admin adds them on the server (number fixed at creation, name editable, block/unblock), and a counter machine chooses which of them it is from the list the server offers. A machine signs in with that till's id, never a number it typed. The counter settings window asks two questions and no longer sets a printer; a new counter saves its bills to a file on its own machine until somebody picks a printer in the app | Done: 368 tests green, driven over the LAN; the Swing window is covered by tests, not driven on screen |
| 3O | `pos.clients.max` caps how many counters a shop may have, not only how many may bill at once: creating one past the limit is refused with the licence message, Add a counter goes grey, and a counter beyond the limit (a licence cut below the counters a shop already has) is red and marked "Licence limit exceeded", as blocked ones now are | Done: 368 tests green, driven over the LAN and in the browser |
| L1 | Offline subscription licence per till: Ed25519-signed key bound to a hardware machine ID, warn/grace days, billing lock, Licence screen + banner, vendor `tools/license/LicenseTool.java` | Done: 172 tests green, real key verified on this PC |
| 5A | Windows installer: jpackage + WiX 3.14, trimmed JDK 21 runtime, one file, `tools/build-installer.ps1` | Done: installed and run from the exe on this PC |
| 5B | One folder per till, `%LOCALAPPDATA%\GoRockitPOS`, data in `data\`, with an automatic move from the old `MassPOS` folder | Done: 235 tests green, this PC's own till migrated |
| 5C | Desktop shell: the shortcut starts the server when it is down, waits for it, and opens the screen in an Edge `--app` window | Done: driven from the installed shortcut |
| 5D | A copy of the database, settings and licence outside the till's folder at every start and clean shutdown | Done: survived an uninstall that removed the program |
| 5E | Hotel billing: suppliers (who served) in their own table, table number asked once per bill when Settings says so, both frozen on the invoice and printed above the bill number; takings by the hour | Done: 256 tests green, driven in the browser |
| 4 | Counter → master sync over LAN (see [docs/multi-terminal-and-rbac.md](docs/multi-terminal-and-rbac.md)) | Designed, not built |
| Later | Flyway migrations, cloud sync, composition scheme / bill of supply, periodic offsite copies, tray icon with Quit, single instance | Not started |

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
- **A mark on a bill row goes in the name column, never the quantity column.** The "low stock" mark
  (an item at or below zero that the shop will not bill without stock, taken from the catalogue at
  `pick` time and kept on the row as `lowStock`) first went beside the unit in the qty cell, where
  the cell is `w-24`, the input is `w-full` inside a flex row, and a 56-px chip squeezed the
  quantity box to nothing - the cashier could not see or type the quantity. Name is the one
  flexible column. Its wrapper `div` is rendered whether or not there is a mark, so React never
  tears the input out and rebuilds it under the cursor when an item is replaced on a row.
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
- **`blockBrowserKeys()`** (`shortcuts.ts`, installed in `main.tsx`) cancels the browser's meaning of
  F1–F12, Ctrl+letter (except editing keys and Ctrl+Shift+C/I/J), Ctrl+1–9, Alt+letter/Left/Right/Home
  and the right-click menu outside text boxes, in the capture phase on window, before any handler.
  So **`defaultPrevented` is always true for a function key**: never use it to tell whether another
  handler took one (`useShortcuts` ignores it for function keys).
- **Screen keys**: `useScreenKeys` (shortcuts.ts) binds a screen's own F-keys while it is open —
  F2 its search box, F3/F4 what it does next (Bills: day, new return; Products: add). `KeyBox`
  (ui.tsx) draws the key inside the box. Pass `!dialogOpen` so they do nothing behind a dialog.
- **Refreshing is a page reload, and the screen survives it.** `App` keeps the current screen in
  `localStorage` (`masspos.screen`) and comes back to it, instead of dropping a manager reading a
  report back onto the billing grid. The header has a Refresh button on every screen; **F5 reloads
  everywhere except billing**, where it has long been "remove this line" and the cart is live.
- **Esc Esc has two jobs.** From another screen, App brings you back to billing. On the billing
  screen App installs no handler at all, so Billing has its own: it puts the cursor back on the
  green row after a click has taken focus off the grid. Both ignore an Esc a dialog is using.
- **F1 on the payment screen marks a parcel.** It is also the grid's customer key, which is safe
  only because `useShortcuts` is passed `inFront && !modalOpen` - keep that guard.
- **F9 opens the menu everywhere** (`MENU_KEY`, handled in `App`), so it is deliberately absent from
  `ASSIGNABLE_KEYS`; `isModalOpen()` (a counter kept by `Modal`) stops it opening behind a dialog.
- **Sorting is one module for both lists** (`sorting.ts`: the six fields, the comparator, and the
  per-machine `masspos.productSort` / `masspos.stockSort`), with `SortMenu` as a popover that *takes
  focus* - a grid cell that kept focus behind it would still receive the arrows. F4 opens it on both
  screens; ↑↓ pick the field, ←→ the direction, Enter and Esc both close and hand focus back, because
  every key applies at once and there is no pending state to discard. Its Esc calls `stopPropagation`,
  so it never counts toward Esc Esc back to billing. Code and name compare through an `Intl.Collator`
  with `numeric: true`, or "10" sorts before "2" in a shop whose codes are numbers.
- **The Products list is the whole catalogue, filtered and sorted on screen** (`?all=true`), stopped
  items included. `?q=` stays as it was for the billing grid - ranked by closeness, capped at 25,
  sellable only - because sorting a server-capped 25 rows would sort the wrong 25.
- **Products and Stock share one list pattern** (and Bills a close cousin): a focused `div role=grid`, arrows for row and action,
  Enter to run, dialogs returning focus to the list. Products' table is `table-fixed` with a colgroup
  and `truncate` + `title` on the name, so a 150-character name cannot push the columns off screen.
- **Bills screen is keyboard-first** (`Invoices.tsx`): the list `div` holds focus (`role=grid`) and
  handles arrows/Enter; row actions come from `actionsFor`, which gives each action its blocked
  reason, and ←/→ skip blocked ones. F2 (window listener, off while a dialog is open) focuses the
  search; Esc there calls `stopPropagation` so it never counts toward Esc Esc. Dialogs return focus to
  the list on close. Edit chains come from `GET /api/invoices/{id}/history` (walks
  `replacesInvoiceNumber` back and `findFirstByReplacesInvoiceNumber` forward).
- **Forms show server field errors**: `ApiError.fieldErrors` + `errorFor()` + `<Field error>`.
  Keep new forms on that pattern rather than toasting the message. **A refusal from inside a dialog
  must be shown inside that dialog**: a toast renders behind the modal's backdrop, where the person
  who caused it cannot read it (staff "Add a person" and "Reset PIN" were both broken this way).
- **The sign-in screen is the brand's**: the circuit board behind it is `frontend/public/login-bg.svg`,
  generated by `tools/make-login-bg.py` (seeded, so it regenerates byte-identical). The rocket and the
  wordmark are cut out of the one logo file with CSS (`LogoPart` in `Login.tsx`) rather than shipped as
  separate images, and the product name is written in the logo's own blue `#1a59d3`, orange `#fe6313`
  and green `#0a8c4b`, sampled from its pixels.
- The in-app browser pane cannot send a real Space keydown or edit text with Backspace; test those
  by dispatching a `KeyboardEvent` and with `form_input`.

## Theme notes (frontend/src/theme.ts, src/main/java/com/masspos/theme)

- **A theme is applied by redefining Tailwind's CSS variables, not by editing classes.** Tailwind 4
  paints `bg-sky-600` as `var(--color-sky-600)`, so `applyTheme` sets the slate (neutral) and sky
  (primary) scales, and for dark themes rose / amber / emerald, on `<html>`. The default theme sets
  **nothing**: the page keeps the colours it was built with, so "keep the current look" is exact.
- **Eleven colours per theme, everything else derived** (`varsFor`). Neutral scale: step 100 is the page,
  900 the text, the steps between are blends, so borders and muted text follow the theme. A theme is
  dark when `luminance(background) < 0.2` (same constant on the server, `ThemeRules`); dark themes tint
  toward the page instead of white.
- **Dark chrome is its own scope.** The header, the slide-in menu, the bill table heading and the
  total bars carry `.nav-scope` (index.css): inside it the slate scale runs from the nav colour (900) to
  its text (50), `white` is the nav text, and rose-300 / sky-200 / sky-500 are remapped. New dark bars
  must get the class or a light theme will turn them into light bars with white text.
- **Do not use `bg-white`, `text-white` on primary, or `bg-slate-800 text-white` for new UI.** Use
  `bg-surface`, `text-on-primary`, `bg-secondary text-on-secondary` (registered with `@theme` in
  index.css, defaulting to the old colours). `text-white` stays right on rose/amber fills. Fixed
  status bars use `bg-[#be123c]` style values because the status scales flip in dark themes.
- **Preview = real components in a scoped box.** `previewStyle(theme)` puts the theme's variables on one
  `div.themed`; CSS variables cascade, so the preview needs no copy of any component.
- **Server is the authority.** `ThemeRules` enforces shape and WCAG floors on every custom theme (apply and
  save); built-ins are always the server's copy (`ThemePresets`, `themes/presets.json`), so a client cannot
  rename a poor palette "high-contrast". `ThemeRulesTest` holds every preset to AA. Keep `PAIRS` in
  `ThemeRules.java` and `theme.ts` in step. The Default is exempt (white on sky-600 is 4.1:1).
- **Sync is polling, deliberately** (`themeSync.ts`): `GET /api/theme/revision` every 4 s and on window
  focus; fetch the theme only when the number moves. Survives sleep, Wi-Fi drops and server restarts
  with no reconnection code.
- **Admin only to change; everyone reads.** The Theme screen is in every role's menu, with the builder not
  rendered for non-admins. `theme_settings` is a new table (SQLite takes it on upgrade) and audited.

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
| zxing core | 3.5.3 | QR codes for the `WINDOWS` printer type only; `core` alone, no `javase` |
| Maven | 3.9.16 | Through `mvnw` / `mvnw.cmd` |

## Project structure

```
src/main/java/com/masspos/
├── PosApplication.java
├── config/              PosProperties (pos.data-dir, pos.terminal), SqliteDataSourceConfig,
│                        FirstRunSetup (writes pos.properties template, warns on missing seller data),
│                        SqliteDataSourceConfig (also swaps in a staged backup before the DB opens),
│                        DataDirMigration (moves a pre-0.1.2 till's data folder in; runs from main)
├── backup/              DatabaseBackups (VACUUM INTO, staged restore), BackupController,
│                        OffsiteBackups + BackupProperties (the copy kept outside the till's
│                        folder, written at every start and every clean shutdown)
├── desktop/             DesktopLauncher (what the shortcut runs: finds or starts the server, then
│                        opens the screen in an app window), PosPaths (one definition of where a
│                        till keeps its program and its data, shared with the app)
├── common/              IndiaTime (Asia/Kolkata)
├── common/persistence/  BaseEntity, UuidV7 + @GeneratedUuidV7 + UuidV7IdGenerator, PosSqliteDialect, LocalDateIsoConverter
├── audit/               AuditRevision, AuditRevisionListener, AuditActor / AuditContext, AuditTrailGuard (triggers)
├── user/ catalog/       User, UserRole / Product, UnitOfMeasure
├── billing/             Invoice, InvoiceItem, InvoiceSequence, InvoiceNumbers, InvoiceStatus, GstStates,
│                        CompanyProperties (pos.company), SellerDetails (@Embeddable snapshot), Gstin + @ValidGstin
├── inventory/           InventoryLedgerEvent, LedgerEventType
├── hardware/            EscPos, CodePage, PrinterConnection (+ Serial / Tcp / Windows / File),
│                        ReceiptLayout (page rendering for driver printers), PrinterConnections,
│                        PrinterSettings/-Form/-Repository/-Service + PrinterBootstrap (the row the
│                        screen edits), PrinterProperties (pos.printer, the seed), PrinterStatus,
│                        ThermalPrinter, HardwareController
├── receipt/             ReceiptFormatter, ReceiptProperties (pos.receipt), ReceiptService, ReceiptController, ReceiptCopy, Amounts
└── web/                 LocalApiGuard (Host + X-POS-Client), ApiExceptionHandler (ProblemDetail)
src/main/resources/      application.properties (defaults), pos-template.properties (copied to <data-dir>/pos.properties)
src/test/java/com/masspos/  TestFixtures, PersistenceIntegrationTest, HardwareApiIntegrationTest, + unit tests per package
```

A till is one folder. The installer puts the program in `%LOCALAPPDATA%\GoRockitPOS` and
`pos.data-dir` defaults to its `data` subfolder; elsewhere it is `~/GoRockitPOS/data`.
`POS_DATA_DIR` overrides it. It holds:

- `pos.db` and its WAL files
- `pos.properties`: per-till settings, loaded through `spring.config.import`; a template is written
  on first start
- `license.lic`
- `logs/`
- `backups/`: the copies taken from the settings screen
- `printer-out/`: FILE printer output

Beside it, outside anything the installer owns, `%LOCALAPPDATA%\GoRockitPOS-backups` holds what
`OffsiteBackups` writes at every start and every clean shutdown. Uninstalling deletes the till's
folder and everything under it, so that is the only copy that survives one.

## Configuration model

- **Layers**, where later wins: `application.properties` in the jar, then
  `<data-dir>/pos.properties`, then environment variables.
- **Groups:**

  | Prefix | Holds | Bound by |
  |---|---|---|
  | `pos.terminal` | terminal code | `PosProperties` |
  | `pos.company` | seller | `CompanyProperties` |
  | `pos.printer` | printer settings, **first start only** | `PrinterProperties` -> `printer_settings` |
  | `pos.receipt` | footer | `ReceiptProperties` |
  | `pos.backup` | where the copy outside the till folder goes, and how many to keep | `BackupProperties` |

- **A new setting** needs three things:
  - a record component
  - an empty or default entry in `application.properties`
  - a commented, explained entry in `pos-template.properties`

  Skipping the third is not cosmetic. `pos.clients.max` shipped without it, so the shop's own
  `pos.properties` never mentioned the setting that turns the whole multi-till feature on; the
  install guide told the owner to go and edit a line that was not there, the server stayed on
  loopback, and the counter said only that nothing had answered. The template is how a shop finds
  out a setting exists.
- **`pos.version` is the one exception, deliberately**: it is build metadata, not a shop setting, so it
  is *not* in `pos-template.properties` - a shop that could edit it could make the Settings screen
  claim a version the till does not have. It comes from the pom through resource filtering
  (`@project.version@`), and `build-installer.ps1` passes the installer's version as
  `-Dpos.version` on the server launcher, which outranks it, so an installed till names its own build.
  The installer's `-Version` now defaults to the pom's, so the two cannot drift.
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
- **A bill's history is its versions and its returns** (`SaleService.History`, `GET
  /api/invoices/{id}/history`): the edit chain, and the credit notes taken against any bill in it.
  Gathered over the whole chain, not the one version asked about - a bill that has been returned
  against can no longer be edited, so in practice they hang off the last version, but somebody
  opening the cancelled original still has to be told the goods came back and which document says
  so. It is why that bill can no longer be edited or cancelled.
  - **A return opens on the same story** (`GET /api/returns/{id}/history` →
    `SaleService.historyOfReturn`, which walks to the original bill and hands back its history):
    the bill it came off, that bill's versions, and every other return against it. A return taken
    without a bill comes back on its own rather than as a 404 - "nothing was sold against this" is
    an answer, not an error. One `Story` component in `Invoices.tsx` draws both ends, so a bill and
    a return read the same, with the document that was opened named `(Opened)` and ringed. That
    marking is not decoration: with three documents stacked in one dialog the first question is
    which one was clicked.
- **Returns are credit notes** (`CreditNote*`, `ReturnService`), own series `T1-2627-R0001`, manager
  only. Against a bill, each line's refund is a pro-rata share of that bill line, and the line's
  last return takes the exact remainder, so returns never exceed the bill. A bill with any credit
  note against it can no longer be edited or cancelled (`SaleService.requireNoReturns`).
- **Reports net returns**: day totals, payments per mode, cashiers and tills subtract credit notes;
  the GST summary nets them off rates and HSN. `DayReport.totals` is the net figure.
- **Nothing is stamped on the customer's own bill**: `ReceiptCopy.ORIGINAL` has an empty label and
  only a reprint says DUPLICATE COPY (asked for 2026-10-21).
- **A parcel bill carries a circled P beside the UPI code**, the way a counter marks one by hand.
  `Invoice.parcel` is set before the invoice is persisted and is in
  `AuditTrailGuard.FROZEN_INVOICE_COLUMNS`, so it cannot be changed after issue; an edited parcel
  bill stays a parcel unless the cashier says otherwise.
  - **A printer that draws its own QR leaves no room beside it**, so for a parcel the till draws
    the code and the mark together (`ParcelQr`) and sends the block as a picture (`EscPos.raster`,
    `GS v 0`). Ordinary bills still use the printer's QR command: a picture is many times the
    bytes, which matters on a 9600-baud port. `ParcelQrTest` decodes the drawn code back with
    zxing, which is the only thing that proves it still scans.
  - **The code stays centred on the paper, mark or no mark**, and at the same size: the block is
    padded on the left by as much as the mark takes on the right, so printing it centred leaves
    the code where it has always been. `ParcelQr` sizes everything from the paper width
    (`columns * 12` dots) and clamps the mark to the margin beside the code, so it fits on 58 mm
    as well as 80 mm. Dots per module are whole numbers, so `CODE_SHARE_PERCENT` is a target: 42%
    keeps the usual bill at five dots a module, the size it printed at before there was a mark.
- **Receipt items are four columns**: name, rate, quantity, amount, sized to the widest figure on
  that bill so they line up, with a name too long for its column carried on underneath. **No HSN on
  the line and no per-line tax rate** (asked for by the shop, 2026-10-21): Rule 46 wants the rate
  rate-wise, which the GST summary gives, but a GST-registered shop over the HSN turnover threshold
  needs HSN on its tax invoices - put it back for those shops before shipping to one.
- `Invoice` and `CreditNote` implement `TaxDocument` (`InvoiceItem`/`CreditNoteItem`: `TaxLine`), which
  is what `ReceiptFormatter` lays out.
  - A new immutable table, or a new fiscal or seller column on `Invoice`, must also be added to
    `AuditTrailGuard.FROZEN_INVOICE_COLUMNS`.
- **Stock** is `SUM(quantity_delta_milli)` from the ledger. Never add a stock column.
- **A product is never deleted for real** (`Product.delete`). `invoice_item`, `credit_note_item` and
  `inventory_ledger_event` all hold a foreign key to `product` and are append-only, so the row cannot
  go and the references cannot be rewritten. Delete therefore sets `deleted`, clears `active`, rewrites
  the item's own `sku` to `~<uuid>` and drops its barcode: gone from every screen and from billing,
  with the code and barcode free to use again, while the bills that sold it still read the code they
  were taken under from their own copies. **Every product query must exclude `deleted`** -
  `findBySkuIgnoreCase` and `findByBarcode` are what free the code, so they filter it in JPQL rather
  than by method name. A stopped item (`active = false`) is a different thing: it stays on Products and
  Stock, marked, and only leaves billing.
- **`Product.billWithoutStock`** exempts one item from the shop-wide `blockNegativeStock` rule
  (`SaleService` checks both). The sale still records its SALE ledger event, so the count goes
  negative: the exemption is about refusing the sale, never about skipping the movement.
- **The item code (`sku`) is editable** (`Product.changeCode`, checked for clashes by
  `ProductController.checkCode`, which excludes the item being saved). Invoice and credit note lines
  copy the code at issue, so an old bill keeps the code it was sold under.
- **A new column on an existing table needs a default**: SQLite refuses
  `ALTER TABLE ... ADD COLUMN ... NOT NULL` on a populated table, which is every shipped till. Give
  the field `@Column(nullable = false, columnDefinition = "<type> default <value>")`;
  `RestartOnExistingDatabaseTest.aNewColumnReachesATableThatAlreadyHasRows` drops the column from a
  populated table and restarts to prove it.
- **A new value in a persisted enum breaks every database already out there.** Hibernate writes an
  `@Enumerated(STRING)` column as `role varchar(16) not null check (role in ('CASHIER','MANAGER',
  'AUDITOR','ADMIN'))`, on the entity table *and* its `_aud` twin. SQLite cannot alter a CHECK
  constraint and `ddl-auto=update` leaves it alone, so a fresh install accepts the new value and
  every till in a shop refuses it with `SQLITE_CONSTRAINT_CHECK`. Found by adding `SUPPLIER` to
  `UserRole` and starting on an existing database; a fresh test database would have passed and
  shipped it. Add a table instead - SQLite takes a new table on upgrade - or plan the
  create-copy-drop-rename rebuild of both tables, which for `*_aud` means working around the
  append-only triggers.
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
- **Invoice numbers** follow `{terminal}-{ddMMyy}-{daily:04d}` (`T1-071126-0001`) and are ≤16 chars,
  so the terminal code is ≤3 chars. Returns are `{terminal}-{ddMMyy}-R{daily:03d}`.
  - **The running number starts again at 1 every morning, which is why the day is in the number.**
    Rule 46(b) wants a serial unique for the financial year; without the date, the first bill of
    every day of the year would be numbered the same. The rule allows the serial to run "in one or
    multiple series", and a series a till a day is one. Judged in `IndiaTime.ZONE`.
  - **There are two counters, and they are not interchangeable.** `invoice_daily_sequence` /
    `credit_note_daily_sequence` (one row a till a day) give the number the shop reads.
    `invoice_sequence` / `credit_note_sequence` (one row a till a financial year) keep counting
    across the year and never restart: that is what `Invoice.sequenceNo` holds, what the gapless
    audit trail is kept on, and what `uk_invoice_terminal_fy_seq` is on. Both are reserved together
    in `SaleService.reserveNumber`, inside the transaction that persists the document.
  - **The day's count went into new tables on purpose.** Re-keying the existing unique constraint to
    the date would have been the obvious move and is the one thing SQLite cannot do to a populated
    table - the same trap as a CHECK constraint, and it would have broken every till that restored
    a backup. A new table is something SQLite takes on upgrade;
    `RestartOnExistingDatabaseTest.aTillNumberingByTheYearUpgradesToNumberingByTheDay` drops both
    day tables from a populated database, restarts, and proves the old bills survive untouched.
  - **Tills shipped before this numbered by the financial year** (`T1-2627-00123`). Both shapes are
    three parts, so `serialOf` / `counterOf` read either and an upgraded till shows its old bills
    exactly as it always did. Never write code that assumes the middle part is a financial year.
  - **That is the number of record, not what a person is shown.** Screens and the receipt show the
    running number alone (`0004`) with the counter beside it as its own labelled field -
    `InvoiceNumbers.serialOf` / `counterOf`, `billNo()` / `counterOf()` in `api.ts`. The whole
    string is still what is stored, filed and synced, so it stays on the bill detail dialog, in the
    audit trail, and in any reference to *another* document ("Replaces bill", "Against bill") where
    which counter issued it is part of the identity. `/api/invoices/lookup` takes either, and a
    bare number means **today's** bill, because that is the one being asked about at a counter.
- **Dates.** Business dates and times use `IndiaTime.ZONE`, never the JVM default.
- **Sync bookkeeping stays off audited entities.** Use an unaudited outbox table.
- **"Which till" is `CurrentCounter.code()`, never `pos.terminal().code()`.** With counters billing
  against a server, the machine holding the database is not the machine ringing up the bill. Bill
  and credit-note numbering, held bills, stock ledger origin, "last bill on this till" and audit
  revisions all go through `CurrentCounter`, which answers from the signed-in session and falls
  back to this machine's own code when nobody is signed in. `pos.terminal().code()` now means only
  "this physical machine" - its printer, its drawer, its folder.
  - The counter is fixed **at sign-in** and kept on the `AuthToken`. Not read from a header per
    request (a signed-in browser could then renumber its own bills) and not from the client's IP
    (DHCP changes it).
  - **`settings.thisTerminal` is the till the browser asking is billing as**, resolved through
    `CurrentCounter` and the `terminal` row, never from `pos.terminal()`. Answering from this
    machine's own properties showed every counter in the shop the *server's* name in the header and
    marked the server's row as "this machine" in Tills in this shop - three machines all told they
    were the first one. `pos.terminal()` is still the right answer for this machine's own printer,
    drawer and folder.
  - **A counter's name travels in the login body** (`counterName`), not beside its number in a
    header: it is text somebody typed, and a name in Devanagari or Tamil cannot go through an HTTP
    header at all. `ClientRegistry.admit` writes it onto the `terminal` row at *every* sign-in, so
    renaming a machine in its own settings window reaches the screens without anyone editing the
    database.
- **A counter is a different program, not a till with the database removed.**
  `com.masspos.counter` is the client application: no Spring, no JPA, no licence, one properties
  file in `%LOCALAPPDATA%\GoRockitPOS-Counter`. It builds as `mass-pos-counter.jar` (its own
  `maven-jar-plugin` execution, an explicit include list) with its libraries in `target/counter-lib`,
  and `tools/build-installer.ps1` makes a second, smaller jpackage image from them. Keep
  `com.masspos.client` (the server's view of its clients) and `com.masspos.counter` (the client
  itself) apart: a wildcard include across them would pull Spring into the counter.
  - **The counter reuses the hardware classes rather than owning a copy of ESC/POS.** The include
    list names only the Spring-free ones. `CounterAppTest` and a classpath probe in the build
    guard it; if the counter jar ever passes 5 MB the installer script fails, because that means
    the include list has stopped working.
  - **The print agent is why a counter is more than a browser window.** It listens on the
    counter's own loopback, takes a bill the server has already laid out, and writes it to the
    printer and drawer standing there. The server renders once - `/api/invoices/{id}/receipt-job`
    returns both the bytes a thermal printer executes and the lines a driver printer renders, via
    `EscPos.rendered` - so a counter's bill can never drift from the server's.
  - **A new counter prints to a file, not to a guess.** `CounterConfig.printerType` defaults to
  `FILE`, spooling to `<counter folder>/printer-out` on that machine, and the counter's startup
  window no longer asks about printers at all - the printer page in the app does, because that is
  the screen that can see this machine's ports and name the printers installed on it. The old
  `WINDOWS` default sent bills to whatever Windows called the default printer, which on a counter
  being set up is nothing anybody chose.
- **A counter sends `print: false` when it bills.** Otherwise the bill comes out in the back
    office, where nobody is standing to take it.
  - **The counter number travels on the address** (`?counter=T2&agent=8767`), is kept per machine
    in `localStorage`, and goes out as `X-POS-Counter` on every request. The server acts on it
    once, at sign-in.
- **The shop's counters are the shop's, kept on the server** (`TerminalAdmin`, `TerminalController`,
  the Tills panel's Add / Rename / Block). A counter machine used to make itself up: somebody typed
  a number and a name into the window on that machine, and the first sign-in created whatever they
  had typed. A typo made a till, two machines could argue over one number, and the shop's list of
  tills was only ever a list of whatever had turned up.
  - **`pos.clients.max` caps how many counters the shop may have**, not only how many may bill at
    once (`ClientRegistry.roomForMoreCounters`, checked in `TerminalAdmin.create`). It used to cap
    the sign-ins alone, so a shop licensed for one could create as many counters as it liked and
    only find out at the till - with no error in between. One consequence worth knowing: with
    creation capped, every counter that exists has a place, so the "shop is full, wait for one to
    sign out" refusal is now a backstop rather than the usual path. It still fires for a shop whose
    licence has been **cut** below the counters it already has.
  - **A counter beyond the licence is kept and marked, never deleted** (`overLicenceLimit`, the red
    row and "Licence limit exceeded" on the tills panel). A licence cut from three counters to one
    leaves the other two rows and their bills, which are the shop's records; they simply cannot be
    billed on. Which ones are covered goes by age - `counters()` is oldest first - so the line
    between them does not move about as counters are renamed.
  - **The number is settled at creation and never changes.** It is stamped into every bill that
    till issues and those bills are what the shop files, so `Terminal.code` is
    `updatable = false` and nothing offers to edit it. The name and the floor are the editable part.
  - **A machine says which till it is by id** (`counterId` in the login body → `ClientRegistry
    .admit`), not by a number it is holding a copy of. A copy goes stale the moment the shop
    corrects anything; an id either is one of this shop's counters or is not. The id is only the
    *link*: the code is still what goes into bill numbers, reports and the audit trail, and
    `CurrentCounter.code()` is unchanged, so none of the append-only tables moved.
  - **`GET /api/clients/counters` is the picker's list** (public, like `/places`, because it is
    asked before anybody can sign in). Blocked counters are on it, marked - a machine that simply
    could not find itself would have no way of telling whether it was blocked or never created.
  - **A sign-in no longer renames or re-activates a till.** `Terminal.seen()` touches `lastSeenAt`
    and nothing else; going through `describe()` meant a blocked counter let itself back in by
    signing in, and a machine could rename the shop's till from its own window.
- **A counter's place is held by a live sign-in** (`ClientRegistry.taken`), not by a row in
  `terminal`. Three things follow, and each was a bug before:
  - **The cap is checked at every sign-in**, not only the first one a machine ever made. It used to
    be checked only when a machine had no row yet, so a shop set up for two counters could have
    three billing at once as long as each had signed in at some point in the past. The counter
    asking is never counted against itself (`taken(exceptCode)`), or a till that rebooted could not
    get back into a full shop.
  - **Logging out gives the place back at once.** A machine switched off without signing out holds
    it until `pos.clients.idle-minutes` has passed with no word (4 h; the screen's own "still here"
    refreshes `lastSeenAt`). Deliberately *not* the three-minute presence window: a counter asleep
    or off the Wi-Fi for five minutes would otherwise lose its place to another machine and come
    back still signed in, billing without one.
  - **One counter, one session** (`AuthService.endEarlierSessionsOn`, counters only). The counter
    app opens exactly one window, so a counter signing in again means the window before it has
    gone. Those abandoned sign-ins used to stay open for ever, and each went on holding the place
    and showing the cashier who had left - so pressing Log out on a counter looked like it did
    nothing, because the session behind it was still there. The server's own till is left alone: it
    is a shared machine where a manager and an auditor may both be signed in, it holds no place,
    and every API test signs several people in on it.
  - **An admin can sign a till out** (`POST /api/clients/{code}/release`, the Sign out button on
    the tills panel). It ends that till's sessions as well as winding `lastSeenAt` back, because
    nothing the machine does can give the place back once it has been switched off or carried away
    - and on a shop set up for one counter that is the difference between the next machine billing
    now and not billing until this evening.
  - **`whyNot(code)` is the one place the refusal is worded**, used by `admit` and by
    `GET /api/clients/places`, so the counter settings window can never promise a place the sign-in
    then refuses. The settings window asks before enabling its button; the sign-in asks again
    inside the transaction that writes it, which is what settles a race for the last place.
- **`GET /api/clients/places` needs no token**, and is in `AuthFilter.PUBLIC_PATHS` for the one
  reason that matters: a counter has to know whether the shop has room for it *before* anybody
  signs in. It gives away nothing `DiscoveryResponder` does not already broadcast to the whole LAN.
- **A refusal of the machine carries `counterSetup: true`** (`ApiExceptionHandler`). The sign-in
  screen tells that apart from a wrong PIN and posts to the agent's `/settings-window`, which is how
  the counter that loses the race lands back in its own settings window with the server's sentence
  on it. A web page cannot open a window belonging to the counter program; the agent is the one
  piece both ends can reach.
- **One counter process per machine** (`CounterApp.windowOfRunningCounter` → `PrintAgent.runningOn`,
  which reads `server` out of `/hello`). Clicking the shortcut again used to start a whole second
  copy: only one could hold the printer, the loser said so in a window and carried on running
  anyway, and a shop that opens the till a few times a day finished the week with a row of them in
  Task Manager. The second process now opens a window on the server the first is already using and
  exits.
- **A counter turned away watches for a place** (`CounterSettings`, a 4 s Swing `Timer`). Somebody
  logging out across the room frees a place at once, and the only thing between this machine and
  billing is asking again - so it asks itself, quietly (the last answer stays on screen rather than
  flickering "Looking..." at nobody), and the button lights up on its own.
- **"Save and start billing" is a promise** (`CounterSettings`). It stays disabled until every field
  is filled in and sane *and* the server has said this counter can join - the local part is
  `problemWith(config)`, the rest is `ServerFinder.roomFor`. Editing the counter number or where the
  server is throws the server's answer away and the button goes out until it has been asked again.
  A lit button that leads to a sign-in screen which turns somebody away is worse than no button.
- **"Who is at which till" is in memory, not in the database** (`TillPresence`). A till counts as in
  use when both hold: somebody is signed in on it (an unrevoked `auth_token` for that code), and its
  screen has posted `/api/clients/here` in the last three minutes. **Signed in and in use are
  different questions and the screen shows both** - `signedIn` holds the shop's place, `inUse` means
  a screen is answering - because a list that called a signed-in-but-quiet counter "Idle" would seem
  to contradict the count of free places above it. Every signed-in screen posts it
  once a minute (`App.tsx`). Neither half is enough on its own - a machine switched off without
  signing out would read as billing all night, and a machine sitting on the sign-in page is not
  billing. The times are deliberately not persisted: `terminal` is `@Audited`, so a heartbeat a
  minute a till would bury the shop's real changes under thousands of revisions a day. For the same
  reason `ClientRegistry.stillThere` only writes `lastSeenAt` once the row is halfway to going
  stale (`idleMinutes / 2`), which is early enough that a till at work never loses its place.
- **The tills panel asks for itself** (`GET /api/settings/tills`, polled every 10 s). Re-reading the
  whole of `/api/settings` would reset the shop-details form beside it - `Settings.tsx` resets the
  form whenever the settings object changes - and throw away whatever is part way typed.
- **`pos.clients.max` is the one switch for a shop server.** 0 (the default, and every till shipped
  so far) = loopback only, nothing on the network can reach it. Above 0, `ServerBinding` binds
  0.0.0.0 and `LocalApiGuard` widens its Host check from loopback to this machine's own addresses
  and names - never to anything else, so DNS rebinding is still refused. `ClientRegistry.admit`
  caps the count at sign-in and refuses a counter that claims the server's own code, which would
  reuse bill numbers.

### Licensing

- **The vendor public key is compiled in** (`VendorKey.PUBLIC_KEY`), never a property or env var:
  a configurable key lets a shop sign its own licences. The private key and `licences.csv` live in
  the vendor's folder (`C:\Users\sivar\MassPOS-licensing` here), never in the repo (`.gitignore`
  covers `vendor-private.key`, `licences.csv`, `*.lic`).
- **Key format `MPL1.<payload>.<sig>`** is written by `tools/license/LicenseTool.java` and read by
  `LicenseToken`. Change both together; `LicenseToolCompatibilityTest` runs the real tool to keep
  them in step. New payload fields must be optional on the app side (unknown fields are ignored).
- **Only `@RequiresLicense` endpoints lock**: `POST /api/sales`, `/api/returns`,
  `/api/invoices/{id}/replace`. Anything that issues a new tax document needs it; nothing else may
  (reports, reprints, cancel, setup must work on a lapsed till). `LicenseInterceptor` runs after
  `RoleInterceptor`. A licence never blocks startup.
- **Dates are judged in `IndiaTime.ZONE`** and at `max(today, newest audit_revision.timestamp)`
  (1 h tolerance), so winding the clock back cannot extend a licence.
- **Machine ID** (`OsMachineIdentity`): Windows = SMBIOS UUID (`HKLM\SYSTEM\HardwareConfig
  LastConfig`) + `MachineGuid`, via `reg.exe` (~60 ms, cached). Changing the inputs or `SALT`
  changes every till's ID and invalidates every issued licence.
- **API tests that bill** must `@Import(TestLicensing.Config.class)` and call
  `TestLicensing.install(DATA_DIR)` in the static block that wipes the data dir.

### Hardware

- **"This till's printer" means two different things, and the screen has to pick the right one.**
  The server's printer is the `printer_settings` row in its database. A counter's printer is in that
  counter's own `counter.properties`, because that is the machine the paper comes out of and the
  only machine that can see its ports. Settings > Printer is one page with two places to send it:
  the browser on the server asks `/api/hardware/printer/*`, a counter asks its own print agent
  (`hasPrintAgent()` → `agentRequest()` in `counter.ts` → `/settings`, `/status`, `/test-page`).
  Asking the server from a counter was the bug: a manager at Counter 2 who chose a printer there set
  the *server's* row, so the back office's bills - and every other counter's - followed it.
  - **The agent is therefore more than a pipe for bytes**: it owns which printer this counter has.
    Its handlers run on a small thread pool rather than the listener's own thread, because asking
    the printer for its status waits on the printer thread and that wait would otherwise hold up
    the next bill. `config` is read and written only under `PrintAgent`'s `settings` lock, and the
    printer thread reads a whole `PrinterProperties` out of one volatile field: a half-applied
    change - a new type with the old port still in it - would reach the paper.
  - **The counter carries the server's own `PrinterSettingsForm`** (in the counter jar's include
    list) so the two ends cannot drift in their field names. Its `jakarta.validation` annotations
    are not on a counter's classpath, which is harmless: the JVM ignores an annotation whose type
    is missing. Checked rather than assumed - Jackson read it, wrote it and described its target on
    `mass-pos-counter.jar` + `counter-lib` alone, with no validator anywhere on it (2026-11-10).
    The agent checks the ranges itself in `CounterPrinter.read`, and its message is the whole of
    the answer - there is no validator and no error page out there.
  - **`CounterConfig` reads and writes the folder it was loaded from** (`loadFrom`), not
    `folder()`, so a test exercising the screen that edits a counter cannot overwrite a
    developer's real `counter.properties` in `%LOCALAPPDATA%`.
- **The server's printer is a per-till database row, not a property.** `printer_settings` (one row,
  seeded once from `pos.printer.*`) is what the Settings screen edits on the server;
  `pos.properties` is only the seed after that. Three things follow:
  - **It must never sync.** Every counter has its own printer: pushing the master's row out would
    stop the whole shop printing. Keep it out of the phase-4 sync set.
  - **Nothing caches the settings across a job.** `ThermalPrinter` asks
    `PrinterSettingsService.active()` each time, and that answers from a volatile field refreshed on
    save, so the print path never waits on a query.
  - **`PrinterConnections` rebuilds the connection when the settings record stops being `equals`**
    to the one it built from, and only on the printer's worker thread. Anything that asks for a
    connection from another thread could close a port mid-job, which is why `target()` describes the
    printer from the settings instead of opening one.
- **Building a connection touches no hardware.** Serial, TCP, Windows and File all connect when a
  job is written, so a printer that is off, unplugged or misconfigured cannot stop the till starting,
  billing, or changing its own settings back.
- **`ReceiptFormatter` takes the printer settings per call**, not through the constructor: paper
  width can change between two bills.
- **`WINDOWS` prints through a driver, so it renders instead of sending bytes.** ESC/POS bytes on an
  inkjet come out as rubbish, so `WindowsPrinterConnection` lays `EscPos.printedLines()` out with
  `ReceiptLayout` and hands the page to the spooler. Two consequences:
  - **A job with no printed lines must never reach the driver.** A drawer kick is exactly that, and
    it would otherwise feed a blank sheet out of the printer on every cash sale.
  - **`EscPos` keeps the preview and `printedLines()` in step.** Anything new that prints has to
    appear in both, or it reaches a thermal printer and not a driver one. `plainText()` is derived
    from the same flush, which is what keeps the FILE preview honest.
- **The QR code is drawn by the till for `WINDOWS`** (zxing, model 2, EC level M — the same symbol
  the ESC/POS command asks the printer for). A QR that cannot be encoded is logged and skipped: the
  bill still prints.
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

### Installer and the desktop shell

- **The shortcut runs `DesktopLauncher`, not the server.** It probes the till's port, starts
  `GoRockitPOS-Server.exe` when nothing answers, waits for the port, then opens Edge - Chrome,
  then the default browser - with `--app=`. Clicking it again with the till up costs one connect.
- **Two jpackage traps, each of which cost a build:**
  - `--main-class` on the command line is inherited by an added launcher. Passing it made the
    server a second copy of the desktop launcher, which started another server, eight deep.
  - An added launcher inherits the main application's main class unless its own properties name
    one, so `build-installer.ps1` reads `Main-Class` out of the repackaged jar and writes it into
    `target\server-launcher.properties`. Never hardcode `JarLauncher`: Boot has moved it once.
  - `DesktopLauncher` marks the process it starts and refuses to run if it finds that mark on
    itself, so a mispackaged build gives one dialog instead of filling a till's memory.
- **`--java-options` belongs on the server launcher, not the command line.** The shell is a
  short-lived JVM with no use for a 512 MB pre-touched heap, and the command line would hand the
  options to both.
- **Uninstalling deletes the installed folder and everything under it.** Measured both ways here:
  the whole folder where the installer had created it, `data` left behind where the folder
  already existed. Treat the data as gone - which is what `OffsiteBackups` exists for.
- **The program cannot be installed a level down** (`--install-dir GoRockitPOS\program`), which
  would have put `data` out of the uninstaller's reach: WiX fails with ICE64, the intermediate
  user-profile directory is not in the RemoveFile table, and jpackage passes no `-sice:` through.
- **Windows refuses the same app version over itself** (error 1638), so a rebuild for a till that
  already has one needs a new `-Version` or an uninstall first.
- **`DataDirMigration` runs from `main`, before Spring.** By the time any bean exists, logging has
  created the new data folder and the datasource is about to create an empty `pos.db` in it.
- **The offsite folder is worked out from the data directory**, not named outright, so the
  integration tests write under `target\` instead of into the developer's own AppData.

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
- **Backups are taken with `VACUUM INTO`**, which writes a complete, checkpointed copy while the
  till keeps billing - never copy `pos.db` by hand, as its WAL may hold writes the file does not.
- **A restore is staged, never done live.** `DatabaseBackups.stageRestore` checks the file really
  is one of this till's databases (it looks for `invoice`, `product` and `shop_settings`), copies
  today's data first, and leaves `restore-pending.db`. `SqliteDataSourceConfig` swaps it in on the
  next start, before anything opens the database. **The old `-wal` and `-shm` go with the old
  database**: a leftover write-ahead log would be replayed onto the restored file and wreck it.

## Hardware notes

- **jSerialComm native library.** It is extracted to `java.io.tmpdir/jSerialComm/<version>`, or
  `~/.jSerialComm/...` as a fallback. `-DjSerialComm.library.path` only loads a library already in
  that directory; it does not extract there. If antivirus interferes, unpack the library at
  package time into the app image and pass `-DjSerialComm.library.path=$APPDIR/native`.
- **USB printer-class devices** (Windows `USB001`) are invisible to jSerialComm: they need the
  vendor's virtual COM driver, a LAN printer, or the `WINDOWS` type, which goes through the driver
  and gives up the drawer and the paper status in exchange.
- **Serial is exercised on real hardware** (2026-10-21): a TVS-E RP 3230 ABW paired over Bluetooth,
  `SERIAL` on its outgoing SPP port at 9600 with flow control NONE. It printed and answered the
  DLE EOT status query (`responding=true, online=true`), so the drawer and paper state work on this
  path. Bluetooth pairing creates several "Standard Serial over Bluetooth link" ports and
  **jSerialComm cannot tell them apart** - same descriptive name, no VID/PID, no serial number. The
  paired device's name is only in the registry: the port's `BTHENUM\...` instance id carries the
  Bluetooth address, and `BTHENUM\DEV_<address>` holds the FriendlyName. Until the setup screen
  reads that, finding the right COM port is trial and error.
- **A USB thermal printer with no driver installed has no print queue at all**, so the `WINDOWS`
  type cannot see it either - it shows in Windows Settings as "Driver is unavailable" but is absent
  from `Get-Printer` and from `PrintServiceLookup`.
- **`WINDOWS` with no printer name is a trap.** Java reports `printer-is-accepting-jobs =
  accepting-jobs` for a printer Windows has marked Offline, so `job.print()` spools happily, the
  till reports success, and nothing ever comes out. Nothing in javax.print exposes the offline
  state. That is why `PrinterConnections.describe()` resolves and names the default printer instead
  of saying "the Windows default": the screen then shows which queue the bills are really going to.
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

## Product name

The product is **GO RockIt**, from GoGo Digital Services, whose logo is the login screen's
(`frontend/public/gds-logo.webp`). The name appears on screens, receipts, the licence messages, the
`pos.properties` template and the docs. Deliberately *not* renamed, because each would break a
till or a licence already in the field:

| Kept as it is | Why |
|---|---|
| `com.masspos` packages, `mass-pos.jar`, `spring.application.name` | Internal names; renaming churns every file and changes the installed artifact |
| Licence key prefix `MPL1`, the machine-ID salt | Changing either invalidates every licence already issued |
| `X-POS-Client: mass-pos-ui` | The guard checks the header is present, not what it says |

The data directory was on that list until 0.1.2, and came off it deliberately: a till is now one
folder, `%LOCALAPPDATA%\GoRockitPOS`, with the shop's data in `data\`. `DataDirMigration` moves an
older till's `%LOCALAPPDATA%\MassPOS` across on the first start after the upgrade, which is what
made the rename safe. Renaming it again would need the same treatment.

## Open decisions and known gaps
- **Offsite copies are taken at start and clean shutdown only.** A till left running for days and
  then updated has a copy from the morning it was last started, and one killed from Task Manager
  never writes the shutdown copy at all. A periodic copy while it runs would bound that; so would
  a tray icon with Quit, so restarting no longer means Task Manager. Both are phase 9 in the
  README roadmap.

- **JSON old/new state**: Envers snapshots plus `_mod` flags. A JSON projection is planned in the
  audit-report API. Pending sign-off.
- **Unregistered sellers** now bill without tax as a plain "BILL". **Composition-scheme** sellers
  (registered, but may not collect tax) still need a *bill of supply* with its mandatory
  declaration; add a GST scheme setting for that.
- **A "no sale" drawer opening from a counter still opens the server's drawer.** `Billing.tsx`
  posts `/api/hardware/drawer/open`, which is the back office's printer: the counter's own drawer
  hangs off its own printer, so this needs a drawer kick through the print agent the way printing
  already goes. The printer *settings* are per counter now; this one action is not.
- **"No sale" drawer openings are only logged.** Persist them once login exists.
- **The drawer kick is chosen by the caller.** Derive it from a cash tender in Phase 3.
- **No status pre-check before printing.**
- **Indic scripts** need raster printing.
- **FK enforcement** exists only on tables Hibernate created. Fixed by the Flyway move.
- **Licence server (L2) not built**: no online check-in, no remote block of a machine, no alert on a
  reused key. A key already cannot bill on a second machine; stopping a machine = not renewing.
- **Offline licensing stops copying, not cracking**: the jar can be decompiled and the check patched
  out. Obfuscation and the L2 check-in raise the bar.
- **`WINDOWS` printing is exercised on real hardware** (Canon G3080 on this PC, 2026-10-20: the job
  reached the spooler as "GO RockIt receipt" and came out on paper). Serial and TCP are still fakes and
  error paths only. One thing to watch: `PrinterJob.print()` blocks until the spooler takes the job,
  inside `pos.printer.timeout` + 3 s. A sleeping printer could spend that and have the till report a
  print failure for a bill that prints a moment later; raise `pos.printer.timeout` if it happens.
- **Not yet exercised on real hardware**: serial and TCP printing (fakes and error paths only). Also
  untested: the `frontend` profile and the WiX/DMG installers.
