# GDPdU description standard DTD

`gdpdu-01-09-2004.dtd` (version 1.5, 1 September 2004) is the DTD of the
"Beschreibungsstandard für die Datenträgerüberlassung" that German tax
auditors' software (e.g. IDEA) uses to import exported data. The GoBD export
(`GobdExportService`, `GobdIndex`) writes an `index.xml` that references it;
`GobdExportTest#indexXmlIsValidAgainstTheGdpduDtd` validates against this file.

- Source of this copy: <https://github.com/mozilla-it/intacct-to-gobd/blob/master/supporting_docs/gdpdu-01-09-2004.dtd>
  (byte-for-byte, SHA-256 `af3d4c5a19e991f2d8c53995bc708680bbd7ff9326fde539c55b7e2c63f848a2`).
  The standard itself is published by the German tax administration (BMF / BZSt).
- Do not edit the file. Its content model for `Media`
  (`Command*, Table*, Command*`) is ambiguous; strict validators such as
  `xmllint` report that as an error in the DTD, Java's parser accepts it.
