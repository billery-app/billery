package de.billery.desktop;

import de.billery.api.error.ErrorBody;
import de.billery.api.error.ErrorCode;
import de.billery.audit.AuditTrail;
import de.billery.service.GobdExportService;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The GoBD export of the desktop app into a folder the user picked (#22): the same export as {@code
 * GET /api/export/gobd} ({@link GobdExportService}), written straight into a file instead of a
 * browser download, because the app window has no download folder of its own. Desktop only, with
 * the window's per-launch secret ({@link DesktopUiToken}) like the backups.
 *
 * <p>The ZIP is written as {@code <name>.zip.part} and renamed when it is complete, so a cut-off
 * export never lies in the folder under the final name; an existing file is never overwritten (the
 * next free name {@code … (2).zip} is used).
 */
@RestController
@Profile("desktop")
public class DesktopExportController {

  private final GobdExportService exports;
  private final AuditTrail auditTrail;
  private final DesktopUiToken uiToken;

  /**
   * @param exports the GoBD export
   * @param auditTrail records the export (outside SaaS a no-op)
   */
  @Autowired
  public DesktopExportController(GobdExportService exports, AuditTrail auditTrail) {
    this(exports, auditTrail, DesktopUiToken.fromEnvironment());
  }

  DesktopExportController(
      GobdExportService exports, AuditTrail auditTrail, DesktopUiToken uiToken) {
    this.exports = exports;
    this.auditTrail = auditTrail;
    this.uiToken = uiToken;
  }

  /**
   * Body of the export request.
   *
   * @param from the first document date (default: 1 January of this year)
   * @param to the last document date (default: today)
   * @param documents whether the PDFs and XRechnungs go into the ZIP
   * @param directory the absolute folder the ZIP is written into
   */
  public record FolderExportRequest(
      LocalDate from, LocalDate to, Boolean documents, String directory) {}

  /**
   * Writes the GoBD export into the folder.
   *
   * @param token the window's secret ({@code X-Desktop-Token}); 403 without it
   * @param request period, with files or not, target folder
   * @return {@code {"path": ...}} of the written ZIP; 400 for an unusable folder or period, the
   *     limits of the export as for the download (413 / 429)
   * @throws IOException if the file cannot be written
   */
  @PostMapping("/api/desktop/export/gobd")
  public ResponseEntity<Map<String, Object>> exportToFolder(
      @RequestHeader(value = DesktopUiToken.HEADER, required = false) String token,
      @RequestBody FolderExportRequest request)
      throws IOException {
    if (!uiToken.matches(token)) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
    LocalDate start = request.from() != null ? request.from() : LocalDate.now().withDayOfYear(1);
    LocalDate end = request.to() != null ? request.to() : LocalDate.now();
    boolean documents = request.documents() == null || request.documents();
    if (start.isAfter(end)) {
      return ResponseEntity.badRequest()
          .body(ErrorBody.from(ErrorCode.EXPORT_PERIOD_INVALID.with()));
    }
    Path folder = folder(request.directory());
    if (folder == null) {
      return ResponseEntity.badRequest()
          .body(
              ErrorBody.from(
                  ErrorCode.EXPORT_FOLDER_INVALID.with(
                      "folder", request.directory() == null ? "" : request.directory())));
    }

    auditTrail.gobdExportRequested(start, end, documents);
    GobdExportService.PreparedExport export;
    try {
      export = exports.prepare(start, end, documents);
    } catch (RuntimeException e) {
      auditTrail.gobdExportRefused(start, end, documents, e.getMessage());
      throw e;
    }
    int count = export.documentCount();
    Path target = freeName(folder, "GoBD-Export_" + start + "_" + end);
    Path partial = target.resolveSibling(target.getFileName() + ".part");
    boolean complete = false;
    try (export;
        OutputStream out = Files.newOutputStream(partial)) {
      export.writeTo(out);
      complete = true;
    } finally {
      auditTrail.gobdExportFinished(start, end, documents, count, complete ? null : "abgebrochen");
      if (!complete) {
        Files.deleteIfExists(partial);
      }
    }
    move(partial, target);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("path", target.toString());
    return ResponseEntity.ok(body);
  }

  /** The folder when it is absolute, exists and is writable; null otherwise. */
  private static Path folder(String directory) {
    if (directory == null || directory.isBlank()) {
      return null;
    }
    Path path = Paths.get(directory.strip());
    return path.isAbsolute() && Files.isDirectory(path) && Files.isWritable(path)
        ? path.toAbsolutePath()
        : null;
  }

  /** {@code name.zip}, or {@code name (2).zip} … when that exists already. */
  static Path freeName(Path folder, String name) {
    Path candidate = folder.resolve(name + ".zip");
    for (int n = 2; Files.exists(candidate); n++) {
      candidate = folder.resolve(name + " (" + n + ").zip");
    }
    return candidate;
  }

  private static void move(Path partial, Path target) throws IOException {
    try {
      Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(partial, target);
    }
  }
}
