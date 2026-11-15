package de.billery.api.controller;

import de.billery.service.GobdExportService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDate;
import org.springframework.http.HttpHeaders;
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
   * <p>The ZIP is streamed into the response while it is produced, on the request thread (not as an
   * asynchronous {@code StreamingResponseBody}: the SaaS tenant of the database connections is
   * bound to this thread). Period, document count and the export slot are checked before the first
   * byte, so a refusal still answers 413 or 429 with a JSON message through {@code
   * ApiExceptionHandler}. A failure after the first byte cannot change the status any more; the ZIP
   * then ends without its central directory and cannot be opened (the SPA checks this).
   *
   * @param from first document date (default: 1 January of the current year)
   * @param to last document date (default: today)
   * @param documents whether to include PDFs and XRechnungs (default {@code true})
   * @param response receives the ZIP, or 400 when {@code from} is after {@code to}
   * @throws IOException if the client goes away while the ZIP is written
   */
  @GetMapping("/gobd")
  public void gobdExport(
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(required = false, defaultValue = "true") boolean documents,
      HttpServletResponse response)
      throws IOException {
    LocalDate start = from != null ? from : LocalDate.now().withDayOfYear(1);
    LocalDate end = to != null ? to : LocalDate.now();
    if (start.isAfter(end)) {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      return;
    }
    try (GobdExportService.PreparedExport export =
        gobdExportService.prepare(start, end, documents)) {
      String filename = "GoBD-Export_" + start + "_" + end + ".zip";
      response.setContentType("application/zip");
      response.setHeader(
          HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
      export.writeTo(response.getOutputStream());
    }
  }
}
