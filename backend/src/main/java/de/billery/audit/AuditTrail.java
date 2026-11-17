package de.billery.audit;

import java.time.LocalDate;

/**
 * Records accesses that let data leave the application, for the investigation of a security
 * incident. Today that is the GoBD export (the whole bookkeeping of a period in one file).
 *
 * <p>Write-ahead: {@link #gobdExportRequested} is written <em>before</em> any data goes out and
 * must succeed, or there is no export; the outcome follows as a second entry ({@link
 * #gobdExportRefused} or {@link #gobdExportFinished}). A crash in the middle therefore leaves at
 * least the request.
 *
 * <p>The core only calls it; what happens depends on the mode: the SaaS profile writes the entries
 * to the administration log of the active company ({@code tenant_audit}, readable by its owners and
 * administrators), self-host and desktop have one user and record nothing ({@link NoAuditTrail}).
 */
public interface AuditTrail {

  /**
   * A GoBD export was requested. Called before its checks and before the first byte; a failure here
   * must stop the export (the caller lets the exception through).
   *
   * @param from first document date of the period
   * @param to last document date of the period
   * @param withFiles whether the PDFs and XRechnungs are to be included
   */
  void gobdExportRequested(LocalDate from, LocalDate to, boolean withFiles);

  /**
   * The requested export was refused before any data left (a limit, another export running).
   *
   * @param from first document date of the period
   * @param to last document date of the period
   * @param withFiles whether the PDFs and XRechnungs were to be included
   * @param reason why
   */
  void gobdExportRefused(LocalDate from, LocalDate to, boolean withFiles, String reason);

  /**
   * The export was sent, completely or in part. Called after it has ended, outside its read-only
   * database snapshot.
   *
   * @param from first document date of the period
   * @param to last document date of the period
   * @param withFiles whether the PDFs and XRechnungs were included
   * @param documents how many documents the export contained
   * @param problem null when the export was complete, otherwise why it broke off
   */
  void gobdExportFinished(
      LocalDate from, LocalDate to, boolean withFiles, int documents, String problem);
}
