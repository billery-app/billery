package de.billery.xmlgenerator;

import de.billery.data.Invoice;
import de.billery.data.InvoiceItem;
import de.billery.data.PartySnapshot;
import de.billery.data.PartySnapshot.Party;
import de.billery.document.DocumentType;
import de.billery.tax.TaxTreatment;
import de.billery.tax.VatBreakdown;
import de.billery.tax.VatCalculator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Writes an XRechnung 3.0 (CIUS of EN 16931) in UBL 2.1 syntax: a {@code ubl:Invoice} for invoices,
 * a {@code ubl:CreditNote} (type code 381) for a Stornorechnung.
 *
 * <p>The generator is pure: it reads nothing from the database. {@code EInvoiceService} passes the
 * document, its items, the parties (frozen at issue for issued documents) and, for a credit note,
 * the cancelled invoice. Amounts come from {@link VatCalculator}, so the XML always matches the
 * PDF.
 *
 * <p>Mapping notes:
 *
 * <ul>
 *   <li>Sub-items are flattened into their own lines (XRechnung has no line hierarchy outside the
 *       extension). {@code CUSTOM} note rows become the line note (BT-127) of their parent line.
 *   <li>The service date is written as delivery date (BT-72), a service period as invoicing period
 *       (BG-14).
 *   <li>Seller and buyer are assumed to be in Germany (no country field exists yet).
 *   <li>A tax ID that starts with two letters is a VAT ID (BT-31 / BT-48), otherwise a tax number
 *       (Steuernummer, BT-32) or, for the buyer, a legal registration ID (BT-47).
 * </ul>
 *
 * <p>{@link #missingFields} lists what the XRechnung rules require but the data lacks (e.g. BR-DE-2
 * seller contact, BT-49 buyer e-mail); generate only when it is empty.
 */
public class XRechnungGenerator {

  /** XRechnung 3.0 specification identifier (BT-24). */
  public static final String CUSTOMIZATION_ID =
      "urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0";

  private static final String PROFILE_ID = "urn:fdc:peppol.eu:2017:poacc:billing:01:1.0";
  private static final String NS_INVOICE = "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2";
  private static final String NS_CREDIT_NOTE =
      "urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2";
  private static final String NS_CAC =
      "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2";
  private static final String NS_CBC =
      "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2";
  private static final String EUR = "EUR";
  private static final Pattern VAT_ID = Pattern.compile("^[A-Z]{2}[0-9A-Z+*.]{2,13}$");

  /**
   * Lists the fields the XRechnung rules require but the data lacks, as German messages for the
   * user. An empty list means the document can be generated.
   *
   * @param invoice the document
   * @param items its items
   * @param parties seller and buyer
   * @return the missing fields, empty if complete
   */
  public List<String> missingFields(
      Invoice invoice, List<InvoiceItem> items, PartySnapshot parties) {
    List<String> missing = new ArrayList<>();
    Party seller = parties.seller();
    Party buyer = parties.buyer();
    requireValue(missing, seller.name(), "Firmenname (Firmendaten)");
    requireValue(missing, seller.city(), "Ort (Firmendaten)");
    requireValue(missing, seller.postalCode(), "PLZ (Firmendaten)");
    requireValue(
        missing, seller.contactName(), "Ansprechpartner (Vor-/Nachname in den Firmendaten)");
    requireValue(missing, seller.phone(), "Telefon (Firmendaten)");
    requireValue(missing, seller.email(), "E-Mail (Firmendaten)");
    requireValue(missing, seller.taxId(), "USt-IdNr. oder Steuernummer (Firmendaten)");
    requireValue(missing, buyer.name(), "Name des Kunden");
    requireValue(missing, buyer.city(), "Ort des Kunden");
    requireValue(missing, buyer.postalCode(), "PLZ des Kunden");
    requireValue(missing, buyer.email(), "E-Mail des Kunden (elektronische Adresse, BT-49)");
    requireValue(
        missing, invoice.getDocumentNumber(), "Rechnungsnummer (erst nach dem Ausstellen)");
    requireValue(missing, invoice.getInvoiceDate(), "Rechnungsdatum");
    if (items == null || items.isEmpty()) {
      missing.add("Positionen");
    }
    if (TaxTreatment.parse(invoice.getTaxTreatment()) == TaxTreatment.REVERSE_CHARGE) {
      requireValue(missing, buyer.taxId(), "USt-IdNr. des Kunden (Reverse Charge)");
    }
    return missing;
  }

  /**
   * Generates the XRechnung XML.
   *
   * @param invoice the document (an invoice type or a credit note)
   * @param items its item tree
   * @param parties seller and buyer
   * @param cancelledInvoice for a credit note: the invoice it cancels (BT-25); otherwise {@code
   *     null}
   * @return the XML document, UTF-8
   */
  public String generate(
      Invoice invoice, List<InvoiceItem> items, PartySnapshot parties, Invoice cancelledInvoice) {
    boolean creditNote = invoice.getDocumentTypeId() == DocumentType.CREDIT_NOTE;
    TaxTreatment treatment = TaxTreatment.parse(invoice.getTaxTreatment());
    VatBreakdown vat = VatCalculator.calculate(items, treatment);
    String root = creditNote ? "ubl:CreditNote" : "ubl:Invoice";

    XmlWriter xml = new XmlWriter();
    xml.raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
    xml.open(
        root,
        "xmlns:ubl=\""
            + (creditNote ? NS_CREDIT_NOTE : NS_INVOICE)
            + "\" xmlns:cac=\""
            + NS_CAC
            + "\" xmlns:cbc=\""
            + NS_CBC
            + "\"");
    xml.leaf("cbc:CustomizationID", CUSTOMIZATION_ID);
    xml.leaf("cbc:ProfileID", PROFILE_ID);
    xml.leaf("cbc:ID", invoice.getDocumentNumber());
    xml.leaf("cbc:IssueDate", invoice.getInvoiceDate());
    if (!creditNote && !blank(invoice.getDueDate())) {
      xml.leaf("cbc:DueDate", invoice.getDueDate());
    }
    xml.leaf(
        creditNote ? "cbc:CreditNoteTypeCode" : "cbc:InvoiceTypeCode", creditNote ? "381" : "380");
    if (!blank(invoice.getNotes())) {
      xml.leaf("cbc:Note", invoice.getNotes());
    }
    xml.leaf("cbc:DocumentCurrencyCode", EUR);
    xml.leaf(
        "cbc:BuyerReference",
        blank(parties.buyer().buyerReference())
            ? invoice.getDocumentNumber()
            : parties.buyer().buyerReference());
    if (!blank(invoice.getWorkStartDate()) && !blank(invoice.getWorkEndDate())) {
      xml.open("cac:InvoicePeriod");
      xml.leaf("cbc:StartDate", invoice.getWorkStartDate());
      xml.leaf("cbc:EndDate", invoice.getWorkEndDate());
      xml.close("cac:InvoicePeriod");
    }
    if (creditNote && cancelledInvoice != null) {
      xml.open("cac:BillingReference");
      xml.open("cac:InvoiceDocumentReference");
      xml.leaf("cbc:ID", cancelledInvoice.getDocumentNumber());
      xml.leaf("cbc:IssueDate", cancelledInvoice.getInvoiceDate());
      xml.close("cac:InvoiceDocumentReference");
      xml.close("cac:BillingReference");
    }
    writeSeller(xml, parties.seller());
    writeBuyer(xml, parties.buyer());
    if (!blank(invoice.getWorkStartDate()) && blank(invoice.getWorkEndDate())) {
      xml.open("cac:Delivery");
      xml.leaf("cbc:ActualDeliveryDate", invoice.getWorkStartDate());
      xml.close("cac:Delivery");
    }
    writePayment(xml, invoice, parties.seller(), creditNote);
    writeTaxTotal(xml, vat, treatment, invoice.getTaxExemptionReason());
    writeMonetaryTotal(xml, vat);
    writeLines(xml, items, treatment, creditNote);
    xml.close(root);
    return xml.toString();
  }

  private static void writeSeller(XmlWriter xml, Party seller) {
    xml.open("cac:AccountingSupplierParty");
    xml.open("cac:Party");
    xml.leaf("cbc:EndpointID", seller.email(), "schemeID=\"EM\"");
    writeAddress(xml, seller);
    writeTaxScheme(xml, seller.taxId(), true);
    xml.open("cac:PartyLegalEntity");
    xml.leaf("cbc:RegistrationName", seller.name());
    xml.close("cac:PartyLegalEntity");
    xml.open("cac:Contact");
    xml.leaf("cbc:Name", seller.contactName());
    xml.leaf("cbc:Telephone", seller.phone());
    xml.leaf("cbc:ElectronicMail", seller.email());
    xml.close("cac:Contact");
    xml.close("cac:Party");
    xml.close("cac:AccountingSupplierParty");
  }

  private static void writeBuyer(XmlWriter xml, Party buyer) {
    xml.open("cac:AccountingCustomerParty");
    xml.open("cac:Party");
    xml.leaf("cbc:EndpointID", buyer.email(), "schemeID=\"EM\"");
    writeAddress(xml, buyer);
    boolean vatId = isVatId(buyer.taxId());
    if (vatId) {
      writeTaxScheme(xml, buyer.taxId(), false);
    }
    xml.open("cac:PartyLegalEntity");
    xml.leaf("cbc:RegistrationName", buyer.name());
    if (!vatId && !blank(buyer.taxId())) {
      xml.leaf("cbc:CompanyID", buyer.taxId());
    }
    xml.close("cac:PartyLegalEntity");
    xml.close("cac:Party");
    xml.close("cac:AccountingCustomerParty");
  }

  private static void writeAddress(XmlWriter xml, Party party) {
    xml.open("cac:PostalAddress");
    if (!blank(party.street())) {
      xml.leaf("cbc:StreetName", party.street());
    }
    xml.leaf("cbc:CityName", party.city());
    xml.leaf("cbc:PostalZone", party.postalCode());
    xml.open("cac:Country");
    xml.leaf("cbc:IdentificationCode", party.countryCode());
    xml.close("cac:Country");
    xml.close("cac:PostalAddress");
  }

  /**
   * A VAT ID goes into the {@code VAT} scheme (BT-31 / BT-48); a German tax number of the seller
   * into the {@code FC} scheme (BT-32).
   */
  private static void writeTaxScheme(XmlWriter xml, String taxId, boolean seller) {
    if (blank(taxId)) {
      return;
    }
    boolean vatId = isVatId(taxId);
    if (!vatId && !seller) {
      return;
    }
    xml.open("cac:PartyTaxScheme");
    xml.leaf("cbc:CompanyID", vatId ? taxId.replace(" ", "") : taxId);
    xml.open("cac:TaxScheme");
    xml.leaf("cbc:ID", vatId ? "VAT" : "FC");
    xml.close("cac:TaxScheme");
    xml.close("cac:PartyTaxScheme");
  }

  private static void writePayment(
      XmlWriter xml, Invoice invoice, Party seller, boolean creditNote) {
    xml.open("cac:PaymentMeans");
    if (blank(seller.iban())) {
      // BR-DE-1 requires payment instructions; without an IBAN the means stay unspecified.
      xml.leaf("cbc:PaymentMeansCode", "1");
    } else {
      xml.leaf("cbc:PaymentMeansCode", "58");
      xml.open("cac:PayeeFinancialAccount");
      xml.leaf("cbc:ID", seller.iban().replace(" ", ""));
      xml.close("cac:PayeeFinancialAccount");
    }
    xml.close("cac:PaymentMeans");
    xml.open("cac:PaymentTerms");
    xml.leaf(
        "cbc:Note",
        creditNote
            ? "Gutschrift zur Stornierung, keine Zahlung fällig."
            : blank(invoice.getDueDate())
                ? "Zahlbar sofort ohne Abzug."
                : "Zahlbar bis " + invoice.getDueDate() + " ohne Abzug.");
    xml.close("cac:PaymentTerms");
  }

  private static void writeTaxTotal(
      XmlWriter xml, VatBreakdown vat, TaxTreatment treatment, String exemptionReason) {
    xml.open("cac:TaxTotal");
    xml.amount("cbc:TaxAmount", vat.tax());
    for (VatBreakdown.Group group : vat.groups()) {
      xml.open("cac:TaxSubtotal");
      xml.amount("cbc:TaxableAmount", group.net());
      xml.amount("cbc:TaxAmount", group.tax());
      writeTaxCategory(xml, "cac:TaxCategory", group.ratePercent(), treatment, exemptionReason);
      xml.close("cac:TaxSubtotal");
    }
    xml.close("cac:TaxTotal");
  }

  private static void writeTaxCategory(
      XmlWriter xml,
      String element,
      BigDecimal rate,
      TaxTreatment treatment,
      String exemptionReason) {
    xml.open(element);
    xml.leaf("cbc:ID", treatment.xrechnungCategory());
    xml.leaf("cbc:Percent", rate.setScale(2, RoundingMode.HALF_UP).toPlainString());
    if (!treatment.chargesVat() && "cac:TaxCategory".equals(element)) {
      // BR-E-10 / BR-AE-10: the exemption reason is required for these categories.
      xml.leaf("cbc:TaxExemptionReason", treatment.legalNote(exemptionReason));
    }
    xml.open("cac:TaxScheme");
    xml.leaf("cbc:ID", "VAT");
    xml.close("cac:TaxScheme");
    xml.close(element);
  }

  private static void writeMonetaryTotal(XmlWriter xml, VatBreakdown vat) {
    xml.open("cac:LegalMonetaryTotal");
    xml.amount("cbc:LineExtensionAmount", vat.net());
    xml.amount("cbc:TaxExclusiveAmount", vat.net());
    xml.amount("cbc:TaxInclusiveAmount", vat.gross());
    xml.amount("cbc:PayableAmount", vat.gross());
    xml.close("cac:LegalMonetaryTotal");
  }

  private static void writeLines(
      XmlWriter xml, List<InvoiceItem> items, TaxTreatment treatment, boolean creditNote) {
    List<InvoiceItem> priced = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    flatten(items, priced, notes);
    String lineElement = creditNote ? "cac:CreditNoteLine" : "cac:InvoiceLine";
    for (int i = 0; i < priced.size(); i++) {
      InvoiceItem item = priced.get(i);
      xml.open(lineElement);
      xml.leaf("cbc:ID", String.valueOf(i + 1));
      if (!notes.get(i).isEmpty()) {
        xml.leaf("cbc:Note", notes.get(i));
      }
      xml.leaf(
          creditNote ? "cbc:CreditedQuantity" : "cbc:InvoicedQuantity",
          item.getQuantity().stripTrailingZeros().toPlainString(),
          "unitCode=\"" + unitCode(item.getUnit()) + "\"");
      xml.amount("cbc:LineExtensionAmount", money(item.getTotalPrice()));
      xml.open("cac:Item");
      if (!blank(item.getItemDescription())) {
        xml.leaf("cbc:Description", item.getItemDescription());
      }
      xml.leaf("cbc:Name", blank(item.getItemName()) ? "Position " + (i + 1) : item.getItemName());
      BigDecimal rate =
          treatment.chargesVat()
              ? VatCalculator.rate(item.getTaxRatePercent())
              : BigDecimal.ZERO.setScale(2);
      writeTaxCategory(xml, "cac:ClassifiedTaxCategory", rate, treatment, "");
      xml.close("cac:Item");
      xml.open("cac:Price");
      xml.amount("cbc:PriceAmount", money(item.getUnitPrice()));
      xml.close("cac:Price");
      xml.close(lineElement);
    }
  }

  /**
   * Depth-first: priced items become lines; the text of {@code CUSTOM} notes is attached to the
   * nearest priced ancestor as its line note.
   */
  static void flatten(List<InvoiceItem> items, List<InvoiceItem> priced, List<String> notes) {
    if (items == null) {
      return;
    }
    for (InvoiceItem item : items) {
      boolean isNote = InvoiceItem.ItemType.CUSTOM.getValue().equalsIgnoreCase(item.getItemType());
      if (isNote) {
        if (!priced.isEmpty()) {
          int last = priced.size() - 1;
          String text = noteText(item);
          notes.set(last, notes.get(last).isEmpty() ? text : notes.get(last) + "\n" + text);
        }
      } else {
        priced.add(item);
        notes.add("");
      }
      flatten(item.getChildren(), priced, notes);
    }
  }

  static String noteText(InvoiceItem note) {
    String name = note.getItemName() == null ? "" : note.getItemName().trim();
    String description = note.getItemDescription() == null ? "" : note.getItemDescription().trim();
    if (name.isEmpty()) {
      return description;
    }
    return description.isEmpty() ? name : name + ": " + description;
  }

  /**
   * Maps a German unit to UN/ECE Recommendation 20; unknown units become {@code C62} ("one").
   *
   * @param unit the unit as entered, e.g. "Std", "Stk", "kg"
   * @return the code, e.g. {@code HUR}
   */
  static String unitCode(String unit) {
    String key = unit == null ? "" : unit.trim().toLowerCase(Locale.ROOT).replace(".", "");
    return switch (key) {
      case "h", "std", "stunde", "stunden", "hour", "hours" -> "HUR";
      case "min", "minute", "minuten" -> "MIN";
      case "tag", "tage", "day", "days" -> "DAY";
      case "woche", "wochen", "week" -> "WEE";
      case "monat", "monate", "month" -> "MON";
      case "jahr", "jahre", "year" -> "ANN";
      case "stk", "stück", "stueck", "piece", "pcs" -> "H87";
      case "kg" -> "KGM";
      case "g" -> "GRM";
      case "t" -> "TNE";
      case "m" -> "MTR";
      case "km" -> "KMT";
      case "m2", "m²", "qm" -> "MTK";
      case "m3", "m³", "cbm" -> "MTQ";
      case "l", "liter" -> "LTR";
      case "pauschal", "psch", "pausch" -> "LS";
      default -> "C62";
    };
  }

  static BigDecimal money(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP);
  }

  static boolean isVatId(String taxId) {
    return !blank(taxId) && VAT_ID.matcher(taxId.replace(" ", "")).matches();
  }

  private static void requireValue(List<String> missing, String value, String label) {
    if (blank(value)) {
      missing.add(label);
    }
  }

  static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
