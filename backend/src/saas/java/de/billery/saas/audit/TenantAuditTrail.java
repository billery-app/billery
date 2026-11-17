package de.billery.saas.audit;

import de.billery.audit.AuditTrail;
import de.billery.saas.provisioning.TenantAuditLog;
import java.time.LocalDate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * SaaS: the GoBD exports go into the administration log of the active company ({@code
 * tenant_audit}), with the acting user from the connection: the request before any data goes out
 * ({@link TenantAuditLog#GOBD_EXPORT_REQUESTED}), then the refusal ({@link
 * TenantAuditLog#GOBD_EXPORT_REFUSED}) or the sent export ({@link TenantAuditLog#GOBD_EXPORTED}).
 * Its owners and administrators see them next to the team actions; the operator uses them to tell
 * which data an account took out ({@code docs/operations/saas-incident-response.md}).
 */
@Component
@Profile("saas")
public class TenantAuditTrail implements AuditTrail {

  private final TenantAuditLog log;

  /**
   * Creates the trail.
   *
   * @param log the administration log of the active company
   */
  public TenantAuditTrail(TenantAuditLog log) {
    this.log = log;
  }

  private static String period(LocalDate from, LocalDate to, boolean withFiles) {
    return from + " bis " + to + (withFiles ? ", mit PDF und XRechnung" : ", ohne Belegdateien");
  }

  @Override
  public void gobdExportRequested(LocalDate from, LocalDate to, boolean withFiles) {
    log.record(TenantAuditLog.GOBD_EXPORT_REQUESTED, period(from, to, withFiles));
  }

  @Override
  public void gobdExportRefused(LocalDate from, LocalDate to, boolean withFiles, String reason) {
    log.record(TenantAuditLog.GOBD_EXPORT_REFUSED, period(from, to, withFiles) + ": " + reason);
  }

  @Override
  public void gobdExportFinished(
      LocalDate from, LocalDate to, boolean withFiles, int documents, String problem) {
    log.record(
        TenantAuditLog.GOBD_EXPORTED,
        period(from, to, withFiles)
            + ", "
            + documents
            + " Belege"
            + (problem == null ? ", vollständig" : ", abgebrochen: " + problem));
  }
}
