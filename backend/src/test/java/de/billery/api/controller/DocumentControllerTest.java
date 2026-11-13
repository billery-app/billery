package de.billery.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.billery.api.controller.DocumentController.ExportRequest;
import de.billery.service.DocumentConversionService;
import de.billery.service.DocumentConversionService.DocumentSection;
import de.billery.service.DocumentConversionService.ExchangeDocument;
import de.billery.service.FileSecurityService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

/** The template exchange endpoints: export as a download, import without storing anything. */
class DocumentControllerTest {

  private final DocumentController controller =
      new DocumentController(new DocumentConversionService(new FileSecurityService()));

  private static final List<DocumentSection> SECTIONS =
      List.of(new DocumentSection("Fußzeile", List.of("Text: Vielen Dank, {{kunde}}!")));

  @Test
  void exportsADownloadThatImportsBackToTheSameSections() {
    ResponseEntity<?> export =
        controller.exportDocument(new ExportRequest("Rechnung – Modern", "DOCX", SECTIONS));

    assertEquals(HttpStatus.OK, export.getStatusCode());
    String disposition = export.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
    assertTrue(disposition.startsWith("attachment;"), disposition);
    assertTrue(disposition.contains(".docx"), disposition);

    byte[] file = (byte[]) export.getBody();
    ResponseEntity<?> imported =
        controller.importDocument(new MockMultipartFile("file", "Rechnung.docx", null, file));

    assertEquals(HttpStatus.OK, imported.getStatusCode());
    assertEquals(SECTIONS, ((ExchangeDocument) imported.getBody()).sections());
  }

  @Test
  void refusesAnUnknownFormatAndAnEmptyExportWithAMessage() {
    ResponseEntity<?> pdf = controller.exportDocument(new ExportRequest("x", "pdf", SECTIONS));
    ResponseEntity<?> empty = controller.exportDocument(new ExportRequest("x", "odt", List.of()));

    assertEquals(HttpStatus.BAD_REQUEST, pdf.getStatusCode());
    assertTrue(message(pdf).contains("docx"));
    assertEquals(HttpStatus.BAD_REQUEST, empty.getStatusCode());
    assertTrue(message(empty).contains("keine Texte"));
  }

  @Test
  void refusesAFileThatIsNoDocumentWithAMessage() {
    ResponseEntity<?> refused =
        controller.importDocument(
            new MockMultipartFile("file", "rechnung.docx", null, "kein zip".getBytes()));

    assertEquals(HttpStatus.BAD_REQUEST, refused.getStatusCode());
    assertTrue(message(refused).contains("DOCX"), message(refused));
  }

  @Test
  void theFileNameCannotBreakTheHeaderOrLeaveTheDownloadFolder() {
    assertEquals("a b c", DocumentController.fileName("a/b\\c"));
    assertEquals("Rechnung x", DocumentController.fileName("Rechnung\"\r\nx"));
    assertEquals("Vorlage", DocumentController.fileName("  "));
    assertEquals("Vorlage", DocumentController.fileName(null));
    assertEquals(100, DocumentController.fileName("x".repeat(300)).length());
  }

  @SuppressWarnings("unchecked")
  private static String message(ResponseEntity<?> response) {
    return ((Map<String, String>) response.getBody()).get("message");
  }
}
