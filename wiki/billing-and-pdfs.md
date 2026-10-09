---
updated: 2026-10-09
---

# Quotes, invoices, payments and PDFs

Documents and money: quotes and invoices, installments and cancelling, payments to suppliers and employees, and the generated PDFs. The Serviços rows that invoices bill are in [catalog-services-bookings.md](catalog-services-bookings.md).

## Supplier services, invoice tax code, installments, cancel and delete (shipped 2026-10-06, `17425b5`)

Rodrigo asked for four things: usual services per supplier, offered when registering a bill (he
first wrote "invoice/fatura" and confirmed he meant Pagamentos); a field on client invoices for the
code the tax office gives them; cancelling or deleting an invoice; and paying an invoice in parts
("400 €, half now and half later"), then the same cancel and delete for payments. Checked locally
(590 tests green, headless Chrome in light, dark, pt-PT and 390px, the PDF rendered) and shipped the
same day once he asked for it. Decisions that matter later, taken without asking him apart from the
Pagamentos question:

- **A supplier's services are their own list** (`crm.suppliers.services`: description, unit, a usual
  price or none when it varies; at most 50), not links into the catalog. The catalog holds sale prices
  and feeds the customer-facing bot's price answers (`list_standard_items`), so supplier costs stay out
  of it. The payment form shows the picked supplier's services as ticks: a tick fills the first empty
  line or adds one tagged `data-from-service`, unticking removes it, removing the line unticks it, and
  switching supplier drops the tagged lines. Nothing on the payment points back at the service. `PATCH`
  keeps them when omitted, replaces them on a list and clears them on `[]`.
- **The tax office code is taken to be the ATCUD** (`Invoice.taxOfficeCode`, at most 80 characters,
  labelled "Código AT (ATCUD)"). It is optional on create and added or changed later from the invoice
  detail, because it usually arrives after the invoice. The PDF prints it on the number line as
  `ATCUD:<code>` without doubling a typed `ATCUD:`. This is an assumption Rodrigo hasn't confirmed.
- **With installments, `dueDate` is the next open part's date** (the last part's once all are
  received). Agents' due-date sweeps, overdue flags, Home and the bot's tools therefore follow the plan
  without knowing about it. Money is counted part by part. The API always sends `paidEur` and
  `outstandingEur`. Home's outstanding subtracts received parts. Overdue, aging and due-soon count each
  open part by its own date. Collected and the cash-flow chart count each part in the month it came in
  (`Invoice.receipts()`, a Document twin in `OverviewService`, and `paidPartExpression()` for
  aggregations).
- **The installment rules are pure, in `InvoiceInstallments`.** Parts must add up to what is owed,
  at most 24 in all, and received parts are frozen. One part with nothing received removes the plan.
  `InvoiceRepository.change` writes only while the stored status and `installments` array still equal
  what was read, retrying three times. That works on mock data too, because Mongo compares numbers by
  value inside embedded documents (mongosh's Int32 matches the app's Long).
- **Mark paid** now receives every open part. It is idempotent (a second call used to move `paidAt`)
  and does nothing on a cancelled invoice. The last part received appends `invoice.paid`; a single part
  writes no event.
- **Agents get `invoice.amountDue`**: the open parts due by today or the due date, or everything owed
  without a plan. The three reminder templates print it instead of `invoice.total`. The text is
  unchanged for invoices without a plan, and existing agents keep their stored copy.
- **Cancel and delete differ.** Cancel keeps the invoice and its number as `CANCELLED`, and only
  works when nothing was received (`409 invoice_paid` / `installments_paid`). Delete removes any
  invoice. Both reopen the Serviços rows the invoice billed (`reopenInvoiced`). A cancelled invoice
  no longer blocks converting its quote again, in the dashboard route, the agents' convert action and
  the quote detail. Neither writes a domain event.
- **Payments got the same pair** the same day, at Rodrigo's request: cancel only while unpaid
  (`409 payment_paid` / `payment_cancelled`), delete in any state, both from the payment detail.
  `PaymentRepository.markPaid` now leaves a paid or cancelled payment as it is (it used to move
  `paidAt` and would re-pay a cancelled one). Cancelled payments already fell out of every money
  total, because Home, Financeiro and the records count only pending and paid ones.

`create-mocks` now seeds services on both suppliers, ATCUDs on FAT-001 and FAT-002, and FAT-002 in two
installments with the first one received.

Production rollout (2026-10-06): backup `mongo-20261006T130350Z.archive.gz` (also in GCS) right
before. The commit was rebased onto the mobile overhaul's merge (`977c2d3`, PR #44), which had moved
`main` since the session started. CI tested, built and deployed `17425b5`, Mongo was not bounced, and
there were 0 `ERROR` lines. No data migration: existing invoices read as paid in one go and existing
suppliers as having no services until someone edits them.

## Euro amounts round to cents (since `b6448f9`, 2026-10-01)

- **Euro-to-cents truncated everywhere** until this change: `lineItem()` and the services routes did
  `(eur * 100).toLong()`, so 4.35 € was stored as 4.34 € (1.15 → 1.14, 0.29 → 0.28) on quote, invoice,
  payment and service lines. `eurToCents` now rounds, as bookings already did; stored documents keep
  their cents.

## Quote and invoice PDFs: redesign (shipped 2026-09-29, `1d9b44e`)

Rodrigo asked to improve the generated PDFs. Production then had five active tenants: three on the
untouched default page, one on classic with its own layout and a red accent (most quotes), one on
band with teal (most invoices); both customized tenants fill in only the company name. So the redesign
keeps every style, preset and saved block position, and has to look right with sparse data.
Decisions that matter later:

- **One default page.** An empty `layout` prints `DocumentLayouts.DEFAULT` instead of a separate
  hardcoded page. DEFAULT's items block moved from y=290 to 312 (the old frame overlapped the client
  block by 14pt and the table header covered the client's address line); payment and terms moved to
  x=42. `DocumentLayoutsTest` now checks every preset, classic included, for overlapping frames.
- **Shared rules** for all styles: amounts `1 234,56 €`; accent text darkened to 4.5:1 on white (a
  yellow accent reads as dark gold) and text on accent fills chosen by contrast; issue date plus
  validity or due date on the title line; badges only for paid, overdue, cancelled and accepted;
  footer and "Página X de Y" on every page; real letter-spacing (PDF `Tc`), so labels copy as words;
  payment terms up to 6 lines and conditions up to 8 (they were cut at 4 and 2).
- **Classic** keeps its accent header bar, rounded rows and total pill, but rows are compact (a
  five-line quote used to spill onto a second page), the service/description split no longer repeats
  the description, and quantity × unit price prints under the amount when the quantity isn't 1.
- **Gotchas.** PDFBox throws `No glyph for U+…` on any character Montserrat lacks (emoji the AI may
  write into an item), so all text passes through a glyph filter. One `PdfGenerator` instance is shared
  and keeps per-document fonts and theme in fields, so `buildDocument` is `@Synchronized`. Labels are
  still Portuguese-only; the tenant's locale is not used.
- The studio preview mirrors these rules (`--doc-brand-text` and `--doc-brand-ink`, computed in
  `doc-template.js` with the generator's formulas). Downloads regenerate PDFs, so existing documents
  get the new look on their next download; files already sent keep the old one.

**PDF default terms (fixed in `9efdbbb`).** The old default payment terms were a construction
milestone schedule ("15% na adjudicação, 70% a meio da execução da obra…") left over from the first
customer, printed on every quote and invoice whose tenant left Settings → Documentos → "Condições de
pagamento" empty. That was every production tenant (34 invoices by 2026-09-29), and invoices also got
the quote line "válido por 30 dias". Nobody noticed because the template studio never showed it: its
`defaults` DTO had only default-valued fields, and with `encodeDefaults` off it serialized as `{}`.
Now invoices default to "Pagamento até à data de vencimento." with no terms section, and quotes get no
payment section and the 30-day line only without their own validity date. Empty sections are skipped,
and the studio receives the defaults and previews the same. Downloads regenerate the PDF on each
request, so old invoices download with the new text. Only files already sent (for example over
WhatsApp) keep the old wording. Gotcha for any DTO that must always carry its values: don't give its
properties default values.
