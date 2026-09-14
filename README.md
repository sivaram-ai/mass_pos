# Mass POS

Offline-first billing and inventory for Indian retail, shipped as a single desktop installer. GST
tax invoices, stock that survives being offline, an append-only audit trail for MCA Rule 11(g), and
receipts printed straight to a thermal printer. No database server, no printer driver, no internet
needed to bill.

Built to be sold to more than one shop: supermarkets, restaurants and counters with several tills
on different floors.

> **Status:** the till works end to end — sign in, build a bill, take payment, print, cancel,
> reports. Counter-to-master sync over the LAN is designed but not built yet (see
> [docs/multi-terminal-and-rbac.md](docs/multi-terminal-and-rbac.md)).

## What it does today

| Area | What you get |
|---|---|
| Billing | Keyboard-first grid: code or name lookup in each row, rate, quantity and discount cells, Space to take the bill, hold and resume, split payments (cash / UPI / card / voucher), change due, print |
| GST | Tax-inclusive or exclusive pricing, CGST + SGST or IGST by place of supply, cess, round-off to the rupee, gapless numbering per till. Shops without a GSTIN bill without tax |
| Stock | Goods receipts, corrections, damages; stock is the sum of movements, never a number that gets overwritten |
| Bills | Day list, full bill view, reprint as DUPLICATE, cancel with a reason (stock goes back) |
| Reports | Day takings by tender, cashier and till; best sellers; GST rate-wise and HSN summaries for GSTR-1; the audit trail |
| Staff | Cashier, manager, auditor and admin accounts; PIN sign-in that lasts the shift |
| Settings | Company and GST details, receipt footer, service contact, rounding, keyboard shortcuts, the shop's tills |

## Quick start

You need a **JDK 21**. Everything else (Maven, Node) is fetched by the build.

```powershell
.\mvnw.cmd -B package
java -jar target\mass-pos.jar
```

Then open **http://127.0.0.1:8765**.

1. The first start creates an `admin` account and writes its PIN into `<data-dir>\logs\pos.log`.
   Sign in with it; the app makes you choose your own PIN straight away.
2. Go to **Settings** and fill in the shop's legal name and address. Billing stays blocked until
   both are set. Add the GSTIN if the shop is registered for GST: bills are then tax invoices.
   Without a GSTIN the shop bills without GST: no tax is charged whatever rate an item carries,
   and the receipt is a plain "BILL". HSN/SAC codes and the CIN are optional too.
3. Add a product under **Products**, record what arrived under **Stock**, and bill it.

Data lives in `%LOCALAPPDATA%\MassPOS` (Windows) or `~/MassPOS`: the database, logs,
`pos.properties` and, when no printer is configured, the receipts as text files under
`printer-out`.

For UI development with hot reload, run the backend as above and `npm run dev` in `frontend/`
(port 5173, proxying `/api` to 8765).

## Using the billing screen

The bill is a grid: **S.No · delete · Code · Name · Rate · Qty · Disc · Amount**. The green row at
the bottom is where the next item goes. Type an item code (or scan a barcode) in **Code**, or part
of a name in **Name**; a list opens under the cell, `↑`/`↓` pick and `Enter` puts the item on the
row. The cursor then lands in **Qty** with 1 selected, so typing the count and `Enter` moves on to
the next item. An item saved without a price lands in **Rate** first, marked "Enter rate", and
the quantity cannot be reached until a rate is typed.

Every row stays editable: `←`/`→` move across its cells, `↑`/`↓` move to the row above or below,
and typing a new code or name in an old row replaces that item. The red bin (or `F5`) deletes any
row.

Ringing up the same item again straight after itself adds to that row instead of starting a new
one. Rows fold only when **both the code and the rate** match, and only into the row directly
above, so the bill keeps the order items were rung up in:

| Rung up | Rows on the bill |
|---|---|
| 2, 2 | 2 × 2 |
| 2, 1, 1 | 2, 1 × 2 |
| 2, 1, 1, 2 | 2, 1 × 2, 2 |
| 2, 1, 1, 2, 2 | 2, 1 × 2, 2 × 2 |
| 2 at ₹70, 2 at ₹75 | two rows (different rate) |
| 2 at ₹70, 3 at ₹70 | two rows (different code) |

The new row folds when the cursor leaves it (`Enter` after the quantity, an arrow key, a click) or
when the bill is taken or held, so its quantity and rate can still be typed first.

The header is one line: **Menu** (the screens, and Log out, slide in from the left), the shop and
till, date and clock, whether bills carry GST, the customer, and the till's last bill and amount.
The footer lists the shortcuts, which can also be clicked (hide the list under Settings → Keyboard
shortcuts), then items and quantity, the total, and **Take bill**. The bill on screen and the last
bill stay put while another screen is open, and the last bill is fetched again after a restart,
so `F10` always has something to reprint.

On the payment screen the modes run down the left: Cash, UPI, Card, Voucher. `↑` `↓` change the
mode, `Enter` (or `Space` in the amount) takes the bill, and an amount below the total splits the
bill across modes. For cash, type what the customer hands over to see the change. With the amount
left empty, a cash bill is taken for the exact total, unless **Ask for the cash received before
taking a cash bill** is ticked under Settings → Keyboard shortcuts (per machine): then it asks.

Every key can be remapped in Settings; these are the defaults.

| Key | Action |
|---|---|
| `Space` | Take the bill: opens the payment screen. Inside a name being typed, Space is just a space |
| `F2` | Go to the new item row |
| `F3` / `F4` | Go to the quantity / discount of the current row |
| `F5` | Delete the current row |
| `F6` / `F7` | Hold a bill / list held bills: `↑` `↓` choose, `Enter` resumes (a bill already on screen is held first), `Delete` deletes |
| `F8` | Open the drawer without a sale (asks for a reason, and records it) |
| `F10` | Reprint the last bill as a duplicate |
| `F1` | Customer name, and for a GST shop the customer's GSTIN and state |
| `Insert` | Edit today's bill (manager). Asks for the bill number (the last bill is filled in; its serial, e.g. `42`, is enough) |
| `F12` | Return (manager). Asks for the bill the goods were sold on; leave it empty for a return without the bill |
| `Esc` | Cancel what is being typed in a cell; otherwise clear the bill, or stop an edit or return (asks first) |
| `Esc` `Esc` | On any other screen: back to billing |

Parked bills take no invoice number and hold no stock: nothing is sold until the bill is taken.
Held bills are listed newest first.

### Changing a bill after it was taken

- **Edit** (`Insert`, or Edit on the Bills screen): today's bills only. The bill opens on the grid;
  change, add or delete rows and press `Space`. In one step the old bill is cancelled (its stock
  comes back), a new bill is printed marked "Replaces bill …", and only the difference changes
  hands: collected on the payment screen when the new bill costs more, or shown as
  **Give back** when it costs less. Money already paid carries over to the new bill.
- **Return** (`F12`, or Return / Return without a bill on the Bills screen): issues a credit note
  numbered in its own series (`T1-2627-R0001`) and prints it with the refund. Against a bill, its
  lines load with the quantity still returnable; delete what is not coming back and lower the
  quantities. The refund is that bill's own share for each item, so returns can never add up to
  more than the bill. Without a bill, items are priced like a sale. The stock goes back either way.
- A bill that has a return against it can no longer be edited or cancelled (that would put the
  same stock and money back twice); anything more coming back is another return.
- Reports and the Bills screen show takings net of returns; the GST summary nets credit notes off
  the rate-wise and HSN figures.

## Who may do what

| Role | Can do |
|---|---|
| **Cashier** | Bill, park and resume, reprint, open the drawer with a reason |
| **Manager** | Also products, prices, stock, cancelling or editing a bill, returns and refunds, day and GST reports |
| **Auditor** | Read-only: every report, GST figures and the audit trail. Cannot bill or edit. |
| **Admin** | Everything, plus staff accounts and shop settings |

Signing in gives a token that **never expires** — a cashier stays signed in until Log out, which is
what a shop floor needs. Changing a PIN signs that person out of every *other* till. A new or reset
account must choose its own PIN before it can bill, so bills are never taken under a handover PIN.

## Settings: what lives where

| Where | What | Why |
|---|---|---|
| **Settings screen** (in the database) | Company name, GSTIN, address, CIN, FSSAI, UPI ID, receipt footer, service contact, rounding | The shop owner edits these; they replicate to other tills |
| **`<data-dir>\pos.properties`** (per machine) | Terminal code, name, floor, printer port and paper width, server port, data directory | These differ per machine, and are needed before the app can start |
| Environment variables | Any property, upper snake case, e.g. `POS_PRINTER_TYPE=SERIAL` | Useful for installers and kiosks |

A commented `pos.properties` template is written into the data directory on first start. Values
there also seed the shop settings the very first time, so an installer can pre-fill a shop.

`.properties` files are read as ISO-8859-1: stick to plain ASCII, and write `Rs.` for ₹.

Key machine settings:

| Property | Default | Meaning |
|---|---|---|
| `pos.terminal.code` | `T1` | Invoice prefix, unique per till (`T1-2627-00001`) |
| `pos.terminal.name` / `.section` | `Counter 1` / empty | Shown in reports and on the settings screen, e.g. "1st floor" |
| `pos.terminal.type` | `COUNTER` | `MASTER` holds the shop's consolidated database |
| `pos.printer.type` | `FILE` | `SERIAL`, `TCP` or `FILE` |
| `pos.printer.serial.port` | empty | `COM3`, `/dev/ttyUSB0`, … |
| `pos.printer.columns` | `48` | `48` for 80 mm paper, `32` for 58 mm |
| `server.port` | `8765` | Also `POS_PORT` |

## Several tills in one shop

Wire each till to a switch, give the master a fixed address, and let each machine bill on its own
database. Sales and stock flow counter → master; catalogue, staff and settings flow master →
counter. The full reasoning, including why a shared database file over a network drive destroys
shops' data, is in [docs/multi-terminal-and-rbac.md](docs/multi-terminal-and-rbac.md).

The UI port stays on loopback even on the master, so nobody on the network can drive a till's
screen or open its cash drawer.

## Receipt printer and cash drawer

- **SERIAL** covers RS-232 printers and USB printers that appear as a COM port. Most USB printers
  need the vendor's virtual COM port driver; one installed only as a Windows printer (`USB001`) is
  invisible to jSerialComm.
- **TCP** is for network printers on raw port 9100.
- **FILE** writes each receipt to `printer-out` as bytes plus a readable preview — the default, so
  the app is usable before any hardware arrives.

The drawer plugs into the printer's DK port. It opens with an original receipt when the bill is
paid in cash, never on a reprint, and a "no sale" opening asks for a reason and logs it.

Receipts carry the GST Rule 46 fields, plus CIN and FSSAI when set, the tender and change, a
rate-wise tax summary, and a UPI QR code if you set a UPI ID. Printing happens after the sale is
saved, so a paper jam never loses a bill: the screen says printing failed and offers a reprint.

`₹` prints as `Rs.`, and Hindi or Tamil text prints as `?` — thermal printers work from a
single-byte code page. Indic receipts need image printing, which is not built yet.

## HTTP API

Every state-changing call needs the `X-POS-Client` header and a bearer token; requests whose `Host`
is not a loopback name are refused. Together these stop a web page open on the till from driving it.

| Area | Endpoints |
|---|---|
| Sign-in | `POST /api/auth/login`, `/logout`, `/change-pin`, `GET /api/auth/me` |
| Staff | `GET/POST /api/users`, `PUT /api/users/{id}`, `POST /api/users/{id}/pin` |
| Catalogue | `GET /api/products?q=`, `POST /api/products`, `PUT /api/products/{id}`, `POST /api/products/{id}/deactivate` |
| Stock | `GET /api/stock`, `POST /api/stock/receipts`, `/adjustments`, `/damages` |
| Billing | `POST /api/sales`, `POST /api/sales/quote`, `GET /api/invoices`, `GET /api/invoices/last`, `GET /api/invoices/lookup?number=`, `GET /api/invoices/{id}`, `POST /api/invoices/{id}/cancel`, `POST /api/invoices/{id}/replace` (edit), `POST /api/invoices/{id}/receipt` |
| Returns | `GET /api/invoices/{id}/returnable`, `POST /api/returns/quote`, `POST /api/returns`, `GET /api/returns`, `GET /api/returns/{id}`, `POST /api/returns/{id}/receipt` |
| Held bills | `GET/POST /api/holds`, `DELETE /api/holds/{id}` |
| Reports | `GET /api/reports/day`, `/gst`, `/audit` |
| Settings | `GET/PUT /api/settings` |
| Hardware | `GET /api/hardware/printer/status`, `/serial-ports`, `POST /api/hardware/printer/test-page`, `/drawer/open` |

Errors come back as RFC 7807 problem details whose `detail` is fit to show on screen. A refused
form also carries `errors`, one message per field (`{"gstin": "is not a valid GSTIN ..."}`), which
the screens use to mark each input. A bill attempted before the shop's name and address are set
returns `409`, not a crash.

## Compliance design

- **Audit trail (Rule 11(g)).** Every entity is audited by Hibernate Envers; each revision records
  the user, the till and the time. SQLite triggers reject UPDATE and DELETE on audit tables, and
  the app refuses to start if auditing was switched off. The Reports screen shows the log.
- **Invoices.** Numbered `{till}-{financial year}-{sequence}`, gapless per till. An issued bill is
  frozen and can only be cancelled, never edited or deleted; cancelling returns the stock with
  compensating ledger entries.
- **Seller details are copied onto each bill** when it is issued, so editing the shop's settings
  never rewrites an old invoice or its reprint.
- **GSTINs are check-digit verified**, for your own GSTIN and the buyer's.
- **Money is integer paise, tax rates basis points.** No floating point anywhere.

Engineering design, not legal advice: have your chartered accountant check the invoice format
before going live.

## Building the installer

```powershell
.\mvnw.cmd -B clean package
jlink --add-modules java.base,java.compiler,java.desktop,java.instrument,java.management,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.sql.rowset,jdk.jfr,jdk.unsupported,jdk.crypto.ec,jdk.localedata,jdk.charsets `
      --include-locales=en,hi --strip-debug --no-man-pages --no-header-files --compress=zip-6 `
      --output target\runtime
New-Item -ItemType Directory -Force target\jpackage-input | Out-Null
Copy-Item target\mass-pos.jar target\jpackage-input\
jpackage --type exe --name MassPOS --app-version 0.1.0 --vendor "Mass POS" `
  --input target\jpackage-input --main-jar mass-pos.jar --runtime-image target\runtime `
  --java-options "-XX:+UseG1GC" --java-options "-Xms512m" --java-options "-Xmx512m" `
  --java-options "-XX:MaxGCPauseMillis=20" --java-options "-XX:+AlwaysPreTouch" `
  --java-options "-XX:+UseStringDeduplication" --java-options "-XX:+ExitOnOutOfMemoryError" `
  --win-per-user-install --win-menu --win-shortcut `
  --win-upgrade-uuid 076ca0d4-3fc9-4172-8e1f-c4f9062c6f99 `
  --dest target\installer
```

Windows needs WiX Toolset 3.14 on `PATH`; `--type app-image` builds a runnable folder without it.
On macOS use `--type dmg --mac-package-identifier com.masspos.pos`.

## Project layout

```
frontend/                React + TypeScript + Tailwind; builds into src/main/resources/static
  src/screens/           Billing, Products, Stock, Invoices, Reports, Settings, Users, Login
  src/api.ts             fetch wrapper, money and quantity formatting
  src/shortcuts.ts       configurable keyboard map
src/main/java/com/masspos/
  auth/                  tokens, roles, staff accounts, first-run admin
  billing/               invoices, GST calculator, sales, held bills, quotes
  catalog/ inventory/    products, stock ledger
  settings/              shop settings (database-backed)
  reports/               day, GST and audit reports
  receipt/ hardware/     ESC/POS layout and printing
  audit/ common/ config/ web/   audit trail, ids, properties, API guards
docs/                    multi-terminal and RBAC design
```

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Billing screen says it is blocked | Fill in legal name and address under Settings |
| Cannot sign in on a new install | The admin PIN is in `<data-dir>\logs\pos.log` |
| `Cannot open serial port COM3` | Another program holds it, the name is wrong, or the printer is off |
| Garbled printing | Baud rate or flow control does not match the printer |
| Drawer does not open | Check the cable into the printer's DK port; try `PIN_5` and a longer pulse |
| App closes at startup | An invalid value in `pos.properties`; `logs\pos.log` names it |
| `403 Host not allowed` | Open the app as `http://127.0.0.1:8765` |

## Roadmap

| Phase | Scope | State |
|---|---|---|
| 1–2 | Persistence, audit trail, ESC/POS printing, cash drawer | Done |
| 3 | Roles and sign-in, terminals, GST calculator, products, stock, sales, held bills, reports | Done |
| 4 | React UI: billing, products, stock, bills, reports, staff, settings | Done |
| 5 | Counter → master sync over the LAN | Designed |
| 6 | Flyway migrations, cloud sync for multiple shops, composition scheme / bill of supply | Planned |
| 7 | Desktop shell: open the browser on start, single instance, tray icon | Planned |
