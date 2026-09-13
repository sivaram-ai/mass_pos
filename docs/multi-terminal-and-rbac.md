# Multiple billing machines, floors, and who may do what

Decisions for running several tills in one shop, answering: how the machines connect, which one
holds the master data, and how login works. Implementation status is marked per section.

## 1. How billing machines are connected (the market standard)

Every mainstream Indian retail POS (Marg, GoFrugal, Ginesys, Tally-based counters, Petpooja) uses
the same physical shape:

```
        ┌──────────────── shop LAN, wired ─────────────────┐
        │                                                  │
   [ Counter 1 ]   [ Counter 2 ]   [ Counter 3 ]     [ MASTER / back office ]
    till + printer  till + printer  till + printer    holds the shop database
        │               │               │                   │
        └───────────────┴─── Gigabit switch ────────────────┘
                                │
                         router (internet, optional)
                                │
                      Wi-Fi AP ── handheld scanners, stock-take tablets
```

- **Switched Ethernet in a star**, one cable per till into a common switch. Cheap, and a damaged
  cable takes down only its own till.
- **"Bus network" (coax, one shared cable) is obsolete** and should not be used: one break kills
  every till, and no current hardware ships for it. What people mean by "bus" today is a *serial
  bus* for peripherals (USB / RS-232 to printer, scale, drawer) which is exactly what the till
  already uses for its printer.
- **Cable, not Wi-Fi, for tills.** Wi-Fi is fine for handhelds, not for a counter that must not
  stall mid-bill.
- **Fixed addresses**: give the master a DHCP reservation or a static IP (e.g. `192.168.1.10`),
  because every counter needs to find it by address.

### Where the data lives: three models

| Model | How it works | Verdict |
|---|---|---|
| Shared database file on a network share | Counters open one SQLite/Access file over SMB | **Never.** File-locking over SMB corrupts SQLite. A classic cause of destroyed shop data. |
| Thin client / central server | Counters keep nothing; every keystroke hits the master | Common in older setups. One switch or master reboot stops all billing. |
| **Local-first with sync** | Each till owns its own database and bills alone; changes replicate to the master | **What Mass POS does.** Billing survives a dead master, a dead switch, or a cut cable. |

### Our arrangement

- **Every till is complete on its own**: SQLite, ESC/POS printer, its own invoice number series
  (`T1-2627-00001`, `T2-2627-00001`). A till never waits on the network to print a bill.
- **One machine is designated MASTER** (usually back office, or Counter 1 in a small shop). It
  holds the consolidated database: all counters' sales, the shared catalogue, users and stock.
- **Direction of truth is split by data type**, which removes almost every conflict:

  | Data | Owner | Flow |
  |---|---|---|
  | Sales, returns, cancellations | The till that made them | counter → master (append-only, never conflicts) |
  | Stock movements | The till that recorded them | counter → master (a ledger of +/- events; merges by union) |
  | Products, prices, tax rates | Master | master → counters |
  | Users, roles, PINs | Master | master → counters |
  | Company/GST settings, shortcuts | Master | master → counters |

- **Why this merges safely**: ids are UUIDv7 (generated per till, collision-free), sales are
  append-only, and stock is a ledger of deltas rather than an absolute number, so two offline
  tills can never overwrite each other's stock. A late-arriving sale is simply inserted.
- **Invoice numbering stays gapless per till** because each till has its own prefix and its own
  counter. Do not share one number series across counters.

### Connecting them in practice (Slice D)

1. Wire each till to the switch; give the master a fixed IP.
2. On the master, set `pos.terminal.type=MASTER` and `pos.sync.bind-address=0.0.0.0`, which opens
   the LAN sync port (default 8766). **The UI port 8765 stays on loopback**: no one on the LAN can
   drive a till's screen or open its cash drawer.
3. On each counter set `pos.terminal.type=COUNTER` and `pos.sync.master-url=http://192.168.1.10:8766`.
4. Pair each counter once: the master issues a terminal token, so a random laptop on the LAN
   cannot push sales in.
5. Counters push new rows every few seconds and pull catalogue/user changes. Disconnected tills
   queue and catch up automatically.
6. Allow port 8766 on the master's firewall; it never needs to be exposed to the internet.

**Cloud (Phase 5)** is the same mechanism one level up: masters of several shops replicate to a
central PostgreSQL, which is how you run multiple supermarkets or restaurants on one product.

## 2. Floors, counters and sections (Slice A, done)

Each machine registers itself in a `terminal` table: code (`T1`), display name
(`Counter 1`), section (`Ground floor`, `1st floor`, `Bar`, `Takeaway`) and type
(`MASTER`/`COUNTER`). Values come from that till's `pos.properties`, so a machine identifies
itself and the master can list every till in the shop with its floor.

Reports and the audit trail already tag every invoice, stock movement and revision with the
terminal code, so "what did Counter 2 on the 1st floor sell today" is answerable.

## 3. Login and permissions (Slice A, done)

**No session timeouts.** A cashier signs in at the start of a shift and stays signed in until
someone presses Log out, which is what shop floors expect and what you asked for.

- Login returns an opaque **token** (32 random bytes; only its SHA-256 hash is stored). The browser
  keeps it and sends `Authorization: Bearer <token>`.
- The token **never expires**. Logging out revokes that one row; changing a PIN revokes all of that
  user's tokens.
- No server-side session state, so a restarted till does not log the cashier out.
- PINs are stored as BCrypt hashes and are excluded from the audit log.
- The API is loopback-only, so a token cannot be stolen from across the network.
- Every request binds the signed-in user to the audit trail, so each invoice and stock movement
  records who did it (MCA Rule 11(g)).

### Roles

| Role | Can do |
|---|---|
| **CASHIER** | Bill, hold and resume bills, reprint, open the drawer with a reason |
| **MANAGER** | Everything a cashier can, plus products and prices, stock receipts and adjustments, cancelling an invoice, day reports |
| **AUDITOR** | Read-only: every report, the GST returns data and the audit trail. Cannot bill or edit. |
| **ADMIN** | Everything, plus users, terminals and company/GST settings |

**First start**: if no admin exists, one is created with a random PIN printed into
`logs/pos.log`, and it must be changed at first login.
