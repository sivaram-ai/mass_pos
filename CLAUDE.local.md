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
| 3P | A counter shows no desktop windows at all: the Swing settings dialog is gone and setting a counter up is a page the counter serves itself from its print agent, found before any shop server has been, with every refusal shown in full on it | Done: 365 tests green, driven end to end in the browser |
| 3Q | Signing in where that account is already open on another till stops and says where - counter, number, floor and when - and offers Go back or sign that one out and carry on here, chosen with the arrow keys and Enter | Done: 368 tests green, driven in the browser |
| 3R | Nothing from inside the program reaches a counter's screen: every refusal is a sentence written for a shop, and the exception, its stack, the SQL, the paths and the ids go to the log. `ApiExceptionHandler.forTheScreen` is the gate; `api.ts` guards the browser end | Done: 377 tests green, refusals driven against a running till |
| 3S | A counter's settings are reachable while nobody is signed in: the sign-in screen opens, from its corner, the very page the counter serves itself at first start, so the connection mode, the server's address and which of the shop's counters this machine is can all be changed without closing the program. A counter that is already billing is given a way back that changes nothing | Done: 385 tests green, driven in the browser |
| 4C | One **Items** screen where Products and Stock were two; stock reaches a count only through a purchase order somebody approved, and **Correct** moves one by hand with a reason that decides the movement; **Space** ticks several items onto one order; the order dialog searches both lists as you type; the orders list searches and sorts like Items and says whether each one has been paid for; **F8** shows what an item has cost on every delivery; raw materials are **Non billable items** | Done: 416 tests green, driven in the browser |
| 4B | Counts that went below zero on an older build are brought up to it, one correction each with its reason; and a stock-take correction can no longer go under either, which was the last way in | Done: 413 tests green, driven against a till carrying a -357 |
| 4A | Purchase orders: a manager writes what the shop needs, an administrator approves or turns it down, and the counts move only when the goods are taken in - with what actually arrived, which is not always what was ordered. One order can hold things off the shelf and out of the store | Done: 413 tests green, driven in the browser |
| 3Z | Vendors: who the shop buys from, what it has had from them across both the shelf and the store, what it has paid and what it still owes. The vendor is chosen from a dropdown when goods arrive, deliveries taken before the list existed are found by name, and a payment waits for an administrator before it moves the balance | Done: 407 tests green, driven in the browser |
| 3Y | Raw materials: the store behind the kitchen in a module of its own - butter, onions, oil, carrier bags - bought, used, wasted and corrected on a screen worked exactly like Stock, with its own table, its own ledger and its own History (F6). Never in the billing search, by construction | Done: 402 tests green, driven in the browser |
| 3X | Goods received asks what a delivery cost and who it came from, both optional, both kept on the movement for good and shown in the history; the vendor box suggests from whoever the shop has had deliveries from before | Done: 396 tests green, driven in the browser against an upgraded database |
| 3W | Where a count came from: F6 on Products and Stock opens every movement ever recorded against the item - what came in, what sold, what came back, corrections and losses - each with the count after it, the till, the person and the time, searchable and orderable, or across the whole shop for the last 30 days | Done: 393 tests green, driven in the browser |
| 3V | The stock count stops at empty: a sale takes off what the shelf has and no more, the bill still carries the full quantity, and cancelling puts back what was taken off rather than what was sold | Done: 391 tests green |
| 3U | One bill per counter, not one per address: the bill on screen is kept by the print agent on that machine, so moving a counter from the cable to Wi-Fi comes back to the same bill instead of to a separate one the browser kept against each address | Done: 389 tests green, driven across two addresses in the browser |
| 3T | A till that cannot reach its shop keeps its screen: a bar says so, the bill stays where it is, Refresh asks instead of reloading, and Counter settings is on the bar - and it clears itself when the cable goes back in. The bill on screen survives a reload, and Log out asks first, holding a bill in progress rather than losing it | Done: 387 tests green, driven in the browser |
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
- **The shop going quiet is a bar, never a screen** (`api.ts` `SHOP_UNREACHABLE` / `SHOP_ANSWERED`,
  `ShopUnreachable` in `App.tsx`). A request that never reached the server - a pulled Ethernet
  cable, a switch off at the wall - fires a window event, and the shell draws a red bar under the
  header. Nothing navigates, nothing is cleared, the cursor stays in the bill: a cashier twenty
  items into a trolley that is already packed has nowhere to get those items back from. A refusal
  is **not** this: a 401, a 409, a 500 all mean the shop answered, and the screen that asked has a
  sentence of its own. The events fire on the *change* only, so a till that is out does not shout
  once a request.
  - **It clears itself.** While out, `App` asks `/api/clients/places` (public, no token) every 3 s
    and on window focus; the first request that gets through dispatches `SHOP_ANSWERED` and the bar
    goes. Nobody has to find a button after pushing the cable back in.
  - **A network failure must never sign anybody out.** `/api/auth/me` failing with status 0 used to
    clear the token, so a dropped cable became "sign in again" - with the shop unreachable, so
    nobody could. The token is kept for status 0 and tried again when the shop answers; the same
    goes for `/api/settings`, which keeps the last shop name rather than blanking the header.
  - **Refresh asks the shop before it reloads anything, and reloads only if it answers**
    (`refresh()` in `App.tsx` → `askTheShop()`). Reloading while the shop is out lands on the
    browser's own error page, where the screen is gone and nothing of this program is left to go
    back to. Checking the bar is up first is **not** enough: the cable comes out between two
    requests, and pressing Refresh is the first thing anybody does when a screen stops responding
    - before anything has had the chance to fail. So the ask is the thing that gates the reload,
    and the ask that fails is what raises the bar.
  - **The ask is bounded, at 2.5 s** (`askTheShop`, an `AbortController`). A cable pulled out of a
    switch does not refuse a connection, it swallows it: nothing comes back and the browser waits
    twenty seconds or more before admitting it - `ERR_CONNECTION_TIMED_OUT`, the very page this
    exists to keep a till off. Refresh cannot stand there that long with a bill on screen, and a
    till asking every three seconds must not stack up twenty-second waits. A refusal counts as an
    answer: it proves the cable is in.
  - **The way out is on the bar**: Counter settings, on a counter, which is served by the print
    agent on that machine and so answers whatever the network is doing. See the counter notes.
- **The bill on screen survives a reload** (`billDraft.ts`). It is written back on every change and
  read when the grid opens. Not a held bill - that one is the shop's, named and on the server - but
  this machine's note of its own screen, dropped as soon as the bill is taken, held, cleared or
  signed out from. Stamped with the till's day and ignored after 12 hours, so yesterday's abandoned
  screen is never billed to this morning's customer. Sale mode only: half an edit or a return
  brought back out of storage, against a bill that may since have been returned against, is worse
  than none.
  - **On a counter it is kept by the print agent, not in `localStorage`** (`/bill-draft` on the
    agent; `billHere` / `keepBillHere` / `forgetBillHere` in `counter.ts`). `localStorage` belongs
    to the **address** a page came from, not to the machine it is running on. A counter moved from
    the cable to Wi-Fi reaches its shop at a different address, so the browser hands it a
    different, empty store - and gives the first one back when it moves to the cable again. A
    cashier watched the bill they were ringing up disappear and an abandoned one take its place,
    and the two sets took turns with the connection. The machine is the thing that does not change
    when the network does. **The server's own browser keeps using `localStorage`**: it has no
    agent, and its own address never changes.
  - **So the restore is a request, and the grid opens empty for a moment.** Billing holds
    `restored`, and **the save effect must not run before it** - an empty grid would write over the
    bill it is about to show. Anything typed in the meantime wins (`linesRef.current.length > 0`),
    and the cursor is sent to the start of the bill again once the lines land, because it was put
    at the start of an *empty* one a moment earlier.
  - **The agent writes through a temporary file and moves it into place**, so a counter switched
    off mid-write leaves either the bill before it or the bill after it, never half of each. It has
    its own lock: the bill on screen has nothing to do with which printer the counter has.
  - **A counter clears any `localStorage` draft it finds**, so one left under an address the
    counter used to use cannot come back.
  - **The sign-in token is per address too**, and deliberately left that way: reaching the shop at
    a new address means signing in again, which is a new session on that till.
- **Log out is asked, never done on one click** (`ConfirmSignOut` in `App.tsx`). It sits at the
  foot of the menu one keystroke from the screens above it. With a bill on screen the only two
  answers are "Back to the bill" and "Hold the bill, then log out" - deliberately no "log out
  anyway", because the bill is better on Held bills than gone. Billing reports its line count
  through `onCartChanged` and holds on a bumped `holdRequest`, answering `onHeldForSignOut`; a
  hold the server refused leaves the person on their bill with the reason **in the dialog**.
  - **A bumped request is dealt with once** (`handledHold`). The grid unmounts and is built again
    at every sign-out, so a request left over from the last person re-ran on mount, held nothing,
    and signed the next person straight back out - 200 on login, 204 on logout, in the same second.
    Any new request-counter prop to a screen that remounts needs the same guard.
  - **The menu closes when the dialog opens**, and that is not tidiness. `MenuDrawer` listens for
    keys on the *window in the capture phase* (`addEventListener('keydown', onKey, true)`), before
    anything else sees them, so left open behind the dialog it took the arrows and walked the focus
    ring down its own list. **A dialog opened from the menu closes the menu**, or it has no keys.
  - **The grid is `blocked` by the dialog as well as by the menu** (`blocked={menuOpen || leaving}`).
    Two things come free: the bill behind stops answering arrows and Enter, and closing the dialog
    puts the cursor back in the cell it was in - the `[active, blocked]` effect in `Billing.tsx`
    already does that for the menu, and it is the same return as Esc Esc. **Any new full-screen
    dialog in `App` belongs in `blocked` for the same two reasons.**
  - **Tab is swallowed inside the dialog.** Nothing in it is reached by tabbing, so Tab only ever
    takes the cursor somewhere it cannot be used: the bill behind, or the browser's own chrome.
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
- **Items is one screen where Products and Stock were two** (`screens/Items.tsx`; the two old files
  are gone). They listed the same rows from the same table and made somebody remember which of
  them held the price and which held the count. One list, one sort, one search, and six actions per
  row: Edit, History, Purchase, Correct, Stop/Start, Delete. `masspos.screen` holding `products` or
  `stock` is mapped to `items` in `remembered()`, or the machine that used them most would come
  back to a screen that no longer exists.
  - **Space ticks a row for an order**, and Esc puts the ticks down again (with
    `stopPropagation`, so it does not count toward Esc Esc back to billing while there is something
    in hand). Billing's Space takes the bill; nothing on this screen competes for it, and the
    search box is a text field, where Space is a space.
  - **Purchase is the only way a count goes up by buying**, and it opens `OrderDialog` with
    whatever is ticked - or with the row the cursor is on when nothing is.
- **Nothing on a screen takes a delivery any more.** `POST /api/stock/receipts` and
  `POST /api/raw-materials/{id}/purchases` are gone, not hidden: stock reaches a count through
  `PurchaseOrderService.receive` and nowhere else. `VendorPicker` went with them, and so did
  `GET /api/stock/vendors` - a delivery now carries the vendor off the order.
  - **Opening stock is a correction, not a purchase.** A shop installing this has 400 items on its
    shelves that it did not buy today; `CorrectionReason.OPENING_STOCK` is how they get counted,
    and it is what the tests use to put stock on a shelf.
- **One Correction dialog for both stores** (`components/CorrectionDialog.tsx`, `source: 'stock' |
  'raw'`). It asks why first, because the reason decides the movement - `DAMAGED` writes a DAMAGE,
  `SENT_BACK` a PURCHASE_RETURN, a stock take an ADJUSTMENT - and it replaces the Received, Correct
  and Damage buttons that did the same arithmetic three ways, one of which did not stop at empty.
  - **The labels come from the server** (`GET /api/stock/correction-reasons`), because the server
    writes the same label into the note the history will show. Two copies of a word drift.
  - **A sign-constrained reason turns the quantity round** rather than refusing it: nobody means
    "damaged minus five". The box's own label changes with the reason, and says what the count will
    read afterwards.
- **The order dialog searches like the billing grid** (`components/OrderDialog.tsx`): type a code or
  part of a name, matches appear as you type from both lists at once, ↑↓ walk them, Enter or Tab
  takes one and the cursor lands on the quantity. Ranked exact code → code prefix → name prefix →
  contains, capped at 12, the same order the billing grid uses, because it is the same hand doing
  the same thing. Picking on the last line opens another, so a long order is one hand on the keys.
  - **Its Esc closes the dropdown and nothing else** (`stopPropagation` + `stopImmediatePropagation`),
    or the dialog behind it would close with the list.
  - **Both lists are loaded once into the dialog** rather than asked per keystroke: the catalogue is
    already loaded on these screens, and a server-capped answer would rank the wrong rows.
- **Where a count came from is a screen** (`components/StockHistory.tsx`, `GET /api/stock/history`).
  A count on its own has to be taken on trust, and the first thing anybody does when the screen
  says four and the shelf holds six is ask why. **F6** on Items and Non billable items opens the
  item the cursor is on: every movement ever recorded against it, newest first, each with what the
  shelf held once it had been applied, and with the till, the person and the time.
  - **The reason column may wrap, and is the only one that may.** Every other column is fixed, so a
    table 56rem wide left it about a centimetre across in the whole-shop view and cut
    "Bill T1-171126-0004" - the one thing somebody opens this screen to read - to "Bil…". It is
    `whitespace-normal break-words` now, and the table is `min-w-[74rem]` when the Item column is
    there. A bill number broken across two lines is readable; a truncated one is not.
  - **The running count is only offered where it is honest.** `InventoryService.historyOf` walks it
    backwards from today's count, which works over a whole history and not over a window that
    starts in the middle - so the shop-wide view (**F3**, last 30 days) sends `balanceAfterMilli`
    null and the column reads "—" rather than a number somebody might believe.
  - **The ledger's names are not the shop's.** `WHAT` in the component turns `ADJUSTMENT` into
    "Correction" and `SALE` into "Sold"; `KINDS` gathers the ten ledger types into the five things
    people actually ask for - what came in, what sold, what came back, corrections, losses.
  - **A sale names its bill in the movement's note**, the way a return and a cancellation already
    did (`InventoryService.sell`). That is how the screen answers "where did five of these go"
    without the inventory package reading the billing package back, which would be a cycle for one
    string. Movements recorded before this build have no bill on them.
  - **Read-only, deliberately.** The ledger is append-only, which is what makes the count worth
    anything; a correction is made with **Correct** and turns up here as its own line with the
    reason written on it.
  - **A dialog focuses itself at once *and* again through a frame** (`useLayoutEffect` +
    `requestAnimationFrame`). At once, because F6 then F2 is one movement of a hand and a frame is
    long enough for both keys to arrive - the second would land on the screen behind. Again through
    a frame, because the screen underneath hands focus back to its own list when a dialog closes
    and does that through a frame, so Esc then F6 would otherwise leave the cursor behind. **Both
    were real, and both were found by driving it.**
- **What an item has cost is its own screen** (`components/PriceHistory.tsx`, **F8**,
  `GET /api/purchase-orders/prices`). An item carries one buying price - whatever the last lorry
  charged - and that answers none of the questions a shopkeeper asks. Every delivery, with the
  vendor, the quantity, the price each, the order it came in against and who approved it; the
  dearest row is marked. Opened from Items, from Non billable items and from any line of an order.
- **Sorting is one module** (`sorting.ts`: the seven fields including stock value, the comparator,
  and the per-machine `masspos.itemSort`), with `SortMenu` as a popover that *takes focus* - a grid
  cell that kept focus behind it would still receive the arrows. **`SortMenu` takes its fields and
  labels as props**, so the orders list uses the same popover with its own eight fields. F4 opens
  it; ↑↓ pick the field, ←→ the direction, Enter and Esc both close and hand focus back, because
  every key applies at once and there is no pending state to discard. Its Esc calls `stopPropagation`,
  so it never counts toward Esc Esc back to billing. Code and name compare through an `Intl.Collator`
  with `numeric: true`, or "10" sorts before "2" in a shop whose codes are numbers.
- **The Items list is the whole catalogue, filtered and sorted on screen** (`?all=true`), stopped
  items included. `?q=` stays as it was for the billing grid - ranked by closeness, capped at 25,
  sellable only - because sorting a server-capped 25 rows would sort the wrong 25.
- **Items, Non billable items and Purchase orders share one list pattern** (and Bills a close
  cousin): a focused `div role=grid`, arrows for row and action, Enter to run, dialogs returning
  focus to the list. The tables are `table-fixed` with a colgroup and `truncate` + `title` on the
  name, so a 150-character name cannot push the columns off screen.
- **The orders list sifts what the server sent** (`screens/Purchases.tsx`). Only the dates and the
  state go to the server; the word somebody typed, the money chips and the sort are applied here,
  over the window that came back - the same reasoning as the Items list, and the search covers the
  things on an order's lines so it can be found by the onions on it.
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
- **Held bills** are not invoices: no number, no stock effect, deleted on resume. Listed newest
  first with **the id breaking the tie** (`HeldBillRepository.heldOn`): `createdAt` is a
  millisecond, two bills held in the same breath share one, and the order was then whatever the
  database felt like - a flake that failed the suite about once in ten runs. Every key here is a
  UUIDv7, which carries its timestamp in its leading bits, so `id desc` settles it correctly.
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

### What a till is allowed to say

- **A refusal on a screen is a sentence written for a shop; everything else goes to the log.** The
  person reading it is standing at a counter with a customer waiting. They cannot act on a class
  name, cannot repeat a stack frame down the phone, and should never be shown one. So every message
  says what happened in the shop's own words and what to do next - "Try again, and if it keeps
  happening call service", "Choose one under Settings, Printer" - and the exception, its stack, the
  SQL, the file paths, the ids, the property names, the ports and the status numbers go to
  `log.error`/`log.warn`, where whoever is called out will look.
- **`ApiExceptionHandler.forTheScreen` is the gate, and it is one place on purpose.** Every refusal
  the API makes passes through that class, so the check lives there rather than at twenty throw
  sites: a message matching `INTERNAL` (our own package, a JDK or Spring class, `SQLITE`,
  `constraint failed`, `jdbc:`, a `C:\` path, a UUID, a `pos.*` property, a stack frame) is logged
  in full and replaced with a written sentence. Wording that slips in later is caught without
  anybody remembering to look for it. **Add to the pattern rather than to a checklist.**
  - `@ExceptionHandler(Exception.class)` catches what nobody saw coming. Before it, an unexpected
    failure fell through to the framework's own error page, which answers a cashier with the word
    "Internal" and a number. It keeps the status of anything that is already an `ErrorResponse` - a
    page that is not there is a 404, not "something went wrong on this till" - and logs the rest at
    `error`.
  - **`IllegalArgumentException` is a 400 carrying its own message and `IllegalStateException` a
    409**, which is why the guard matters: those two are thrown both by code written for a shop
    (`SaleService`, `ThemeRules`) and by invariants only ever meant to fail in a test (`UuidV7`,
    `LedgerEventType`, `AuditTrailGuard`).
- **A commit failure never shows what the database said.** `commitFailed` logs the cause and says
  "That could not be saved." A unique-constraint clash is named by its *field* through
  `FieldLabels`, never by its column: `product.barcode` means nothing to a cashier.
- **Not found means "not here any more", not an id.** Roughly twenty `EntityNotFoundException`
  messages used to carry the UUID that was looked up. They say "That is not on this till any more.
  Refresh the screen and try again." The two lookups *by number* keep the number, because that is
  what the cashier typed and the thing they can correct.
- **`api.ts` guards the browser end too**, for the refusals no Java handler wrote:
  - A failed `fetch` is caught and becomes an `ApiError` with status 0 and a written sentence. The
    browser's own wording is "Failed to fetch" or "NetworkError when attempting to fetch resource".
  - A response body that is not JSON is **dropped** (`safeParse` returns null). Every refusal this
    server makes is JSON, so anything else is a page the servlet container wrote on its own, and
    showing it whole is how a stack trace ends up in front of a cashier.
  - `looksInternal()` is the same check as the server's, for a `detail` that came from a filter or a
    library underneath it. Below both, the status decides the sentence - and **no status numbers**:
    a number a cashier cannot act on is worse than a sentence they can.
- **A filter cannot throw its refusal, and `sendError` loses it** (`web/Refusals`). `AuthFilter` and
  `LocalApiGuard` run before any controller, so `ApiExceptionHandler` never sees them.
  `response.sendError(status, message)` looks like the answer and is not: Boot's
  `server.error.include-message` defaults to `never`, so the message is dropped and what the screen
  receives is the container's own error body - `{"status":401,"error":"Unauthorized","path":
  "/api/products","timestamp":"…"}`. The sentence somebody wrote never arrived. Both filters now
  write a `ProblemDetail` themselves, with a `detail` and nothing else in it; the real reason - a
  token that is not ours, a Host that is not this machine's - goes to the filter's log, because a
  refusal is also the one answer an attacker gets. **A new filter writes its answer through
  `Refusals`, never `sendError`.**
- **The machine ID and the licence dates stay on screen.** They look technical and they are not
  optional: the shop reads the machine ID to its supplier to get a key, so `LicensePolicy` prints
  it deliberately. What came out of it - `reg.exe`, a registry path, an IO message - does not
  (`OsMachineIdentity` logs those and throws a clause like "Windows would not answer", which reads
  inside the Licence screen's sentence).
- **A counter has nowhere else to look, so its wording has to carry more.** The print agent's
  `{"error": …}` and its `lastError` are the whole of what the printer screen shows, with no log on
  screen behind them: `PrintAgent.wordsFor` writes to `counter.log` and substitutes a sentence when
  an exception has no message, rather than letting `toString()` put a class name on a manager's
  screen. Same for the setup page - every `catch` in `setup.html` has its own sentence.
- **The desktop shortcut tells the two apart by type.** `DesktopLauncher.Refusal` is a message
  written for whoever double-clicked, shown as it is; anything else prints its stack to stderr and
  gets one sentence in the dialog. The log folder is not named on screen - it is named to the
  engineer, on stderr.
- **Tests pin this, not review.** `ApiExceptionHandlerTest` feeds the handler SQL, a stack frame, a
  UUID and a Windows path and asserts none of it reaches `getDetail()`;
  `PrintAgentTest.sendsTheNextBillToThePrinterJustChosen` asserts the printer screen's message has
  no `Exception`, `java.` or `com.masspos` in it; `SerialPrinterConnectionTest` asserts the
  unconfigured-port message names the screen to go to and not the property.

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
- **A purchase order is the gap between asking and buying** (`com.masspos.purchase`). A manager
  writes down what is needed, an administrator agrees to the money, and the counts move only when
  the lorry arrives. Before this, a delivery was the first anybody heard of a purchase.
  - **Receiving writes no stock movements of its own.** It calls `InventoryService.receive` and
    `RawMaterialService.purchase`, which are now the only callers either has - no screen takes a
    delivery any more. A delivery lands in the history with the vendor, the price and the order
    number on it. **Never add a second way for stock to move.**
  - **What arrived is not what was ordered.** Receiving takes a quantity and a cost per line and
    records what actually came; a line left out came in full at the cost on the order, which is the
    common case and should not need typing. `receivedMilli` is the only thing on a line that is not
    `updatable = false`.
  - **A line points at a product or a raw material**, two nullable keys with a constructor that
    refuses both or neither. Not a loose id with a kind beside it: the shelf and the store are
    different tables and the database should still refuse a line pointing at nothing.
  - **Approving and rejecting are `@RequiresRole({})`** - administrators only, which is the whole
    reason the order exists apart from the delivery. Writing one and taking goods in are a
    manager's job.
  - **`PurchaseOrderStatus` is five words a shopkeeper would use**, on a new table with its own
    CHECK constraint. Nothing finer: an order is asked for, decided, and either turns up or does
    not.
  - **Numbered `PO-2627-0001` from a per-year sequence**, advanced in the transaction that writes
    the order, exactly as invoice numbers are. It carries no legal weight - it is a handle for
    chasing a delivery down a telephone - but gapless still matters for that.
- **The people the shop buys from are `Vendor`s, never `Supplier`s** (`com.masspos.vendor`). That
  word is taken and means the opposite end of the shop: the steward who carried the order to the
  table, printed on hotel bills, with `supplier_id` frozen on every invoice. Freeing it would mean
  altering a populated, audited, printed column - the SQLite trap this project has been bitten by.
  So goods come *from* vendors and food goes *out* with suppliers, and the two never meet.
  - **A delivery still writes the vendor's *name* onto itself**, as `SellerDetails` does on an
    invoice: a vendor renamed next year must not rewrite what the shop bought this year. The new
    `vendor_id` beside it is only the link that lets a vendor's page gather his deliveries without
    matching on spelling. **Nullable, always** - a delivery from somebody off a lorry, or one taken
    before the shop kept a list, has a name and no link, and that is ordinary. Nullable is also
    what lets the column reach tills whose ledger is already full.
  - **A vendor's page gathers both ledgers.** A man who sells the shop bottled water for the
    counter and onions for the kitchen is one vendor; the shelf and the store are the shop's
    business, not his.
  - **Old deliveries are found by name** (`boughtFrom(vendorId, name)`), so writing a vendor down
    for the first time picks up everything already bought from them.
  - **The balance is arithmetic, not accounting**: everything bought at what it was bought for,
    less every payment an administrator has agreed to. No accruals, no postings, no accounts.
  - **A payment waits for an administrator** and is left out of the balance until then, shown as
    its own figure instead. The person who hands money over is rarely the person who decides it
    should be handed over. `@RequiresRole({})` is how "administrator only" is said: ADMIN
    short-circuits the interceptor and an empty list contains nobody else.
  - **`VendorPayment` is the one audited row here that is not immutable**, because approving it
    happens after it is written. Its amount and its vendor are `updatable = false`, so the thing
    approved is the thing that was recorded.
- **Raw materials are a table of their own, not a flag on `Product`** (`com.masspos.rawmaterial`).
  Butter, onions, oil, carrier bags: what the shop buys and uses and never sells.
  - **The billing grid is the reason.** A cashier typing "on" must never be offered Onions among
    the things they can ring up, and the surest way to see to that is for raw materials never to
    be in the catalogue at all - not filtered out of it by a query somebody later forgets. Nothing
    a product needs means anything here either: no selling price, no MRP, no GST rate, no HSN.
  - **Its own ledger too** (`raw_material_ledger_event`). `inventory_ledger_event.product_id` is
    NOT NULL with an FK to the catalogue and the table is append-only on every till in the field;
    making it nullable would be an ALTER on a populated table, which SQLite will not do. A new
    table it takes. Registered in `AuditTrailGuard` like the other one - **a new append-only table
    must be, or it is only append-only in Hibernate.**
  - **Its own movement enum** (`RawMaterialMovement`), and this is the subtle one. Hibernate writes
    `@Enumerated(STRING)` as a CHECK constraint listing every value, on the table *and* its `_aud`
    twin, and SQLite cannot alter one. Adding CONSUMED to the shared `LedgerEventType` would have
    put a value in a list every shipped till would then refuse. A new enum on a new table carries
    its own constraint. It also drops three values that can never occur here (nothing is sold) and
    adds the one a kitchen lives by: **CONSUMED**, which is where most of an onion goes.
  - **The store stops at empty, like the shelf** (`RawMaterialService.takeOut`), and what was
    actually taken off comes straight back on the view, so the screen can say "0 KGS in the store"
    to somebody who typed 30 when there were 25.
  - **One History screen serves both** (`StockHistory`, `source="stock" | "raw"`). The two have
    different tables and different words for what happened to them; what they share is the question
    - why does it say four - so `RawMaterialMovementView` is field-for-field the same shape as
    `StockMovementView`, down to `itemId` and `itemCode` rather than `productId` and `sku`.
  - **A derived query name can take the whole application down at startup.**
    `findByRawMaterialIdOrderByOccurredAtDesc` read `rawMaterialId` as one attribute, which the
    entity does not have, so the repository bean refused to be created and every context failed.
    A convenience `getRawMaterialId()` getter was what made Spring Data believe in it. The finders
    are spelled out with `@Query` now, and that getter is gone.
- **What a delivery cost and who it came from live on the movement** (`buying_price_paise`,
  `vendor_name` on `inventory_ledger_event`), not on the item. An item has one price now; a shop
  has a different delivery every week, and "what did we pay for these" is a question about the
  delivery. Both are optional: a shop that does not track its buying prices leaves them alone.
  - **Per unit, not per delivery.** It is the figure a shopkeeper knows and checks against the next
    lorry, and the total is the quantity times it - which the dialog shows back as it is typed,
    because a per-unit price entered as the whole delivery is a quiet, expensive slip.
  - **The vendor is a name, not a reference**, and for the same reason the seller on an invoice is
    a snapshot: a vendor renamed or removed later must not rewrite the history of what this shop
    bought and from whom. When vendors become records of their own, an event keeps the name it was
    recorded under. The vendor on a delivery now comes off the order it arrived against, so
    nothing asks anybody to type one and `GET /api/stock/vendors` has gone with the dialog that
    did.
  - **Both columns carry SQL defaults** (`bigint default 0`, `varchar(120) default ''`), which is
    the only way a column reaches a populated table on SQLite.
    `RestartOnExistingDatabaseTest.goodsThatArrivedBeforeAnybodyWasAskedWhatTheyCostStillCount`
    drops them from a ledger that has a delivery in it, restarts, and proves the count that
    delivery contributed has not moved.
  - **Nothing on a screen is asked a price or a vendor any more.** They come off the purchase
    order, and `ReceiptRequest` went with the endpoint that took one.
- **Stock reaches a count through a purchase order and nowhere else.** `POST /api/stock/receipts`
  and `POST /api/raw-materials/{id}/purchases` are deleted, with `/adjustments`, `/damages`,
  `/wastage` and `/vendor-returns`; what is left is `POST /api/stock/corrections` and the store's
  own `/corrections`. A manager typing a delivery into a screen was how a purchase reached a shop
  with nobody having agreed to the money, and a rule that only a screen enforces is not a rule -
  which is why the endpoints are gone rather than hidden
  (`StockGuardApiIntegrationTest.refusesToPutGoodsOnTheShelfByHand`).
  - **What a shop already had is a correction, with Opening stock as its reason.** A shop
    installing this has 400 items on its shelves it did not buy today, and making it write a
    purchase order to itself for them would be a lie about where they came from. It is also how
    every test now puts stock on a shelf.
- **`CorrectionReason` is deliberately not persisted** (`com.masspos.inventory`). It decides which
  ledger type a correction writes - a stock take an ADJUSTMENT, something broken a DAMAGE, goods
  going back a PURCHASE_RETURN - and is recorded in the movement's note, so no ledger enum gains a
  value. A new value in a persisted enum is a CHECK constraint SQLite will not alter on a till that
  is already in a shop; this is how to add vocabulary without touching one.
  - **The shelf maps it; the store maps it separately** (`RawMaterialService.inTheStore`), because
    the two have their own enums and their own tables and should keep them.
  - **The label is the server's**, served by `GET /api/stock/correction-reasons` and written into
    the note by `CorrectionReason.wording`. The dialog shows the same string it will be filed
    under, which two copies of the word would not survive.
  - **A correction is capped at empty in both directions**, as every other way out of a count is.
    The old Damage path was not, which is one of the ways a count could still go below zero.
- **The order a delivery came in against is a column, not a note** (`order_number` on
  `inventory_ledger_event` and `raw_material_ledger_event`, `varchar(16) default ''`). "What have we
  paid for these over the years" is then one query; parsing it back out of "Order PO-2627-0001"
  would be a string contract between two packages. Empty on everything that is not a delivery, and
  on deliveries tills took in by hand before orders were the only way in.
  `RestartOnExistingDatabaseTest.goodsThatArrivedBeforeAnybodyWasAskedWhatTheyCostStillCount`
  drops it along with the price and the vendor and proves an upgraded till still counts.
- **What an item has cost is read from the ledgers** (`PurchasePrices`), not from the orders: a
  delivery is where a price is a fact, and an order that was never received never cost anything.
  The approver is looked up once per distinct order number (`PurchaseOrderRepository.byNumbers`),
  not once per row.
- **A vendor payment may name the order it was for** (`VendorPayment.purchaseOrderId`), as a bare
  UUID column and not a mapping: the purchase package already reads the vendor package, and a
  reference back would be a cycle for one column. Nullable and always will be - a shop settling a
  running account is not paying for one delivery.
  - **An order's payment state is worked out, never stored** (`PurchaseOrderView.settled`), from
    approved payments only, exactly as a vendor's balance is. A shop that pays off a balance leaves
    every order UNPAID, which is the honest answer: nobody said which delivery the money was for.
  - **Nothing is owed until the goods are in the shop.** An order waiting for an administrator is a
    request and an approved one is a promise; neither is a debt. `owedPaise` is what actually
    arrived, at the costs on the order, and zero before that - or every shop would open the screen
    to a figure it does not owe anybody.
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
  (`SaleService` checks both). The exemption is about refusing the sale, never about skipping the
  movement.
- **The count stops at empty, always** (`InventoryService.sell`). A sale takes off what the shelf
  has and no more, whatever it bills for; a sale from an empty shelf writes no ledger event at all,
  because `LedgerEventType.checkDelta` refuses a zero one and it would say nothing anyway.
  - **A count below zero is not a fact about a shop.** It used to go negative on the reasoning that
    the goods had left, so the ledger should say so. In a kitchen it compounds: a restaurant's
    screen read **-357** of a biriyani it cooks to order, -37 of another and -25 of a third, which
    tells nobody anything and makes every figure beside it worth ignoring (2026-11-16).
  - **The bill is untouched, and the bill is the record of what was sold**: the money, the tax and
    the customer's copy all carry the full quantity. The ledger is the record of what the *shelf*
    had. So nothing is lost - the invoice line against the ledger event gives what went out over
    and above the count, for any report that wants it.
  - **Cancelling puts back what the bill took off, not what it sold** (`InventoryService.putBack`
    over `InventoryLedgerRepository.movedBy`). Reversing the quantities on the bill would conjure
    stock onto a shelf that was empty when it was billed from: five sold off a shelf of three put
    five back and left two from nowhere. Per product, not per line, or the same item on two lines
    of one bill comes back twice.
  - **A return is not a cancellation and is deliberately left alone.** Goods coming back are
    physically in somebody's hand, so SALE_RETURN adds them whether or not the sale ever took them
    off - the shop really does have them now.
  - **A correction cannot go below empty either** (`InventoryService.adjust`, and the same in
    `RawMaterialService.adjust`). A stock-take is the shop telling the till what is on the floor,
    and what is on the floor is never less than nothing: a correction big enough to take it under
    is somebody saying "there are minus five", which is not a count of anything. This was the one
    way left in.
  - **Counts recorded by an older build are put right, not rewritten** (`bringUpToZero`, offered on
    the Items screen when there is something to correct, `GET /api/stock/below-empty` to ask what).
    One ADJUSTMENT per item, each carrying its reason, so the history reads as the stock-take it is
    rather than as a number that changed itself. The ledger is append-only; nothing else would be
    honest.
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
  - **One account, one till at a time** (`AuthService.settleSessionsElsewhere`). Signing in where
    that account is already open somewhere else stops with a 409 carrying `sessionConflict` and
    the till's details, and the person chooses: go back, or `takeOver: true`, which signs the other
    one out. Not refused outright and not allowed quietly - one person moving between two tills is
    ordinary, and so is a cashier whose last machine was carried off without them signing out, and
    only the person standing there can tell those apart. Sessions on the till being signed in to
    are not in question; `endEarlierSessionsOn` has already dealt with those.
    - **It is thrown after the writes `admit` and `endEarlierSessionsOn` have made**, which is safe
      because `signIn` is `@Transactional` and this is a `RuntimeException`: asking the question
      changes nothing.
    - **Tests that need two counters billing at once need two cashiers.** `ClientsApiIntegrationTest
      .cashierAt` makes one per counter, and signs it out afterwards - creating an account signs it
      in on the server's own till to get through the forced PIN change, and that open session is
      itself "signed in somewhere else".
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
- **A counter shows no windows of its own** (`CounterSetup`, `setup.html`, served by `PrintAgent`
  at `http://127.0.0.1:<agent port>/setup`). Setting a counter up was a Swing dialog for one
  reason: everything it decides has to be settled *before* there is a shop server to serve a page.
  The print agent is a web server on that machine which starts before anything has been found and
  whether or not the shop is reachable, so the page comes from there and the whole product is one
  kind of thing. Consequences:
  - **A request the agent does not answer is a blank page, and the counter has no second screen**
  (`PrintAgent.answering`). `HttpServer` answers an escaped exception by closing the connection
  with nothing in it, which a browser shows as `ERR_EMPTY_RESPONSE`: a Refresh button and no words
  at all. 0.4.16 shipped a counter jar without `setup.html`, so `CounterSetup.page()` threw an
  `IOException`, `onSetup` caught only `RuntimeException`, and setting up a counter was a blank
  browser error on the counter machine *and* on the server machine, with nothing in any log.
  Every handler is now wrapped: anything that escapes is written to `counter.log`, and the
  connection gets a sentence - HTML when the caller asked for a page, JSON otherwise.
  **A new context is registered through `answering`, never raw.**
  - **The page is a resource inside the jar, so nothing fails to compile when it goes missing.**
    Three things catch it now: `forceCreation` on the counter jar execution (the plugin can
    otherwise keep a jar it believes is up to date), `build-installer.ps1` counting
    `com/masspos/counter/setup.html` inside the jar it is about to ship, the way it already counts
    the server's `index.html`, and `PrintAgentTest.servesTheSetupPageWithoutAShopServerAnywhere`,
    which fetches it from a live agent. The tests run against `target/classes`, so **only the
    installer check sees the artifact a shop actually gets** - which is why it is there and not
    just a test.
- **The agent must start even with nothing configured.** It is the only screen a counter that
    cannot reach its shop has. `CounterApp.listening` tries the agent port and the ten after it,
    because a counter with no agent has no printer *and* no way to be set up.
  - **Setup requests are same-origin only** (`notThisMachine`): they are the only ones that change
    which shop a counter bills against, and a browser sends its own origin on a cross-site POST.
    The rest of the agent still answers the shop server's page, through `cors`.
  - **A refused counter is sent there by the browser**, not by the program: `goToCounterSetup` in
    `counter.ts` navigates to the page with the reason on the query string. Nothing has to signal
    across processes any more, so the agent's settings-request queue is gone.
  - **The one thing with no screen left** is an agent that cannot bind any port at all. That goes
    to `counter.log` in the counter's folder, because there is genuinely nowhere else.
  - **The page is reached from the sign-in screen, not only from a refusal.** It used to open one
    way: the shop turned the machine away and `goToCounterSetup` sent the browser there with the
    reason on it. So a counter pointed at the wrong shop, or carried to another one, could only be
    put right by closing GO RockIt Counter and starting it again - and nothing about changing one
    of those settings needed a restart, it only needed a door. The sign-in screen has one in the
    corner (`CounterSettingsCorner` in `Login.tsx` → `openCounterSetup`), drawn only where
    `hasPrintAgent()` is true: the server's own browser has no agent to ask and no counter settings
    to show. Signing out is exactly when somebody changes one of these things, and the sign-in
    screen is what they are looking at while they do.
  - **A click on the shortcut asks the shop before opening a window on it** (`CounterApp
    .windowFor`). A counter already running here used to be sent straight to its last server. One
    that answered this morning may not answer now, and a browser sent to a shop that cannot answer
    shows its own error page - a cloud, a Refresh button, and no way at all back to this counter's
    settings. It goes to the setup page instead, which is served from this machine and cannot fail.
  - **It is the same page, deliberately - not a counter settings screen in the React app.** A
    second screen asking the same two questions is a second set of words to keep in step, and the
    one inside the app would be the one that cannot be reached when the shop's server is down,
    which is exactly when it is wanted. So the corner button opens `/setup` on this machine's own
    agent: the page a counter serves itself at first start, unchanged. A counter that is already
    billing gets one thing a new one does not - `CounterSetup.state` carries `billing`, the screen
    it came from, and the page shows "Back, changing nothing", which Escape also does. A counter
    with no till of its own and no server it has ever reached gets no such button, because there is
    nowhere to send it and this page is the only screen that machine has.
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
