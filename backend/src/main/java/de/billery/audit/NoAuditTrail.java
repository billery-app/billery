package de.billery.audit;

import java.time.LocalDate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Self-host and desktop: one user, no administration log; nothing is recorded. */
@Component
@Profile("!saas")
public class NoAuditTrail implements AuditTrail {

  @Override
  public void gobdExportRequested(LocalDate from, LocalDate to, boolean withFiles) {
    // nothing to record
  }

  @Override
  public void gobdExportRefused(LocalDate from, LocalDate to, boolean withFiles, String reason) {
    // nothing to record
  }

  @Override
  public void gobdExportFinished(
      LocalDate from, LocalDate to, boolean withFiles, int documents, String problem) {
    // nothing to record
  }
}
