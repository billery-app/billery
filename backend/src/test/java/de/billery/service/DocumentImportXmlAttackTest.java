package de.billery.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import de.billery.service.DocumentConversionService.DocumentSection;
import de.billery.service.DocumentConversionService.ExchangeDocument;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockMultipartFile;

/**
 * The DOCX / ODT template import against hostile XML (threat model T20, G7): external entities to a
 * local file and to a URL, an external DTD with and without a reference into it, a parameter
 * entity, and an entity expansion ("billion laughs"), in every XML part the import or its libraries
 * read, including a DOCX part whose name does not end in {@code .xml}. None of them may read the
 * file, reach the network or expand. Each target also has a control without the attack that must
 * import normally, so a refusal is the defence and not a broken test file.
 */
class DocumentImportXmlAttackTest {

  private static final DocumentConversionService SERVICE =
      new DocumentConversionService(new FileSecurityService());

  /** The text of the local file an external entity points to; must never appear in a result. */
  private static final String SECRET = "geheimer-dateiinhalt-4711";

  /** The text every test file carries in its body. */
  private static final String BODY = "Hallo Welt";

  private static final List<String> TARGETS =
      List.of(
          "docx word/document.xml",
          "docx word/styles.xml",
          "docx [Content_Types].xml",
          "docx _rels/.rels",
          "docx word/_rels/document.xml.rels",
          "docx word/header1.bin",
          "odt content.xml",
          "odt styles.xml",
          "odt meta.xml",
          "odt META-INF/manifest.xml");

  @TempDir static Path dir;

  private static Path secretFile;
  private static HttpServer server;
  private static final AtomicInteger REQUESTS = new AtomicInteger();

  @BeforeAll
  static void start() throws Exception {
    secretFile = dir.resolve("secret.txt");
    Files.writeString(secretFile, SECRET);
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          REQUESTS.incrementAndGet();
          // an entity gets the secret as text, a DTD a declaration of it
          String answer =
              exchange.getRequestURI().getPath().equals("/entity")
                  ? SECRET
                  : "<!ENTITY remote \"" + SECRET + "\">";
          byte[] body = answer.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  @AfterAll
  static void stop() {
    server.stop(0);
  }

  private static String url(String path) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + path;
  }

  /** Kind, the DOCTYPE's subset or external id, and the reference that uses it. */
  private static List<String[]> attacks() {
    String laughs =
        "<!ENTITY a \"lol\">"
            + "<!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;\">"
            + "<!ENTITY c \"&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;\">"
            + "<!ENTITY d \"&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;\">"
            + "<!ENTITY e \"&d;&d;&d;&d;&d;&d;&d;&d;&d;&d;\">"
            + "<!ENTITY f \"&e;&e;&e;&e;&e;&e;&e;&e;&e;&e;\">"
            + "<!ENTITY g \"&f;&f;&f;&f;&f;&f;&f;&f;&f;&f;\">"
            + "<!ENTITY h \"&g;&g;&g;&g;&g;&g;&g;&g;&g;&g;\">"
            + "<!ENTITY i \"&h;&h;&h;&h;&h;&h;&h;&h;&h;&h;\">";
    return List.of(
        new String[] {"file entity", " [<!ENTITY x SYSTEM \"" + secretFile.toUri() + "\">]", "&x;"},
        new String[] {"http entity", " [<!ENTITY x SYSTEM \"" + url("/entity") + "\">]", "&x;"},
        new String[] {"external DTD", " SYSTEM \"" + url("/dtd") + "\"", "&remote;"},
        new String[] {"external DTD without reference", " SYSTEM \"" + url("/plain") + "\"", ""},
        new String[] {
          "parameter entity", " [<!ENTITY % p SYSTEM \"" + url("/param") + "\"> %p;]", "&remote;"
        },
        new String[] {"billion laughs", " [" + laughs + "]", "&i;"});
  }

  static Stream<Arguments> cases() {
    List<Arguments> cases = new ArrayList<>();
    for (String[] attack : attacks()) {
      for (String target : TARGETS) {
        cases.add(Arguments.of(attack[0] + " in " + target, target, attack[1], attack[2]));
      }
    }
    return cases.stream();
  }

  static Stream<String> targets() {
    return TARGETS.stream();
  }

  /** Without an attack, every test file imports with its body text: the files are valid. */
  @ParameterizedTest(name = "control: {0}")
  @MethodSource("targets")
  void theTestFilesImportWithoutAnAttack(String target) throws Exception {
    ExchangeDocument document = importFile(target, build(target, null, ""));

    assertTrue(text(document).contains(BODY), target + ": " + text(document));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void hostileXmlIsNeverResolvedNorExpanded(String name, String target, String doctype, String use)
      throws Exception {
    byte[] upload = build(target, doctype, use);
    REQUESTS.set(0);

    Object outcome =
        assertTimeoutPreemptively(
            Duration.ofSeconds(10),
            () -> {
              try {
                return importFile(target, upload);
              } catch (RuntimeException e) {
                return e;
              }
            },
            name + ": the import must end quickly (no expansion)");

    assertEquals(0, REQUESTS.get(), name + ": the import reached the network");
    if (outcome instanceof ExchangeDocument document) {
      String text = text(document);
      assertFalse(text.contains(SECRET), name + ": the entity was resolved: " + text);
      assertFalse(text.contains("lollollol"), name + ": the entities were expanded");
    } else {
      assertTrue(
          outcome instanceof IllegalArgumentException,
          name + ": refused with an unexpected exception " + outcome);
    }
    if (!target.endsWith(".bin")) {
      // every part with an XML name is refused by our own check, before any library reads it
      assertTrue(
          outcome instanceof IllegalArgumentException e
              && rootCause(e).getMessage().contains("declares a DOCTYPE"),
          name + ": not refused by the DOCTYPE check: " + outcome);
    }
  }

  /**
   * The second layer: the libraries alone (without our check) resolve and expand nothing either, so
   * an update of POI or odfdom that changes their defaults shows up here.
   */
  @ParameterizedTest(name = "libraries alone: {0}")
  @MethodSource("cases")
  void theLibrariesAloneResolveNothingEither(String name, String target, String doctype, String use)
      throws Exception {
    byte[] upload = build(target, doctype, use);
    REQUESTS.set(0);

    String text =
        assertTimeoutPreemptively(
            Duration.ofSeconds(10),
            () -> {
              try {
                return libraryText(target, upload);
              } catch (Exception e) {
                return ""; // refused by the library
              }
            },
            name + ": the library must end quickly (no expansion)");

    assertEquals(0, REQUESTS.get(), name + ": the library reached the network");
    assertFalse(text.contains(SECRET), name + ": the library resolved the entity: " + text);
    assertFalse(text.contains("lollollol"), name + ": the library expanded the entities");
  }

  /** What the library reads from the file: all text of a DOCX, body and generator of an ODT. */
  private static String libraryText(String target, byte[] upload) throws Exception {
    if (target.startsWith("odt")) {
      try (org.odftoolkit.odfdom.doc.OdfTextDocument odt =
          org.odftoolkit.odfdom.doc.OdfTextDocument.loadDocument(
              new java.io.ByteArrayInputStream(upload))) {
        StringBuilder all = new StringBuilder();
        for (DocumentSection section :
            DocumentConversionService.odtBodyToSections(odt.getContentRoot())) {
          all.append(section.heading()).append(section.paragraphs());
        }
        return all + String.valueOf(odt.getOfficeMetadata().getGenerator());
      }
    }
    try (org.apache.poi.xwpf.usermodel.XWPFDocument docx =
            new org.apache.poi.xwpf.usermodel.XWPFDocument(
                new java.io.ByteArrayInputStream(upload));
        org.apache.poi.xwpf.extractor.XWPFWordExtractor extractor =
            new org.apache.poi.xwpf.extractor.XWPFWordExtractor(docx)) {
      return extractor.getText();
    }
  }

  private static Throwable rootCause(Throwable t) {
    return t.getCause() == null ? t : rootCause(t.getCause());
  }

  /**
   * Positive control: the same files do read the local file and reach the server when a parser
   * without protection reads them, so the tests above would notice a parser that resolves them.
   */
  @org.junit.jupiter.api.Test
  void theAttackFilesAreDangerousForAParserWithoutProtection() throws Exception {
    javax.xml.parsers.DocumentBuilderFactory naive =
        javax.xml.parsers.DocumentBuilderFactory.newInstance();
    naive.setNamespaceAware(true);
    // Deliberately unprotected: the positive control must show the attack files work.
    /* nosemgrep: java.lang.security.audit.xxe.documentbuilderfactory-external-general-entities-true.documentbuilderfactory-external-general-entities-true */
    naive.setFeature("http://xml.org/sax/features/external-general-entities", true);
    naive.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", true);
    String[] fileEntity = attacks().get(0);
    String[] httpEntity = attacks().get(1);

    String read =
        naive
            .newDocumentBuilder()
            .parse(
                new java.io.ByteArrayInputStream(
                    part(build("odt content.xml", fileEntity[1], fileEntity[2]), "content.xml")))
            .getDocumentElement()
            .getTextContent();
    REQUESTS.set(0);
    naive
        .newDocumentBuilder()
        .parse(
            new java.io.ByteArrayInputStream(
                part(
                    build("docx word/document.xml", httpEntity[1], httpEntity[2]),
                    "word/document.xml")));

    assertTrue(read.contains(SECRET), "the file entity reads the file: " + read);
    assertTrue(REQUESTS.get() > 0, "the http entity reaches the server");
  }

  /** One entry of a zip. */
  private static byte[] part(byte[] zip, String name) throws Exception {
    try (java.util.zip.ZipInputStream in =
        new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
      for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
        if (e.getName().equals(name)) {
          return in.readAllBytes();
        }
      }
    }
    throw new IllegalStateException("no " + name);
  }

  private static ExchangeDocument importFile(String target, byte[] upload) {
    String name = target.startsWith("odt") ? "vorlage.odt" : "vorlage.docx";
    return SERVICE.importDocument(new MockMultipartFile("file", name, null, upload));
  }

  private static String text(ExchangeDocument document) {
    StringBuilder all = new StringBuilder();
    for (DocumentSection section : document.sections()) {
      all.append(section.heading()).append('\n');
      section.paragraphs().forEach(p -> all.append(p).append('\n'));
    }
    return all.toString();
  }

  // --- building the files: a DOCTYPE only in the attacked part, the reference in its text -------

  /**
   * The file for {@code target}; {@code doctype == null} builds the control without any DOCTYPE.
   */
  private static byte[] build(String target, String doctype, String use) throws Exception {
    String part = target.substring(target.indexOf(' ') + 1);
    return target.startsWith("odt")
        ? odt(new Attack(part, doctype, use))
        : docx(new Attack(part, doctype, use));
  }

  /** Where the DOCTYPE goes and what references it. */
  private record Attack(String part, String doctype, String use) {
    /** The XML declaration plus, in the attacked part, the DOCTYPE for root {@code root}. */
    String prolog(String partName, String root) {
      String declaration = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
      return doctype != null && part.equals(partName)
          ? declaration + "<!DOCTYPE " + root + doctype + ">"
          : declaration;
    }

    /** The reference, only in the attacked part. */
    String use(String partName) {
      return doctype != null && part.equals(partName) ? use : "";
    }
  }

  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  private static final String R =
      "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
  private static final String PKG_RELS =
      "http://schemas.openxmlformats.org/package/2006/relationships";

  private static byte[] docx(Attack a) throws Exception {
    Map<String, String> parts = new LinkedHashMap<>();
    parts.put(
        "[Content_Types].xml",
        a.prolog("[Content_Types].xml", "Types")
            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
            + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
            + "<Override PartName=\"/word/header1.bin\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml\"/>"
            + "<Override PartName=\"/docProps/x"
            + a.use("[Content_Types].xml")
            + ".xml\" ContentType=\"application/xml\"/>"
            + "</Types>");
    parts.put(
        "_rels/.rels",
        a.prolog("_rels/.rels", "Relationships")
            + "<Relationships xmlns=\""
            + PKG_RELS
            + "\"><Relationship Id=\"rId1\" Type=\""
            + R
            + "/officeDocument\" Target=\"word/document.xml\"/>"
            + "<Relationship Id=\"rId9\" Type=\"urn:x"
            + a.use("_rels/.rels")
            + "\" Target=\"x.xml\" TargetMode=\"External\"/>"
            + "</Relationships>");
    parts.put(
        "word/_rels/document.xml.rels",
        a.prolog("word/_rels/document.xml.rels", "Relationships")
            + "<Relationships xmlns=\""
            + PKG_RELS
            + "\"><Relationship Id=\"rId1\" Type=\""
            + R
            + "/styles\" Target=\"styles.xml\"/>"
            + "<Relationship Id=\"rId2\" Type=\""
            + R
            + "/header\" Target=\"header1.bin\"/>"
            + "<Relationship Id=\"rId9\" Type=\"urn:x"
            + a.use("word/_rels/document.xml.rels")
            + "\" Target=\"x.xml\" TargetMode=\"External\"/>"
            + "</Relationships>");
    parts.put(
        "word/document.xml",
        a.prolog("word/document.xml", "w:document")
            + "<w:document xmlns:w=\""
            + W
            + "\" xmlns:r=\""
            + R
            + "\"><w:body><w:p><w:r><w:t>"
            + BODY
            + a.use("word/document.xml")
            + "</w:t></w:r></w:p>"
            + "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"rId2\"/></w:sectPr>"
            + "</w:body></w:document>");
    parts.put(
        "word/styles.xml",
        a.prolog("word/styles.xml", "w:styles")
            + "<w:styles xmlns:w=\""
            + W
            + "\"><w:style w:type=\"paragraph\" w:styleId=\"Normal\"><w:name w:val=\"Normal"
            + a.use("word/styles.xml")
            + "\"/></w:style></w:styles>");
    parts.put(
        "word/header1.bin",
        a.prolog("word/header1.bin", "w:hdr")
            + "<w:hdr xmlns:w=\""
            + W
            + "\"><w:p><w:r><w:t>Kopf"
            + a.use("word/header1.bin")
            + "</w:t></w:r></w:p></w:hdr>");
    return zip(parts, null);
  }

  private static final String OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0";
  private static final String TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0";

  private static byte[] odt(Attack a) throws Exception {
    Map<String, String> parts = new LinkedHashMap<>();
    parts.put(
        "META-INF/manifest.xml",
        a.prolog("META-INF/manifest.xml", "manifest:manifest")
            + "<manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\" manifest:version=\"1.3\">"
            + "<manifest:file-entry manifest:full-path=\"/\" manifest:media-type=\"application/vnd.oasis.opendocument.text\"/>"
            + "<manifest:file-entry manifest:full-path=\"content.xml\" manifest:media-type=\"text/xml\"/>"
            + "<manifest:file-entry manifest:full-path=\"styles.xml\" manifest:media-type=\"text/xml\"/>"
            + "<manifest:file-entry manifest:full-path=\"meta.xml\" manifest:media-type=\"text/xml"
            + a.use("META-INF/manifest.xml")
            + "\"/></manifest:manifest>");
    parts.put(
        "content.xml",
        a.prolog("content.xml", "office:document-content")
            + "<office:document-content xmlns:office=\""
            + OFFICE
            + "\" xmlns:text=\""
            + TEXT
            + "\" office:version=\"1.3\"><office:body><office:text><text:p>"
            + BODY
            + a.use("content.xml")
            + "</text:p></office:text></office:body></office:document-content>");
    parts.put(
        "styles.xml",
        a.prolog("styles.xml", "office:document-styles")
            + "<office:document-styles xmlns:office=\""
            + OFFICE
            + "\" xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\""
            + " office:version=\"1.3\"><office:styles><style:style style:name=\"Standard"
            + a.use("styles.xml")
            + "\" style:family=\"paragraph\"/></office:styles></office:document-styles>");
    parts.put(
        "meta.xml",
        a.prolog("meta.xml", "office:document-meta")
            + "<office:document-meta xmlns:office=\""
            + OFFICE
            + "\" xmlns:meta=\"urn:oasis:names:tc:opendocument:xmlns:meta:1.0\""
            + " office:version=\"1.3\"><office:meta><meta:generator>Test"
            + a.use("meta.xml")
            + "</meta:generator></office:meta></office:document-meta>");
    return zip(parts, "application/vnd.oasis.opendocument.text");
  }

  /** A zip with an optional stored {@code mimetype} first (ODT); every entry stored. */
  private static byte[] zip(Map<String, String> entries, String mimetype) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      if (mimetype != null) {
        put(zip, "mimetype", mimetype.getBytes(StandardCharsets.US_ASCII));
      }
      for (Map.Entry<String, String> entry : entries.entrySet()) {
        // stored: the files are small and repetitive, the ratio check stays out of the way
        put(zip, entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8));
      }
    }
    return bytes.toByteArray();
  }

  private static void put(ZipOutputStream zip, String name, byte[] data) throws Exception {
    ZipEntry entry = new ZipEntry(name);
    entry.setMethod(ZipEntry.STORED);
    entry.setSize(data.length);
    CRC32 crc = new CRC32();
    crc.update(data);
    entry.setCrc(crc.getValue());
    zip.putNextEntry(entry);
    zip.write(data);
    zip.closeEntry();
  }
}
