package de.billery.pdf;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.billery.data.*;
import de.billery.document.DocumentStrategyRegistry;
import de.billery.document.DocumentType;
import de.billery.repository.*;
import de.billery.service.DocumentNotFoundException;
import de.billery.storage.FileStorage;
import de.billery.tax.TaxTreatment;
import de.billery.tax.VatBreakdown;
import de.billery.tax.VatCalculator;
import de.billery.xmlgenerator.XmlChars;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.xhtmlrenderer.pdf.ITextOutputDevice;
import org.xhtmlrenderer.pdf.ITextRenderer;
import org.xhtmlrenderer.pdf.PdfAConformance;
import org.xml.sax.InputSource;

@Component
public class TemplateBasedPdfGenerator {

  private static final Logger LOGGER = Logger.getLogger(TemplateBasedPdfGenerator.class.getName());

  private static final DateTimeFormatter DATE_FORMATTER =
      DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMAN);

  /** Largest logo box in the PDF, in CSS pixels. */
  private static final int LOGO_MAX_WIDTH = 200;

  private static final int LOGO_MAX_HEIGHT = 80;

  /** Cell font of the line-item table when the block has no format settings. */
  private static final String DEFAULT_CELL_FONT = "font-size: 10.5pt; color: #1f2937;";

  /** Hex colors only: config values end up inside {@code style} attributes. */
  private static final java.util.regex.Pattern HEX_COLOR =
      java.util.regex.Pattern.compile("^#[0-9a-fA-F]{3,8}$");

  /** Font family names (letters, digits, spaces, commas, quotes, hyphens). */
  private static final java.util.regex.Pattern FONT_FAMILY =
      java.util.regex.Pattern.compile("^[A-Za-z0-9 ,'-]{1,60}$");

  /** A placeholder with spaces inside the braces, e.g. {@code {{ firma }}}. */
  private static final java.util.regex.Pattern VARIABLE_WITH_SPACES =
      java.util.regex.Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*\\}\\}");

  /** Placeholders left after substitution: unknown or misspelled variable names. */
  private static final java.util.regex.Pattern UNRESOLVED_VARIABLE =
      java.util.regex.Pattern.compile("\\{\\{[^{}]*\\}\\}");

  private final CustomerRepository customerRepository;
  private final OwnerRepository ownerRepository;
  private final InvoiceRepository invoiceRepository;
  private final InvoiceTemplateRepository templateRepository;
  private final FileStorage files;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final DocumentStrategyRegistry strategies = new DocumentStrategyRegistry();

  /**
   * Creates the generator.
   *
   * @param customerRepository reads the document's customer
   * @param ownerRepository reads the company data for the letterhead
   * @param invoiceRepository reads the document and its line items
   * @param templateRepository resolves the block template (type default or per-document override)
   * @param files resolves the logos a template refers to (only files of the active tenant)
   */
  public TemplateBasedPdfGenerator(
      CustomerRepository customerRepository,
      OwnerRepository ownerRepository,
      InvoiceRepository invoiceRepository,
      InvoiceTemplateRepository templateRepository,
      FileStorage files) {
    this.files = files;
    this.customerRepository = customerRepository;
    this.ownerRepository = ownerRepository;
    this.invoiceRepository = invoiceRepository;
    this.templateRepository = templateRepository;
  }

  /**
   * Renders the invoice to PDF with its resolved block template.
   *
   * @param invoice the document to render
   * @return the PDF bytes
   * @throws IOException if HTML generation or PDF conversion fails
   * @throws MissingConfigurationException if no owner or no usable template is configured
   */
  public byte[] generatePdfInvoice(Invoice invoice) throws IOException {
    String htmlContent = generateInvoiceHtml(invoice);
    try {
      return convertHtmlToPdf(htmlContent);
    } catch (Exception e) {
      throw new IOException("Failed to convert invoice " + invoice.getId() + " to PDF", e);
    }
  }

  /**
   * Renders the invoice to the XHTML that {@link #convertHtmlToPdf} turns into a PDF. All
   * user-entered and template text is XML-escaped, so the output is always well-formed.
   *
   * @param invoice the document to render
   * @return the XHTML document
   * @throws IOException if the template blocks cannot be rendered
   * @throws MissingConfigurationException if no owner or no usable template is configured
   */
  public String generateInvoiceHtml(Invoice storedInvoice) throws IOException {
    Customer customer = customerRepository.findById(storedInvoice.getCustomerId());
    Owner owner = getOwner();
    List<InvoiceItem> items = invoiceRepository.findItemsByInvoiceId(storedInvoice.getId());
    InvoiceTemplate template = loadTemplateForInvoice(storedInvoice);
    Invoice invoice = storedInvoice;
    if (storedInvoice.getDocumentTypeId() == DocumentType.CREDIT_NOTE) {
      // A Stornorechnung stores the cancelled amounts as positive values (the document type carries
      // the sign); on paper they are shown negative. The items list is a fresh, render-local copy.
      invoice = negatedCopy(storedInvoice);
      negateItems(items);
    }
    LOGGER.fine(
        () ->
            "Rendering invoice "
                + storedInvoice.getId()
                + " with template "
                + template.getId()
                + (Integer.valueOf(template.getId()).equals(storedInvoice.getTemplateId())
                    ? " (override)"
                    : ""));
    try {
      return generateHtmlFromBlocks(template, invoice, customer, owner, items);
    } catch (RuntimeException e) {
      throw new IOException("Failed to render invoice " + storedInvoice.getId(), e);
    }
  }

  /**
   * Renders an unsaved template draft to PDF through the same pipeline as real documents, for the
   * template editor's "exact preview". Nothing is stored.
   *
   * @param draft the editor's template (blocks, CSS, margins)
   * @param invoiceId the document to render with, or {@code null} for a built-in sample document
   * @return the PDF bytes
   * @throws IOException if HTML generation or PDF conversion fails
   * @throws MissingConfigurationException if the draft has no blocks or no owner is configured
   * @throws DocumentNotFoundException if {@code invoiceId} does not exist
   */
  public byte[] generateTemplatePreviewPdf(InvoiceTemplate draft, Integer invoiceId)
      throws IOException {
    String html = generateTemplatePreviewHtml(draft, invoiceId);
    try {
      return convertHtmlToPdf(html);
    } catch (Exception e) {
      throw new IOException("Failed to convert the template preview to PDF", e);
    }
  }

  /**
   * XHTML of {@link #generateTemplatePreviewPdf}.
   *
   * @param draft the editor's template
   * @param invoiceId the document to render with, or {@code null} for the sample document
   * @return the XHTML document
   */
  public String generateTemplatePreviewHtml(InvoiceTemplate draft, Integer invoiceId) {
    if (!hasValidBlocks(draft)) {
      throw new MissingConfigurationException("Die Vorlage enthält keine Blöcke.");
    }
    Invoice invoice;
    Customer customer;
    List<InvoiceItem> items;
    if (invoiceId != null) {
      Invoice stored = invoiceRepository.findById(invoiceId);
      if (stored == null) {
        throw new DocumentNotFoundException(invoiceId);
      }
      customer = customerRepository.findById(stored.getCustomerId());
      items = invoiceRepository.findItemsByInvoiceId(invoiceId);
      invoice = stored;
      if (stored.getDocumentTypeId() == DocumentType.CREDIT_NOTE) {
        invoice = negatedCopy(stored);
        negateItems(items);
      }
    } else {
      items = sampleItems();
      invoice = sampleInvoice(draft.getDocumentTypeId(), items);
      customer =
          new Customer(
              "Anna", "Müller", "54321", "Beispielstadt", "Beispielweg", 5, "", "", "", "");
    }
    Owner owner = getOwner();
    return generateHtmlFromBlocks(draft, invoice, customer, owner, items);
  }

  /** Sample line items of the template preview: two VAT rates, so the VAT column shows. */
  private static List<InvoiceItem> sampleItems() {
    List<InvoiceItem> items = new ArrayList<>();
    items.add(sampleItem("Beratung und Planung", "5", "Std.", "100.00", "19"));
    items.add(sampleItem("Fachbuch", "1", "Stk.", "50.00", "7"));
    return items;
  }

  private static InvoiceItem sampleItem(
      String name, String quantity, String unit, String unitPrice, String ratePercent) {
    InvoiceItem item = new InvoiceItem();
    item.setItemType(InvoiceItem.ItemType.SERVICE.getValue());
    item.setItemName(name);
    item.setQuantity(new BigDecimal(quantity));
    item.setUnit(unit);
    item.setUnitPrice(new BigDecimal(unitPrice));
    item.setTotalPrice(new BigDecimal(quantity).multiply(new BigDecimal(unitPrice)));
    item.setTaxRatePercent(new BigDecimal(ratePercent));
    return item;
  }

  /** Sample document of the template preview, dated today, net = sum of the sample items. */
  private static Invoice sampleInvoice(int documentTypeId, List<InvoiceItem> items) {
    Invoice invoice = new Invoice();
    java.time.LocalDate today = java.time.LocalDate.now();
    invoice.setDocumentTypeId(documentTypeId);
    invoice.setDocumentNumber("MUSTER-0001");
    invoice.setInvoiceDate(today.toString());
    invoice.setDueDate(today.plusDays(14).toString());
    invoice.setWorkStartDate(today.toString());
    invoice.setNotes("Dies ist eine Vorschau mit Beispieldaten.");
    invoice.setTotalPrice(
        items.stream().map(InvoiceItem::getTotalPrice).reduce(BigDecimal.ZERO, BigDecimal::add));
    return invoice;
  }

  /**
   * Returns a copy of a credit note whose net amount is negated, so net, tax and gross render (and
   * substitute into variables) as negative values. Never persisted.
   */
  private static Invoice negatedCopy(Invoice source) {
    Invoice copy =
        new Invoice(
            source.getId(),
            source.getCustomerId(),
            source.getInvoiceDate(),
            source.getDueDate(),
            source.getTotalPrice().negate(),
            source.getTaxRate(),
            source.getPaidDate(),
            source.getNotes(),
            source.getDocumentNumber(),
            source.getVersion(),
            source.getIsLatest(),
            source.getDocumentTypeId(),
            source.getDocumentStatusId(),
            source.getVersionGroupId(),
            source.getWorkStartDate(),
            source.getWorkEndDate(),
            source.getParentInvoiceId(),
            source.getPeriodId(),
            source.getConvertedToInvoiceId(),
            source.getOfferValidUntil(),
            source.getCostCalculationBasis(),
            source.getTermsAndConditions(),
            source.getArchived());
    copy.setTemplateId(source.getTemplateId());
    copy.setCancelsInvoiceId(source.getCancelsInvoiceId());
    return copy;
  }

  /** Negates unit and line prices of a render-local item tree; quantities stay positive. */
  private static void negateItems(List<InvoiceItem> items) {
    if (items == null) {
      return;
    }
    for (InvoiceItem item : items) {
      BigDecimal total = item.getTotalPrice();
      item.setUnitPrice(item.getUnitPrice().negate());
      item.setTotalPrice(total.negate()); // BigDecimal has no negative zero
      negateItems(item.getChildren());
    }
  }

  /**
   * Resolves the template to render an invoice with. Honours the per-document override stored on
   * {@code invoice.templateId}; falls back to the document-type default picker when the override is
   * absent, missing, or has no usable block JSON.
   */
  InvoiceTemplate loadTemplateForInvoice(Invoice invoice) {
    if (invoice.getDocumentTypeId() == DocumentType.CREDIT_NOTE) {
      // A Stornorechnung looks like the invoice it cancels: same override or type default.
      Invoice cancelled = invoiceRepository.findById(invoice.getCancelsInvoiceId());
      if (cancelled != null) {
        return loadTemplateForInvoice(cancelled);
      }
    }
    Integer templateId = invoice.getTemplateId();
    if (templateId != null) {
      InvoiceTemplate override = templateRepository.findById(templateId);
      if (override != null && hasValidBlocks(override)) {
        return override;
      }
      if (LOGGER.isLoggable(Level.WARNING)) {
        LOGGER.warning(
            "Invoice "
                + invoice.getId()
                + " references template "
                + templateId
                + " which is missing or has no blocks; falling back to type default");
      }
    }
    return getTemplateForDocumentType(invoice.getDocumentTypeId());
  }

  private InvoiceTemplate getTemplateForDocumentType(Integer documentTypeId) {
    List<InvoiceTemplate> templates = templateRepository.findByDocumentTypeId(documentTypeId);

    for (InvoiceTemplate template : templates) {
      if (template.isDefault() && hasValidBlocks(template)) {
        return template;
      }
    }

    for (InvoiceTemplate template : templates) {
      if (hasValidBlocks(template)) {
        return template;
      }
    }

    if (!templates.isEmpty() && hasValidBlocks(templates.get(0))) {
      return templates.get(0);
    }

    throw new MissingConfigurationException(
        "Für diesen Dokumenttyp ist keine Vorlage vorhanden (Typ " + documentTypeId + ").");
  }

  private boolean hasValidBlocks(InvoiceTemplate template) {
    return template.getBlocks() != null
        && !template.getBlocks().isEmpty()
        && !"[]".equals(template.getBlocks())
        && !"null".equals(template.getBlocks());
  }

  /**
   * Renders a template with the given document data to XHTML (no database access except the
   * cancelled invoice of a credit note). Package-private for the renderer parity test.
   *
   * @param template the block template
   * @param invoice the document (credit notes already negated)
   * @param customer the buyer
   * @param owner the seller
   * @param items the line-item tree
   * @return the XHTML document
   */
  String generateHtmlFromBlocks(
      InvoiceTemplate template,
      Invoice invoice,
      Customer customer,
      Owner owner,
      List<InvoiceItem> items) {

    String cssContent = template.getCssContent() != null ? template.getCssContent() : "";
    String blocksJson = template.getBlocks() != null ? template.getBlocks() : "[]";

    LOGGER.fine("PDF Generator: Parsing blocks, JSON length: " + blocksJson.length());
    List<Map<String, Object>> blocks = parseBlocks(blocksJson);
    LOGGER.fine("PDF Generator: Parsed " + blocks.size() + " blocks");
    blocks.sort(Comparator.comparingInt(b -> ((Number) b.getOrDefault("order", 0)).intValue()));
    migrateInvoiceTitle(blocks);
    if (invoice.getDocumentTypeId() == DocumentType.CREDIT_NOTE) {
      overrideInvoiceTitle(
          blocks, strategies.getStrategy(DocumentType.CREDIT_NOTE).getDisplayName());
    }

    // One VAT computation per render; the items are already negated for a credit note.
    VatBreakdown vat =
        VatCalculator.calculate(items, TaxTreatment.parse(invoice.getTaxTreatment()));
    invoice.setComputedTaxAmount(vat.tax());

    List<String> rendered = new ArrayList<>();
    List<Boolean> halfWidth = new ArrayList<>();
    for (Map<String, Object> block : blocks) {
      String blockType = (String) block.get("type");
      Map<String, Object> blockData = (Map<String, Object>) block.get("data");
      Map<String, Object> blockConfig = (Map<String, Object>) block.get("config");

      rendered.add(
          renderBlock(blockType, blockData, blockConfig, invoice, customer, owner, items, vat));
      halfWidth.add("half".equals(getStringValue(blockConfig, "width", "full")));
    }
    String bodyContent = layoutBlocks(rendered, halfWidth);

    StringBuilder html = new StringBuilder();
    html.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    html.append(
        "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">\n");
    html.append("<html xmlns=\"http://www.w3.org/1999/xhtml\">\n");
    html.append("<head>\n");
    html.append("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\"/>\n");
    html.append("<title>")
        .append(escapeHtml(invoice.getFormattedInvoiceNumber()))
        .append("</title>\n");

    html.append("<style type=\"text/css\">\n");
    html.append(getBaseStyles());
    html.append(cssForStyleElement(cssContent));
    html.append("</style>\n");

    html.append("</head>\n<body>\n");
    html.append("<div class=\"invoice-box\" style=\"padding: ")
        .append(template.getMarginTopMm())
        .append("mm ")
        .append(template.getMarginRightMm())
        .append("mm ")
        .append(template.getMarginBottomMm())
        .append("mm ")
        .append(template.getMarginLeftMm())
        .append("mm;\">\n");
    html.append(bodyContent);
    html.append("</div>\n");
    html.append("</body>\n</html>");

    String htmlString = html.toString();
    htmlString = fixSelfClosingTags(htmlString);
    LOGGER.fine("PDF Generator: Generated HTML, length: " + htmlString.length());

    return htmlString;
  }

  /**
   * Replaces the title of the invoice-meta block. A credit note borrows the cancelled invoice's
   * template, whose title says "Rechnung"; it must say "Stornorechnung".
   */
  private static void overrideInvoiceTitle(List<Map<String, Object>> blocks, String title) {
    for (Map<String, Object> block : blocks) {
      if ("invoice-meta".equals(block.get("type")) && block.get("data") instanceof Map<?, ?>) {
        ((Map<String, Object>) block.get("data")).put("invoiceTitle", title);
      }
    }
  }

  private void migrateInvoiceTitle(List<Map<String, Object>> blocks) {
    Map<String, Object> header = null;
    Map<String, Object> meta = null;
    for (Map<String, Object> block : blocks) {
      String type = (String) block.get("type");
      if ("header".equals(type)) {
        header = block;
      } else if ("invoice-meta".equals(type)) {
        meta = block;
      }
    }
    if (header != null && meta != null) {
      Map<String, Object> headerData = (Map<String, Object>) header.get("data");
      Map<String, Object> metaData = (Map<String, Object>) meta.get("data");
      if (headerData != null && metaData != null) {
        if (headerData.containsKey("invoiceTitle") && !metaData.containsKey("invoiceTitle")) {
          metaData.put("invoiceTitle", headerData.get("invoiceTitle"));
          headerData.remove("invoiceTitle");
        }
      }
      Map<String, Object> headerConfig = (Map<String, Object>) header.get("config");
      if (headerConfig != null) {
        headerConfig.remove("layout");
      }
    }
  }

  private String fixSelfClosingTags(String html) {
    if (html == null) {
      return html;
    }
    return html.replaceAll("<br>", "<br/>")
        .replaceAll("<br/>", "<br/>")
        .replaceAll("<hr>", "<hr/>")
        .replaceAll("<img([^>]*?)/?>", "<img$1/>");
  }

  private List<Map<String, Object>> parseBlocks(String blocksJson) {
    if (blocksJson == null || blocksJson.isEmpty()) {
      return new ArrayList<>();
    }

    try {
      return objectMapper.readValue(blocksJson, new TypeReference<>() {});
    } catch (Exception e) {
      LOGGER.warning("Error parsing blocks JSON: " + e.getMessage());
      return new ArrayList<>();
    }
  }

  private String renderBlock(
      String blockType,
      Map<String, Object> blockData,
      Map<String, Object> blockConfig,
      Invoice invoice,
      Customer customer,
      Owner owner,
      List<InvoiceItem> items,
      VatBreakdown vat) {

    Map<String, Object> localBlockData = blockData != null ? blockData : new HashMap<>();
    Map<String, Object> localBlockConfig = blockConfig != null ? blockConfig : new HashMap<>();

    // Every variable resolves in every block: users should not need to know which block has
    // which data.
    VariableContext vars = new VariableContext(owner, customer, invoice);

    return switch (blockType) {
      case "header" -> renderHeader(localBlockData, localBlockConfig, vars);
      case "address" -> renderAddress(localBlockData, localBlockConfig, vars);
      case "invoice-meta" -> renderInvoiceMeta(localBlockData, localBlockConfig, vars);
      case "line-items" ->
          renderLineItems(localBlockData, localBlockConfig, items, vat.groups().size() > 1);
      case "totals" -> renderTotals(localBlockData, localBlockConfig, invoice, vat);
      case "notes" -> renderNotesBlock(localBlockData, localBlockConfig, vars);
      case "payment" -> renderPayment(localBlockData, localBlockConfig, vars);
      case "footer" -> renderFooter(localBlockData, localBlockConfig, vars);
      default -> "";
    };
  }

  private String renderHeader(
      Map<String, Object> data, Map<String, Object> config, VariableContext vars) {
    String companyName = getStringValue(data, "companyName", "");
    String companySlogan = getStringValue(data, "companySlogan", "");
    String logoUrl = getStringValue(data, "logoUrl", "");
    Boolean showSlogan = getBooleanValue(config, "showSlogan", true);
    String logoAlignment = getStringValue(config, "logoAlignment", "left");
    double fs = getFormatFontSize(config, 21);
    String fc = getFormatFontColor(config, "#2563eb");
    String ff = getFormatFontFamily(config, "Inter");

    companyName = substituteVariables(companyName, vars);
    companySlogan = substituteVariables(companySlogan, vars);

    String imgTag = "";
    if (logoUrl != null && !logoUrl.isEmpty()) {
      imgTag =
          "<img src=\""
              + escapeHtml(logoUrl)
              + "\" alt=\"Logo\" style=\""
              + logoSizeStyle(logoUrl)
              + "\"/>";
    }

    String h1Style =
        "margin: 0; font-size: "
            + formatPt(fs)
            + "; color: "
            + fc
            + "; font-family: "
            + ff
            + ", "
            + genericFontFamily(ff)
            + ";";
    String sloganPt = formatPt(Math.max(6, fs - 6));

    StringBuilder html = new StringBuilder();
    html.append("<div class=\"header\" style=\"margin-bottom: 40px;\">");

    if ("top".equals(logoAlignment)) {
      if (!imgTag.isEmpty()) {
        html.append("<div style=\"text-align: center; margin-bottom: 10px;\">")
            .append(imgTag)
            .append("</div>");
      }
      html.append("<div class=\"brand\">");
    } else if ("right".equals(logoAlignment)) {
      html.append("<table style=\"width: 100%; border-collapse: collapse; margin: 0;\"><tr>");
      html.append(
          "<td style=\"vertical-align: middle; padding: 0; border: none;\"><div class=\"brand\">");
    } else {
      html.append("<table style=\"width: 100%; border-collapse: collapse; margin: 0;\"><tr>");
      if (!imgTag.isEmpty()) {
        html.append(
                "<td style=\"width: 1%; vertical-align: middle; padding: 0 20px 0 0; border: none;\">")
            .append(imgTag)
            .append("</td>");
      }
      html.append(
          "<td style=\"vertical-align: middle; padding: 0; border: none;\"><div class=\"brand\">");
    }

    html.append("<h1 style=\"").append(h1Style).append("\">").append(companyName).append("</h1>");
    if (showSlogan && companySlogan != null && !companySlogan.isEmpty()) {
      html.append("<p style=\"margin: 5px 0 0 0; color: #6b7280; font-size: ")
          .append(sloganPt)
          .append(";\">")
          .append(companySlogan)
          .append("</p>");
    }
    html.append("</div>");

    if ("right".equals(logoAlignment)) {
      html.append("</td>");
      html.append(
              "<td style=\"width: 1%; text-align: right; vertical-align: middle; padding: 0; border: none;\">")
          .append(imgTag)
          .append("</td>");
      html.append("</tr></table>");
    } else if (!"top".equals(logoAlignment)) {
      html.append("</td></tr></table>");
    }

    html.append("</div>");
    return html.toString();
  }

  private String renderAddress(
      Map<String, Object> data, Map<String, Object> config, VariableContext vars) {
    String role = getStringValue(data, "role", "sender");
    Boolean showLabels = getBooleanValue(config, "showLabels", true);
    String label =
        escapeHtml(
            getStringValue(config, "label", "sender".equals(role) ? "Absender" : "Empfänger"));

    String name = getStringValue(data, "name", "");
    String address = getStringValue(data, "address", "");
    String email = getStringValue(data, "email", "");
    String phone = getStringValue(data, "phone", "");
    String vatId = getStringValue(data, "vatId", "");

    name = substituteVariables(name, vars);
    address = substituteVariables(address, vars);
    email = substituteVariables(email, vars);
    phone = substituteVariables(phone, vars);
    vatId = substituteVariables(vatId, vars);

    double fs = getFormatFontSize(config, 10.5);
    String bodyStyle = buildBodyStyle(config, 10.5, "#1f2937");
    String labelPt = formatPt(Math.max(6, fs - 2));

    StringBuilder html = new StringBuilder();
    html.append("<div class=\"address-block\" style=\"margin-bottom: 30px;\">");

    if (showLabels) {
      html.append("<span style=\"font-size: ")
          .append(labelPt)
          .append(
              "; font-weight: 700; text-transform: uppercase; color: #6b7280; display: block; margin-bottom: 8px;\">")
          .append(label)
          .append("</span>");
    }

    html.append("<div style=\"").append(bodyStyle).append("; line-height: 1.6;\">");

    if (name != null && !name.isEmpty()) {
      html.append("<strong>").append(name).append("</strong><br/>");
    }
    if (address != null && !address.isEmpty()) {
      html.append(address.replace("\n", "<br/>")).append("<br/>");
    }
    if (email != null && !email.isEmpty()) {
      html.append(email).append("<br/>");
    }
    if (phone != null && !phone.isEmpty()) {
      html.append(phone).append("<br/>");
    }
    if (vatId != null && !vatId.isEmpty()) {
      html.append("UST-ID: ").append(vatId);
    }

    html.append("</div>");
    html.append("</div>");

    return html.toString();
  }

  private String renderInvoiceMeta(
      Map<String, Object> data, Map<String, Object> config, VariableContext vars) {
    Invoice invoice = vars.invoice();
    String invoiceTitle = getStringValue(data, "invoiceTitle", "");
    String invoiceId = getStringValue(data, "invoiceId", "");
    String invoiceDate = getStringValue(data, "invoiceDate", "");
    String dueDate = getStringValue(data, "dueDate", "");
    Boolean showOrderNumber = getBooleanValue(config, "showOrderNumber", true);
    String orderNumber = getStringValue(data, "orderNumber", "");
    String leistungsdatum = getStringValue(data, "leistungsdatum", "");
    String leistungszeitraum = getStringValue(data, "leistungszeitraum", "");
    String alignment = getStringValue(config, "alignment", "right");
    String textAlign =
        "left".equals(alignment) ? "left" : "center".equals(alignment) ? "center" : "right";
    double bodyFs = metaBodyFontSize(config);
    double fs = bodyFs * 12 / 7; // title: 18pt at the default 10.5pt body
    String fc = getFormatFontColor(config, "#1f2937");
    String bodyStyle = buildBodyStyle(config, bodyFs, 10.5, "#1f2937");

    invoiceTitle = substituteVariables(invoiceTitle, vars);
    invoiceId = substituteVariables(invoiceId, vars);
    invoiceDate = substituteVariables(invoiceDate, vars);
    dueDate = substituteVariables(dueDate, vars);
    orderNumber = substituteVariables(orderNumber, vars);
    leistungsdatum = substituteVariables(leistungsdatum, vars);
    leistungszeitraum = substituteVariables(leistungszeitraum, vars);

    if (invoiceId.isEmpty()) {
      invoiceId = escapeHtml(invoice.getFormattedInvoiceNumber());
    }
    if (invoiceDate.isEmpty()) {
      invoiceDate = formatDate(invoice.getInvoiceDate());
    }
    if (dueDate.isEmpty()) {
      dueDate = formatDate(invoice.getDueDate());
    }
    if (leistungsdatum.isEmpty()) {
      leistungsdatum = formatDate(invoice.getWorkStartDate());
    }
    if (leistungszeitraum.isEmpty()) {
      leistungszeitraum = formatPeriod(invoice.getWorkStartDate(), invoice.getWorkEndDate());
    }

    StringBuilder html = new StringBuilder();
    html.append("<div class=\"invoice-meta-section\" style=\"margin-bottom: 30px;\">");

    if (invoiceTitle != null && !invoiceTitle.isEmpty()) {
      html.append("<h2 style=\"margin: 0 0 16px 0; font-size: ")
          .append(formatPt(fs))
          .append("; font-weight: 600; color: ")
          .append(fc)
          .append("; text-align: ")
          .append(textAlign)
          .append(";\">")
          .append(invoiceTitle)
          .append("</h2>");
    }

    html.append("<div style=\"")
        .append(bodyStyle)
        .append("; text-align: ")
        .append(textAlign)
        .append(";\">");
    html.append("<div style=\"margin-bottom: 8px;\"><strong>")
        .append(strategies.getStrategy(invoice.getDocumentTypeId()).getNumberLabel())
        .append(":</strong> ")
        .append(invoiceId)
        .append("</div>");
    html.append("<div style=\"margin-bottom: 8px;\"><strong>Datum:</strong> ")
        .append(invoiceDate)
        .append("</div>");
    if (invoice.getCancelsInvoiceId() > 0) {
      // §14 UStG: the Stornorechnung must name the invoice it cancels.
      Invoice cancelled = invoiceRepository.findById(invoice.getCancelsInvoiceId());
      if (cancelled != null) {
        html.append("<div style=\"margin-bottom: 8px;\"><strong>Storno zu Rechnung:</strong> ")
            .append(escapeHtml(cancelled.getFormattedInvoiceNumber()))
            .append(" vom ")
            .append(formatDate(cancelled.getInvoiceDate()))
            .append("</div>");
      }
    } else {
      html.append("<div style=\"margin-bottom: 8px;\"><strong>Fällig am:</strong> ")
          .append(dueDate)
          .append("</div>");
    }
    if (showOrderNumber && orderNumber != null && !orderNumber.isEmpty()) {
      html.append("<div><strong>Bestellnummer:</strong> ").append(orderNumber).append("</div>");
    }
    if (leistungsdatum != null
        && !leistungsdatum.isEmpty()
        && (leistungszeitraum == null || leistungszeitraum.isEmpty())) {
      html.append("<div style=\"margin-bottom: 8px;\"><strong>Leistungsdatum:</strong> ")
          .append(leistungsdatum)
          .append("</div>");
    }
    if (leistungszeitraum != null && !leistungszeitraum.isEmpty()) {
      html.append("<div style=\"margin-bottom: 8px;\"><strong>Leistungszeitraum:</strong> ")
          .append(leistungszeitraum)
          .append("</div>");
    }
    html.append("</div>");
    html.append("</div>");

    return html.toString();
  }

  /**
   * Renders the line-item table.
   *
   * @param mixedRates the document has lines with different VAT rates; the rate of each line must
   *     then be identifiable (§14 Abs. 4 Nr. 8 UStG), so a "USt." column is added when the template
   *     does not show one
   */
  private String renderLineItems(
      Map<String, Object> data,
      Map<String, Object> config,
      List<InvoiceItem> items,
      boolean mixedRates) {
    String currency = (String) config.getOrDefault("currency", "EUR");
    double fs = getFormatFontSize(config, 10.5);
    String fc = getFormatFontColor(config, "#1f2937");
    String tfs = formatPt(fs);

    List<Map<String, Object>> columns =
        (List<Map<String, Object>>)
            data.getOrDefault(
                "columns",
                Arrays.asList(
                    Map.of(
                        "key",
                        "description",
                        "label",
                        "Beschreibung",
                        "show",
                        true,
                        "width",
                        "50%"),
                    Map.of("key", "quantity", "label", "Menge", "show", true, "width", "15%"),
                    Map.of("key", "unit", "label", "Einheit", "show", true, "width", "10%"),
                    Map.of("key", "rate", "label", "Preis", "show", true, "width", "12%"),
                    Map.of("key", "amount", "label", "Gesamt", "show", true, "width", "13%")));

    List<Map<String, Object>> visibleColumns =
        columns.stream()
            .filter(col -> col.get("show") == null || (Boolean) col.get("show"))
            .collect(Collectors.toList());
    if (mixedRates && indexOfColumn(visibleColumns, "taxRate") < 0) {
      int amountIndex = indexOfColumn(visibleColumns, "amount");
      Map<String, Object> vatColumn = Map.of("key", "taxRate", "label", "USt.", "show", true);
      visibleColumns.add(amountIndex < 0 ? visibleColumns.size() : amountIndex, vatColumn);
    }

    StringBuilder html = new StringBuilder();
    html.append("<table style=\"width: 100%; border-collapse: collapse; margin-top: 20px;\">");
    html.append("<thead><tr>");

    String fontCss = "font-size: " + tfs + "; color: " + fc + ";";
    for (Map<String, Object> col : visibleColumns) {
      html.append(
              "<th style=\"background: #f3f4f6; text-align: left; padding: 12px; border-bottom: 2px solid #e5e7eb; ")
          .append(fontCss)
          .append("\">")
          .append(escapeHtml(col.get("label") == null ? "" : String.valueOf(col.get("label"))))
          .append("</th>");
    }
    html.append("</tr></thead>");

    html.append("<tbody>");

    for (InvoiceItem item : items) {
      renderItemRow(html, item, visibleColumns, currency, false, fontCss);
    }

    html.append("</tbody>");
    html.append("</table>");

    return html.toString();
  }

  private void renderItemRow(
      StringBuilder html,
      InvoiceItem item,
      List<Map<String, Object>> visibleColumns,
      String currency,
      boolean isChild,
      String fontCss) {
    boolean isNote = InvoiceItem.ItemType.CUSTOM.getValue().equalsIgnoreCase(item.getItemType());
    if (isNote) {
      renderNoteItemRow(html, item, visibleColumns, isChild);
    } else {
      renderPricedItemRow(html, item, visibleColumns, currency, isChild, fontCss);
    }
    if (item.getChildren() != null) {
      for (InvoiceItem child : item.getChildren()) {
        renderItemRow(html, child, visibleColumns, currency, true, fontCss);
      }
    }
  }

  /**
   * Renders a "Freitext" note row as a single merged cell that starts at the "Menge" column and
   * spans to the end of the table. The cell carries the description text with a light amber
   * background to mirror the editor's note tint. Pricing columns are intentionally absent — a note
   * has no quantity, unit or price.
   */
  void renderNoteItemRow(
      StringBuilder html,
      InvoiceItem item,
      List<Map<String, Object>> visibleColumns,
      boolean isChild) {
    int n = visibleColumns.size();
    int mergeStart = indexOfColumn(visibleColumns, "quantity");
    if (mergeStart < 0) {
      mergeStart = 1;
    }
    if (mergeStart > n) {
      mergeStart = n;
    }
    int prefixCount = mergeStart;

    html.append("<tr>");

    String childIndent = isChild ? "padding-left: 32px;" : "";
    String labelStyle =
        "padding: 12px; border-bottom: 1px solid #f3f4f6; font-size: 13px; text-align: left;"
            + " color: #92400e; background: #fffbeb; vertical-align: top;"
            + " white-space: pre-wrap;"
            + " "
            + childIndent;
    String mergeStyle =
        "padding: 12px; border-bottom: 1px solid #f3f4f6; font-size: 13px; text-align: left;"
            + " color: #374151; background: #fffbeb; vertical-align: top;"
            + " white-space: pre-wrap;"
            + " "
            + childIndent;

    for (int i = 0; i < prefixCount; i++) {
      String key = (String) visibleColumns.get(i).get("key");
      Object value = getItemValue(item, key);
      String displayValue = value != null ? value.toString() : "";
      html.append("<td style=\"")
          .append(labelStyle)
          .append("\">")
          .append(escapeHtml(displayValue))
          .append("</td>");
    }

    int span = n - mergeStart;
    if (span < 1) {
      span = 1;
    }
    String description = item.getItemDescription() != null ? item.getItemDescription() : "";
    html.append("<td colspan=\"")
        .append(span)
        .append("\" style=\"")
        .append(mergeStyle)
        .append("\">")
        .append(escapeHtml(description))
        .append("</td>");

    html.append("</tr>");
  }

  /**
   * Renders a priced line (ARTICLE or SERVICE): one {@code <td>} per visible column. Identical to
   * the pre-note behaviour, factored out so {@link #renderItemRow} can dispatch on type.
   */
  void renderPricedItemRow(
      StringBuilder html,
      InvoiceItem item,
      List<Map<String, Object>> visibleColumns,
      String currency,
      boolean isChild) {
    renderPricedItemRow(html, item, visibleColumns, currency, isChild, DEFAULT_CELL_FONT);
  }

  /**
   * Renders one priced line-item row.
   *
   * @param fontCss font size and color from the block's format settings, applied to every cell
   */
  void renderPricedItemRow(
      StringBuilder html,
      InvoiceItem item,
      List<Map<String, Object>> visibleColumns,
      String currency,
      boolean isChild,
      String fontCss) {
    html.append("<tr>");
    String descriptionStyle =
        isChild
            ? "padding: 8px 12px; border-bottom: 1px solid #f3f4f6; font-size: 12px; text-align: left; padding-left: 32px; color: #6b7280;"
            : "";
    for (Map<String, Object> col : visibleColumns) {
      String key = (String) col.get("key");
      Object value = getItemValue(item, key);
      boolean isCurrency = "rate".equals(key) || "amount".equals(key);
      boolean isNumeric = "quantity".equals(key) || "taxRate".equals(key) || isCurrency;
      String align = isNumeric ? "text-align: right;" : "text-align: left;";

      String displayValue;
      if (isCurrency && value instanceof BigDecimal amount) {
        displayValue = formatCurrency(amount, currency);
      } else if ("quantity".equals(key) && value instanceof BigDecimal quantity) {
        displayValue = formatQuantity(quantity);
      } else if ("taxRate".equals(key) && value instanceof BigDecimal rate) {
        displayValue = formatPercent(rate);
      } else {
        displayValue = value != null ? value.toString() : "";
      }

      String cellStyle;
      if (isChild && "description".equals(key)) {
        cellStyle = descriptionStyle;
      } else {
        cellStyle = "padding: 12px; border-bottom: 1px solid #f3f4f6; " + fontCss + " " + align;
      }

      html.append("<td style=\"")
          .append(cellStyle)
          .append("\">")
          .append(escapeHtml(displayValue))
          .append("</td>");
    }
    html.append("</tr>");
  }

  /**
   * Returns the index of the first column whose key equals {@code key}, or {@code -1} if absent.
   * Used by {@link #renderNoteItemRow} to locate the "Menge" column.
   */
  private static int indexOfColumn(List<Map<String, Object>> columns, String key) {
    for (int i = 0; i < columns.size(); i++) {
      if (key.equals(columns.get(i).get("key"))) {
        return i;
      }
    }
    return -1;
  }

  private static String escapeHtml(String s) {
    if (s == null) {
      return "";
    }
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  private Object getItemValue(InvoiceItem item, String key) {
    return switch (key) {
      case "description" -> item.getItemName();
      case "quantity" -> item.getQuantity();
      case "unit" -> item.getUnit();
      case "rate" -> item.getUnitPrice();
      case "amount" -> item.getTotalPrice();
      case "taxRate" -> item.getTaxRatePercent();
      default -> "";
    };
  }

  /**
   * Renders the totals: net, one VAT line per rate group, gross. A document without VAT
   * (Kleinunternehmer, reverse charge, tax-exempt) shows no VAT line but the legal note its
   * treatment requires; that note is printed even when the template hides the tax line, because the
   * invoice is incomplete without it.
   */
  private String renderTotals(
      Map<String, Object> data, Map<String, Object> config, Invoice invoice, VatBreakdown vat) {
    Boolean showTax = getBooleanValue(config, "showTax", true);
    String labelSubtotal = escapeHtml(getStringValue(config, "labelSubtotal", "Zwischensumme"));
    String labelTax = escapeHtml(getStringValue(config, "labelTax", "MwSt."));
    String labelTotal = escapeHtml(getStringValue(config, "labelTotal", "Gesamtbetrag"));
    String currency = getStringValue(data, "currency", "EUR");
    String alignment = getStringValue(config, "alignment", "right");
    double totalFs = getFormatFontSize(config, 10.5);
    String totalFc = getFormatFontColor(config, "#1f2937");
    String totalSizePt = formatPt(Math.min(24, totalFs + 3));
    String tableMargin;
    if ("left".equals(alignment)) {
      tableMargin = "margin-left: 0; margin-right: auto;";
    } else if ("center".equals(alignment)) {
      tableMargin = "margin-left: auto; margin-right: auto;";
    } else {
      tableMargin = "margin-left: auto; margin-right: 0;";
    }
    TaxTreatment treatment = TaxTreatment.parse(invoice.getTaxTreatment());

    StringBuilder html = new StringBuilder();
    html.append("<div style=\"margin-top: 30px;\">");
    html.append("<table style=\"width: 300px; border-collapse: collapse; ")
        .append(tableMargin)
        .append("\">");

    String rowPt = formatPt(totalFs);
    appendTotalsRow(html, labelSubtotal, formatCurrency(vat.net(), currency), rowPt);
    if (showTax && treatment.chargesVat()) {
      boolean mixed = vat.groups().size() > 1;
      for (VatBreakdown.Group group : vat.groups()) {
        String label =
            labelTax
                + " "
                + formatPercent(group.ratePercent())
                + (mixed ? " auf " + formatCurrency(group.net(), currency) : "");
        appendTotalsRow(html, label, formatCurrency(group.tax(), currency), rowPt);
      }
    }

    html.append("<tr style=\"border-top: 2px solid ").append(totalFc).append(";\">");
    html.append("<td style=\"padding: 12px 8px; font-size: ")
        .append(totalSizePt)
        .append("; font-weight: 800; color: ")
        .append(totalFc)
        .append(";\">")
        .append(labelTotal)
        .append("</td>");
    html.append("<td style=\"padding: 12px 8px; text-align: right; font-size: ")
        .append(totalSizePt)
        .append("; font-weight: 800; color: ")
        .append(totalFc)
        .append(";\">")
        .append(formatCurrency(vat.gross(), currency))
        .append("</td>");
    html.append("</tr>");

    html.append("</table>");
    String note = treatment.legalNote(invoice.getTaxExemptionReason());
    if (!note.isEmpty()) {
      html.append("<p class=\"tax-note\" style=\"margin-top: 12px; font-size: 10pt;\">")
          .append(escapeHtml(note))
          .append("</p>");
    }
    html.append("</div>");

    return html.toString();
  }

  /**
   * Appends one label/amount row of the totals table.
   *
   * @param fontSize CSS font size from the block's format settings (e.g. {@code 10.5pt})
   */
  private static void appendTotalsRow(
      StringBuilder html, String label, String value, String fontSize) {
    html.append("<tr><td style=\"padding: 8px; font-weight: 600; color: #6b7280; font-size: ")
        .append(fontSize)
        .append(";\">")
        .append(label)
        .append("</td><td style=\"padding: 8px; text-align: right; font-size: ")
        .append(fontSize)
        .append(";\">")
        .append(value)
        .append("</td></tr>");
  }

  /** "19 %" / "7 %"; decimals only when the rate has any. */
  private static String formatPercent(BigDecimal rate) {
    return rate.stripTrailingZeros().toPlainString().replace('.', ',') + " %";
  }

  /** German quantity: "2", "1,5", "0,25" (no rounding to whole numbers). */
  private static String formatQuantity(BigDecimal quantity) {
    return quantity.stripTrailingZeros().toPlainString().replace('.', ',');
  }

  private String renderPayment(
      Map<String, Object> data, Map<String, Object> config, VariableContext vars) {
    String paymentMethod = getStringValue(data, "paymentMethod", "");
    String bankName = getStringValue(data, "bankName", "");
    String iban = getStringValue(data, "iban", "");
    String bic = getStringValue(data, "bic", "");
    String reference = getStringValue(data, "reference", "");
    Boolean showBic = getBooleanValue(config, "showBic", true);

    paymentMethod = substituteVariables(paymentMethod, vars);
    bankName = substituteVariables(bankName, vars);
    iban = substituteVariables(iban, vars);
    bic = substituteVariables(bic, vars);
    reference = substituteVariables(reference, vars);

    StringBuilder html = new StringBuilder();
    html.append(
        "<div class=\"payment-block\" style=\"margin-top: 40px; padding: 20px; background: #f9fafb; border-radius: 8px;\">");
    double paymentFs = getFormatFontSize(config, 10.5);
    html.append("<h3 style=\"margin: 0 0 15px 0; font-size: ")
        .append(formatPt(Math.max(6, paymentFs - 2)))
        .append("; font-weight: 700; text-transform: uppercase; color: #6b7280;\">")
        .append("Zahlungsinformationen</h3>");
    html.append("<div style=\"")
        .append(buildBodyStyle(config, 10.5, "#1f2937"))
        .append(" line-height: 1.8;\">");

    if (paymentMethod != null && !paymentMethod.isEmpty()) {
      html.append("<div><strong>Zahlungsart:</strong> ").append(paymentMethod).append("</div>");
    }
    if (bankName != null && !bankName.isEmpty()) {
      html.append("<div><strong>Bank:</strong> ").append(bankName).append("</div>");
    }
    if (iban != null && !iban.isEmpty()) {
      html.append("<div><strong>IBAN:</strong> ").append(iban).append("</div>");
    }
    if (showBic && bic != null && !bic.isEmpty()) {
      html.append("<div><strong>BIC:</strong> ").append(bic).append("</div>");
    }
    if (reference != null && !reference.isEmpty()) {
      html.append("<div><strong>Verwendungszweck:</strong> ").append(reference).append("</div>");
    }

    html.append("</div>");
    html.append("</div>");

    return html.toString();
  }

  private String renderFooter(
      Map<String, Object> data, Map<String, Object> config, VariableContext vars) {
    String text = getStringValue(data, "text", "");
    String website = getStringValue(data, "website", "");
    String registrationNumber = getStringValue(data, "registrationNumber", "");
    String taxNumber = getStringValue(data, "taxNumber", "");
    String layout = getStringValue(config, "layout", "centered");

    text = substituteVariables(text, vars);
    website = substituteVariables(website, vars);
    registrationNumber = substituteVariables(registrationNumber, vars);
    taxNumber = substituteVariables(taxNumber, vars);

    StringBuilder html = new StringBuilder();
    html.append(
            "<div class=\"footer\" style=\"margin-top: 60px; padding-top: 20px; border-top: 1px solid #eee; ")
        .append(buildBodyStyle(config, 9, "#9ca3af"))
        .append(" text-align: ")
        .append("centered".equals(layout) ? "center" : "left")
        .append(";\">");

    if (text != null && !text.isEmpty()) {
      html.append("<p style=\"margin: 0 0 10px 0;\">").append(text).append("</p>");
    }
    if (website != null && !website.isEmpty()) {
      html.append("<p style=\"margin: 0 0 5px 0;\">").append(website).append("</p>");
    }
    if (registrationNumber != null && !registrationNumber.isEmpty()) {
      html.append("<p style=\"margin: 0 0 5px 0;\">Registernummer: ")
          .append(registrationNumber)
          .append("</p>");
    }
    if (taxNumber != null && !taxNumber.isEmpty()) {
      html.append("<p style=\"margin: 0;\">Steuernummer: ").append(taxNumber).append("</p>");
    }

    html.append("</div>");

    return html.toString();
  }

  private String renderNotesBlock(
      Map<String, Object> data, Map<String, Object> config, VariableContext vars) {
    Boolean showTitle = getBooleanValue(config, "showTitle", true);
    Boolean showBox = getBooleanValue(config, "showBox", true);
    String title = escapeHtml(getStringValue(config, "title", "Notizen"));
    String backgroundColor = safeColor(getStringValue(config, "backgroundColor", ""), "#fef3c7");
    String borderColor = safeColor(getStringValue(config, "borderColor", ""), "#f59e0b");
    double notesFs = getFormatFontSize(config, 9.5);
    double titleSize = Math.max(7, notesFs);

    String content = getStringValue(data, "content", "");
    content = replaceVariables(content, vars);

    if (content.isEmpty()) {
      return "";
    }

    StringBuilder html = new StringBuilder();
    if (showBox) {
      html.append("<div style=\"margin-top: 30px; padding: 12px 16px; background-color: ")
          .append(backgroundColor)
          .append("; border-left: 4px solid ")
          .append(borderColor)
          .append("; border-radius: 4px;\">");
    } else {
      html.append("<div style=\"margin-top: 30px;\">");
    }
    if (showTitle) {
      html.append("<p style=\"margin: 0; font-size: ")
          .append(formatPt(titleSize))
          .append("; color: #92400e; font-weight: 600;\">")
          .append(title)
          .append("</p>");
    }
    html.append("<p style=\"margin: 4px 0 0 0; ")
        .append(buildBodyStyle(config, 9.5, "#78350f"))
        .append(" white-space: pre-wrap;\">")
        .append(content.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
        .append("</p>");
    html.append("</div>");
    return html.toString();
  }

  /**
   * Explicit size of the logo. Flying Saucer ignores {@code max-width} / {@code max-height} on
   * images, so a logo was printed at its pixel size: a large one overflowed onto its own page. The
   * size is computed from the uploaded file (scaled down to fit the box, never enlarged); for files
   * ImageIO cannot read (SVG) only the height is set and the width follows the aspect ratio.
   *
   * @param logoUrl the stored logo URL
   * @return CSS {@code width} and {@code height} declarations
   */
  String logoSizeStyle(String logoUrl) {
    try {
      Optional<byte[]> logo = files.read(logoUrl);
      if (logo.isPresent()) {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(logo.get()));
        if (image != null && image.getWidth() > 0 && image.getHeight() > 0) {
          double scale =
              Math.min(
                  1.0,
                  Math.min(
                      (double) LOGO_MAX_WIDTH / image.getWidth(),
                      (double) LOGO_MAX_HEIGHT / image.getHeight()));
          return "width: "
              + Math.max(1, (int) Math.round(image.getWidth() * scale))
              + "px; height: "
              + Math.max(1, (int) Math.round(image.getHeight() * scale))
              + "px;";
        }
      }
    } catch (IOException e) {
      // Without the query: a SaaS upload URL carries a signature that opens the file.
      LOGGER.log(
          Level.FINE, "Logo size could not be read: " + logoUrl.replaceFirst("[?#].*$", ""), e);
    }
    return "height: 60px;";
  }

  /**
   * Arranges rendered blocks on the page. Blocks are full width by default; two consecutive blocks
   * with {@code config.width = "half"} share one row (e.g. recipient left, document data right), a
   * half-width block without a partner takes the left half. Rows are tables because Flying Saucer
   * has no flexbox. The editor preview groups blocks the same way ({@code blockModel.js >
   * layoutRows}).
   *
   * @param rendered HTML of each block, in order
   * @param halfWidth whether each block is half width
   * @return the page body HTML
   */
  static String layoutBlocks(List<String> rendered, List<Boolean> halfWidth) {
    StringBuilder body = new StringBuilder();
    int i = 0;
    while (i < rendered.size()) {
      if (!halfWidth.get(i)) {
        body.append(rendered.get(i));
        i++;
        continue;
      }
      boolean paired = i + 1 < rendered.size() && halfWidth.get(i + 1);
      body.append(
              "<table class=\"block-row\" style=\"width: 100%; border-collapse: collapse; margin: 0;\">")
          .append(
              "<tr><td style=\"width: 50%; vertical-align: top; padding: 0 12px 0 0; border: none;\">")
          .append(rendered.get(i))
          .append(
              "</td><td style=\"width: 50%; vertical-align: top; padding: 0 0 0 12px; border: none;\">")
          .append(paired ? rendered.get(i + 1) : "")
          .append("</td></tr></table>");
      i += paired ? 2 : 1;
    }
    return body.toString();
  }

  /**
   * Makes user CSS safe to embed in the XHTML {@code <style>} element: a literal {@code <} would
   * either break the XML parse or close the element and inject markup. CSS never needs a raw {@code
   * <}; the CSS escape {@code \3C} keeps the meaning inside strings.
   *
   * @param css the template's custom CSS
   * @return CSS without raw {@code <} or {@code &}
   */
  static String cssForStyleElement(String css) {
    if (css == null) {
      return "";
    }
    return css.replace("&", "\\26 ").replace("<", "\\3C ");
  }

  private String getBaseStyles() {
    return """
        .invoice-box {
          font-family: 'Inter', system-ui, -apple-system, sans-serif;
          color: #1f2937;
          line-height: 1.5;
        }

        .invoice-box .header {
          margin-bottom: 40px;
        }

        .invoice-box .brand h1 {
          margin: 0;
          letter-spacing: -1px;
        }

        .invoice-box .brand p {
          margin: 5px 0 0 0;
          color: #6b7280;
        }

        .invoice-box .address-block {
          margin-bottom: 30px;
        }

        .invoice-box .section-title {
          font-size: 12px;
          font-weight: 700;
          text-transform: uppercase;
          color: #6b7280;
          margin-bottom: 8px;
          display: block;
        }

        .invoice-box table {
          width: 100%;
          border-collapse: collapse;
          margin-top: 20px;
        }

        .invoice-box th {
          background: #f3f4f6;
          text-align: left;
          padding: 12px;
          font-size: 13px;
          border-bottom: 2px solid #e5e7eb;
        }

        .invoice-box td {
          padding: 12px;
          border-bottom: 1px solid #f3f4f6;
          font-size: 14px;
        }

        .invoice-box .totals-container {
          display: flex;
          justify-content: flex-end;
          margin-top: 30px;
        }

        .invoice-box .totals-table {
          width: 300px;
        }

        .invoice-box .totals-table tr td:first-child {
          font-weight: 600;
          color: #6b7280;
        }

        .invoice-box .grand-total {
          font-size: 18px;
          font-weight: 800;
          color: #2563eb;
          border-top: 2px solid #2563eb;
        }

        .invoice-box .payment-block {
          margin-top: 40px;
          padding: 20px;
          background: #f9fafb;
          border-radius: 8px;
        }

        .invoice-box .payment-block h3 {
          margin: 0 0 15px 0;
          font-size: 14px;
          text-transform: uppercase;
          color: #6b7280;
        }

        .invoice-box .footer {
          margin-top: 60px;
          padding-top: 20px;
          border-top: 1px solid #eee;
          font-size: 12px;
          color: #9ca3af;
          text-align: center;
        }

        @media print {
          .invoice-box {
            box-shadow: none;
            margin: 0;
            padding: 0;
          }

          @page {
            size: A4;
            margin: 0;
          }
        }
        """;
  }

  @SuppressWarnings("unused")
  public byte[] convertHtmlToPdf(String html) throws Exception {
    LOGGER.fine("PDF Generator: Starting HTML to PDF conversion");

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

    // Template content is user input: load logos from the upload directory only, never from the
    // network or other local files (see RestrictedUserAgent).
    ITextOutputDevice outputDevice = new ITextOutputDevice(ITextRenderer.DEFAULT_DOTS_PER_POINT);
    RestrictedUserAgent userAgent =
        new RestrictedUserAgent(outputDevice, ITextRenderer.DEFAULT_DOTS_PER_PIXEL, files::read);
    ITextRenderer renderer =
        new ITextRenderer(
            ITextRenderer.DEFAULT_DOTS_PER_POINT,
            ITextRenderer.DEFAULT_DOTS_PER_PIXEL,
            outputDevice,
            userAgent);
    LOGGER.fine("PDF Generator: Created ITextRenderer");
    // Every document is PDF/A-3b (ISO 19005-3): fonts embedded, sRGB output intent and XMP
    // metadata (both set by Flying Saucer). Issued invoices get their e-invoice XML attached
    // afterwards (FacturXEmbedder), which PDF/A-3 allows.
    renderer.setPdfAConformance(PdfAConformance.PDF_A_3B);
    EmbeddedFonts.install(renderer.getFontResolver());

    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      // The XHTML DOCTYPE points at w3.org. Never fetch it: rendering must work offline (desktop)
      // and must not resolve external entities. Only XML's built-in entities are used.
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      DocumentBuilder builder = factory.newDocumentBuilder();
      // Control characters pasted into names or notes are not allowed in XML and would make the
      // parse fail; they are dropped here, so stored snapshots render as well.
      Document document = builder.parse(new InputSource(new StringReader(XmlChars.clean(html))));
      LOGGER.fine("PDF Generator: Parsed HTML to Document");

      renderer.setDocument(document);
      LOGGER.fine("PDF Generator: Set document on renderer");

      renderer.layout();
      LOGGER.fine("PDF Generator: Layout completed");

      renderer.createPDF(outputStream);
      LOGGER.fine("PDF Generator: PDF created");

    } catch (Exception e) {
      LOGGER.log(Level.SEVERE, "PDF Generator: Error during PDF conversion", e);
      throw e;
    }

    return outputStream.toByteArray();
  }

  /**
   * Returns the configured company (owner). There is deliberately no fallback: rendering a document
   * with placeholder company or bank data would produce a wrong invoice.
   */
  private Owner getOwner() {
    List<Owner> owners = ownerRepository.findAll();
    if (owners.isEmpty()) {
      throw new MissingConfigurationException(
          "Bitte zuerst die Firmendaten (Inhaber) anlegen, bevor Dokumente erzeugt werden.");
    }
    return owners.get(0);
  }

  private String formatDate(String dateString) {
    if (dateString == null || dateString.isEmpty()) {
      return "";
    }
    try {
      return DATE_FORMATTER.format(java.time.LocalDate.parse(dateString));
    } catch (Exception e) {
      return dateString;
    }
  }

  private String formatPeriod(String startDate, String endDate) {
    String start = formatDate(startDate);
    String end = formatDate(endDate);
    if (start.isEmpty() && end.isEmpty()) {
      return "";
    }
    if (end.isEmpty()) {
      return start;
    }
    if (start.isEmpty()) {
      return end;
    }
    return start + " – " + end;
  }

  @SuppressWarnings("unused")
  /** German amount with thousands separator, independent of the JVM locale: "1.234,56 €". */
  private String formatCurrency(BigDecimal amount, String currency) {
    return String.format(Locale.GERMANY, "%,.2f", amount) + " €";
  }

  private String getStringValue(Map<String, Object> map, String key, String defaultValue) {
    if (map == null || !map.containsKey(key)) {
      return defaultValue;
    }
    Object value = map.get(key);
    return value != null ? value.toString() : defaultValue;
  }

  private Boolean getBooleanValue(Map<String, Object> map, String key, boolean defaultValue) {
    if (map == null || !map.containsKey(key)) {
      return defaultValue;
    }
    Object value = map.get(key);
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    return defaultValue;
  }

  private double getDoubleValue(Map<String, Object> map, String key, double defaultValue) {
    if (map == null || !map.containsKey(key)) {
      return defaultValue;
    }
    Object value = map.get(key);
    if (value instanceof Number) {
      return ((Number) value).doubleValue();
    }
    return defaultValue;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> getFormatConfig(Map<String, Object> config) {
    if (config == null) {
      return new HashMap<>();
    }
    Object formatObj = config.get("format");
    if (formatObj instanceof Map) {
      return (Map<String, Object>) formatObj;
    }
    return new HashMap<>();
  }

  private double getFormatFontSize(Map<String, Object> config, double defaultPt) {
    return getDoubleValue(getFormatConfig(config), "fontSize", defaultPt);
  }

  private String getFormatFontColor(Map<String, Object> config, String defaultColor) {
    return safeColor(getStringValue(getFormatConfig(config), "fontColor", ""), defaultColor);
  }

  private String getFormatFontFamily(Map<String, Object> config, String defaultFamily) {
    String family = getStringValue(getFormatConfig(config), "fontFamily", "");
    return FONT_FAMILY.matcher(family).matches() ? family : defaultFamily;
  }

  /**
   * Returns the value when it is a hex color, else the fallback. Mirrors {@code safeColor} in the
   * editor's {@code blockRegistry.js}.
   *
   * @param value user-entered color
   * @param fallback color used for anything else
   * @return a color that is safe to put into CSS
   */
  static String safeColor(String value, String fallback) {
    return value != null && HEX_COLOR.matcher(value).matches() ? value : fallback;
  }

  /**
   * CSS for the body text of a block from its format settings (the editor's format toolbar). The
   * template editor preview builds the same declarations ({@code blockRegistry.js > bodyStyle}).
   *
   * @param config the block config with an optional {@code format} map
   * @param defaultPt font size when the block has none
   * @param defaultColor font color when the block has none
   * @return {@code font-size}, {@code color}, {@code font-weight} and {@code font-family}
   */
  private String buildBodyStyle(Map<String, Object> config, double defaultPt, String defaultColor) {
    return buildBodyStyle(config, getFormatFontSize(config, defaultPt), defaultPt, defaultColor);
  }

  /**
   * Font size of the invoice-meta body text. The block's format size means the body text; the title
   * is derived from it. Older editors stored the title size (18pt by default) there, which rendered
   * the whole block at that size: values of 16pt and more are read as such a title size. Mirrors
   * {@code blockModel.js > migrateBlock}.
   *
   * @param stored the format font size stored in the block
   * @return the body font size in pt
   */
  static double metaBodyFontSizeOf(double stored) {
    return stored >= 16 ? Math.round(stored * 7 / 12 * 2) / 2.0 : stored;
  }

  /** Body font size of an invoice-meta block config ({@link #metaBodyFontSizeOf}). */
  private double metaBodyFontSize(Map<String, Object> config) {
    return metaBodyFontSizeOf(getFormatFontSize(config, 10.5));
  }

  /**
   * Body-text CSS with an explicit font size (see {@link #buildBodyStyle(Map, double, String)}).
   */
  private String buildBodyStyle(
      Map<String, Object> config, double fontSize, double defaultPt, String defaultColor) {
    double fs = fontSize > 0 ? fontSize : defaultPt;
    String fc = getFormatFontColor(config, defaultColor);
    String ff = getFormatFontFamily(config, "Inter");
    String fw =
        "bold".equals(getStringValue(getFormatConfig(config), "fontWeight", ""))
            ? "bold"
            : "normal";
    return "font-size: "
        + formatPt(fs)
        + "; color: "
        + fc
        + "; font-weight: "
        + fw
        + "; font-family: "
        + ff
        + ", "
        + genericFontFamily(ff)
        + ";";
  }

  /**
   * Generic CSS family for a font name. The PDF renderer only has the standard PDF fonts and picks
   * one by the generic family, so a serif choice (Georgia, Times New Roman) prints in Times and a
   * monospace one in Courier instead of falling back to Helvetica. Mirrors {@code blockRegistry.js
   * > genericFontFamily}.
   *
   * @param family the configured font name
   * @return {@code serif}, {@code monospace} or {@code sans-serif}
   */
  static String genericFontFamily(String family) {
    String f = family == null ? "" : family.toLowerCase(Locale.ROOT);
    if (f.contains("georgia") || f.contains("times")) {
      return "serif";
    }
    if (f.contains("courier")) {
      return "monospace";
    }
    return "sans-serif";
  }

  private String formatPt(double pt) {
    if (pt == (long) pt) {
      return String.valueOf((long) pt) + "pt";
    }
    return String.format("%.2f", pt).replace(',', '.') + "pt";
  }

  /**
   * Replaces the {@code {{variable}}} placeholders in a plain-text block field and XML-escapes the
   * result. Every block field passes through here exactly once before it is appended to the HTML,
   * so neither template text nor customer/owner data can break the XHTML (e.g. "Müller & Söhne").
   */
  /**
   * The data every block can reference through {@code {{variables}}}. Any part may be {@code null}
   * (e.g. a template preview without customer); its variables then resolve to empty text.
   */
  record VariableContext(Owner owner, Customer customer, Invoice invoice) {}

  /**
   * Resolves the variables of a block field and HTML-escapes the result.
   *
   * @param text the field value as stored in the template
   * @param vars the document data
   * @return escaped text, ready to be inserted into the HTML
   */
  private String substituteVariables(String text, VariableContext vars) {
    return escapeHtml(replaceVariables(text, vars));
  }

  /**
   * Resolves all known variables and removes unknown ones. A misspelled placeholder must never
   * appear on a document sent to a customer; the template editor warns about it instead.
   *
   * @param text the field value as stored in the template
   * @param vars the document data
   * @return the text with every {@code {{...}}} replaced (not escaped)
   */
  String replaceVariables(String text, VariableContext vars) {
    if (text == null || !text.contains("{{")) {
      return text;
    }
    // `{{ firma }}` means `{{firma}}` (the editor accepts the spaces too).
    String normalized = VARIABLE_WITH_SPACES.matcher(text).replaceAll("{{$1}}");
    String result = replaceVariables(normalized, vars.owner(), vars.customer(), vars.invoice());
    return UNRESOLVED_VARIABLE.matcher(result).replaceAll("");
  }

  private String replaceVariables(String text, Owner owner, Customer customer, Invoice invoice) {
    if (text == null || !text.contains("{{")) {
      return text;
    }

    String result = text;

    if (owner != null) {
      result = result.replace("{{firma}}", nullToEmpty(owner.getCompanyName()));
      result = result.replace("{{company_name}}", nullToEmpty(owner.getCompanyName()));
      result =
          result.replace(
              "{{adresse}}",
              owner.getStreet()
                  + " "
                  + owner.getHouseNumber()
                  + ", "
                  + owner.getPostalCode()
                  + " "
                  + owner.getCity());
      result =
          result.replace(
              "{{company_address}}",
              owner.getStreet()
                  + " "
                  + owner.getHouseNumber()
                  + ", "
                  + owner.getPostalCode()
                  + " "
                  + owner.getCity());
      result = result.replace("{{telefon}}", nullToEmpty(owner.getPhone()));
      result = result.replace("{{company_phone}}", nullToEmpty(owner.getPhone()));
      result = result.replace("{{email}}", nullToEmpty(owner.getEmail()));
      result = result.replace("{{company_email}}", nullToEmpty(owner.getEmail()));
      result = result.replace("{{website}}", nullToEmpty(owner.getUrl()));
      result = result.replace("{{company_website}}", nullToEmpty(owner.getUrl()));
      result = result.replace("{{ust_id}}", nullToEmpty(owner.getTaxId()));
      result = result.replace("{{company_tax_id}}", nullToEmpty(owner.getTaxId()));
    }

    if (customer != null) {
      result =
          result.replace(
              "{{kunde}}",
              nullToEmpty(customer.getFirstName()) + " " + nullToEmpty(customer.getLastName()));
      result =
          result.replace(
              "{{client_name}}",
              nullToEmpty(customer.getFirstName()) + " " + nullToEmpty(customer.getLastName()));
      result =
          result.replace(
              "{{kunde_strasse}}",
              customer.getStreet() != null
                  ? customer.getStreet() + " " + customer.getHouseNumber()
                  : "");
      result =
          result.replace(
              "{{client_address}}",
              customer.getStreet() != null
                  ? customer.getStreet() + " " + customer.getHouseNumber()
                  : "");
      result =
          result.replace(
              "{{kunde_plz_ort}}",
              nullToEmpty(customer.getPostalCode()) + " " + nullToEmpty(customer.getCity()));
      result = result.replace("{{kunde_email}}", nullToEmpty(customer.getEmail()));
      result = result.replace("{{client_email}}", nullToEmpty(customer.getEmail()));
      result = result.replace("{{kunde_telefon}}", nullToEmpty(customer.getPhone()));
      result = result.replace("{{client_phone}}", nullToEmpty(customer.getPhone()));
    }

    if (invoice != null) {
      result =
          result.replace("{{rechnungsnummer}}", nullToEmpty(invoice.getFormattedInvoiceNumber()));
      result =
          result.replace("{{invoice_number}}", nullToEmpty(invoice.getFormattedInvoiceNumber()));
      result = result.replace("{{rechnungsdatum}}", formatDate(invoice.getInvoiceDate()));
      result = result.replace("{{invoice_date}}", formatDate(invoice.getInvoiceDate()));
      result = result.replace("{{faelligkeitsdatum}}", formatDate(invoice.getDueDate()));
      result = result.replace("{{due_date}}", formatDate(invoice.getDueDate()));
      result = result.replace("{{leistungsdatum}}", formatDate(invoice.getWorkStartDate()));
      result =
          result.replace(
              "{{leistungszeitraum}}",
              formatPeriod(invoice.getWorkStartDate(), invoice.getWorkEndDate()));

      result = result.replace("{{netto}}", formatCurrency(invoice.getNetAmount(), "EUR"));
      result = result.replace("{{net_amount}}", formatCurrency(invoice.getNetAmount(), "EUR"));
      result = result.replace("{{steuersatz}}", String.format("%.1f", invoice.getTaxRate()));
      result = result.replace("{{tax_rate}}", String.format("%.1f", invoice.getTaxRate()));
      result = result.replace("{{steuer}}", formatCurrency(invoice.getTaxAmount(), "EUR"));
      result = result.replace("{{tax_amount}}", formatCurrency(invoice.getTaxAmount(), "EUR"));
      result = result.replace("{{betrag}}", formatCurrency(invoice.getGrossAmount(), "EUR"));
      result = result.replace("{{gross_amount}}", formatCurrency(invoice.getGrossAmount(), "EUR"));
      result = result.replace("{{rabatt}}", formatCurrency(BigDecimal.ZERO, "EUR"));
      result = result.replace("{{discount}}", formatCurrency(BigDecimal.ZERO, "EUR"));
      result = result.replace("{{waehrung}}", "EUR");
      result = result.replace("{{currency}}", "EUR");
      result = result.replace("{{notizen}}", nullToEmpty(invoice.getNotes()));
      result = result.replace("{{notes}}", nullToEmpty(invoice.getNotes()));
      if (TaxTreatment.parse(invoice.getTaxTreatment()) == TaxTreatment.SMALL_BUSINESS) {
        result =
            result.replace(
                "{{kleinunternehmer_hinweis}}",
                "Hinweis: Als Kleinunternehmer im Sinne von § 19 Abs. 1 UStG wird Umsatzsteuer nicht berechnet");
      } else {
        result = result.replace("{{kleinunternehmer_hinweis}}", "");
      }
    }

    if (owner != null) {
      result = result.replace("{{bank_name}}", "");
      result = result.replace("{{iban}}", nullToEmpty(owner.getIban()));
      result = result.replace("{{bic}}", "");
      result =
          result.replace(
              "{{verwendungszweck}}",
              invoice != null ? nullToEmpty(invoice.getFormattedInvoiceNumber()) : "");
    }

    return result;
  }

  private String nullToEmpty(String value) {
    return value != null ? value : "";
  }
}
