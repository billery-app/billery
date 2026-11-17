package de.billery.audit;

import java.time.LocalDate;

/**
 * Records accesses that let data leave the application, for the investigation of a security
 * incident. Today that is the GoBD export (the whole bookkeeping of a period in one file).
 *
 * <p>The core only calls it; what happens depends on the mode: the SaaS profile writes the entry to
 * the administration log of the active company ({@code tenant_audit}, readable by its owners and
 * administrators), self-host and desktop have one user and record nothing ({@link NoAuditTrail}).
 */
// Not a functional interface by intent: further kinds of access (other exports) may follow.
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface AuditTrail {

  /**
   * A GoBD export was sent (completely or in part). Called after the export has ended, outside its
   * read-only database snapshot. Refused exports (limits, busy) are not recorded: no data left.
   *
   * @param from first document date of the period
   * @param to last document date of the period
   * @param withFiles whether the PDFs and XRechnungs were included
   * @param documents how many documents the export contained
   * @param problem null when the export was complete, otherwise why it broke off
   */
  void gobdExport(LocalDate from, LocalDate to, boolean withFiles, int documents, String problem);
}
