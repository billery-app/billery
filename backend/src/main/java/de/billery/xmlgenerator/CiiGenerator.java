package de.billery.xmlgenerator;

import static de.billery.xmlgenerator.XRechnungGenerator.blank;
import static de.billery.xmlgenerator.XRechnungGenerator.isVatId;
import static de.billery.xmlgenerator.XRechnungGenerator.money;

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

/**
 * Writes the same XRechnung 3.0 as {@link XRechnungGenerator}, but in the UN/CEFACT Cross Industry
 * Invoice syntax (CII, D16B): the XML embedded in the ZUGFeRD / Factur-X PDF (profile XRECHNUNG),
 * see {@code pdf.FacturXEmbedder}.
 *
 * <p>Both generators take the same input and the same rules: amounts from {@link VatCalculator},
 * sub-items flattened, {@code CUSTOM} notes as line notes, a credit note as type 381 with a
 * reference to the cancelled invoice, the required data checked by {@link
 * XRechnungGenerator#missingFields}. {@code ZugferdTest} compares the two documents field by field.
 *
 * <p>The element order is fixed by the CII schema; the KoSIT validator (scenario "EN16931 XRechnung
 * (CII)") checks it in the {@code xrechnung} CI job.
 */
public class CiiGenerator {

  private static final String NS_RSM =
      "urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100";
  private static final String NS_RAM =
      "urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100";
  private static final String NS_UDT =
      "urn:un:unece:uncefact:data:standard:UnqualifiedDataType:100";
  private static final String NS_QDT = "urn:un:unece:uncefact:data:standard:QualifiedDataType:100";
  private static final String PROFILE_ID = "urn:fdc:peppol.eu:2017:poacc:billing:01:1.0";
  private static final String DATE_FORMAT = "format=\"102\"";

  /**
   * Generates the CII XML.
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

    XmlWriter xml = new XmlWriter();
    xml.raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
    xml.open(
        "rsm:CrossIndustryInvoice",
        "xmlns:rsm=\""
            + NS_RSM
            + "\" xmlns:ram=\""
            + NS_RAM
            + "\" xmlns:udt=\""
            + NS_UDT
            + "\" xmlns:qdt=\""
            + NS_QDT
            + "\"");

    xml.open("rsm:ExchangedDocumentContext");
    xml.open("ram:BusinessProcessSpecifiedDocumentContextParameter");
    xml.leaf("ram:ID", PROFILE_ID);
    xml.close("ram:BusinessProcessSpecifiedDocumentContextParameter");
    xml.open("ram:GuidelineSpecifiedDocumentContextParameter");
    xml.leaf("ram:ID", XRechnungGenerator.CUSTOMIZATION_ID);
    xml.close("ram:GuidelineSpecifiedDocumentContextParameter");
    xml.close("rsm:ExchangedDocumentContext");

    xml.open("rsm:ExchangedDocument");
    xml.leaf("ram:ID", invoice.getDocumentNumber());
    xml.leaf("ram:TypeCode", creditNote ? "381" : "380");
    date(xml, "ram:IssueDateTime", "udt", invoice.getInvoiceDate());
    if (!blank(invoice.getNotes())) {
      xml.open("ram:IncludedNote");
      xml.leaf("ram:Content", invoice.getNotes());
      xml.close("ram:IncludedNote");
    }
    xml.close("rsm:ExchangedDocument");

    xml.open("rsm:SupplyChainTradeTransaction");
    writeLines(xml, items, treatment);
    writeAgreement(xml, invoice, parties);
    xml.open("ram:ApplicableHeaderTradeDelivery");
    if (!blank(invoice.getWorkStartDate()) && blank(invoice.getWorkEndDate())) {
      xml.open("ram:ActualDeliverySupplyChainEvent");
      date(xml, "ram:OccurrenceDateTime", "udt", invoice.getWorkStartDate());
      xml.close("ram:ActualDeliverySupplyChainEvent");
    }
    xml.close("ram:ApplicableHeaderTradeDelivery");
    writeSettlement(xml, invoice, parties.seller(), vat, treatment, creditNote, cancelledInvoice);
    xml.close("rsm:SupplyChainTradeTransaction");
    xml.close("rsm:CrossIndustryInvoice");
    return xml.toString();
  }

  private static void writeLines(XmlWriter xml, List<InvoiceItem> items, TaxTreatment treatment) {
    List<InvoiceItem> priced = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    XRechnungGenerator.flatten(items, priced, notes);
    for (int i = 0; i < priced.size(); i++) {
      InvoiceItem item = priced.get(i);
      xml.open("ram:IncludedSupplyChainTradeLineItem");
      xml.open("ram:AssociatedDocumentLineDocument");
      xml.leaf("ram:LineID", String.valueOf(i + 1));
      if (!notes.get(i).isEmpty()) {
        xml.open("ram:IncludedNote");
        xml.leaf("ram:Content", notes.get(i));
        xml.close("ram:IncludedNote");
      }
      xml.close("ram:AssociatedDocumentLineDocument");
      xml.open("ram:SpecifiedTradeProduct");
      xml.leaf("ram:Name", blank(item.getItemName()) ? "Position " + (i + 1) : item.getItemName());
      if (!blank(item.getItemDescription())) {
        xml.leaf("ram:Description", item.getItemDescription());
      }
      xml.close("ram:SpecifiedTradeProduct");
      xml.open("ram:SpecifiedLineTradeAgreement");
      xml.open("ram:NetPriceProductTradePrice");
      xml.plainAmount("ram:ChargeAmount", money(item.getUnitPrice()));
      xml.close("ram:NetPriceProductTradePrice");
      xml.close("ram:SpecifiedLineTradeAgreement");
      xml.open("ram:SpecifiedLineTradeDelivery");
      xml.leaf(
          "ram:BilledQuantity",
          item.getQuantity().stripTrailingZeros().toPlainString(),
          "unitCode=\"" + XRechnungGenerator.unitCode(item.getUnit()) + "\"");
      xml.close("ram:SpecifiedLineTradeDelivery");
      xml.open("ram:SpecifiedLineTradeSettlement");
      BigDecimal rate =
          treatment.chargesVat()
              ? VatCalculator.rate(item.getTaxRatePercent())
              : BigDecimal.ZERO.setScale(2);
      xml.open("ram:ApplicableTradeTax");
      xml.leaf("ram:TypeCode", "VAT");
      xml.leaf("ram:CategoryCode", treatment.xrechnungCategory());
      xml.leaf("ram:RateApplicablePercent", percent(rate));
      xml.close("ram:ApplicableTradeTax");
      xml.open("ram:SpecifiedTradeSettlementLineMonetarySummation");
      xml.plainAmount("ram:LineTotalAmount", money(item.getTotalPrice()));
      xml.close("ram:SpecifiedTradeSettlementLineMonetarySummation");
      xml.close("ram:SpecifiedLineTradeSettlement");
      xml.close("ram:IncludedSupplyChainTradeLineItem");
    }
  }

  private static void writeAgreement(XmlWriter xml, Invoice invoice, PartySnapshot parties) {
    xml.open("ram:ApplicableHeaderTradeAgreement");
    xml.leaf(
        "ram:BuyerReference",
        blank(parties.buyer().buyerReference())
            ? invoice.getDocumentNumber()
            : parties.buyer().buyerReference());
    writeSeller(xml, parties.seller());
    writeBuyer(xml, parties.buyer());
    xml.close("ram:ApplicableHeaderTradeAgreement");
  }

  private static void writeSeller(XmlWriter xml, Party seller) {
    xml.open("ram:SellerTradeParty");
    xml.leaf("ram:Name", seller.name());
    xml.open("ram:DefinedTradeContact");
    xml.leaf("ram:PersonName", seller.contactName());
    xml.open("ram:TelephoneUniversalCommunication");
    xml.leaf("ram:CompleteNumber", seller.phone());
    xml.close("ram:TelephoneUniversalCommunication");
    xml.open("ram:EmailURIUniversalCommunication");
    xml.leaf("ram:URIID", seller.email());
    xml.close("ram:EmailURIUniversalCommunication");
    xml.close("ram:DefinedTradeContact");
    writeAddress(xml, seller);
    writeElectronicAddress(xml, seller);
    if (!blank(seller.taxId())) {
      boolean vatId = isVatId(seller.taxId());
      xml.open("ram:SpecifiedTaxRegistration");
      xml.leaf(
          "ram:ID",
          vatId ? seller.taxId().replace(" ", "") : seller.taxId(),
          "schemeID=\"" + (vatId ? "VA" : "FC") + "\"");
      xml.close("ram:SpecifiedTaxRegistration");
    }
    xml.close("ram:SellerTradeParty");
  }

  private static void writeBuyer(XmlWriter xml, Party buyer) {
    boolean vatId = isVatId(buyer.taxId());
    xml.open("ram:BuyerTradeParty");
    xml.leaf("ram:Name", buyer.name());
    if (!vatId && !blank(buyer.taxId())) {
      xml.open("ram:SpecifiedLegalOrganization");
      xml.leaf("ram:ID", buyer.taxId());
      xml.close("ram:SpecifiedLegalOrganization");
    }
    writeAddress(xml, buyer);
    writeElectronicAddress(xml, buyer);
    if (vatId) {
      xml.open("ram:SpecifiedTaxRegistration");
      xml.leaf("ram:ID", buyer.taxId().replace(" ", ""), "schemeID=\"VA\"");
      xml.close("ram:SpecifiedTaxRegistration");
    }
    xml.close("ram:BuyerTradeParty");
  }

  private static void writeAddress(XmlWriter xml, Party party) {
    xml.open("ram:PostalTradeAddress");
    xml.leaf("ram:PostcodeCode", party.postalCode());
    if (!blank(party.street())) {
      xml.leaf("ram:LineOne", party.street());
    }
    xml.leaf("ram:CityName", party.city());
    xml.leaf("ram:CountryID", party.countryCode());
    xml.close("ram:PostalTradeAddress");
  }

  /** BT-34 / BT-49: the e-mail address as electronic address (scheme EM), as in the UBL. */
  private static void writeElectronicAddress(XmlWriter xml, Party party) {
    xml.open("ram:URIUniversalCommunication");
    xml.leaf("ram:URIID", party.email(), "schemeID=\"EM\"");
    xml.close("ram:URIUniversalCommunication");
  }

  private static void writeSettlement(
      XmlWriter xml,
      Invoice invoice,
      Party seller,
      VatBreakdown vat,
      TaxTreatment treatment,
      boolean creditNote,
      Invoice cancelledInvoice) {
    xml.open("ram:ApplicableHeaderTradeSettlement");
    xml.leaf("ram:InvoiceCurrencyCode", "EUR");
    xml.open("ram:SpecifiedTradeSettlementPaymentMeans");
    if (blank(seller.iban())) {
      // BR-DE-1 requires payment instructions; without an IBAN the means stay unspecified.
      xml.leaf("ram:TypeCode", "1");
    } else {
      xml.leaf("ram:TypeCode", "58");
      xml.open("ram:PayeePartyCreditorFinancialAccount");
      xml.leaf("ram:IBANID", seller.iban().replace(" ", ""));
      xml.close("ram:PayeePartyCreditorFinancialAccount");
    }
    xml.close("ram:SpecifiedTradeSettlementPaymentMeans");
    for (VatBreakdown.Group group : vat.groups()) {
      xml.open("ram:ApplicableTradeTax");
      xml.plainAmount("ram:CalculatedAmount", group.tax());
      xml.leaf("ram:TypeCode", "VAT");
      if (!treatment.chargesVat()) {
        // BR-E-10 / BR-AE-10: the exemption reason is required for these categories.
        xml.leaf("ram:ExemptionReason", treatment.legalNote(invoice.getTaxExemptionReason()));
      }
      xml.plainAmount("ram:BasisAmount", group.net());
      xml.leaf("ram:CategoryCode", treatment.xrechnungCategory());
      xml.leaf("ram:RateApplicablePercent", percent(group.ratePercent()));
      xml.close("ram:ApplicableTradeTax");
    }
    if (!blank(invoice.getWorkStartDate()) && !blank(invoice.getWorkEndDate())) {
      xml.open("ram:BillingSpecifiedPeriod");
      date(xml, "ram:StartDateTime", "udt", invoice.getWorkStartDate());
      date(xml, "ram:EndDateTime", "udt", invoice.getWorkEndDate());
      xml.close("ram:BillingSpecifiedPeriod");
    }
    xml.open("ram:SpecifiedTradePaymentTerms");
    xml.leaf(
        "ram:Description",
        creditNote
            ? "Gutschrift zur Stornierung, keine Zahlung fällig."
            : blank(invoice.getDueDate())
                ? "Zahlbar sofort ohne Abzug."
                : "Zahlbar bis " + invoice.getDueDate() + " ohne Abzug.");
    if (!creditNote && !blank(invoice.getDueDate())) {
      date(xml, "ram:DueDateDateTime", "udt", invoice.getDueDate());
    }
    xml.close("ram:SpecifiedTradePaymentTerms");
    xml.open("ram:SpecifiedTradeSettlementHeaderMonetarySummation");
    xml.plainAmount("ram:LineTotalAmount", vat.net());
    xml.plainAmount("ram:TaxBasisTotalAmount", vat.net());
    xml.amount("ram:TaxTotalAmount", vat.tax());
    xml.plainAmount("ram:GrandTotalAmount", vat.gross());
    xml.plainAmount("ram:DuePayableAmount", vat.gross());
    xml.close("ram:SpecifiedTradeSettlementHeaderMonetarySummation");
    if (creditNote && cancelledInvoice != null) {
      xml.open("ram:InvoiceReferencedDocument");
      xml.leaf("ram:IssuerAssignedID", cancelledInvoice.getDocumentNumber());
      date(xml, "ram:FormattedIssueDateTime", "qdt", cancelledInvoice.getInvoiceDate());
      xml.close("ram:InvoiceReferencedDocument");
    }
    xml.close("ram:ApplicableHeaderTradeSettlement");
  }

  /** A date as CII writes it: {@code <udt:DateTimeString format="102">20261231</...>}. */
  private static void date(XmlWriter xml, String element, String prefix, String isoDate) {
    xml.open(element);
    xml.leaf(prefix + ":DateTimeString", isoDate.replace("-", ""), DATE_FORMAT);
    xml.close(element);
  }

  private static String percent(BigDecimal rate) {
    return rate.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }
}
