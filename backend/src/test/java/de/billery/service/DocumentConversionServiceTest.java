package de.billery.service;

import static org.junit.jupiter.api.Assertions.*;

import de.billery.service.DocumentConversionService.DocumentSection;
import de.billery.service.DocumentConversionService.ExchangeDocument;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * The exchange format: DOCX / ODT as sections (heading + paragraphs) and back. docx4j needs a JAXB
 * implementation at run time (the JAXB API alone is not enough): without {@code
 * docx4j-JAXB-ReferenceImpl} the DOCX export answered 500 in the running application while every
 * other test stayed green.
 */
class DocumentConversionServiceTest {

  private static final DocumentConversionService SERVICE =
      new DocumentConversionService(new FileSecurityService());

  /** A template as the SPA exports it: one heading per block, "Feld: Wert" lines below. */
  private static final List<DocumentSection> TEMPLATE =
      List.of(
          new DocumentSection(
              "Kopfzeile", List.of("Firmenname: {{firma}}", "Slogan: Gut & günstig")),
          new DocumentSection(
              "Hinweise",
              List.of(
                  "Text: Zahlbar innerhalb von 14 Tagen.", "Bitte {{rechnungsnummer}} angeben.")),
          new DocumentSection("Fußzeile", List.of("Text: Vielen Dank für Ihr Vertrauen!")));

  private static ExchangeDocument importFile(String name, byte[] bytes) {
    return SERVICE.importDocument(new MockMultipartFile("file", name, null, bytes));
  }

  private static List<String> entries(byte[] zipBytes) throws Exception {
    List<String> names = new ArrayList<>();
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
      for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        names.add(entry.getName());
      }
    }
    return names;
  }

  @Test
  void exportsADocxAndAnOdt() throws Exception {
    assertTrue(entries(SERVICE.exportToDocx(TEMPLATE)).contains("word/document.xml"));
    assertTrue(entries(SERVICE.exportToOdt(TEMPLATE)).contains("content.xml"));
  }

  @Test
  void aDocxExportedByTheAppImportsWithItsHeadingsAndVariables() throws Exception {
    ExchangeDocument imported = importFile("vorlage.docx", SERVICE.exportToDocx(TEMPLATE));

    assertEquals("vorlage", imported.name());
    assertEquals(TEMPLATE, imported.sections());
  }

  @Test
  void anOdtExportedByTheAppImportsWithItsHeadingsAndVariables() throws Exception {
    assertEquals(TEMPLATE, importFile("vorlage.odt", SERVICE.exportToOdt(TEMPLATE)).sections());
  }

  @Test
  void aSecondRoundTripChangesNothing() throws Exception {
    List<DocumentSection> once = importFile("a.odt", SERVICE.exportToOdt(TEMPLATE)).sections();
    List<DocumentSection> twice = importFile("b.docx", SERVICE.exportToDocx(once)).sections();

    assertEquals(once, twice);
  }

  @Test
  void aLineBreakInAnExportedParagraphBecomesASpace() throws Exception {
    List<DocumentSection> withBreak =
        List.of(new DocumentSection("Hinweise", List.of("Text: Zeile eins\nZeile zwei")));

    assertEquals(
        List.of(new DocumentSection("Hinweise", List.of("Text: Zeile eins Zeile zwei"))),
        importFile("x.docx", SERVICE.exportToDocx(withBreak)).sections());
  }

  @Test
  void textBeforeTheFirstHeadingIsASectionWithoutHeading() throws Exception {
    String body =
        "<text:p>Sehr geehrte Damen und Herren,</text:p>"
            + "<text:h text:outline-level=\"1\">Fußzeile</text:h><text:p>Danke</text:p>";

    assertEquals(
        List.of(
            new DocumentSection(null, List.of("Sehr geehrte Damen und Herren,")),
            new DocumentSection("Fußzeile", List.of("Danke"))),
        importFile("brief.odt", odt(body)).sections());
  }

  @Test
  void anOdtWithLibreOfficeMarkupImportsHeadingsListsTablesAndSpacing() throws Exception {
    String body =
        "<text:h text:style-name=\"Heading_20_1\" text:outline-level=\"1\">Angebot</text:h>"
            + "<text:p text:style-name=\"P1\">Hallo <text:span text:style-name=\"T1\">{{kunde}}</text:span>,"
            + "<text:s text:c=\"2\"/>Betrag:<text:tab/>{{betrag}}<text:line-break/>Danke"
            + "<office:annotation><dc:creator>X</dc:creator><text:p>Kommentar</text:p></office:annotation></text:p>"
            + "<text:p text:style-name=\"P2\"/>"
            + "<text:list><text:list-item><text:p>Punkt &lt;eins&gt; &amp; zwei</text:p></text:list-item></text:list>"
            + "<table:table table:name=\"T\"><table:table-row><table:table-cell>"
            + "<text:p>Zelle</text:p></table:table-cell></table:table-row></table:table>"
            + "<text:p>Fu<text:note text:note-class=\"footnote\"><text:note-body>"
            + "<text:p>Fußnote</text:p></text:note-body></text:note>ß</text:p>";

    assertEquals(
        List.of(
            new DocumentSection(
                "Angebot",
                List.of(
                    "Hallo {{kunde}},  Betrag:\t{{betrag}}",
                    "Danke",
                    "Punkt <eins> & zwei",
                    "Zelle",
                    "Fuß"))),
        importFile("libre.odt", odt(body)).sections());
  }

  @Test
  void aDocxWithWordHeadingsAndATableImportsInReadingOrder() throws Exception {
    String body =
        "<w:p><w:pPr><w:pStyle w:val=\"berschrift1\"/></w:pPr><w:r><w:t>Kopfzeile</w:t></w:r></w:p>"
            + "<w:p><w:r><w:t>Firmenname: {{firma}}</w:t></w:r></w:p>"
            + "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Zelle A</w:t></w:r></w:p></w:tc>"
            + "<w:tc><w:p><w:r><w:t>Zelle B</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
            + "<w:p><w:pPr><w:pStyle w:val=\"Heading2\"/></w:pPr><w:r><w:t>Fußzeile</w:t></w:r></w:p>"
            + "<w:p/><w:p><w:r><w:t>Danke</w:t></w:r></w:p>";

    assertEquals(
        List.of(
            new DocumentSection(
                "Kopfzeile", List.of("Firmenname: {{firma}}", "Zelle A", "Zelle B")),
            new DocumentSection("Fußzeile", List.of("Danke"))),
        importFile("word.docx", docx(body, false)).sections());
  }

  @Test
  void anOdtWithoutTextIsReportedInsteadOfBecomingAnEmptyTemplate() throws Exception {
    assertThrows(EmptyDocumentException.class, () -> importFile("leer.odt", odt("<text:p/>")));
  }

  @Test
  void anExportBeyondTheLimitsIsRefused() throws Exception {
    List<DocumentSection> tooMany =
        java.util.Collections.nCopies(
            DocumentConversionService.MAX_SECTIONS + 1, new DocumentSection("X", List.of("y")));
    assertThrows(IllegalArgumentException.class, () -> SERVICE.exportToDocx(tooMany));
    String tooLong = "x".repeat(DocumentConversionService.MAX_TEXT_LENGTH + 1);
    assertThrows(
        IllegalArgumentException.class,
        () -> SERVICE.exportToOdt(List.of(new DocumentSection("X", List.of(tooLong)))));
    assertThrows(IllegalArgumentException.class, () -> SERVICE.exportToOdt(List.of()));
  }

  @Test
  void elementsAreRecognisedByNamespaceNotByTheirPrefix() throws Exception {
    // Prefixes are free in XML. odfdom also remembers a prefix process-wide, so a document with
    // unusual prefixes must not break the plain ODT imported after it either.
    String body =
        "<x:p xmlns:x=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\""
            + " xmlns:y=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\">Hallo<x:s x:c=\"2\"/>{{kunde}}"
            + "<y:annotation><x:p>Geheimer Kommentar</x:p></y:annotation></x:p>";

    ExchangeDocument prefixed = importFile("praefix.odt", odt(body));
    ExchangeDocument plainAfterwards = importFile("normal.odt", SERVICE.exportToOdt(TEMPLATE));

    assertEquals(
        List.of(new DocumentSection(null, List.of("Hallo  {{kunde}}"))), prefixed.sections());
    assertEquals(TEMPLATE, plainAfterwards.sections());
  }

  @Test
  void theExtractionMatchesNamespacesWhateverPrefixTheDomCarries() throws Exception {
    // A plain namespace-aware DOM keeps the document's own prefixes (odfdom may or may not,
    // depending on which document it loaded first in this process).
    String xml =
        "<y:text xmlns:y=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
            + " xmlns:x=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\">"
            + "<x:h x:outline-level=\"2\">Titel</x:h>"
            + "<x:p>Hallo<x:s x:c=\"2\"/>{{kunde}}<x:tab/>Ende"
            + "<y:annotation><x:p>Geheimer Kommentar</x:p></y:annotation></x:p>"
            + "<x:list><x:list-item><x:p>Punkt</x:p></x:list-item></x:list></y:text>";
    javax.xml.parsers.DocumentBuilderFactory factory =
        javax.xml.parsers.DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    org.w3c.dom.Element root =
        factory
            .newDocumentBuilder()
            .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
            .getDocumentElement();

    assertEquals(
        List.of(new DocumentSection("Titel", List.of("Hallo  {{kunde}}\tEnde", "Punkt"))),
        DocumentConversionService.odtBodyToSections(root));
  }

  @Test
  void aDeeplyNestedOdtIsRefusedInsteadOfOverflowingTheStack() throws Exception {
    String body =
        "<text:p>"
            + "<text:span>".repeat(10_000)
            + "x"
            + "</text:span>".repeat(10_000)
            + "</text:p>";
    byte[] odt = odtWithContent(odtContent(body), true);
    assertTrue(
        new FileSecurityService().validateArchive(odt, "odt").isValid(), "passes the zip checks");

    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> importFile("tief.odt", odt));
    assertTrue(rootCause(e).getMessage().contains("nested"), rootCause(e).toString());
  }

  @Test
  void anOdtNestedJustBelowTheLimitIsStillImported() throws Exception {
    // office:document-content, office:body, office:text and text:p take 4 of the allowed levels.
    int spans = DocumentConversionService.MAX_ODT_DEPTH - 4;
    String body =
        "<text:p>" + "<text:span>".repeat(spans) + "x" + "</text:span>".repeat(spans) + "</text:p>";
    byte[] odt = odtWithContent(odtContent(body), true);

    DocumentConversionService.checkXmlDepth(odt);
    assertDoesNotThrow(() -> importFile("grenze.odt", odt));

    String tooDeep =
        "<text:p>"
            + "<text:span>".repeat(spans + 1)
            + "x"
            + "</text:span>".repeat(spans + 1)
            + "</text:p>";
    byte[] odt2 = odtWithContent(odtContent(tooDeep), true);
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class, () -> DocumentConversionService.checkXmlDepth(odt2));
    assertTrue(e.getMessage().contains("nested"), e.getMessage());
  }

  @Test
  void aDeeplyNestedDocxIsRefusedInsteadOfOverflowingTheStack() throws Exception {
    String body =
        "<w:p>"
            + "<w:sdt><w:sdtContent>".repeat(10_000)
            + "<w:r><w:t>x</w:t></w:r>"
            + "</w:sdtContent></w:sdt>".repeat(10_000)
            + "</w:p>";
    byte[] docx = docx(body, true);
    assertTrue(
        new FileSecurityService().validateArchive(docx, "docx").isValid(), "passes the zip checks");

    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> importFile("tief.docx", docx));
    assertTrue(rootCause(e).getMessage().contains("nested"), rootCause(e).toString());
  }

  @Test
  void deletedTextFromChangeTrackingIsNotImportedFromAnOdt() throws Exception {
    // LibreOffice keeps deleted paragraphs with "track changes" on under text:tracked-changes.
    String body =
        "<text:tracked-changes><text:changed-region text:id=\"ct1\"><text:deletion>"
            + "<office:change-info><dc:creator>X</dc:creator><dc:date>2026-10-01T10:00:00</dc:date></office:change-info>"
            + "<text:p>Gelöschte IBAN DE00 1234</text:p></text:deletion></text:changed-region>"
            + "</text:tracked-changes>"
            + "<text:p>Rechnung<text:change text:change-id=\"ct1\"/> für {{kunde}}</text:p>";

    assertEquals(
        List.of(new DocumentSection(null, List.of("Rechnung für {{kunde}}"))),
        importFile("aenderungen.odt", odt(body)).sections());
  }

  @Test
  void deletedTextFromChangeTrackingIsNotImportedFromADocx() throws Exception {
    String body =
        "<w:p><w:r><w:t xml:space=\"preserve\">Rechnung </w:t></w:r>"
            + "<w:del w:id=\"1\" w:author=\"X\"><w:r><w:delText>Gelöschte IBAN DE00 1234 </w:delText></w:r></w:del>"
            + "<w:ins w:id=\"2\" w:author=\"X\"><w:r><w:t xml:space=\"preserve\">für </w:t></w:r></w:ins>"
            + "<w:r><w:t>{{kunde}}</w:t></w:r></w:p>";

    assertEquals(
        List.of(new DocumentSection(null, List.of("Rechnung für {{kunde}}"))),
        importFile("aenderungen.docx", docx(body, false)).sections());
  }

  @Test
  void aRealisticNestingDepthIsStillImported() throws Exception {
    String body =
        "<text:p>" + "<text:span>".repeat(40) + "tief" + "</text:span>".repeat(40) + "</text:p>";

    assertEquals(
        List.of(new DocumentSection(null, List.of("tief"))),
        importFile("ok.odt", odt(body)).sections());
  }

  private static Throwable rootCause(Throwable t) {
    return t.getCause() == null ? t : rootCause(t.getCause());
  }

  /** A minimal DOCX; {@code stored}: document.xml uncompressed (passes the ratio check). */
  private static byte[] docx(String body, boolean stored) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
      zip.write(
          ("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                  + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                  + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                  + "</Types>")
              .getBytes(StandardCharsets.UTF_8));
      zip.putNextEntry(new ZipEntry("_rels/.rels"));
      zip.write(
          ("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                  + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                  + "</Relationships>")
              .getBytes(StandardCharsets.UTF_8));
      byte[] document =
          ("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                  + "<w:body>"
                  + body
                  + "</w:body></w:document>")
              .getBytes(StandardCharsets.UTF_8);
      ZipEntry entry = new ZipEntry("word/document.xml");
      if (stored) {
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(document.length);
        CRC32 crc = new CRC32();
        crc.update(document);
        entry.setCrc(crc.getValue());
      }
      zip.putNextEntry(entry);
      zip.write(document);
      zip.closeEntry();
    }
    byte[] docx = bytes.toByteArray();
    return docx;
  }

  /** A minimal ODT the way LibreOffice packs it: stored mimetype first, manifest, content. */
  private static byte[] odt(String officeTextBody) throws Exception {
    return odtWithContent(odtContent(officeTextBody));
  }

  private static String odtContent(String officeTextBody) {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<office:document-content"
        + " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
        + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\""
        + " xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\""
        + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\" office:version=\"1.3\">"
        + "<office:body><office:text>"
        + "<text:sequence-decls><text:sequence-decl text:display-outline-level=\"0\" text:name=\"Table\"/></text:sequence-decls>"
        + officeTextBody
        + "</office:text></office:body></office:document-content>";
  }

  private static byte[] odtWithContent(String content) throws Exception {
    return odtWithContent(content, false);
  }

  /** {@code stored}: content.xml uncompressed, so a repetitive file passes the ratio check. */
  private static byte[] odtWithContent(String content, boolean stored) throws Exception {
    String manifest =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\" manifest:version=\"1.3\">"
            + "<manifest:file-entry manifest:full-path=\"/\" manifest:media-type=\"application/vnd.oasis.opendocument.text\"/>"
            + "<manifest:file-entry manifest:full-path=\"content.xml\" manifest:media-type=\"text/xml\"/>"
            + "</manifest:manifest>";
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      byte[] mimetype =
          "application/vnd.oasis.opendocument.text".getBytes(StandardCharsets.US_ASCII);
      ZipEntry entry = new ZipEntry("mimetype");
      entry.setMethod(ZipEntry.STORED);
      entry.setSize(mimetype.length);
      CRC32 crc = new CRC32();
      crc.update(mimetype);
      entry.setCrc(crc.getValue());
      zip.putNextEntry(entry);
      zip.write(mimetype);
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("META-INF/manifest.xml"));
      zip.write(manifest.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
      ZipEntry contentEntry = new ZipEntry("content.xml");
      if (stored) {
        contentEntry.setMethod(ZipEntry.STORED);
        contentEntry.setSize(contentBytes.length);
        CRC32 contentCrc = new CRC32();
        contentCrc.update(contentBytes);
        contentEntry.setCrc(contentCrc.getValue());
      }
      zip.putNextEntry(contentEntry);
      zip.write(contentBytes);
      zip.closeEntry();
    }
    return bytes.toByteArray();
  }
}
