package de.billery.api.controller;

import de.billery.service.GobdExportService;
import java.time.LocalDate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Data exports for third parties (tax audit). */
@RestController
@RequestMapping("/api/export")
public class ExportController {

  private final GobdExportService gobdExportService;

  public ExportController(GobdExportService gobdExportService) {
    this.gobdExportService = gobdExportService;
  }

  /**
   * GoBD data export (§147 Abs. 6 AO) of the issued invoices and credit notes of a period as a ZIP:
   * CSV tables, a GDPdU {@code index.xml} and, unless disabled, the PDF and XRechnung of each
   * document.
   *
   * @param from first document date (default: 1 January of the current year)
   * @param to last document date (default: today)
   * @param documents whether to include PDFs and XRechnungs (default {@code true})
   * @return the ZIP file, or 400 when {@code from} is after {@code to}; the limits of {@link
   *     GobdExportService#configureLimits} answer 413 (too long a period, too many documents, ZIP
   *     too large) or 429 (another export is running) through {@code ApiExceptionHandler}
   */
  @GetMapping("/gobd")
  public ResponseEntity<byte[]> gobdExport(
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(required = false, defaultValue = "true") boolean documents) {
    LocalDate start = from != null ? from : LocalDate.now().withDayOfYear(1);
    LocalDate end = to != null ? to : LocalDate.now();
    if (start.isAfter(end)) {
      return ResponseEntity.badRequest().build();
    }
    byte[] zip = gobdExportService.export(start, end, documents);
    String filename = "GoBD-Export_" + start + "_" + end + ".zip";
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/zip"))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
        .body(zip);
  }
}
