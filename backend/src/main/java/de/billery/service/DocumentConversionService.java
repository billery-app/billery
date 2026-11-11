package de.billery.service;

import de.billery.service.FileSecurityService.ValidationResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import org.apache.commons.io.FilenameUtils;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart;
import org.odftoolkit.odfdom.doc.OdfTextDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Service for converting between document formats (DOCX, ODT, HTML) and handling template variable
 * extraction and preservation.
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

  public DocumentConversionService(FileSecurityService fileSecurityService) {
    this.fileSecurityService = fileSecurityService;
  }

  /** Import document from uploaded file to EditorDocument format */
  public EditorDocument importDocument(MultipartFile file) throws Exception {
    // Validate file security
    ValidationResult validation = fileSecurityService.validateFile(file);
    if (!validation.isValid()) {
      throw new IllegalArgumentException(validation.getMessage());
    }

    String extension =
        FilenameUtils.getExtension(file.getOriginalFilename()).toLowerCase(Locale.ROOT);
    byte[] archive = file.getBytes();
    ValidationResult archiveValidation = fileSecurityService.validateArchive(archive, extension);
    if (!archiveValidation.isValid()) {
      throw new IllegalArgumentException("Invalid document archive");
    }

    EditorDocument imported;
    try {
      imported =
          switch (extension) {
            case "docx" -> importDocx(file, archive);
            case "odt" -> importOdt(file, archive);
            default -> throw new IllegalArgumentException("Unsupported file format");
          };
    } catch (Exception e) {
      throw new IllegalArgumentException("Document import failed", e);
    }
    if (convertHtmlToPlainText(imported.getHtmlContent()).isBlank()) {
      throw new EmptyDocumentException();
    }
    return imported;
  }

  /** Export EditorDocument to DOCX format */
  public byte[] exportToDocx(EditorDocument document) throws Exception {
    try {
      // Create new DOCX document using docx4j
      WordprocessingMLPackage wordPackage = WordprocessingMLPackage.createPackage();
      MainDocumentPart documentPart = wordPackage.getMainDocumentPart();

      // One paragraph per HTML block; the {{variables}} stay in the text where they are.
      for (String paragraph :
          htmlToParagraphs(processTemplateVariables(document.getHtmlContent()))) {
        documentPart.addParagraphOfText(paragraph);
      }

      // Save to byte array
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      wordPackage.save(outputStream);

      return outputStream.toByteArray();
    } catch (Exception e) {
      throw new RuntimeException("Failed to export to DOCX", e);
    }
  }

  /** Export EditorDocument to ODT format */
  public byte[] exportToOdt(EditorDocument document) throws Exception {
    try (OdfTextDocument odtDocument = OdfTextDocument.newTextDocument()) {
      // A new document starts with one empty paragraph; drop it so the export does not begin
      // with a blank line.
      Node root = odtDocument.getContentRoot();
      List<Node> emptyParagraphs = new ArrayList<>();
      for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
        if (isText(child, "p") && child.getTextContent().isBlank()) {
          emptyParagraphs.add(child);
        }
      }
      emptyParagraphs.forEach(root::removeChild);
      // One paragraph per HTML block; the {{variables}} stay in the text where they are.
      for (String paragraph :
          htmlToParagraphs(processTemplateVariables(document.getHtmlContent()))) {
        odtDocument.newParagraph(paragraph);
      }

      // Save to byte array
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      odtDocument.save(outputStream);

      return outputStream.toByteArray();
    } catch (Exception e) {
      throw new RuntimeException("Failed to export to ODT", e);
    }
  }

  /** Import DOCX file using Apache POI XWPF with better HTML conversion */
  private EditorDocument importDocx(MultipartFile file, byte[] archive) throws Exception {
    checkXmlDepth(archive);
    try (InputStream inputStream = new ByteArrayInputStream(archive);
        XWPFDocument document = new XWPFDocument(inputStream)) {

      // Convert to HTML with better formatting preservation
      StringBuilder htmlContent = new StringBuilder();
      htmlContent.append("<div>");

      for (XWPFParagraph paragraph : document.getParagraphs()) {
        String paragraphText = paragraph.getText();
        if (paragraphText == null || paragraphText.trim().isEmpty()) {
          htmlContent.append("<br>");
        } else {
          // Basic formatting preservation
          String styledText = convertParagraphToHtml(paragraph);
          htmlContent.append("<p>").append(styledText).append("</p>");
        }
      }

      htmlContent.append("</div>");

      // Extract template variables
      List<TemplateVariable> variables = extractTemplateVariables(htmlContent.toString());

      return EditorDocument.builder()
          .name(FilenameUtils.getBaseName(file.getOriginalFilename()))
          .htmlContent(htmlContent.toString())
          .templateVariables(variables)
          .originalFormat("docx")
          .build();
    }
  }

  /** Convert XWPFParagraph to HTML with basic formatting */
  private String convertParagraphToHtml(XWPFParagraph paragraph) {
    String text = paragraph.getText();
    if (text == null || text.isEmpty()) {
      return "";
    }

    // Escape HTML characters
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  /**
   * Import ODT file using ODF Toolkit. The text is read paragraph by paragraph: {@code
   * getTextContent()} of the {@code office:text} root returns an empty string in odfdom 0.13, which
   * once made every ODT import an empty template.
   */
  private EditorDocument importOdt(MultipartFile file, byte[] archive) throws Exception {
    checkXmlDepth(archive);
    try (InputStream inputStream = new ByteArrayInputStream(archive);
        OdfTextDocument odtDocument = OdfTextDocument.loadDocument(inputStream)) {
      String htmlContent = odtBodyToHtml(odtDocument.getContentRoot());

      return EditorDocument.builder()
          .name(FilenameUtils.getBaseName(file.getOriginalFilename()))
          .htmlContent(htmlContent)
          .templateVariables(extractTemplateVariables(htmlContent))
          .originalFormat("odt")
          .build();
    }
  }

  /**
   * HTML of an ODT body ({@code office:text}): paragraphs and headings (also inside lists, tables
   * and sections) in document order, comments and footnotes left out. Elements are recognised by
   * namespace URI and local name, never by prefix: prefixes are free in XML, and odfdom even keeps
   * the first prefix it saw for a namespace process-wide, so names like {@code x:p} do occur.
   *
   * @param officeText the {@code office:text} element (any namespace-aware DOM)
   * @return {@code <div>…</div>}
   * @throws IllegalArgumentException if the elements nest deeper than {@value #MAX_ODT_DEPTH}
   */
  static String odtBodyToHtml(Node officeText) {
    StringBuilder html = new StringBuilder("<div>");
    appendOdtBlocks(officeText, html, 0);
    return html.append("</div>").toString();
  }

  /** Appends every paragraph and heading below {@code node} as HTML, in document order. */
  private static void appendOdtBlocks(Node node, StringBuilder html, int depth) {
    checkDepth(depth);
    for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child.getNodeType() != Node.ELEMENT_NODE || isOdtSideContent(child)) {
        continue;
      }
      if (isText(child, "p")) {
        appendOdtParagraph(html, "p", child, depth + 1);
      } else if (isText(child, "h")) {
        appendOdtParagraph(html, "h" + headingLevel(child), child, depth + 1);
      } else {
        appendOdtBlocks(child, html, depth + 1);
      }
    }
  }

  private static void appendOdtParagraph(
      StringBuilder html, String tag, Node paragraph, int depth) {
    StringBuilder text = new StringBuilder();
    appendOdtText(paragraph, text, depth);
    if (text.toString().isBlank()) {
      html.append("<br>");
    } else {
      html.append('<').append(tag).append('>').append(text).append("</").append(tag).append('>');
    }
  }

  /** The escaped text of a paragraph, with ODF spaces, tabs and line breaks resolved. */
  private static void appendOdtText(Node node, StringBuilder text, int depth) {
    checkDepth(depth);
    for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child.getNodeType() == Node.TEXT_NODE) {
        text.append(escapeHtml(child.getNodeValue()));
      } else if (child.getNodeType() == Node.ELEMENT_NODE && !isOdtSideContent(child)) {
        if (isText(child, "s")) {
          text.append(" ".repeat(spaceCount(child)));
        } else if (isText(child, "tab")) {
          text.append('\t');
        } else if (isText(child, "line-break")) {
          text.append("<br>");
        } else {
          appendOdtText(child, text, depth + 1);
        }
      }
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

  private static int headingLevel(Node heading) {
    String level = ((Element) heading).getAttributeNS(ODF_TEXT_NS, "outline-level");
    try {
      return level.isEmpty() ? 1 : Math.min(Math.max(Integer.parseInt(level), 1), 6);
    } catch (NumberFormatException e) {
      return 1;
    }
  }

  private static String escapeHtml(String text) {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  /** Extract template variables from content */
  public List<TemplateVariable> extractTemplateVariables(String content) {
    List<TemplateVariable> variables = new ArrayList<>();
    Pattern pattern = Pattern.compile("\\{\\{([^}]+)\\}\\}");
    Matcher matcher = pattern.matcher(content);

    while (matcher.find()) {
      String variableName = matcher.group(1).trim();
      variables.add(
          TemplateVariable.builder().name(variableName).placeholder(matcher.group()).build());
    }

    return variables;
  }

  /** Process template variables in content */
  private String processTemplateVariables(String content) {
    // Ensure template variables are properly formatted
    Pattern pattern = Pattern.compile("\\{\\{([^}]+)\\}\\}");
    Matcher matcher = pattern.matcher(content);

    StringBuffer result = new StringBuffer();
    while (matcher.find()) {
      String variableName = matcher.group(1).trim();
      matcher.appendReplacement(result, "{{" + variableName + "}}");
    }
    matcher.appendTail(result);

    return result.toString();
  }

  /**
   * Splits editor HTML into the plain texts of its blocks ({@code p}, {@code div}, {@code
   * h1}–{@code h6}, {@code li}, {@code br}), for the exports. Empty blocks are dropped.
   *
   * @param html the template HTML
   * @return one entry per non-empty block, in order
   */
  static List<String> htmlToParagraphs(String html) {
    if (html == null) {
      return List.of();
    }
    String withBreaks = html.replaceAll("(?i)</(p|div|h[1-6]|li)\\s*>|<br\\s*/?>", "\n");
    List<String> paragraphs = new ArrayList<>();
    for (String block : withBreaks.split("\n")) {
      String text = convertHtmlToPlainText(block);
      if (!text.isBlank()) {
        paragraphs.add(text);
      }
    }
    return paragraphs;
  }

  /** Convert HTML to plain text (basic implementation) */
  private static String convertHtmlToPlainText(String html) {
    // Simple HTML to text conversion
    return html.replaceAll("<[^>]*>", "")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
        .trim();
  }

  /** Editor document data class */
  public static class EditorDocument {
    private String name;
    private String htmlContent;
    private List<TemplateVariable> templateVariables;
    private String originalFormat;

    private EditorDocument() {}

    public static Builder builder() {
      return new Builder();
    }

    // Getters
    public String getName() {
      return name;
    }

    public String getHtmlContent() {
      return htmlContent;
    }

    public List<TemplateVariable> getTemplateVariables() {
      return templateVariables;
    }

    public String getOriginalFormat() {
      return originalFormat;
    }

    public static class Builder {
      private EditorDocument document = new EditorDocument();

      public Builder name(String name) {
        document.name = name;
        return this;
      }

      public Builder htmlContent(String htmlContent) {
        document.htmlContent = htmlContent;
        return this;
      }

      public Builder templateVariables(List<TemplateVariable> templateVariables) {
        document.templateVariables = templateVariables;
        return this;
      }

      public Builder originalFormat(String originalFormat) {
        document.originalFormat = originalFormat;
        return this;
      }

      public EditorDocument build() {
        return document;
      }
    }
  }

  /** Template variable data class */
  public static class TemplateVariable {
    private String name;
    private String placeholder;

    private TemplateVariable() {}

    public static Builder builder() {
      return new Builder();
    }

    // Getters
    public String getName() {
      return name;
    }

    public String getPlaceholder() {
      return placeholder;
    }

    public static class Builder {
      private TemplateVariable variable = new TemplateVariable();

      public Builder name(String name) {
        variable.name = name;
        return this;
      }

      public Builder placeholder(String placeholder) {
        variable.placeholder = placeholder;
        return this;
      }

      public TemplateVariable build() {
        return variable;
      }
    }
  }
}
