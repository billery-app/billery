# Verfahrensdokumentation (GoBD) – Billery

> **Zielgruppe:** Inhaber:in des Unternehmens, Steuerberatung, Betriebsprüfung.
> **Zweck:** Beschreibt, wie Billery Rechnungen erzeugt, unveränderbar
> aufbewahrt und für den Datenzugriff bereitstellt (GoBD Rz. 151 ff.).
> Teil 1 beschreibt die Software und gilt für jede Installation. Teil 2 listet, was
> das Unternehmen für seinen Betrieb selbst festhalten muss – erst beides zusammen
> ist die vollständige Verfahrensdokumentation.

Technische Details für Entwickler stehen in
[`architecture/data-model.md`](../architecture/data-model.md#invoice-lifecycle-gobd).

## Teil 1 – Software

### 1. Belegarten

| Belegart | Nummernkreis (Standard-Präfix) | Buchhaltungsrelevant |
|----------|-------------------------------|----------------------|
| Rechnung | `RE-JJJJ-NNN` | ja |
| Teilrechnung | `TR-JJJJ-NNN` | ja |
| Endrechnung | `SR-JJJJ-NNN` | ja |
| Stornorechnung | `ST-JJJJ-NNN` | ja |
| Angebot | `AN-JJJJ-NNN` | nein |
| Kostenvoranschlag | `KV-JJJJ-NNN` | nein |

Je Belegart und Jahr gibt es einen eigenen, lückenlos fortlaufenden Nummernkreis
(Tabelle `document_number_series`). Eine Nummer ist im ganzen System eindeutig.

### 2. Lebenszyklus einer Rechnung

1. **Entwurf** – frei änderbar und löschbar, noch **ohne** Rechnungsnummer. Ein
   gelöschter Entwurf hinterlässt daher keine Lücke im Nummernkreis.
2. **Ausstellen** – erst jetzt wird die nächste Nummer des Jahres des
   Rechnungsdatums vergeben. Vorher prüft das Programm die Pflichtangaben
   (Rechnungsdatum, Leistungsdatum, mindestens eine Position, Steuersätze 19 % / 7 %,
   Befreiungsgrund bzw. USt-IdNr. des Kunden bei steuerfreien bzw.
   Reverse-Charge-Rechnungen). Gleichzeitig werden das Rechnungsbild (HTML, aus dem
   das PDF erzeugt wird) sowie die Daten von Rechnungssteller und Kunde
   **eingefroren**. Schlägt einer dieser Schritte fehl, wird nichts ausgestellt
   und keine Nummer verbraucht.
3. **Unveränderbarkeit** – eine ausgestellte Rechnung kann weder geändert noch
   gelöscht noch neu gestaltet werden; das Programm lehnt jeden solchen Versuch ab.
   PDF und E-Rechnung werden immer aus den eingefrorenen Daten erzeugt; spätere
   Änderungen an Kunden, Firmendaten oder Vorlagen wirken sich nicht aus.
4. **Zahlungen** – jeder Zahlungseingang wird mit tatsächlichem Datum und Betrag
   erfasst; der Status (offen / teilweise bezahlt / bezahlt) ergibt sich daraus.
   Zahlungen werden nie gelöscht: eine Fehlbuchung wird durch eine Gegenbuchung
   mit negativem Betrag korrigiert.
5. **Korrektur** – eine fehlerhafte Rechnung wird storniert. Dabei entsteht eine
   **Stornorechnung** mit eigener Nummer, die auf die Originalrechnung verweist
   und deren Positionen, Steuersätze und Besteuerungsart übernimmt. Die
   Originalrechnung bleibt unverändert erhalten und erhält nur den Status
   „Storniert“. Die korrigierte Rechnung wird als neuer Entwurf angelegt und
   regulär ausgestellt.

### 3. Umsatzsteuer

- Jede Position trägt ihren Steuersatz (19 % oder 7 %); die Steuer wird je
  Steuersatz summiert und gerundet.
- Besteuerungsarten: Regelbesteuerung, Kleinunternehmer (§ 19 UStG, automatisch,
  wenn in den Firmendaten hinterlegt), Steuerschuldnerschaft des
  Leistungsempfängers (§ 13b UStG) und steuerfreie Leistungen (§ 4 UStG mit
  Angabe des Befreiungsgrunds). Der jeweils vorgeschriebene Hinweis wird auf die
  Rechnung gedruckt.
- Innergemeinschaftliche Lieferungen und Ausfuhren werden derzeit nicht
  unterstützt (Kunden haben kein Länderfeld).

### 4. Protokollierung

Jeder Schritt (Anlegen, Bearbeiten eines Entwurfs, Ausstellen, Zahlung,
Gegenbuchung, Storno) wird mit Zeitpunkt und Details im Änderungsprotokoll des
Belegs festgehalten (Tabelle `document_history`). Einträge ausgestellter Belege
werden nie geändert oder gelöscht. Die Desktop- und die selbst betriebene
Version kennen keine Benutzerkonten; ein ausführender Benutzer wird dort daher
nicht protokolliert.

In der SaaS-Version speichert jeder Eintrag zusätzlich den angemeldeten
Benutzer, der den Schritt ausgeführt hat; die Belegansicht zeigt seinen Namen
oder seine E-Mail-Adresse. Verwaltungsschritte eines Unternehmens (Einladen,
Annehmen und Zurückziehen von Einladungen, Rollenwechsel, Entfernen und
Verlassen von Mitgliedern, Anlegen und Schließen des Unternehmens) stehen mit
Zeitpunkt, ausführendem Benutzer und Betroffenem in einem eigenen Protokoll
(Einstellungen → Team & Zugriff, für Inhaber und Administratoren).

Die Anwendung kann Protokolleinträge nur anlegen und lesen, nicht ändern; das
setzt die Datenbank über Berechtigungen durch. Das ist **kein**
manipulationssicheres Protokoll: Der Betreiber der Datenbank kann Einträge
technisch ändern. Die Einträge werden so lange aufbewahrt wie die Daten des
Unternehmens und mit ihnen gelöscht.

### 5. E-Rechnung

Für ausgestellte Rechnungen und Stornorechnungen erzeugt das Programm eine
XRechnung 3.0 (UBL). Sie wird aus den beim Ausstellen eingefrorenen Daten
gebildet und ist daher bei jedem Abruf identisch. Die Erzeugung wird
automatisiert gegen den offiziellen KoSIT-Validator geprüft.

Das PDF einer ausgestellten Rechnung oder Stornorechnung ist eine
ZUGFeRD-Rechnung (Profil XRECHNUNG): ein PDF/A-3 mit eingebetteten Schriften,
in das dieselbe XRechnung in CII-Syntax als Datei `xrechnung.xml` eingebettet
ist. Das eingebettete XML ist die E-Rechnung, die sichtbare Seite ihre lesbare
Fassung; beide werden aus denselben eingefrorenen Daten erzeugt, und ein
automatischer Test vergleicht XML, XRechnung und Seite bei jeder Änderung.
Fehlen Pflichtangaben der E-Rechnung (z. B. die E-Mail-Adresse des Kunden),
enthält das PDF kein XML; das Programm nennt die fehlenden Angaben.

### 6. Datenzugriff (§ 147 Abs. 6 AO)

Unter *Einstellungen → Daten & Export* wird für einen frei wählbaren
Zeitraum ein ZIP-Archiv erzeugt mit:

- `belege.csv`, `positionen.csv`, `zahlungen.csv`, `historie.csv`, `kunden.csv`
  (Semikolon-getrennt, UTF-8, Dezimalkomma, Datum `TT.MM.JJJJ`),
- `index.xml` mit der Tabellenbeschreibung nach dem GDPdU-Beschreibungsstandard
  (automatisiert gegen die DTD `gdpdu-01-09-2004.dtd` geprüft), über die
  Prüfsoftware die CSV-Dateien einliest,
- optional dem PDF und der XRechnung jedes Belegs.

Exportiert werden alle ausgestellten Rechnungen und Stornorechnungen, deren
Belegdatum im Zeitraum liegt.

Ein Export ist durch Betriebsgrenzen beschränkt, damit er den Server nicht
überlastet: standardmäßig höchstens 3 660 Tage, 10 000 Belege (2 000, wenn PDF und
XRechnung mit erzeugt werden), 128 MB Archivgröße und ein gleichzeitiger Export.
Wird eine Grenze überschritten, lehnt das Programm den Export mit einer Meldung ab;
es kürzt ihn **nie** stillschweigend. In dem Fall ist der Zeitraum auf mehrere
Exporte aufzuteilen oder ohne PDF und XRechnung zu exportieren. Der Betreiber kann
die Grenzen anpassen (Variablen `EXPORT_*`, siehe
[`../reference/env-vars.md`](../reference/env-vars.md#gobd-export-limits)).

### 7. Speicherung

Alle Daten liegen in einer relationalen Datenbank (PostgreSQL oder, in der
Desktop-Version, eine H2-Datei im Anwendungsdatenverzeichnis). Kunden mit Belegen
können nicht gelöscht werden; die Datenbank verhindert das zusätzlich.

## Teil 2 – Vom Unternehmen festzuhalten

Diese Angaben hängen vom Betrieb ab und können nicht von der Software
vorgegeben werden. Bitte als Anhang zu dieser Dokumentation führen:

- Wer das Programm bedient und wer Rechnungen ausstellen darf.
- Installationsart (Desktop oder Server), Rechner bzw. Server, eingesetzte Version.
- Datensicherung: Was wird wie oft wohin gesichert (bei der Desktop-Version die
  Datenbankdatei im Anwendungsdatenverzeichnis), wie wird die Wiederherstellung
  geprüft.
- Zugriffsschutz: Wer hat Zugang zum Rechner bzw. Server und zur Datenbank.
  Direkte Änderungen an der Datenbank außerhalb des Programms sind nicht zulässig.
- Aufbewahrung: Rechnungen 8 Jahre (§ 147 Abs. 3 AO), Aufbewahrungsort der
  regelmäßigen GoBD-Exporte.
- Versand: Auf welchem Weg Rechnungen und E-Rechnungen an Kunden gehen und wo
  gesendete E-Rechnungen abgelegt werden.
- Bei Programmwechsel oder Updates: Datum und Version, Ergebnis des
  Funktionstests.

Diese Dokumentation ersetzt keine steuerliche Beratung; sie sollte mit der
Steuerberatung abgestimmt werden.
