# GO RockIt: installing at a shop

For the person who sets up a till. The vendor builds the installer with
`tools\build-installer.ps1`; this page is what to do with the `MassPOS-<version>.exe` it produces.

## The shop's PC needs

- Windows 10 or 11, 64-bit, 4 GB RAM (2 GB works with a smaller heap, see CLAUDE.local.md).
- About 500 MB of disk, plus room for the database.
- **No Java and no internet.** The installer carries its own runtime; licensing is offline.
- A receipt printer if the shop wants paper bills (see step 7).

## Install, in order

1. **Copy `MassPOS-<version>.exe`** to the PC (pen drive is fine) and run it. It installs for the
   current Windows user and adds a Start-menu entry and a desktop shortcut. The installer is not
   code-signed yet, so Windows SmartScreen says "unknown publisher": click **More info**, then
   **Run anyway**.
2. **Start GO RockIt** from the Start menu. It starts the till's server in the background; no
   window opens.
3. **Make the shop's icon.** The app does not open the browser itself. Create a shortcut to
   `http://127.0.0.1:8765` on the desktop (right-click, New, Shortcut) and name it GO RockIt.
   Also put a shortcut to GO RockIt in `shell:startup` so the till is ready after a reboot.
4. **Sign in as `admin`.** The first-start PIN is in
   `%LOCALAPPDATA%\MassPOS\logs\pos.log`. The app makes you choose your own PIN straight away.
5. **Name the till.** Close GO RockIt, edit `%LOCALAPPDATA%\MassPOS\pos.properties`, set
   `pos.terminal.code` (T1, T2, ...: unique per till, at most 3 characters, it prefixes every
   invoice number) and `pos.terminal.name`, then start it again.
6. **Settings:** legal name and address (billing stays blocked until both are set), GSTIN if the
   shop is GST-registered, receipt footer, UPI ID.
7. **Settings > Printer:** pick the printer. A printer that has only a Windows driver (inkjet,
   laser) uses type `WINDOWS`; a thermal receipt printer on a COM port or LAN uses `SERIAL` or `TCP`.
8. **Licence:** Menu > Licence shows the **machine ID**. Send it to the GO RockIt supplier, paste
   the key they return. Until then everything works except new bills, edits and returns.
9. **Add products** (Products), record opening stock (Stock), take a test bill and cancel it.

## Vendor: issuing the licence

```powershell
java tools\license\LicenseTool.java issue --dir C:\Users\sivar\MassPOS-licensing --machine <MACHINE-ID> `
     --customer-id C002 --customer "Shop name" --valid-until 2027-10-31
```

## Data and backup

Everything the shop owns is in `%LOCALAPPDATA%\MassPOS` (database `pos.db` and its `-wal` and
`-shm` files, `pos.properties`, `license.lic`, `logs`). There is no backup button yet. Until there
is: **close GO RockIt every evening and copy that whole folder to a pen drive or cloud folder.**
Copying `pos.db` alone while the app runs gives a stale copy.

**Moving to a new PC:** install, start once, close, copy `pos.db`, `pos.db-wal`, `pos.db-shm` and
`pos.properties` from the old PC over the new ones. Do not copy `license.lic`: it is tied to the old
machine, so ask for a new key for the new machine ID. Keep the same `pos.terminal.code` only if the
old PC is retired.

## Where things live

The program is installed in `%LOCALAPPDATA%\GoRockIt`; everything the shop owns stays in
`%LOCALAPPDATA%\MassPOS`. They are deliberately apart, so removing the program can never take
the database with it.

## Updating to a new version

Run the new installer over the old one: the upgrade id is fixed, so a build with a new version
number replaces the program and keeps the data folder. Rebuilding the *same* version and
installing it again does not work - Windows refuses it with error 1638, "another version of this
product is already installed" - so pass a new `-Version` to the build script, or uninstall first.
Take a copy of `%LOCALAPPDATA%\MassPOS` before any upgrade. A till installed with the first 0.1.0
build has the program inside the data folder: uninstall that one before installing a later build.

## If something is wrong

| Symptom | Fix |
|---|---|
| Page does not open | GO RockIt is not running: start it from the Start menu. Use `http://127.0.0.1:8765`, not `localhost` |
| Cannot sign in | The admin PIN is in `%LOCALAPPDATA%\MassPOS\logs\pos.log` |
| "Billing locked" | Menu > Licence says why |
| Bills print to files, not paper | No printer chosen: Settings > Printer |
| App closes at startup | A bad value in `pos.properties`; `logs\pos.log` names it |
