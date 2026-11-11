package de.billery.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Writes the {@code index.xml} of the GoBD export: the table description of the GDPdU description
 * standard (DTD {@code gdpdu-01-09-2004.dtd}), which audit software (e.g. IDEA) uses to import the
 * CSV files. The column lists here must match the CSV headers written by {@link GobdExportService}
 * column for column; primary-key columns come first, as the standard requires.
 */
final class GobdIndex {

  private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  private static final List<Table> TABLES =
      List.of(
          new Table(
              "belege.csv",
              "Belege",
              "Ausgestellte Rechnungen und Stornorechnungen",
              1,
              List.of(
                  new Column("Beleg_ID", 'N'),
                  new Column("Belegart", 'A'),
                  new Column("Belegnummer", 'A'),
                  new Column("Status", 'A'),
                  new Column("Belegdatum", 'D'),
                  new Column("Faellig", 'D'),
                  new Column("Leistungsbeginn", 'D'),
                  new Column("Leistungsende", 'D'),
                  new Column("Kunden_ID", 'N'),
                  new Column("Netto", 'M'),
                  new Column("Steuer", 'M'),
                  new Column("Brutto", 'M'),
                  new Column("Bezahlt", 'M'),
                  new Column("Besteuerung", 'A'),
                  new Column("Befreiungsgrund", 'A'),
                  new Column("Storno_zu", 'A'))),
          new Table(
              "positionen.csv",
              "Positionen",
              "Positionen der Belege (Beträge der Stornorechnungen positiv)",
              2,
              List.of(
                  new Column("Belegnummer", 'A'),
                  new Column("Position", 'N'),
                  new Column("Uebergeordnete_Position", 'A'),
                  new Column("Art", 'A'),
                  new Column("Bezeichnung", 'A'),
                  new Column("Beschreibung", 'A'),
                  new Column("Menge", 'M'),
                  new Column("Einheit", 'A'),
                  new Column("Einzelpreis", 'M'),
                  new Column("Netto", 'M'),
                  new Column("Steuersatz", 'M'))),
          new Table(
              "zahlungen.csv",
              "Zahlungen",
              "Zahlungseingänge; Stornobuchungen mit negativem Betrag",
              1,
              List.of(
                  new Column("Zahlung_ID", 'N'),
                  new Column("Belegnummer", 'A'),
                  new Column("Datum", 'D'),
                  new Column("Betrag", 'M'),
                  new Column("Zahlungsart", 'A'),
                  new Column("Referenz", 'A'),
                  new Column("Notiz", 'A'),
                  new Column("Storno_von_Zahlung", 'A'))),
          new Table(
              "historie.csv",
              "Historie",
              "Änderungsprotokoll der Belege",
              1,
              List.of(
                  new Column("Eintrag_ID", 'N'),
                  new Column("Belegnummer", 'A'),
                  new Column("Zeitpunkt", 'A'),
                  new Column("Aktion", 'A'),
                  new Column("Details", 'A'))),
          new Table(
              "kunden.csv",
              "Kunden",
              "Kunden der exportierten Belege",
              1,
              List.of(
                  new Column("Kunden_ID", 'N'),
                  new Column("Vorname", 'A'),
                  new Column("Nachname", 'A'),
                  new Column("Strasse", 'A'),
                  new Column("Hausnummer", 'A'),
                  new Column("PLZ", 'A'),
                  new Column("Ort", 'A'),
                  new Column("E_Mail", 'A'),
                  new Column("Steuernummer_USt_IdNr", 'A'),
                  new Column("Kaeuferreferenz", 'A'))));

  private GobdIndex() {}

  /**
   * Builds the index for an export period.
   *
   * @param from first document date
   * @param to last document date
   * @return the XML text
   */
  static String xml(LocalDate from, LocalDate to) {
    StringBuilder x = new StringBuilder();
    x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        .append("<!DOCTYPE DataSet SYSTEM \"gdpdu-01-09-2004.dtd\">\n")
        .append("<DataSet>\n")
        .append("  <Version>1.0</Version>\n")
        .append("  <DataSupplier>\n")
        .append("    <Name>Billery</Name>\n")
        .append("    <Location>Export aus der Rechnungsverwaltung</Location>\n")
        .append("    <Comment>GoBD-Datenexport (Belege ")
        .append(from.format(GERMAN_DATE))
        .append(" bis ")
        .append(to.format(GERMAN_DATE))
        .append(")</Comment>\n")
        .append("  </DataSupplier>\n")
        .append("  <Media>\n")
        .append("    <Name>GoBD-Export</Name>\n");
    for (Table table : TABLES) {
      appendTable(x, table, from, to);
    }
    x.append("  </Media>\n").append("</DataSet>\n");
    return x.toString();
  }

  private static void appendTable(StringBuilder x, Table table, LocalDate from, LocalDate to) {
    x.append("    <Table>\n")
        .append("      <URL>")
        .append(table.file())
        .append("</URL>\n")
        .append("      <Name>")
        .append(table.name())
        .append("</Name>\n")
        .append("      <Description>")
        .append(table.description())
        .append("</Description>\n")
        .append("      <Validity>\n")
        .append("        <Range><From>")
        .append(from.format(GERMAN_DATE))
        .append("</From><To>")
        .append(to.format(GERMAN_DATE))
        .append("</To></Range>\n")
        .append("        <Format>DD.MM.YYYY</Format>\n")
        .append("      </Validity>\n")
        .append("      <UTF8/>\n")
        .append("      <DecimalSymbol>,</DecimalSymbol>\n")
        .append("      <DigitGroupingSymbol>.</DigitGroupingSymbol>\n")
        // Line 1 is the header row.
        .append("      <Range><From>2</From></Range>\n")
        .append("      <VariableLength>\n")
        .append("        <ColumnDelimiter>;</ColumnDelimiter>\n")
        .append("        <RecordDelimiter>&#13;&#10;</RecordDelimiter>\n")
        .append("        <TextEncapsulator>\"</TextEncapsulator>\n");
    for (int i = 0; i < table.columns().size(); i++) {
      Column column = table.columns().get(i);
      String element = i < table.keys() ? "VariablePrimaryKey" : "VariableColumn";
      x.append("        <")
          .append(element)
          .append("><Name>")
          .append(column.name())
          .append("</Name>")
          .append(typeElement(column.type()))
          .append("</")
          .append(element)
          .append(">\n");
    }
    x.append("      </VariableLength>\n").append("    </Table>\n");
  }

  private static String typeElement(char type) {
    return switch (type) {
      case 'N' -> "<Numeric/>";
      case 'M' -> "<Numeric><Accuracy>2</Accuracy></Numeric>";
      case 'D' -> "<Date><Format>DD.MM.YYYY</Format></Date>";
      default -> "<AlphaNumeric/>";
    };
  }

  /**
   * Column type: {@code A} alphanumeric, {@code N} integer, {@code M} money (2 decimals), {@code D}
   * date.
   */
  private record Column(String name, char type) {}

  private record Table(
      String file, String name, String description, int keys, List<Column> columns) {}
}
