# GST compliance: what GO RockIt does today, and what full compliance still needs

Written 2026-11-18, against the code at that date. It answers one question honestly - how much of
"complete GST compliance" is already in a shop's hands, and what each of the missing pieces
actually costs - so the order of work can be decided on facts rather than on a wish list.

**The short of it.** Billing-side GST is done and has been in the field for a year: rates, the
CGST/SGST/IGST split, inclusive and exclusive pricing, GSTIN checking, tax invoices, credit notes,
and rate-wise and HSN-wise summaries for GSTR-1. **The purchase side has no tax on it at all**, and
that single gap is what stands between this till and ITC, GSTR-3B, GSTR-2B reconciliation, the
purchase register and RCM. Everything in the missing list is downstream of it, which is why it is
stage one and why nothing else should be started first.

---

## Already in, and exercised

| What | Where | Notes |
|---|---|---|
| GST on every sale, per line | `GstCalculator`, `GstAmounts` | Rate in basis points, cess beside it, money in paise |
| CGST + SGST, or IGST | `SaleService` (`interState`) | Decided by place of supply against the seller's own state, which comes off the GSTIN |
| Inclusive **and** exclusive pricing | `Product.taxInclusive` | Per item, so a shop can price either way on one bill |
| Rate-wise breakup on the bill | `ReceiptFormatter` | Rule 46 wants the rate rate-wise, and that is what prints |
| GSTIN capture and validation | `Gstin`, `@ValidGstin` | Format **and** mod-36 check character, for the shop, the buyer on a bill and every vendor |
| B2B and B2C | `SaleRequest.buyerGstin`, `placeOfSupply` | A buyer GSTIN is refused outright where the shop itself is unregistered |
| Unregistered shops | `Invoice.isTaxInvoice()` | Prints "BILL", no tax, no rate summary - not a tax invoice pretending to be one |
| Tax invoices and credit notes | `Invoice`, `CreditNote` | Separate gapless series per till per year, append-only, never deleted |
| HSN per item, UQC per line | `Product.hsnCode` | Optional, because a shop below the threshold does not need it |
| GSTR-1 preparation | `Reports.GstReport` | Rate-wise and HSN-wise, over any period, **net of returns**; CSV export |
| Audit history | Envers on every entity | Every rate change, every bill, every correction, with who and when |

What that adds up to: a shop can bill correctly, print a compliant tax invoice, take a return as a
credit note, and hand its accountant the rate-wise and HSN-wise figures GSTR-1 asks for.

---

## Missing, in the order it has to be built

### Stage 1 - Tax on what the shop buys *(the foundation; nothing else works without it)*

`purchase_order_line` carries a quantity and a cost and **no tax of any kind**. So the database
holds no input tax, which means no ITC, no 3B, no 2B matching and no purchase register - none of
them are reports waiting to be written, they are arithmetic over numbers that do not exist yet.

- GST rate, cess rate and HSN/SAC on every order line, defaulting from the item.
- Taxable value, CGST, SGST, IGST and cess per line and per order, worked out by the same
  `GstCalculator` the sale uses - one tax engine, never two.
- The vendor's GSTIN and the place of supply snapshotted on the order, as the seller is on an
  invoice: the vendor may be renamed or re-registered next year.
- **The vendor's own invoice number and date** on the delivery. This is what GSTR-2B matches on;
  without it, reconciliation has nothing to match.
- Interstate purchases: IGST where the vendor's state differs from the shop's.

Everything here is a new column on a table that has never shipped, so SQLite takes it without the
usual `ALTER` trouble.

### Stage 2 - Purchase documents
- **Debit notes** as tax documents (`PurchaseReturn` exists and carries the goods; it needs the tax
  and a document number of its own).
- **Purchase register**: every purchase in a period with taxable value, tax, vendor and GSTIN.

### Stage 3 - Input tax credit
- Eligible / ineligible / blocked per line (section 17(5) is a list a shopkeeper will not type -
  a default per category and an override per line).
- Credit taken when the goods are received, reversed on a debit note.
- An ITC ledger and a running claimable figure.

### Stage 4 - Returns and liability
- **GSTR-1** as the portal takes it: B2B, B2CL, B2CS, CDNR, HSN, DOCS sections in the GSTN JSON
  schema, not only the summary screens that exist now.
- **GSTR-3B** summary: 3.1 outward supplies, 4 eligible ITC, 5 exempt - worked out, with every
  figure traceable to the bills behind it.
- **GST liability**: output tax less input tax, per period, with the difference explained.

### Stage 5 - GSTR-2B reconciliation
- Import the 2B JSON the portal gives (it is a download, not an API, for a shop this size).
- Match on vendor GSTIN + invoice number + date + value, with tolerance on the value.
- Three lists that matter: matched, in the books but not in 2B (chase the vendor), in 2B but not in
  the books (somebody did not record a purchase).

### Stage 6 - Reverse charge
- A flag on the vendor and on the item (unregistered vendor, notified goods and services).
- Tax paid by the shop rather than collected, shown separately in 3B 3.1(d) and claimed in 4(A)(3).

### Stage 7 - Rate management with effective dates
- A `gst_rate` table keyed by HSN with effective-from dates, resolved at the bill's date.
- Old bills are already safe: every invoice snapshots the rate it was issued at, so this is about
  **the next rate change**, not about rewriting history.

### Stage 8 - Reporting and export
- Filters by period, category, HSN/SAC, customer and vendor (period alone is what exists).
- Excel (.xlsx) and PDF. Both mean a new library on a till that is deliberately one small folder:
  Apache POI is about 12 MB with its dependencies. Worth weighing against CSV, which every
  accountant's Excel opens, and the printed report that already exists.

### Stage 9 - E-invoicing and e-way bills *(needs decisions, and the internet)*

These are the only items on the list that a shop cannot have on its own terms, and they need
answers before any code:

- **Is this shop above the e-invoice threshold** (₹5 crore AATO)? Below it, an IRN is not
  required and building one is work for nothing.
- **Which GSP** (ClearTax, Masters India, Cygnet and so on)? Each has its own API, its own
  credentials and its own per-document price. The IRP cannot be called directly by a till.
- **What does the till do when the internet is down?** This is the hard part, and it is a shop
  decision, not a technical one: the bill must still print and the customer must still leave, so
  the IRN has to be fetched later and the invoice reprinted - or the shop must stop billing, which
  no shop will accept. The design is a queue with retries and a visible "IRN pending" state.
- **E-way bills** apply per consignment above ₹50,000, which for a counter-sale shop is rare and
  for a wholesaler is daily. Which is this shop?

---

## What this means for sequencing

1. **Stage 1 is not optional and not negotiable** - six stages are arithmetic over its columns.
2. Stages 2-4 are the ones an accountant will feel: debit notes, ITC, 3B.
3. Stage 5 is what stops a shop losing credit it is owed, and it is cheap once stage 1 exists.
4. Stages 6-8 are smaller and can be slotted in when they come up.
5. Stage 9 waits on the four answers above. It is the only part with an outside dependency, a
   running cost per document, and a design question about what a till does when the line is down.
