package de.billery.service;

import de.billery.data.InvoiceTemplate;
import de.billery.repository.InvoiceTemplateRepository;
import jakarta.annotation.PostConstruct;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Gives templates without block data the default blocks when the application starts.
 *
 * <p>The SQL seeds ({@code V1__baseline.sql}, {@code scheme-h2.sql}) create every default template
 * with blocks. A template without blocks comes from the DOCX/ODT import, which stores only the
 * HTML; the block-based PDF path refuses such a template ("Die Vorlage enthält keine Blöcke."), so
 * it gets the default layout of its document type here.
 */
@Service
@Profile("!saas") // runs at startup without a tenant; the SaaS profile seeds templates per tenant
public class TemplateBlocksBackfill {

  private static final String JSON_ID_KEY = "{\"id\":\"";

  private final InvoiceTemplateRepository templateRepository;

  /**
   * @param templateRepository the templates
   */
  public TemplateBlocksBackfill(InvoiceTemplateRepository templateRepository) {
    this.templateRepository = templateRepository;
  }

  /** Writes the default blocks into every template whose block data is missing or empty. */
  @PostConstruct
  public void backfillMissingBlocks() {
    for (InvoiceTemplate template : templateRepository.findAll()) {
      if (hasNoBlocks(template.getBlocks())) {
        template.setBlocks(getDefaultBlocksJson(template.getDocumentTypeId()));
        templateRepository.update(template);
      }
    }
  }

  private static boolean hasNoBlocks(String blocks) {
    return blocks == null || blocks.isEmpty() || "[]".equals(blocks) || "null".equals(blocks);
  }

  /**
   * The default layout: header, sender and receiver address, document data, line items, totals,
   * payment and footer.
   *
   * @param documentTypeId the document type (2 = offer, otherwise titled as an invoice)
   * @return the blocks JSON
   */
  private String getDefaultBlocksJson(int documentTypeId) {
    String invoiceTitle = documentTypeId == 2 ? "Angebot" : "Rechnung";
    return "["
        + JSON_ID_KEY
        + UUID.randomUUID()
        + "\",\"type\":\"header\",\"order\":0,\"data\":{\"companyName\":\"{{firma}}\",\"companySlogan\":\"\",\"logoUrl\":\"\"},\"config\":{\"showSlogan\":true,\"logoAlignment\":\"left\"}},"
        + JSON_ID_KEY
        + UUID.randomUUID()
        + "\",\"type\":\"address\",\"order\":1,\"data\":{\"role\":\"sender\",\"name\":\"{{firma}}\",\"address\":\"{{adresse}}\",\"email\":\"{{email}}\",\"phone\":\"{{telefon}}\",\"vatId\":\"{{ust_id}}\"},\"config\":{\"showLabels\":true,\"label\":\"Absender\"}},"
        + JSON_ID_KEY
        + UUID.randomUUID()
        + "\",\"type\":\"address\",\"order\":2,\"data\":{\"role\":\"receiver\",\"name\":\"{{kunde}}\",\"address\":\"{{kunde_strasse}}, {{kunde_plz_ort}}\",\"email\":\"{{kunde_email}}\",\"phone\":\"{{kunde_telefon}}\",\"vatId\":\"\"},\"config\":{\"showLabels\":true,\"label\":\"Empfänger\"}},"
        + JSON_ID_KEY
        + UUID.randomUUID()
        + "\",\"type\":\"invoice-meta\",\"order\":3,\"data\":{\"invoiceTitle\":\""
        + invoiceTitle
        + "\",\"invoiceId\":\"{{rechnungsnummer}}\",\"invoiceDate\":\"{{rechnungsdatum}}\",\"dueDate\":\"{{faelligkeitsdatum}}\",\"orderNumber\":\"\"},\"config\":{\"showOrderNumber\":true,\"alignment\":\"right\"}},"
        + JSON_ID_KEY
        + UUID.randomUUID()
        + "\",\"type\":\"line-items\",\"order\":4,\"data\":{\"items\":[{\"description\":\"Beispielposition 1\",\"quantity\":1,\"unit\":\"Stück\",\"rate\":100,\"amount\":100},{\"description\":\"Beispielposition 2\",\"quantity\":2,\"unit\":\"Stück\",\"rate\":50,\"amount\":100}],\"columns\":[{\"key\":\"description\",\"label\":\"Beschreibung\",\"show\":true,\"width\":\"50%\"},{\"key\":\"quantity\",\"label\":\"Menge\",\"show\":true,\"width\":\"15%\"},{\"key\":\"unit\",\"label\":\"Einheit\",\"show\":true,\"width\":\"10%\"},{\"key\":\"rate\",\"label\":\"Preis\",\"show\":true,\"width\":\"12%\"},{\"key\":\"amount\",\"label\":\"Gesamt\",\"show\":true,\"width\":\"13%\"}]},\"config\":{\"showUnit\":true,\"showRate\":true,\"currency\":\"EUR\",\"decimalPlaces\":2}},"
        + JSON_ID_KEY
        + UUID.randomUUID()
        + "\",\"type\":\"totals\",\"order\":5,\"data\":{\"subtotal\":200,\"taxRate\":19,\"taxAmount\":38,\"total\":238,\"discount\":0,\"currency\":\"EUR\"},\"config\":{\"showDiscount\":false,\"showTax\":true,\"labelSubtotal\":\"Zwischensumme\",\"labelTax\":\"MwSt.\",\"labelTotal\":\"Gesamtbetrag\",\"alignment\":\"right\"}},"
        + JSON_ID_KEY
        + UUID.randomUUID()
        + "\",\"type\":\"payment\",\"order\":6,\"data\":{\"paymentMethod\":\"Banküberweisung\",\"bankName\":\"{{bank_name}}\",\"iban\":\"{{iban}}\",\"bic\":\"{{bic}}\",\"reference\":\"{{verwendungszweck}}\"},\"config\":{\"showBic\":true}},"
        + JSON_ID_KEY
        + UUID.randomUUID()
        + "\",\"type\":\"footer\",\"order\":7,\"data\":{\"text\":\"Vielen Dank für Ihr Vertrauen!\",\"website\":\"{{website}}\",\"registrationNumber\":\"\",\"taxNumber\":\"{{ust_id}}\"},\"config\":{\"layout\":\"centered\"}}"
        + "]";
  }
}
