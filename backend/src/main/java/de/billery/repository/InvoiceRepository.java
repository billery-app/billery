package de.billery.repository;

import static de.billery.data.DatabaseUtils.emptyToNull;
import static de.billery.data.DatabaseUtils.nullToEmpty;

import de.billery.api.dto.SearchResult.SearchInvoice;
import de.billery.data.Invoice;
import de.billery.data.InvoiceItem;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Repository for documents ({@code invoice}: invoices, offers, cost calculations, credit notes, …)
 * and their line items ({@code invoice_item}, a tree via {@code parent_item_id}).
 *
 * <p>All statements run through the shared {@link JdbcClient}, so they join the caller's
 * transaction; database errors surface as {@code DatabaseException} (see {@link BaseRepository}).
 * {@code invoice.tax_rate} is stored as a fraction (0.19) and exposed as a percentage (19).
 */
@Repository
public class InvoiceRepository extends BaseRepository {

  private static final String OPERATION = "operation";
  private static final int PAID_STATUS_ID = 6;

  /** Columns and joins of {@link #search}. */
  private static final String SEARCH_SELECT =
      """
      SELECT i.*,
             CONCAT(c.first_name, ' ', c.last_name) as customer_name,
             dt.name as document_type_name,
             ds.name as document_status_name
      FROM invoice i
      LEFT JOIN customer c ON i.customer_id = c.id
      LEFT JOIN document_type dt ON i.document_type_id = dt.id
      LEFT JOIN document_status ds ON i.document_status_id = ds.id
      """;

  /** Search predicate over number, customer, notes and dates (raw and transliterated query). */
  private static final String SEARCH_PREDICATE =
      """
      (CAST(i.id AS VARCHAR(50)) LIKE :pattern
       OR LOWER(i.document_number) LIKE LOWER(:pattern)
       OR LOWER(c.first_name) LIKE LOWER(:pattern)
       OR LOWER(c.last_name) LIKE LOWER(:pattern)
       OR LOWER(c.email) LIKE LOWER(:pattern)
       OR LOWER(i.notes) LIKE LOWER(:pattern)
       OR CAST(i.invoice_date AS VARCHAR(10)) LIKE :date OR CAST(i.due_date AS VARCHAR(10)) LIKE :date OR CAST(i.paid_date AS VARCHAR(10)) LIKE :date
       OR LOWER(i.document_number) LIKE LOWER(:normalized)
       OR LOWER(c.first_name) LIKE LOWER(:normalized)
       OR LOWER(c.last_name) LIKE LOWER(:normalized)
       OR LOWER(c.email) LIKE LOWER(:normalized)
       OR LOWER(i.notes) LIKE LOWER(:normalized)
       OR CAST(i.invoice_date AS VARCHAR(10)) LIKE :date OR CAST(i.due_date AS VARCHAR(10)) LIKE :date OR CAST(i.paid_date AS VARCHAR(10)) LIKE :date)
      """;

  /**
   * Items with display fallbacks: a missing unit, name or description comes from the referenced
   * article or service. Callers append a {@code WHERE} clause and {@code ORDER BY ii.id}.
   */
  private static final String ITEM_DISPLAY_SELECT =
      """
      SELECT ii.id, ii.invoice_id, ii.item_type, ii.item_id, ii.parent_item_id,
             ii.quantity, ii.unit_price, ii.total_price, ii.tax_rate_percent,
             COALESCE(ii.unit, a.unit, s.unit) as unit,
             COALESCE(ii.item_name, a.article_name, s.servicename) as item_name,
             COALESCE(ii.item_description, a.article_description, s.service_description)
               as item_description
      FROM invoice_item ii
      LEFT JOIN article a ON UPPER(ii.item_type) = 'ARTICLE' AND ii.item_id = a.id
      LEFT JOIN service s ON UPPER(ii.item_type) = 'SERVICE' AND ii.item_id = s.id
      """;

  /**
   * Stable order of the paged list: drafts without number first, then by number (newest first), the
   * id breaking ties. Explicit, because PostgreSQL and H2 sort {@code NULL} differently.
   */
  private static final String PAGE_ORDER =
      " ORDER BY CASE WHEN i.document_number IS NULL THEN 0 ELSE 1 END,"
          + " i.document_number DESC, i.id DESC";

  /** Source of the paged list; the customer join serves the name search. */
  private static final String PAGE_FROM =
      " FROM invoice i LEFT JOIN customer c ON c.id = i.customer_id";

  /** Maps an {@code invoice} row ({@code SELECT *}). */
  private static final RowMapper<Invoice> INVOICE_MAPPER =
      (rs, rowNum) -> {
        Invoice invoice =
            new Invoice(
                rs.getInt("id"),
                rs.getInt("customer_id"),
                nullToEmpty(rs.getString("invoice_date")),
                nullToEmpty(rs.getString("due_date")),
                decimalOrZero(rs, "total_price"),
                decimalOrZero(rs, "tax_rate").movePointRight(2),
                nullToEmpty(rs.getString("paid_date")),
                nullToEmpty(rs.getString("notes")),
                nullToEmpty(rs.getString("document_number")),
                rs.getInt("version"),
                rs.getInt("is_latest"),
                rs.getInt("document_type_id"),
                rs.getInt("document_status_id"),
                rs.getInt("version_group_id"),
                nullToEmpty(rs.getString("work_start_date")),
                nullToEmpty(rs.getString("work_end_date")),
                rs.getInt("parent_invoice_id"),
                rs.getInt("period_id"),
                rs.getInt("converted_to_invoice_id"),
                nullToEmpty(rs.getString("offer_valid_until")),
                nullToEmpty(rs.getString("cost_calculation_basis")),
                nullToEmpty(rs.getString("terms_and_conditions")),
                rs.getInt("archived"));
        invoice.setRenderedHtml(nullToEmpty(rs.getString("rendered_html")));
        int templateId = rs.getInt("template_id");
        invoice.setTemplateId(rs.wasNull() ? null : templateId);
        invoice.setCancelsInvoiceId(rs.getInt("cancels_invoice_id"));
        invoice.setTaxTreatment(rs.getString("tax_treatment"));
        invoice.setTaxExemptionReason(nullToEmpty(rs.getString("tax_exemption_reason")));
        invoice.setPartySnapshot(nullToEmpty(rs.getString("party_snapshot")));
        return invoice;
      };

  /** Maps an {@code invoice_item} row ({@code SELECT *}), using the item's own stored texts. */
  private static final RowMapper<InvoiceItem> ITEM_MAPPER =
      (rs, rowNum) -> {
        InvoiceItem item =
            new InvoiceItem(
                rs.getInt("id"),
                rs.getInt("invoice_id"),
                rs.getString("item_type"),
                rs.getInt("item_id"),
                decimalOrZero(rs, "quantity"),
                decimalOrZero(rs, "unit_price"),
                decimalOrZero(rs, "total_price"));
        item.setParentItemId(rs.getInt("parent_item_id"));
        item.setItemName(nullToEmpty(rs.getString("item_name")));
        item.setItemDescription(nullToEmpty(rs.getString("item_description")));
        item.setUnit(nullToEmpty(rs.getString("unit")));
        item.setTaxRatePercent(decimalOrZero(rs, "tax_rate_percent"));
        return item;
      };

  /** Maps a search hit (the {@link #SEARCH_SELECT} columns). */
  private static final RowMapper<SearchInvoice> SEARCH_MAPPER =
      (rs, rowNum) ->
          new SearchInvoice(
              rs.getInt("id"),
              rs.getInt("customer_id"),
              nullToEmpty(rs.getString("customer_name")),
              nullToEmpty(rs.getString("document_number")),
              nullToEmpty(rs.getString("invoice_date")),
              nullToEmpty(rs.getString("due_date")),
              decimalOrZero(rs, "total_price"),
              decimalOrZero(rs, "tax_rate").movePointRight(2),
              nullToEmpty(rs.getString("paid_date")),
              nullToEmpty(rs.getString("notes")),
              rs.getInt("document_type_id"),
              nullToEmpty(rs.getString("document_type_name")),
              rs.getInt("document_status_id"),
              nullToEmpty(rs.getString("document_status_name")));

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc the shared JdbcClient
   */
  public InvoiceRepository(JdbcClient jdbc) {
    super();
    this.jdbc = jdbc;
  }

  /**
   * Returns all documents, newest number first.
   *
   * @return all documents
   */
  public List<Invoice> findAll() {
    return run(
        "findAll invoices",
        () ->
            jdbc.sql("SELECT * FROM invoice ORDER BY document_number DESC")
                .query(INVOICE_MAPPER)
                .list());
  }

  /**
   * Returns the documents whose document date lies in the period, newest number first.
   *
   * @param from first document date (inclusive)
   * @param to last document date (inclusive)
   * @return the documents of the period
   */
  public List<Invoice> findByInvoiceDateBetween(LocalDate from, LocalDate to) {
    return run(
        "findByInvoiceDateBetween invoices",
        () ->
            jdbc.sql(
                    "SELECT * FROM invoice WHERE invoice_date BETWEEN :from AND :to"
                        + " ORDER BY document_number DESC")
                .param("from", from)
                .param("to", to)
                .query(INVOICE_MAPPER)
                .list());
  }

  /**
   * Finds a document by id.
   *
   * @param id the document id
   * @return the document, or null if not found
   */
  public Invoice findById(int id) {
    return run("findById invoice", () -> queryOne("SELECT * FROM invoice WHERE id = :v", id));
  }

  /**
   * Returns a customer's documents, newest number first.
   *
   * @param customerId the customer id
   * @return the customer's documents
   */
  public List<Invoice> findByCustomerId(int customerId) {
    return run(
        OPERATION,
        () ->
            jdbc.sql(
                    "SELECT * FROM invoice WHERE customer_id = :customerId"
                        + " ORDER BY document_number DESC")
                .param("customerId", customerId)
                .query(INVOICE_MAPPER)
                .list());
  }

  /**
   * Inserts a document; the generated id is set on the passed object. The percentage tax rate is
   * stored as a fraction.
   *
   * @param invoice the document to insert
   */
  public void save(Invoice invoice) {
    run(
        OPERATION,
        () -> {
          KeyHolder keys = new GeneratedKeyHolder();
          bindInvoice(
                  jdbc.sql(
                      """
                      INSERT INTO invoice (customer_id, invoice_date, due_date, total_price,
                                         tax_rate, paid_date, notes, document_number,
                                         document_type_id, document_status_id,
                                         version, is_latest, version_group_id, rendered_html,
                                         work_start_date, work_end_date, template_id,
                                         cancels_invoice_id, tax_treatment, tax_exemption_reason)
                      VALUES (:customerId, :invoiceDate, :dueDate, :totalPrice, :taxRate,
                              :paidDate, :notes, :documentNumber, :documentTypeId,
                              :documentStatusId, :version, :isLatest, :versionGroupId,
                              :renderedHtml, :workStartDate, :workEndDate, :templateId,
                              :cancelsInvoiceId, :taxTreatment, :taxExemptionReason)
                      """),
                  invoice)
              .param(
                  "cancelsInvoiceId",
                  invoice.getCancelsInvoiceId() > 0 ? invoice.getCancelsInvoiceId() : null)
              .update(keys);
          invoice.setId(generatedId(keys));
          return null;
        });
  }

  /**
   * Overwrites a document's columns (not {@code cancels_invoice_id}, {@code party_snapshot} or the
   * conversion link), identified by its id.
   *
   * @param invoice the document with updated values
   */
  public void update(Invoice invoice) {
    run(
        OPERATION,
        () ->
            bindInvoice(
                    jdbc.sql(
                        """
                        UPDATE invoice SET customer_id = :customerId, invoice_date = :invoiceDate,
                                          due_date = :dueDate, total_price = :totalPrice,
                                          tax_rate = :taxRate, paid_date = :paidDate,
                                          notes = :notes, document_number = :documentNumber,
                                          document_type_id = :documentTypeId,
                                          document_status_id = :documentStatusId,
                                          version = :version, is_latest = :isLatest,
                                          version_group_id = :versionGroupId,
                                          rendered_html = :renderedHtml,
                                          work_start_date = :workStartDate,
                                          work_end_date = :workEndDate,
                                          template_id = :templateId,
                                          tax_treatment = :taxTreatment,
                                          tax_exemption_reason = :taxExemptionReason
                        WHERE id = :id
                        """),
                    invoice)
                .param("id", invoice.getId())
                .update());
  }

  /**
   * Stores the rendered HTML snapshot of a document.
   *
   * @param invoiceId the document id
   * @param renderedHtml the snapshot (empty is stored as NULL)
   */
  public void updateRenderedHtml(int invoiceId, String renderedHtml) {
    run(
        "updateRenderedHtml",
        () ->
            jdbc.sql("UPDATE invoice SET rendered_html = :html WHERE id = :id")
                .param("html", emptyToNull(renderedHtml))
                .param("id", invoiceId)
                .update());
  }

  /**
   * Deletes a document (its items cascade).
   *
   * @param id the document id
   */
  public void delete(int id) {
    run(OPERATION, () -> update("DELETE FROM invoice WHERE id = :v", id));
  }

  /**
   * Nulls {@code invoice.template_id} on every row that references the given template. Used by
   * {@code DELETE /api/templates/{id}} so that a deleted template doesn't leave referencing
   * invoices with a dangling integer — the renderer falls back to the type default at PDF time.
   * Returns the number of rows updated.
   */
  public int nullTemplateIdForTemplate(int templateId) {
    return run(
        "nullTemplateIdForTemplate",
        () -> update("UPDATE invoice SET template_id = NULL WHERE template_id = :v", templateId));
  }

  /**
   * Sets the status and the paid date in one statement, so the {@code chk_invoice_paid_date_status}
   * invariant (a paid date only with status PAID) holds after every call.
   *
   * @param id the document id
   * @param statusId the new status
   * @param paidDate the date the invoice was fully paid ({@code yyyy-MM-dd}); ignored unless {@code
   *     statusId} is PAID
   */
  public void updateStatus(int id, int statusId, String paidDate) {
    LocalDate paid =
        statusId == PAID_STATUS_ID && paidDate != null && !paidDate.isEmpty()
            ? LocalDate.parse(paidDate)
            : null;
    run(
        "updateStatus",
        () ->
            jdbc.sql(
                    "UPDATE invoice SET document_status_id = :statusId, paid_date = :paidDate"
                        + " WHERE id = :id")
                .param("statusId", statusId)
                .param("paidDate", paid)
                .param("id", id)
                .update());
  }

  /**
   * Issues a draft: stores its document number and the status SENT. The {@code WHERE} clause on the
   * draft status makes a concurrent second issue a no-op instead of a second number.
   *
   * @param id the document id
   * @param documentNumber the number reserved from the series
   * @param sentStatusId the status id of SENT
   * @param draftStatusId the status id of DRAFT
   * @return {@code true} if the draft was issued, {@code false} if it was no longer a draft
   */
  public boolean issue(int id, String documentNumber, int sentStatusId, int draftStatusId) {
    return run(
        "issue",
        () ->
            jdbc.sql(
                        "UPDATE invoice SET document_number = :number, document_status_id = :sent"
                            + " WHERE id = :id AND document_status_id = :draft")
                    .param("number", documentNumber)
                    .param("sent", sentStatusId)
                    .param("id", id)
                    .param("draft", draftStatusId)
                    .update()
                == 1);
  }

  /**
   * Stores the seller and buyer data frozen when a document is issued (see {@code PartySnapshot}).
   *
   * @param id the document
   * @param json the snapshot as JSON
   */
  public void updatePartySnapshot(int id, String json) {
    run(
        "updatePartySnapshot",
        () ->
            jdbc.sql("UPDATE invoice SET party_snapshot = :json WHERE id = :id")
                .param("json", json)
                .param("id", id)
                .update());
  }

  /**
   * Links a converted offer or cost calculation to the document it was converted into.
   *
   * @param id the converted source document
   * @param targetId the document created from it
   */
  public void setConvertedToInvoiceId(int id, int targetId) {
    run(
        "setConvertedToInvoiceId",
        () ->
            jdbc.sql("UPDATE invoice SET converted_to_invoice_id = :targetId WHERE id = :id")
                .param("targetId", targetId)
                .param("id", id)
                .update());
  }

  /**
   * Finds the credit note (Stornorechnung) that cancels the given invoice.
   *
   * @param invoiceId the cancelled invoice
   * @return the credit note, or {@code null} if the invoice has not been cancelled by one
   */
  public Invoice findCreditNoteFor(int invoiceId) {
    return run(
        "findCreditNoteFor",
        () -> queryOne("SELECT * FROM invoice WHERE cancels_invoice_id = :v", invoiceId));
  }

  /**
   * Returns a document's items as a tree (roots with their children). Missing unit, name and
   * description fall back to the referenced article or service.
   *
   * @param invoiceId the document id
   * @return the root items, in insertion order
   */
  public List<InvoiceItem> findItemsByInvoiceId(int invoiceId) {
    return nestInvoiceItems(
        run(
            OPERATION,
            () ->
                jdbc.sql(ITEM_DISPLAY_SELECT + " WHERE ii.invoice_id = :invoiceId ORDER BY ii.id")
                    .param("invoiceId", invoiceId)
                    .query(ITEM_MAPPER)
                    .list()));
  }

  /**
   * Loads the items of one customer's documents in one query, grouped by document and nested like
   * {@link #findItemsByInvoiceId}. Used by list endpoints instead of one query per document.
   *
   * @param customerId the customer id
   * @return document id → root items; documents without items are absent
   */
  public Map<Integer, List<InvoiceItem>> findItemsGroupedByInvoiceForCustomer(int customerId) {
    return groupAndNest(
        run(
            OPERATION,
            () ->
                jdbc.sql(
                        ITEM_DISPLAY_SELECT
                            + " WHERE ii.invoice_id IN"
                            + " (SELECT id FROM invoice WHERE customer_id = :customerId)"
                            + " ORDER BY ii.id")
                    .param("customerId", customerId)
                    .query(ITEM_MAPPER)
                    .list()));
  }

  /** Splits a flat, id-ordered item list by document and nests each document's items. */
  private Map<Integer, List<InvoiceItem>> groupAndNest(List<InvoiceItem> flat) {
    Map<Integer, List<InvoiceItem>> byInvoice = new HashMap<>();
    for (InvoiceItem item : flat) {
      byInvoice.computeIfAbsent(item.getInvoiceId(), id -> new ArrayList<>()).add(item);
    }
    byInvoice.replaceAll((id, items) -> nestInvoiceItems(items));
    return byInvoice;
  }

  /**
   * Maps every cancelled invoice to the credit note that cancels it, in one query.
   *
   * @return cancelled invoice id → credit note id
   */
  public Map<Integer, Integer> findCreditNoteIdsByCancelledInvoice() {
    return run(
        "findCreditNoteIdsByCancelledInvoice",
        () -> {
          Map<Integer, Integer> result = new HashMap<>();
          jdbc.sql(
                  "SELECT id, cancels_invoice_id FROM invoice WHERE cancels_invoice_id IS NOT NULL")
              .query(
                  rs -> {
                    result.put(rs.getInt("cancels_invoice_id"), rs.getInt("id"));
                  });
          return result;
        });
  }

  /**
   * Inserts an item; the generated id is set on the passed object. CUSTOM notes are forced to
   * quantity 1, price 0 and no unit.
   *
   * @param item the item to insert
   */
  public void saveInvoiceItem(InvoiceItem item) {
    boolean isNote = isCustomType(item.getItemType());
    if (isNote) {
      item.setUnit("");
    }
    run(
        OPERATION,
        () -> {
          KeyHolder keys = new GeneratedKeyHolder();
          bindItem(
                  jdbc.sql(
                      """
                      INSERT INTO invoice_item (invoice_id, item_type, item_id, item_name,
                                               item_description, quantity, unit_price,
                                               total_price, parent_item_id, unit,
                                               tax_rate_percent)
                      VALUES (:invoiceId, :itemType, :itemId, :itemName, :itemDescription,
                              :quantity, :unitPrice, :totalPrice, :parentItemId, :unit,
                              :taxRatePercent)
                      """),
                  item,
                  isNote)
              .param("parentItemId", item.getParentItemId() > 0 ? item.getParentItemId() : null)
              .param("unit", emptyToNull(item.getUnit()))
              .param("taxRatePercent", item.getTaxRatePercent())
              .update(keys);
          item.setId(generatedId(keys));
          return null;
        });
  }

  private static boolean isCustomType(String itemType) {
    return itemType != null && InvoiceItem.ItemType.CUSTOM.getValue().equalsIgnoreCase(itemType);
  }

  /**
   * Deletes all items of a document.
   *
   * @param invoiceId the document id
   */
  public void deleteAllItemsByInvoiceId(int invoiceId) {
    run(OPERATION, () -> update("DELETE FROM invoice_item WHERE invoice_id = :v", invoiceId));
  }

  /**
   * Returns a document's items as a tree, using only the texts stored on the items.
   *
   * @param invoiceId the document id
   * @return the root items, in insertion order
   */
  public List<InvoiceItem> findInvoiceItemsById(int invoiceId) {
    return nestInvoiceItems(
        run(
            OPERATION,
            () ->
                jdbc.sql("SELECT * FROM invoice_item WHERE invoice_id = :invoiceId ORDER BY id")
                    .param("invoiceId", invoiceId)
                    .query(ITEM_MAPPER)
                    .list()));
  }

  private List<InvoiceItem> nestInvoiceItems(List<InvoiceItem> flat) {
    resolveCatalogNames(flat);
    List<InvoiceItem> roots = new ArrayList<>();
    for (InvoiceItem item : flat) {
      if (item.getParentItemId() == 0) {
        roots.add(item);
      } else {
        for (InvoiceItem parent : flat) {
          if (parent.getId() == item.getParentItemId()) {
            List<InvoiceItem> children = parent.getChildren();
            if (children == null) {
              children = new ArrayList<>();
              parent.setChildren(children);
            }
            children.add(item);
            break;
          }
        }
      }
    }
    return roots;
  }

  /**
   * Backfills the catalog name of legacy rows that were saved without one (the entity used to
   * resolve these lazily in {@code getItemName()}; the SPA always stores a name). Rows saved
   * through the API keep their frozen name and are never re-resolved.
   *
   * @param flat the flat item rows of one document
   */
  private void resolveCatalogNames(List<InvoiceItem> flat) {
    for (InvoiceItem item : flat) {
      if (item.getItemName() == null || item.getItemName().isEmpty()) {
        item.setItemName(catalogNameOf(item));
      }
    }
  }

  /** The catalog name of the referenced article or service, or {@code ""} for CUSTOM rows. */
  private String catalogNameOf(InvoiceItem item) {
    String sql =
        switch (item.getItemTypeEnum()) {
          case ARTICLE -> "SELECT article_name FROM article WHERE id = :id";
          case SERVICE -> "SELECT servicename FROM service WHERE id = :id";
          case CUSTOM -> null;
        };
    if (sql == null || item.getItemId() <= 0) {
      return "";
    }
    return run(
        OPERATION,
        () ->
            jdbc.sql(sql)
                .param("id", item.getItemId())
                .query((rs, rowNum) -> nullToEmpty(rs.getString(1)))
                .optional()
                .orElse(""));
  }

  /** Runs a single-row {@code SELECT *} on {@code invoice} with one parameter {@code :v}. */
  private Invoice queryOne(String sql, Object value) {
    return jdbc.sql(sql).param("v", value).query(INVOICE_MAPPER).optional().orElse(null);
  }

  /** Runs an update with one parameter {@code :v} and returns the affected row count. */
  private int update(String sql, Object value) {
    return jdbc.sql(sql).param("v", value).update();
  }

  /**
   * Binds the document columns shared by insert and update. Empty strings are stored as NULL (an
   * empty document number would collide on the unique index), the percentage tax rate as a
   * fraction, and the ISO date strings of the entity as typed dates.
   */
  private static JdbcClient.StatementSpec bindInvoice(
      JdbcClient.StatementSpec spec, Invoice invoice) {
    return spec.param("customerId", invoice.getCustomerId())
        .param("invoiceDate", dateOrNull(invoice.getInvoiceDate()))
        .param("dueDate", dateOrNull(invoice.getDueDate()))
        .param("totalPrice", invoice.getTotalPrice())
        .param("taxRate", invoice.getTaxRate().movePointLeft(2))
        .param("paidDate", dateOrNull(invoice.getPaidDate()))
        .param("notes", emptyToNull(invoice.getNotes()))
        .param("documentNumber", emptyToNull(invoice.getDocumentNumber()))
        .param("documentTypeId", invoice.getDocumentTypeId())
        .param("documentStatusId", invoice.getDocumentStatusId())
        .param("version", invoice.getVersion())
        .param("isLatest", invoice.getIsLatest())
        .param("versionGroupId", invoice.getVersionGroupId())
        .param("renderedHtml", emptyToNull(invoice.getRenderedHtml()))
        .param("workStartDate", dateOrNull(invoice.getWorkStartDate()))
        .param("workEndDate", dateOrNull(invoice.getWorkEndDate()))
        .param("templateId", invoice.getTemplateId())
        .param("taxTreatment", invoice.getTaxTreatment())
        .param("taxExemptionReason", emptyToNull(invoice.getTaxExemptionReason()));
  }

  /**
   * Binds the item columns shared by insert and update. A CUSTOM note references nothing and
   * carries quantity 1 and price 0.
   */
  private static JdbcClient.StatementSpec bindItem(
      JdbcClient.StatementSpec spec, InvoiceItem item, boolean isNote) {
    return spec.param("invoiceId", item.getInvoiceId())
        .param(
            "itemType",
            item.getItemType() == null ? null : item.getItemType().toUpperCase(Locale.ROOT))
        .param("itemId", isNote ? 0 : item.getItemId())
        .param("itemName", emptyToNull(item.getItemName()))
        .param("itemDescription", emptyToNull(item.getItemDescription()))
        .param("quantity", isNote ? BigDecimal.ONE : item.getQuantity())
        .param("unitPrice", isNote ? BigDecimal.ZERO : item.getUnitPrice())
        .param("totalPrice", isNote ? BigDecimal.ZERO : item.getTotalPrice());
  }

  /**
   * One page of the document list, filtered in SQL.
   *
   * @param query the filters
   * @param offset rows to skip
   * @param limit rows to return
   * @return the documents of the page in {@link #PAGE_ORDER}
   */
  public List<Invoice> findPage(InvoiceListQuery query, int offset, int limit) {
    InvoiceListQuery.Where where = query.toWhere();
    return run(
        "findPage invoices",
        () ->
            jdbc.sql(
                    "SELECT i.*"
                        + PAGE_FROM
                        + where.sql()
                        + PAGE_ORDER
                        + " LIMIT :limit OFFSET :offset")
                .params(where.params())
                .param("limit", limit)
                .param("offset", offset)
                .query(INVOICE_MAPPER)
                .list());
  }

  /**
   * Number of documents matching the filters, for the pager.
   *
   * @param query the filters
   * @return the total across all pages
   */
  public long count(InvoiceListQuery query) {
    InvoiceListQuery.Where where = query.toWhere();
    return run(
        "count invoices",
        () ->
            jdbc.sql("SELECT COUNT(*)" + PAGE_FROM + where.sql())
                .params(where.params())
                .query(Long.class)
                .single());
  }

  /**
   * The years that have documents, for the year filter. Like the former in-browser list it covers
   * all document types and only depends on active / archived.
   *
   * @param archived {@code true} for the archive
   * @return the years, newest first
   */
  public List<Integer> findYears(boolean archived) {
    return run(
        "findYears invoices",
        () ->
            jdbc
                .sql(
                    "SELECT DISTINCT EXTRACT(YEAR FROM invoice_date) AS y FROM invoice"
                        + " WHERE invoice_date IS NOT NULL AND COALESCE(archived, 0) = :archived")
                .param("archived", archived ? 1 : 0)
                .query((rs, row) -> rs.getInt("y"))
                .list()
                .stream()
                .sorted(Comparator.reverseOrder())
                .toList());
  }

  /**
   * Like {@link #findItemsGroupedByInvoiceForCustomer(int)}, restricted to the given documents (one
   * page).
   *
   * @param invoiceIds the document ids
   * @return document id → root items; documents without items are absent
   */
  public Map<Integer, List<InvoiceItem>> findItemsGroupedByInvoiceIds(
      Collection<Integer> invoiceIds) {
    if (invoiceIds.isEmpty()) {
      return new HashMap<>();
    }
    return groupAndNest(
        run(
            OPERATION,
            () ->
                jdbc.sql(ITEM_DISPLAY_SELECT + " WHERE ii.invoice_id IN (:ids) ORDER BY ii.id")
                    .param("ids", invoiceIds)
                    .query(ITEM_MAPPER)
                    .list()));
  }

  /**
   * Like {@link #findCreditNoteIdsByCancelledInvoice()}, restricted to the given cancelled
   * documents.
   *
   * @param invoiceIds the document ids
   * @return cancelled invoice id → credit note id
   */
  public Map<Integer, Integer> findCreditNoteIdsForInvoices(Collection<Integer> invoiceIds) {
    if (invoiceIds.isEmpty()) {
      return new HashMap<>();
    }
    return run(
        "findCreditNoteIdsForInvoices",
        () -> {
          Map<Integer, Integer> result = new HashMap<>();
          jdbc.sql("SELECT id, cancels_invoice_id FROM invoice WHERE cancels_invoice_id IN (:ids)")
              .param("ids", invoiceIds)
              .query(
                  rs -> {
                    result.put(rs.getInt("cancels_invoice_id"), rs.getInt("id"));
                  });
          return result;
        });
  }

  /**
   * Marks a document as archived.
   *
   * @param invoiceId the document id
   */
  public void archive(int invoiceId) {
    run("archive", () -> update("UPDATE invoice SET archived = 1 WHERE id = :v", invoiceId));
  }

  /**
   * Removes the archived mark from a document.
   *
   * @param invoiceId the document id
   */
  public void unarchive(int invoiceId) {
    run("unarchive", () -> update("UPDATE invoice SET archived = 0 WHERE id = :v", invoiceId));
  }

  /**
   * Searches documents by id, number, customer, notes and dates, case-insensitively, against the
   * raw query and a variant with umlauts and ß transliterated.
   *
   * @param query the user's search text
   * @return up to 50 hits, newest invoice date first
   */
  public List<SearchInvoice> search(String query) {
    return run(
        "search invoices",
        () ->
            bindSearch(
                    jdbc.sql(
                        SEARCH_SELECT
                            + " WHERE "
                            + SEARCH_PREDICATE
                            + " ORDER BY i.invoice_date DESC LIMIT 50"),
                    query)
                .query(SEARCH_MAPPER)
                .list());
  }

  /** Binds the three search patterns: raw, transliterated (ä → a, ß → ss) and date. */
  private static JdbcClient.StatementSpec bindSearch(JdbcClient.StatementSpec spec, String query) {
    String normalizedQuery =
        query
            .replace("ä", "a")
            .replace("ö", "o")
            .replace("ü", "u")
            .replace("Ä", "A")
            .replace("Ö", "O")
            .replace("Ü", "U")
            .replace("ß", "ss");
    String searchPattern = "%" + query + "%";
    return spec.param("pattern", searchPattern)
        .param("normalized", "%" + normalizedQuery + "%")
        .param("date", searchPattern);
  }

  /**
   * Finds a document by its number.
   *
   * @param documentNumber the number
   * @return the document, or null if not found
   */
  public Invoice findByDocumentNumber(String documentNumber) {
    return run(
        "findByDocumentNumber",
        () -> queryOne("SELECT * FROM invoice WHERE document_number = :v", documentNumber));
  }

  /**
   * Next version number within a version group.
   *
   * @param versionGroupId the group
   * @return the highest version + 1 (1 for an empty group)
   */
  public int getNextVersion(int versionGroupId) {
    return run(
        "getNextVersion",
        () ->
            jdbc.sql(
                        "SELECT COALESCE(MAX(version), 0) FROM invoice"
                            + " WHERE version_group_id = :group")
                    .param("group", versionGroupId)
                    .query(Integer.class)
                    .single()
                + 1);
  }

  /**
   * Clears the latest flag on every version of a group except one.
   *
   * @param versionGroupId the group
   * @param excludeId the version that stays latest
   */
  public void resetIsLatestForGroup(int versionGroupId, int excludeId) {
    run(
        "resetIsLatestForGroup",
        () ->
            jdbc.sql(
                    "UPDATE invoice SET is_latest = 0"
                        + " WHERE version_group_id = :group AND id != :excludeId")
                .param("group", versionGroupId)
                .param("excludeId", excludeId)
                .update());
  }

  public int resolveVersionGroupId(String baseDocumentNumber) {
    Invoice base = findByDocumentNumber(baseDocumentNumber);
    if (base == null) {
      return 0;
    }
    int vgId = base.getVersionGroupId();
    if (vgId != 0) {
      return vgId;
    }
    return base.getId();
  }
}
