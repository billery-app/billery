package de.billery.api.controller;

import de.billery.service.DocumentConversionService;
import de.billery.service.DocumentConversionService.DocumentSection;
import de.billery.service.DocumentConversionService.ExchangeDocument;
import de.billery.service.EmptyDocumentException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Template exchange with word processors: a template as DOCX or ODT and back.
 *
 * <p>The file format is a list of sections (a heading per block, below it lines like "Text: …" with
 * the template's {@code {{variables}}}). Which block and field a line belongs to is decided by the
 * SPA ({@code utils/documentExchange.js}), which knows the block fields; this controller only
 * converts. Importing saves nothing: the SPA opens the result in the template editor, and the
 * template is saved like any other.
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

  private static final Logger LOGGER = Logger.getLogger(DocumentController.class.getName());

  private static final MediaType DOCX =
      MediaType.parseMediaType(
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
  private static final MediaType ODT =
      MediaType.parseMediaType("application/vnd.oasis.opendocument.text");

  private final DocumentConversionService conversionService;

  /**
   * @param conversionService reads and writes DOCX / ODT
   */
  public DocumentController(DocumentConversionService conversionService) {
    this.conversionService = conversionService;
  }

  /**
   * Reads a DOCX or ODT into sections. Nothing is stored.
   *
   * @param file the upload ({@code .docx} or {@code .odt})
   * @return the file name and the sections; 400 with a German message for an empty, unreadable or
   *     refused file
   */
  @PostMapping("/import")
  public ResponseEntity<?> importDocument(@RequestParam("file") MultipartFile file) {
    try {
      ExchangeDocument document = conversionService.importDocument(file);
      return ResponseEntity.ok(document);
    } catch (EmptyDocumentException e) {
      return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    } catch (IllegalArgumentException e) {
      LOGGER.log(Level.WARNING, "Document import refused", e);
      return ResponseEntity.badRequest()
          .body(
              Map.of(
                  "message",
                  "Das Dokument konnte nicht gelesen werden. Unterstützt werden DOCX- und"
                      + " ODT-Dateien bis 2 MB."));
    }
  }

  /**
   * Writes sections as a DOCX or ODT download.
   *
   * @param request the file name (without extension), the format and the sections
   * @return the file; 400 with a German message for an unknown format or content beyond the limits
   */
  @PostMapping("/export")
  public ResponseEntity<?> exportDocument(@RequestBody ExportRequest request) {
    if (request == null) {
      return ResponseEntity.badRequest().body(Map.of("message", "Keine Exportdaten."));
    }
    String format =
        request.format() == null ? "" : request.format().toLowerCase(Locale.ROOT).strip();
    if (!"docx".equals(format) && !"odt".equals(format)) {
      return ResponseEntity.badRequest()
          .body(Map.of("message", "Unbekanntes Format; möglich sind docx und odt."));
    }
    try {
      DocumentConversionService.checkExportLimits(request.sections());
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }
    byte[] bytes =
        "docx".equals(format)
            ? conversionService.exportToDocx(request.sections())
            : conversionService.exportToOdt(request.sections());
    String filename = fileName(request.name()) + "." + format;
    return ResponseEntity.ok()
        .contentType("docx".equals(format) ? DOCX : ODT)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(bytes);
  }

  /**
   * A file name from a template name: path separators, control characters and quotes removed, at
   * most 100 characters, "Vorlage" when nothing is left.
   */
  static String fileName(String name) {
    String clean = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").strip();
    clean = clean.replaceAll("\\s+", " ");
    if (clean.length() > 100) {
      clean = clean.substring(0, 100).strip();
    }
    return clean.isEmpty() ? "Vorlage" : clean;
  }

  /**
   * The body of {@code POST /api/documents/export}.
   *
   * @param name the file name without extension (usually the template name)
   * @param format {@code docx} or {@code odt}
   * @param sections the content
   */
  public record ExportRequest(String name, String format, List<DocumentSection> sections) {}
}
