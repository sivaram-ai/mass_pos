# GO RockIt: installing at a shop

For the person who sets up a till. The vendor builds the installer with
`tools\build-installer.ps1`; this page is what to do with the `GoRockitPOS-<version>.exe` it produces.

## The shop's PC needs

- Windows 10 or 11, 64-bit, 4 GB RAM (2 GB works with a smaller heap, see CLAUDE.local.md).
- About 500 MB of disk, plus room for the database.
- **No Java and no internet.** The installer carries its own runtime; licensing is offline.
- A receipt printer if the shop wants paper bills (see step 7).

## Install, in order

1. **Copy `GoRockitPOS-<version>.exe`** to the PC (a pen drive is fine) and run it. It installs for
   the current Windows user into `%LOCALAPPDATA%\GoRockitPOS` and puts **GO RockIt** on the desktop
   and in the Start menu. The installer is not code-signed yet, so Windows SmartScreen says
   "unknown publisher": click **More info**, then **Run anyway**.
2. **Double-click GO RockIt.** The shortcut starts the till's server if it is not already running,
   waits for it, and opens the shop's screen in a window of its own - no browser, no address bar.
   The first start takes around half a minute while the database is built; after that it is
   seconds, and clicking the icon again simply opens another window.
3. **Have it ready after a reboot.** Press Win+R, type `shell:startup`, and copy the GO RockIt
   shortcut into the folder that opens.
4. **Sign in as `admin`.** The first-start PIN is in
   `%LOCALAPPDATA%\GoRockitPOS\data\logs\pos.log`. The app makes you choose your own PIN straight away.
5. **Name the till.** Edit `%LOCALAPPDATA%\GoRockitPOS\data\pos.properties`, set
   `pos.terminal.code` (T1, T2, ...: unique per till, at most 3 characters, it prefixes every
   invoice number) and `pos.terminal.name`, then restart the PC so the till picks them up. The
   server has no window of its own: to restart it without rebooting, end `GoRockitPOS-Server.exe`
   in Task Manager and click the shortcut again.
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

Everything the shop owns is in `%LOCALAPPDATA%\GoRockitPOS\data` (database `pos.db` and its `-wal` and
`-shm` files, `pos.properties`, `license.lic`, `logs`). There is no backup button yet. Until there
is: **close GO RockIt every evening and copy that whole folder to a pen drive or cloud folder.**
Copying `pos.db` alone while the app runs gives a stale copy.

**Moving to a new PC:** install, start once, close, copy `pos.db`, `pos.db-wal`, `pos.db-shm` and
`pos.properties` from the old PC over the new ones. Do not copy `license.lic`: it is tied to the old
machine, so ask for a new key for the new machine ID. Keep the same `pos.terminal.code` only if the
old PC is retired.

## Where things live

A till is one folder, `%LOCALAPPDATA%\GoRockitPOS`: the program (`GoRockitPOS.exe`,
`GoRockitPOS-Server.exe`, `app`, `runtime`) and, in `data`, everything the shop owns - `pos.db`
with its `-wal` and `-shm` files, `pos.properties`, `license.lic`, `logs`, `backups`,
`printer-out`.

Installing and upgrading leave `data` alone. That is measured, not assumed: upgrading 0.1.4 to
0.1.5 left the database and all 274 files exactly as they were.

**Uninstalling does not.** Windows deletes the installed folder and everything under it, `data`
included, so the shop's bills, licence and settings go with the program. Copy `data` to a pen
drive before you ever uninstall. To move a till to another PC, install there and copy `data`
across - do not uninstall the old one until the new one is billing.

A till set up before 0.1.2 kept its data in `%LOCALAPPDATA%\MassPOS`; the first start after the
upgrade moves that folder into `data` and says so in `pos.log`.

## Updating to a new version

Run the new installer over the old one: the upgrade id is fixed, so a build with a new version
number replaces the program and keeps the data folder. Rebuilding the *same* version and
installing it again does not work - Windows refuses it with error 1638, "another version of this
product is already installed" - so pass a new `-Version` to the build script, or uninstall first.
Take a copy of `%LOCALAPPDATA%\GoRockitPOS\data` before any upgrade. A till installed with the 0.1.0 or 0.1.1
build keeps its data in `%LOCALAPPDATA%\MassPOS`; the first start of 0.1.2 moves it in for you.

## If something is wrong

| Symptom | Fix |
|---|---|
| Nothing happens when I click GO RockIt | Give it half a minute on a cold start. If a message appears, it names the problem; otherwise look in `data\logs\pos.log` |
| The window opens but the page does not load | The server is still starting, or stopped: close the window and click the shortcut again |
| Cannot sign in | The admin PIN is in `%LOCALAPPDATA%\GoRockitPOS\data\logs\pos.log` |
| "Billing locked" | Menu > Licence says why |
| Bills print to files, not paper | No printer chosen: Settings > Printer |
| App closes at startup | A bad value in `pos.properties`; `logs\pos.log` names it |
