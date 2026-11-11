package de.billery.service;

import static org.junit.jupiter.api.Assertions.*;

import de.billery.service.DocumentConversionService.EditorDocument;
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
 * The export to DOCX and ODT. docx4j needs a JAXB implementation at run time (the JAXB API alone is
 * not enough): without {@code docx4j-JAXB-ReferenceImpl} the DOCX export answered 500 in the
 * running application while every other test stayed green.
 */
class DocumentConversionServiceTest {

  private static EditorDocument document() {
    return EditorDocument.builder()
        .name("Vorlage")
        .htmlContent("<p>Rechnung für {{kunde}}</p><p>Summe {{betrag}}</p>")
        .build();
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
  void exportsADocxWithTheTemplateText() throws Exception {
    byte[] docx = new DocumentConversionService(null).exportToDocx(document());

    List<String> names = entries(docx);
    assertTrue(names.contains("word/document.xml"), names.toString());
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
      for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        if ("word/document.xml".equals(entry.getName())) {
          assertTrue(new String(zip.readAllBytes(), StandardCharsets.UTF_8).contains("kunde"));
        }
      }
    }
  }

  @Test
  void exportsAnOdt() throws Exception {
    byte[] odt = new DocumentConversionService(null).exportToOdt(document());

    assertTrue(entries(odt).contains("content.xml"));
  }

  private static final DocumentConversionService SERVICE =
      new DocumentConversionService(new FileSecurityService());

  private static EditorDocument importFile(String name, byte[] bytes) throws Exception {
    return SERVICE.importDocument(new MockMultipartFile("file", name, null, bytes));
  }

  private static List<String> variableNames(EditorDocument doc) {
    return doc.getTemplateVariables().stream().map(v -> v.getName()).toList();
  }

  @Test
  void anOdtExportedByTheAppImportsWithItsTextAndVariables() throws Exception {
    EditorDocument imported = importFile("vorlage.odt", SERVICE.exportToOdt(document()));

    assertEquals(
        "<div><p>Rechnung für {{kunde}}</p><p>Summe {{betrag}}</p></div>",
        imported.getHtmlContent());
    assertEquals(List.of("kunde", "betrag"), variableNames(imported));
  }

  @Test
  void aDocxExportedByTheAppImportsWithItsParagraphsAndVariables() throws Exception {
    EditorDocument imported = importFile("vorlage.docx", SERVICE.exportToDocx(document()));

    assertEquals(
        "<div><p>Rechnung für {{kunde}}</p><p>Summe {{betrag}}</p></div>",
        imported.getHtmlContent());
    assertEquals(List.of("kunde", "betrag"), variableNames(imported));
  }

  @Test
  void aSecondRoundTripChangesNothing() throws Exception {
    EditorDocument once = importFile("a.odt", SERVICE.exportToOdt(document()));
    EditorDocument twice = importFile("b.odt", SERVICE.exportToOdt(once));

    assertEquals(once.getHtmlContent(), twice.getHtmlContent(), "no variables appended per export");
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

    EditorDocument imported = importFile("libre.odt", odt(body));

    assertEquals(
        "<div><h1>Angebot</h1><p>Hallo {{kunde}},  Betrag:\t{{betrag}}<br>Danke</p><br>"
            + "<p>Punkt &lt;eins&gt; &amp; zwei</p><p>Zelle</p><p>Fuß</p></div>",
        imported.getHtmlContent());
    assertEquals(List.of("kunde", "betrag"), variableNames(imported));
  }

  @Test
  void anOdtWithoutTextIsReportedInsteadOfBecomingAnEmptyTemplate() throws Exception {
    assertThrows(EmptyDocumentException.class, () -> importFile("leer.odt", odt("<text:p/>")));
  }

  @Test
  void elementsAreRecognisedByNamespaceNotByTheirPrefix() throws Exception {
    // Prefixes are free in XML. odfdom also remembers a prefix process-wide, so a document with
    // unusual prefixes must not break the plain ODT imported after it either.
    String body =
        "<x:p xmlns:x=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\""
            + " xmlns:y=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\">Hallo<x:s x:c=\"2\"/>{{kunde}}"
            + "<y:annotation><x:p>Geheimer Kommentar</x:p></y:annotation></x:p>";

    EditorDocument prefixed = importFile("praefix.odt", odt(body));
    EditorDocument plainAfterwards = importFile("normal.odt", SERVICE.exportToOdt(document()));

    assertEquals("<div><p>Hallo  {{kunde}}</p></div>", prefixed.getHtmlContent());
    assertEquals(
        "<div><p>Rechnung für {{kunde}}</p><p>Summe {{betrag}}</p></div>",
        plainAfterwards.getHtmlContent());
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
        "<div><h2>Titel</h2><p>Hallo  {{kunde}}\tEnde</p><p>Punkt</p></div>",
        DocumentConversionService.odtBodyToHtml(root));
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
        "<div><p>Rechnung für {{kunde}}</p></div>",
        importFile("aenderungen.odt", odt(body)).getHtmlContent());
  }

  @Test
  void deletedTextFromChangeTrackingIsNotImportedFromADocx() throws Exception {
    String body =
        "<w:p><w:r><w:t xml:space=\"preserve\">Rechnung </w:t></w:r>"
            + "<w:del w:id=\"1\" w:author=\"X\"><w:r><w:delText>Gelöschte IBAN DE00 1234 </w:delText></w:r></w:del>"
            + "<w:ins w:id=\"2\" w:author=\"X\"><w:r><w:t xml:space=\"preserve\">für </w:t></w:r></w:ins>"
            + "<w:r><w:t>{{kunde}}</w:t></w:r></w:p>";

    assertEquals(
        "<div><p>Rechnung für {{kunde}}</p></div>",
        importFile("aenderungen.docx", docx(body, false)).getHtmlContent());
  }

  @Test
  void aRealisticNestingDepthIsStillImported() throws Exception {
    String body =
        "<text:p>" + "<text:span>".repeat(40) + "tief" + "</text:span>".repeat(40) + "</text:p>";

    assertEquals("<div><p>tief</p></div>", importFile("ok.odt", odt(body)).getHtmlContent());
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
