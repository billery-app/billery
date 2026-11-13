package de.billery.service;

import de.billery.service.FileSecurityService.ValidationResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import org.apache.commons.io.FilenameUtils;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart;
import org.odftoolkit.odfdom.doc.OdfTextDocument;
import org.odftoolkit.odfdom.dom.element.office.OfficeTextElement;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Reads and writes the exchange format of the templates: a DOCX or ODT file as a list of {@link
 * DocumentSection}s, each a heading with the plain-text paragraphs below it. Which block and field
 * a section or a paragraph belongs to is decided by the SPA (its block registry knows the fields);
 * this service only converts between the files and the sections, and keeps what is not the
 * document's current text out (comments, footnotes, deleted tracked changes).
 */
@Service
public class DocumentConversionService {

  private final FileSecurityService fileSecurityService;

  /** ODF text namespace ({@code text:p}, {@code text:h}, {@code text:s}, …). */
  private static final String ODF_TEXT_NS = "urn:oasis:names:tc:opendocument:xmlns:text:1.0";

  /** ODF office namespace ({@code office:annotation}). */
  private static final String ODF_OFFICE_NS = "urn:oasis:names:tc:opendocument:xmlns:office:1.0";

  /**
   * Deepest element nesting a DOCX/ODT XML part may have; real documents stay far below (lists in
   * tables in sections are a few dozen levels). Deeper files are refused instead of overflowing the
   * stack. 100 is also the default of the JDK's own parser limit {@code jdk.xml.maxElementDepth}
   * since Java 25, which the DOM parsers of odfdom and POI run into: a higher value would only move
   * the refusal into the library, with a less helpful message.
   */
  static final int MAX_ODT_DEPTH = 100;

  /** Most sections an export may have (a template has a few dozen at most). */
  static final int MAX_SECTIONS = 200;

  /** Most paragraphs an export may have, over all sections. */
  static final int MAX_PARAGRAPHS = 2000;

  /** Longest text of one heading or paragraph of an export. */
  static final int MAX_TEXT_LENGTH = 10_000;

  /** DOCX style ids and names of headings: Word's ids ("Heading1", German "berschrift1"), title. */
  private static final java.util.regex.Pattern DOCX_HEADING =
      java.util.regex.Pattern.compile("(?i)(heading|berschrift|überschrift)\\s*(\\d)|title|titel");

  public DocumentConversionService(FileSecurityService fileSecurityService) {
    this.fileSecurityService = fileSecurityService;
  }

  /**
   * Reads an uploaded DOCX or ODT into sections.
   *
   * @param file the upload
   * @return the file name without extension and the sections in document order
   * @throws IllegalArgumentException if the file is not an acceptable DOCX/ODT or cannot be read
   * @throws EmptyDocumentException if the document has no text
   */
  public ExchangeDocument importDocument(MultipartFile file) {
    ValidationResult validation = fileSecurityService.validateFile(file);
    if (!validation.isValid()) {
      throw new IllegalArgumentException(validation.getMessage());
    }
    String extension =
        FilenameUtils.getExtension(file.getOriginalFilename()).toLowerCase(Locale.ROOT);
    byte[] archive;
    try {
      archive = file.getBytes();
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException("Document could not be read", e);
    }
    ValidationResult archiveValidation = fileSecurityService.validateArchive(archive, extension);
    if (!archiveValidation.isValid()) {
      throw new IllegalArgumentException("Invalid document archive");
    }

    List<DocumentSection> sections;
    try {
      sections =
          switch (extension) {
            case "docx" -> importDocx(archive);
            case "odt" -> importOdt(archive);
            default -> throw new IllegalArgumentException("Unsupported file format");
          };
    } catch (Exception e) {
      throw new IllegalArgumentException("Document import failed", e);
    }
    if (sections.isEmpty()) {
      throw new EmptyDocumentException();
    }
    return new ExchangeDocument(FilenameUtils.getBaseName(file.getOriginalFilename()), sections);
  }

  /**
   * Writes the sections as a DOCX: every heading in Word's style "Heading 1", every paragraph as a
   * plain paragraph.
   *
   * @param sections the content, checked by {@link #checkExportLimits}
   * @return the DOCX bytes
   */
  public byte[] exportToDocx(List<DocumentSection> sections) {
    checkExportLimits(sections);
    try {
      WordprocessingMLPackage wordPackage = WordprocessingMLPackage.createPackage();
      MainDocumentPart documentPart = wordPackage.getMainDocumentPart();
      for (DocumentSection section : sections) {
        if (hasText(section.heading())) {
          documentPart.addStyledParagraphOfText("Heading1", oneLine(section.heading()));
        }
        for (String paragraph : section.paragraphs()) {
          documentPart.addParagraphOfText(oneLine(paragraph));
        }
      }
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      wordPackage.save(outputStream);
      return outputStream.toByteArray();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to export to DOCX", e);
    }
  }

  /**
   * Writes the sections as an ODT: every heading as {@code text:h} of outline level 1, every
   * paragraph as {@code text:p}.
   *
   * @param sections the content, checked by {@link #checkExportLimits}
   * @return the ODT bytes
   */
  public byte[] exportToOdt(List<DocumentSection> sections) {
    checkExportLimits(sections);
    try (OdfTextDocument odtDocument = OdfTextDocument.newTextDocument()) {
      OfficeTextElement root = odtDocument.getContentRoot();
      // A new document starts with one empty paragraph; drop it so the export does not begin
      // with a blank line.
      List<Node> emptyParagraphs = new ArrayList<>();
      for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
        if (isText(child, "p") && child.getTextContent().isBlank()) {
          emptyParagraphs.add(child);
        }
      }
      emptyParagraphs.forEach(root::removeChild);
      for (DocumentSection section : sections) {
        if (hasText(section.heading())) {
          root.newTextHElement(1).setTextContent(oneLine(section.heading()));
        }
        for (String paragraph : section.paragraphs()) {
          odtDocument.newParagraph(oneLine(paragraph));
        }
      }
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      odtDocument.save(outputStream);
      return outputStream.toByteArray();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to export to ODT", e);
    }
  }

  /**
   * Refuses an export that is larger than a template can be (it is built in memory).
   *
   * @param sections the requested content
   * @throws IllegalArgumentException with a German message if a limit is exceeded or the content is
   *     missing
   */
  public static void checkExportLimits(List<DocumentSection> sections) {
    if (sections == null || sections.isEmpty()) {
      throw new IllegalArgumentException("Die Vorlage enthält keine Texte zum Exportieren.");
    }
    if (sections.size() > MAX_SECTIONS) {
      throw new IllegalArgumentException("Zu viele Abschnitte für einen Export.");
    }
    int paragraphs = 0;
    for (DocumentSection section : sections) {
      if (section == null || section.paragraphs() == null) {
        throw new IllegalArgumentException("Ungültiger Abschnitt im Export.");
      }
      paragraphs += section.paragraphs().size();
      if (tooLong(section.heading())
          || section.paragraphs().stream().anyMatch(p -> p == null || tooLong(p))) {
        throw new IllegalArgumentException("Ein Text des Exports ist zu lang.");
      }
    }
    if (paragraphs > MAX_PARAGRAPHS) {
      throw new IllegalArgumentException("Zu viele Absätze für einen Export.");
    }
  }

  private static boolean tooLong(String text) {
    return text != null && text.length() > MAX_TEXT_LENGTH;
  }

  private static boolean hasText(String text) {
    return text != null && !text.isBlank();
  }

  /** A paragraph of the export is one line; line breaks inside become spaces. */
  private static String oneLine(String text) {
    return text.replaceAll("\\s*[\\r\\n]+\\s*", " ").strip();
  }

  /** The sections of a DOCX body, tables included (their cells in reading order). */
  private static List<DocumentSection> importDocx(byte[] archive) throws Exception {
    checkXmlDepth(archive);
    try (InputStream inputStream = new ByteArrayInputStream(archive);
        XWPFDocument document = new XWPFDocument(inputStream)) {
      SectionCollector collector = new SectionCollector();
      appendDocxElements(document, document.getBodyElements(), collector, 0);
      return collector.sections();
    }
  }

  private static void appendDocxElements(
      XWPFDocument document, List<IBodyElement> elements, SectionCollector collector, int depth) {
    checkDepth(depth);
    for (IBodyElement element : elements) {
      if (element instanceof XWPFParagraph paragraph) {
        String text = paragraph.getText();
        if (isDocxHeading(document, paragraph)) {
          collector.heading(text);
        } else {
          collector.paragraph(text);
        }
      } else if (element instanceof XWPFTable table) {
        for (XWPFTableRow row : table.getRows()) {
          for (XWPFTableCell cell : row.getTableCells()) {
            appendDocxElements(document, cell.getBodyElements(), collector, depth + 1);
          }
        }
      }
    }
  }

  /** Whether the paragraph has a heading or title style (by style id or by the style's name). */
  private static boolean isDocxHeading(XWPFDocument document, XWPFParagraph paragraph) {
    String styleId = paragraph.getStyleID();
    if (styleId == null) {
      return false;
    }
    if (DOCX_HEADING.matcher(styleId).matches()) {
      return true;
    }
    XWPFStyle style = document.getStyles() == null ? null : document.getStyles().getStyle(styleId);
    return style != null
        && style.getName() != null
        && DOCX_HEADING.matcher(style.getName()).matches();
  }

  /** The sections of an ODT. */
  private static List<DocumentSection> importOdt(byte[] archive) throws Exception {
    checkXmlDepth(archive);
    try (InputStream inputStream = new ByteArrayInputStream(archive);
        OdfTextDocument odtDocument = OdfTextDocument.loadDocument(inputStream)) {
      return odtBodyToSections(odtDocument.getContentRoot());
    }
  }

  /**
   * The sections of an ODT body ({@code office:text}): headings ({@code text:h}) start a section,
   * paragraphs (also inside lists, tables and sections) are its text, in document order; comments,
   * footnotes and the change-tracking store are left out. Elements are recognised by namespace URI
   * and local name, never by prefix: prefixes are free in XML, and odfdom even keeps the first
   * prefix it saw for a namespace process-wide, so names like {@code x:p} do occur. The text is
   * read paragraph by paragraph: {@code getTextContent()} of the {@code office:text} root returns
   * an empty string in odfdom 0.13, which once made every ODT import empty.
   *
   * @param officeText the {@code office:text} element (any namespace-aware DOM)
   * @return the sections
   * @throws IllegalArgumentException if the elements nest deeper than {@value #MAX_ODT_DEPTH}
   */
  static List<DocumentSection> odtBodyToSections(Node officeText) {
    SectionCollector collector = new SectionCollector();
    appendOdtBlocks(officeText, collector, 0);
    return collector.sections();
  }

  private static void appendOdtBlocks(Node node, SectionCollector collector, int depth) {
    checkDepth(depth);
    for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child.getNodeType() != Node.ELEMENT_NODE || isOdtSideContent(child)) {
        continue;
      }
      if (isText(child, "p") || isText(child, "h")) {
        StringBuilder text = new StringBuilder();
        appendOdtText(child, text, depth + 1);
        if (isText(child, "h")) {
          collector.heading(text.toString());
        } else {
          collector.paragraph(text.toString());
        }
      } else {
        appendOdtBlocks(child, collector, depth + 1);
      }
    }
  }

  /** The text of a paragraph, with ODF spaces, tabs and line breaks resolved. */
  private static void appendOdtText(Node node, StringBuilder text, int depth) {
    checkDepth(depth);
    for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child.getNodeType() == Node.TEXT_NODE) {
        text.append(child.getNodeValue());
      } else if (child.getNodeType() == Node.ELEMENT_NODE && !isOdtSideContent(child)) {
        if (isText(child, "s")) {
          text.append(" ".repeat(spaceCount(child)));
        } else if (isText(child, "tab")) {
          text.append('\t');
        } else if (isText(child, "line-break")) {
          text.append('\n');
        } else {
          appendOdtText(child, text, depth + 1);
        }
      }
    }
  }

  /**
   * Builds the section list: text before the first heading goes into a section without heading,
   * empty paragraphs are dropped, a line break inside a paragraph splits it into two paragraphs.
   */
  private static final class SectionCollector {
    private final List<DocumentSection> done = new ArrayList<>();

    /** The heading of the open section; empty for text before the first heading. */
    private String openHeading = "";

    private List<String> openParagraphs = new ArrayList<>();

    void heading(String text) {
      String clean = text == null ? "" : text.strip();
      if (clean.isEmpty()) {
        return;
      }
      flush();
      openHeading = clean;
    }

    void paragraph(String text) {
      if (text == null) {
        return;
      }
      for (String line : text.split("\\R")) {
        if (!line.isBlank()) {
          openParagraphs.add(line.strip());
        }
      }
    }

    private void flush() {
      if (!openHeading.isEmpty() || !openParagraphs.isEmpty()) {
        done.add(
            new DocumentSection(
                openHeading.isEmpty() ? null : openHeading, List.copyOf(openParagraphs)));
      }
      openHeading = "";
      openParagraphs = new ArrayList<>();
    }

    List<DocumentSection> sections() {
      flush();
      return List.copyOf(done);
    }
  }

  /**
   * Refuses a DOCX or ODT whose XML parts nest deeper than {@value #MAX_ODT_DEPTH} before the
   * parsing library builds its DOM: both recurse over the nesting (odfdom in {@code
   * OdfElement.isIgnoredElement}, POI in {@code XMLHelper.getDepthOfChildNodes}), so a deeply
   * nested upload overflowed the stack inside the library and answered 500. The check streams every
   * {@code .xml} and {@code .rels} entry with StAX (iterative, DTDs and external entities off); the
   * archive's size limits were already checked by {@link FileSecurityService#validateArchive}.
   *
   * @param archive the DOCX or ODT bytes
   * @throws IllegalArgumentException if an XML part nests too deeply
   * @throws Exception if an XML part cannot be read
   */
  static void checkXmlDepth(byte[] archive) throws Exception {
    XMLInputFactory factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    // This reader is iterative and stops at MAX_ODT_DEPTH itself. Lifting the JDK's limit here
    // makes
    // the refusal (and its message) the same on every Java version instead of an XMLStreamException
    // from the parser on Java 25 and ours on 21.
    if (factory.isPropertySupported("jdk.xml.maxElementDepth")) {
      factory.setProperty("jdk.xml.maxElementDepth", 0);
    }
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
      for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        if (!entry.getName().endsWith(".xml") && !entry.getName().endsWith(".rels")) {
          continue;
        }
        XMLStreamReader reader = factory.createXMLStreamReader(new NonClosingInputStream(zip));
        try {
          int depth = 0;
          while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
              depth++;
              checkDepth(depth);
            } else if (event == XMLStreamConstants.END_ELEMENT) {
              depth--;
            }
          }
        } finally {
          reader.close();
        }
      }
    }
  }

  /** Lets the StAX reader finish one zip entry without closing the whole archive stream. */
  private static final class NonClosingInputStream extends FilterInputStream {
    private NonClosingInputStream(InputStream in) {
      super(in);
    }

    @Override
    public void close() {
      // The zip stream stays open for the next entry.
    }
  }

  private static void checkDepth(int depth) {
    if (depth > MAX_ODT_DEPTH) {
      throw new IllegalArgumentException("ODT elements nested deeper than " + MAX_ODT_DEPTH);
    }
  }

  /** Whether {@code node} is the ODF text element {@code localName}, whatever its prefix. */
  private static boolean isText(Node node, String localName) {
    return ODF_TEXT_NS.equals(node.getNamespaceURI()) && localName.equals(node.getLocalName());
  }

  /**
   * Content that is not the document's current text and must not end up in a template (and later in
   * invoices): comments ({@code office:annotation}), footnotes ({@code text:note}) and the
   * change-tracking store ({@code text:tracked-changes}), which keeps <em>deleted</em> paragraphs
   * as ordinary {@code text:p} under {@code text:deletion}.
   */
  private static boolean isOdtSideContent(Node node) {
    return ODF_OFFICE_NS.equals(node.getNamespaceURI()) && "annotation".equals(node.getLocalName())
        || isText(node, "note")
        || isText(node, "tracked-changes");
  }

  private static int spaceCount(Node space) {
    String count = ((Element) space).getAttributeNS(ODF_TEXT_NS, "c");
    try {
      return count.isEmpty() ? 1 : Math.min(Math.max(Integer.parseInt(count), 1), 100);
    } catch (NumberFormatException e) {
      return 1;
    }
  }

  /**
   * One part of the exchange document.
   *
   * @param heading the heading, or {@code null} for text before the first heading
   * @param paragraphs the plain-text paragraphs below the heading, in order
   */
  public record DocumentSection(String heading, List<String> paragraphs) {}

  /**
   * An imported document.
   *
   * @param name the file name without extension
   * @param sections the sections in document order, never empty
   */
  public record ExchangeDocument(String name, List<DocumentSection> sections) {}
}
