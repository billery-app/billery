package de.billery.pdf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;

/**
 * Turns a PDF/A-3 into a ZUGFeRD 2.x / Factur-X invoice: attaches the e-invoice XML and declares it
 * in the XMP metadata, so people read the PDF and software reads the XML.
 *
 * <p>The XML is an XRechnung in CII syntax ({@code xmlgenerator.CiiGenerator}), so the profile is
 * {@code XRECHNUNG} and the attachment is named {@code xrechnung.xml}, as the ZUGFeRD specification
 * requires for that profile. What PDF/A-3 (ISO 19005-3) and ZUGFeRD ask of the attachment:
 *
 * <ul>
 *   <li>a file specification with {@code /F} and {@code /UF}, {@code /AFRelationship /Alternative}
 *       (the XML is an alternative representation of the visible invoice) and a description,
 *   <li>the embedded file with its MIME type ({@code /Subtype /text#2Fxml}) and {@code /Params}
 *       (size, dates),
 *   <li>the file specification in the catalog's {@code /AF} array (associated file) and in the
 *       {@code /EmbeddedFiles} name tree,
 *   <li>XMP: the {@code fx} properties (document type, file name, version, conformance level) and
 *       their PDF/A extension schema description; the existing XMP (PDF/A identification, dates)
 *       stays as it is.
 * </ul>
 *
 * The input must already be PDF/A-3 (the renderer produces it); veraPDF checks the result in CI.
 */
public final class FacturXEmbedder {

  /** File name of the attachment for the profile XRECHNUNG. */
  public static final String FILE_NAME = "xrechnung.xml";

  /** ZUGFeRD / Factur-X conformance level of the embedded XML. */
  public static final String CONFORMANCE_LEVEL = "XRECHNUNG";

  private static final String FX_NAMESPACE = "urn:factur-x:pdfa:CrossIndustryDocument:invoice:1p0#";

  private FacturXEmbedder() {}

  /**
   * Attaches the XML.
   *
   * @param pdfA a PDF/A-3 document
   * @param xml the CII XML, UTF-8
   * @param invoiceDate the invoice date: the dates of the attachment, so the file is the same on
   *     every download
   * @return the ZUGFeRD PDF
   * @throws IOException if the PDF cannot be read or written
   */
  public static byte[] embed(byte[] pdfA, String xml, LocalDate invoiceDate) throws IOException {
    byte[] content = xml.getBytes(StandardCharsets.UTF_8);
    try (PDDocument document = Loader.loadPDF(pdfA)) {
      PDDocumentCatalog catalog = document.getDocumentCatalog();

      PDEmbeddedFile embedded = new PDEmbeddedFile(document, new ByteArrayInputStream(content));
      embedded.setSubtype("text/xml");
      embedded.setSize(content.length);
      embedded.setCreationDate(calendar(invoiceDate));
      embedded.setModDate(calendar(invoiceDate));

      PDComplexFileSpecification spec = new PDComplexFileSpecification();
      spec.setFile(FILE_NAME);
      spec.setFileUnicode(FILE_NAME);
      spec.setFileDescription("XRechnung (ZUGFeRD / Factur-X)");
      spec.setEmbeddedFile(embedded);
      spec.setEmbeddedFileUnicode(embedded);
      spec.getCOSObject().setName(COSName.getPDFName("AFRelationship"), "Alternative");

      PDEmbeddedFilesNameTreeNode files = new PDEmbeddedFilesNameTreeNode();
      files.setNames(Map.of(FILE_NAME, spec));
      PDDocumentNameDictionary names = catalog.getNames();
      if (names == null) {
        names = new PDDocumentNameDictionary(catalog);
      }
      names.setEmbeddedFiles(files);
      catalog.setNames(names);

      COSArray associated = new COSArray();
      associated.add(spec);
      catalog.getCOSObject().setItem(COSName.getPDFName("AF"), associated);

      PDMetadata metadata = catalog.getMetadata();
      if (metadata == null) {
        throw new IOException("The PDF has no XMP metadata, so it is not PDF/A");
      }
      String xmp;
      try (InputStream in = metadata.createInputStream()) {
        xmp = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      PDMetadata updated =
          new PDMetadata(
              document,
              new ByteArrayInputStream(withFacturX(xmp).getBytes(StandardCharsets.UTF_8)));
      catalog.setMetadata(updated);

      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      return out.toByteArray();
    }
  }

  /** PDFBox takes the attachment's dates as a Calendar: midnight UTC of the date. */
  private static Calendar calendar(LocalDate date) {
    return GregorianCalendar.from(date.atStartOfDay(ZoneOffset.UTC));
  }

  /**
   * Adds the Factur-X properties and the extension schema that describes them to an XMP packet.
   *
   * @param xmp the packet written by the renderer
   * @return the packet with the Factur-X description before {@code </rdf:RDF>}
   * @throws IOException if the packet has no {@code rdf:RDF} element
   */
  static String withFacturX(String xmp) throws IOException {
    int end = xmp.lastIndexOf("</rdf:RDF>");
    if (end < 0) {
      throw new IOException("Unexpected XMP metadata (no rdf:RDF)");
    }
    String facturX =
        "<rdf:Description rdf:about=\"\" xmlns:fx=\""
            + FX_NAMESPACE
            + "\">"
            + "<fx:DocumentType>INVOICE</fx:DocumentType>"
            + "<fx:DocumentFileName>"
            + FILE_NAME
            + "</fx:DocumentFileName>"
            + "<fx:Version>1.0</fx:Version>"
            + "<fx:ConformanceLevel>"
            + CONFORMANCE_LEVEL
            + "</fx:ConformanceLevel>"
            + "</rdf:Description>\n"
            + "<rdf:Description rdf:about=\"\""
            + " xmlns:pdfaExtension=\"http://www.aiim.org/pdfa/ns/extension/\""
            + " xmlns:pdfaSchema=\"http://www.aiim.org/pdfa/ns/schema#\""
            + " xmlns:pdfaProperty=\"http://www.aiim.org/pdfa/ns/property#\">"
            + "<pdfaExtension:schemas><rdf:Bag><rdf:li rdf:parseType=\"Resource\">"
            + "<pdfaSchema:schema>Factur-X PDFA Extension Schema</pdfaSchema:schema>"
            + "<pdfaSchema:namespaceURI>"
            + FX_NAMESPACE
            + "</pdfaSchema:namespaceURI>"
            + "<pdfaSchema:prefix>fx</pdfaSchema:prefix>"
            + "<pdfaSchema:property><rdf:Seq>"
            + property("DocumentFileName", "The name of the embedded XML document")
            + property("DocumentType", "The type of the hybrid document in capital letters")
            + property("Version", "The actual version of the standard applying to the XML")
            + property("ConformanceLevel", "The conformance level of the embedded XML")
            + "</rdf:Seq></pdfaSchema:property>"
            + "</rdf:li></rdf:Bag></pdfaExtension:schemas>"
            + "</rdf:Description>\n";
    return xmp.substring(0, end) + facturX + xmp.substring(end);
  }

  private static String property(String name, String description) {
    return "<rdf:li rdf:parseType=\"Resource\">"
        + "<pdfaProperty:name>"
        + name
        + "</pdfaProperty:name>"
        + "<pdfaProperty:valueType>Text</pdfaProperty:valueType>"
        + "<pdfaProperty:category>external</pdfaProperty:category>"
        + "<pdfaProperty:description>"
        + description
        + "</pdfaProperty:description>"
        + "</rdf:li>";
  }
}
