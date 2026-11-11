# Document Types

> **Audience:** contributors working on the document model or adding
> a new document type.
> **Scope:** the five document types the system supports, their
> IDs, the `DocumentTypeStrategy` that owns the status transition
> rules, and the template seed rows.
> For the runtime data flow see
> [`../architecture/data-model.md`](../architecture/data-model.md)
> and [`../architecture/backend.md`](../architecture/backend.md).

Source of truth: `V1__baseline.sql` (lookup tables),
`backend/src/main/java/de/billery/document/`
(strategy package), and
`frontend/src/data/documentTypes.js` (frontend
labels).

## Lookup table

```sql
INSERT INTO document_type (id, name) VALUES
    (1, 'INVOICE'),
    (2, 'OFFER'),
    (3, 'COST_CALCULATION'),
    (4, 'PARTED_INVOICE'),
    (5, 'FINAL_INVOICE'),
    (6, 'CREDIT_NOTE')
ON CONFLICT DO NOTHING;
```

```sql
INSERT INTO document_status (id, name) VALUES
    (1, 'DRAFT'),
    (2, 'SENT'),
    (3, 'ACCEPTED'),
    (4, 'REJECTED'),
    (5, 'CONVERTED'),
    (6, 'PAID'),
    (8, 'CANCELLED'),
    (9, 'PARTIALLY_PAID')
ON CONFLICT DO NOTHING;
```

Note: id=7 was formerly `OVERDUE` and has been removed. "Overdue" is now
a derived property of `(SENT, past due_date, no paid_date)` — see
`frontend/src/utils/documentStatus.js#isInvoiceOverdue` and the
`migrateDropOverdueStatus` step in `DatabaseMigration.java`.

The IDs are fixed. Renumbering requires a data migration and a
frontend change. Frontend labels are in
`frontend/src/data/documentTypes.js`.

## Strategy package

Each document type has a `DocumentTypeStrategy` implementation that
encodes the **legal status transitions** for that type. Strategies
are registered in
`backend/src/main/java/de/billery/document/DocumentStrategyRegistry.java:10-16`
and looked up by `documentTypeId`.

| `documentTypeId` | Name | Strategy class | What it covers |
|-----------------|------|---------------|----------------|
| 1 | `INVOICE` | `InvoiceStrategy` | Regular invoice. Requested transitions: `DRAFT → SENT` (issue), `SENT/PARTIALLY_PAID → PAID` (record open amount), `SENT/PARTIALLY_PAID/PAID → CANCELLED` (credit note). |
| 2 | `OFFER` | `OfferStrategy` | Offer / quote. Lifecycle: see [Offer and cost calculation lifecycle](#offer-and-cost-calculation-lifecycle). |
| 3 | `COST_CALCULATION` | `CostCalculationStrategy` | Cost calculation. Same lifecycle as the offer (`CostCalculationStrategy` reuses `OfferStrategy.TRANSITIONS`); convertible to an invoice. |
| 4 | `PARTED_INVOICE` | `PartedInvoiceStrategy` | Parted invoice (advance / partial payments). Linked via `invoice.period_id` to the `invoice_period` table. |
| 5 | `FINAL_INVOICE` | `FinalInvoiceStrategy` | Final invoice that closes a parted-invoice series. Same transitions as `INVOICE`. |
| 6 | `CREDIT_NOTE` | `CreditNoteStrategy` | Stornorechnung. Created already issued by cancelling an invoice; no transitions, cannot be created, edited, copied or deleted by hand. |

`DocumentStrategyRegistry.getStrategy(id)` returns the strategy for
the type or falls back to `InvoiceStrategy` (id 1) for unknown
ids. `DocumentService` consults the strategy before persisting a
status change.

## Offer and cost calculation lifecycle

| From | Can move to |
|------|-------------|
| `DRAFT` | `SENT`, `ACCEPTED` (customer agrees at once), `CANCELLED` |
| `SENT` | `ACCEPTED`, `REJECTED`, `CANCELLED` |
| `REJECTED` | `ACCEPTED` (customer changes their mind), `CANCELLED` |
| `ACCEPTED` | `CONVERTED`, `CANCELLED` |
| `CONVERTED`, `CANCELLED` | final |

- **`SENT` is a status, set by hand.** It means "sent to the customer, waiting for the answer" and
  is what the list filters on. There is no mail dispatch yet. When it comes, "send by mail" is an
  action that performs `DRAFT → SENT` and writes a history entry (time, recipient); sending again
  leaves the status and only adds an entry.
- **Setting the status a document already has is a no-op** (`200`, no history entry), so a double
  click on "Angenommen" is harmless.
- **A refused change answers HTTP 400 with a JSON `message`** that names both statuses and the
  possible targets (`InvalidStatusChangeException`, mapped in `ApiExceptionHandler`). Invoice types
  keep their plain `400`.
- **`CONVERTED` is never a plain button.** The SPA converts through `utils/documentConversion.js`,
  which creates the next document and then sets `CONVERTED` with `convertedToInvoiceId`.
- **One table, two readers.** `backend/src/test/resources/status-parity/transitions.json` is read by
  `OfferStatusTransitionsTest` (Java) and by `useDocumentType.spec.js` (SPA, against
  `OFFER_TRANSITIONS` in `utils/documentStatus.js`). Changing the lifecycle means changing the
  strategy, `OFFER_TRANSITIONS` and that file together; either test fails otherwise.

## Strategy base class

`BaseDocumentTypeStrategy` carries the common transition-table
logic. A concrete strategy declares a `static int[][] TRANSITIONS`
table (e.g. `{{1, 2}, {2, 6}, {9, 6}, {2, 8}, {9, 8}, {6, 8}}` for
`InvoiceStrategy`) and the base class exposes `isValidTransition`
and `getAvailableStatuses` based on the table. Add a new
transition by editing the array.

## Template seeds

`V1__baseline.sql` seeds four default templates, one per
document type that has a renderable default. The `FINAL_INVOICE`
type (id 5) has **no** seed template — it is created only when a
parted-invoice series is closed.

| `documentTypeId` | Seed template name |
|-----------------|---------------------|
| 1 | `Standardrechnung` |
| 2 | `Standardangebot` |
| 3 | `Standardkostenberechnung` |
| 4 | `Standardteilrechnung` |
| 5 | (none) |
| 6 | (none — a Stornorechnung renders with the template of the invoice it cancels) |

Each seed carries the same block-JSON shape (header → two
addresses → invoice-meta → line-items → totals → payment → footer).
See [`../architecture/template-system.md`](../architecture/template-system.md)
for the block-JSON contract and
[`../guides/extending.md`](../guides/extending.md)
for how to add a new type.

## Adding a new document type

1. **A new Flyway migration** (`db/migration/V<next>__...sql`) and both
   `scheme-h2.sql` files — add a new `INSERT INTO document_type (id, name)
   VALUES (...)` row with an unused id.
2. **`backend/src/main/java/de/billery/document/`** —
   create a new `XxxStrategy` class extending
   `BaseDocumentTypeStrategy`, define its `TRANSITIONS` table, and
   register it in `DocumentStrategyRegistry`'s constructor.
3. **`frontend/src/data/documentTypes.js`** — add
   the new type's label / icon to the frontend lookup.
4. **Optional** — add a seed template in the same migration (id, name,
   `html_content = '<!-- placeholder -->'`, `is_default = 1`,
   default block JSON).
