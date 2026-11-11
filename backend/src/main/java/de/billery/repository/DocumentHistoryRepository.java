package de.billery.repository;

import static de.billery.data.DatabaseUtils.emptyToNull;
import static de.billery.data.DatabaseUtils.nullToEmpty;

import de.billery.data.DocumentHistoryEntry;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Audit trail of a document ({@code document_history}): who/what changed it and when.
 *
 * <p>Entries of issued documents are never changed or deleted. Only a draft's entries are removed,
 * together with the draft itself ({@link #deleteDraftHistory}); a draft is not yet a business
 * record.
 */
@Repository
public class DocumentHistoryRepository extends BaseRepository {

  /** A document was created (as draft, or as an issued credit note). */
  public static final String ACTION_CREATED = "CREATED";

  /** A draft was edited. */
  public static final String ACTION_UPDATED = "UPDATED";

  /** A draft was issued: number assigned, content frozen. */
  public static final String ACTION_ISSUED = "ISSUED";

  /** The status changed without another dedicated action (offers, cost calculations). */
  public static final String ACTION_STATUS_CHANGED = "STATUS_CHANGED";

  /** A payment was recorded. */
  public static final String ACTION_PAYMENT_RECORDED = "PAYMENT_RECORDED";

  /** A payment was reversed. */
  public static final String ACTION_PAYMENT_REVERSED = "PAYMENT_REVERSED";

  /** An invoice was cancelled by a credit note. */
  public static final String ACTION_CANCELLED = "CANCELLED";

  /** Maps a {@code document_history} row. */
  private static final RowMapper<DocumentHistoryEntry> ENTRY_MAPPER =
      (rs, rowNum) ->
          new DocumentHistoryEntry(
              rs.getInt("id"),
              rs.getInt("invoice_id"),
              rs.getString("action"),
              nullToEmpty(rs.getString("action_date")),
              nullToEmpty(rs.getString("notes")),
              rs.getString("performed_by"),
              null);

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc the shared JdbcClient
   */
  public DocumentHistoryRepository(JdbcClient jdbc) {
    super();
    this.jdbc = jdbc;
  }

  /**
   * Appends an entry.
   *
   * @param invoiceId the document
   * @param action one of the {@code ACTION_*} constants
   * @param notes human-readable details (may be empty)
   */
  public void log(int invoiceId, String action, String notes) {
    run(
        "log document history",
        () ->
            jdbc.sql(
                    "INSERT INTO document_history (invoice_id, action, notes)"
                        + " VALUES (:invoiceId, :action, :notes)")
                .param("invoiceId", invoiceId)
                .param("action", action)
                .param("notes", emptyToNull(notes))
                .update());
  }

  /**
   * Returns a document's audit trail, oldest first.
   *
   * @param invoiceId the document
   * @return the entries (empty if none)
   */
  public List<DocumentHistoryEntry> findByInvoiceId(int invoiceId) {
    return run(
        "findByInvoiceId document history",
        () ->
            jdbc.sql(
                    "SELECT * FROM document_history WHERE invoice_id = :invoiceId"
                        + " ORDER BY action_date, id")
                .param("invoiceId", invoiceId)
                .query(ENTRY_MAPPER)
                .list());
  }

  /**
   * Removes the entries of a draft that is about to be deleted ({@code document_history.invoice_id}
   * has no cascade). Must only be called for drafts.
   *
   * @param invoiceId the draft
   */
  public void deleteDraftHistory(int invoiceId) {
    run(
        "deleteDraftHistory",
        () ->
            jdbc.sql("DELETE FROM document_history WHERE invoice_id = :invoiceId")
                .param("invoiceId", invoiceId)
                .update());
  }
}
