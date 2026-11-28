package de.billery.api.error;

/**
 * The messages of the core API with their codes (#15). The German template is the text of the error
 * answer; the SPA shows {@code errors.<code>} from its message files in the interface language. A
 * code never changes once released; a new message gets a new constant here and an entry in {@code
 * de.json} / {@code en.json} ({@code errorCodes.spec.ts} checks both).
 */
// the constant names say what the message says, like the codes; short names would hide that
@SuppressWarnings("PMD.LongVariable")
public enum ErrorCode implements MessageCode {
  BACKUP_DATABASE_UNREADABLE(
      "backup.databaseUnreadable",
      "Die Datenbank in dieser Sicherung lässt sich nicht öffnen; sie wird nicht wiederhergestellt."),
  BACKUP_FOLDER_CREATE("backup.folderCreate", "Der Ordner kann nicht angelegt werden: {folder}"),
  BACKUP_FOLDER_NOT_WRITABLE(
      "backup.folderNotWritable", "In diesen Ordner kann nicht geschrieben werden: {folder}"),
  BACKUP_FOLDER_RELATIVE("backup.folderRelative", "Bitte einen vollständigen Ordnerpfad angeben."),
  BACKUP_GONE("backup.gone", "Die Sicherung gibt es nicht mehr."),
  BACKUP_INVALID("backup.invalid", "Die Datei ist keine gültige Billery-Sicherung."),
  BACKUP_KEEP_RANGE(
      "backup.keepRange",
      "Die Anzahl der aufbewahrten Sicherungen muss zwischen 1 und {max} liegen."),
  BACKUP_PASSWORD_NEEDED(
      "backup.passwordNeeded", "Diese Sicherung ist verschlüsselt: bitte das Passwort angeben."),
  BACKUP_PASSWORD_SHORT(
      "backup.passwordShort", "Das Passwort muss mindestens {min} Zeichen haben."),
  BACKUP_PASSWORD_WRONG(
      "backup.passwordWrong", "Das Passwort ist falsch, oder die Sicherung ist beschädigt."),
  BACKUP_RESTORE_LOSS(
      "backup.restoreLoss",
      "Seit dieser Sicherung wurden {issued} Belege ausgestellt oder storniert, {payments} Zahlungen erfasst, {changes} Änderungen an Dokumenten gemacht und {files} hochgeladene Dateien (Logos, Anhänge) neu angelegt oder geändert. Sie fehlen nach der Wiederherstellung im Programm; erhalten bleiben sie in der Sicherheitskopie, die vorher angelegt und nicht automatisch gelöscht wird und wie alle Rechnungen aufzubewahren ist (§ 147 AO)."),
  BACKUP_RESTORE_PENDING(
      "backup.restorePending",
      "Eine Wiederherstellung ist vorgemerkt: bis zum Neustart wird nichts mehr gespeichert. Bitte Billery beenden und neu starten, oder die Wiederherstellung unter Einstellungen → Datensicherung abbrechen."),
  BACKUP_RESTORE_STAGED(
      "backup.restoreStaged",
      "Die Sicherung wird beim nächsten Start von Billery wiederhergestellt. Der jetzige Stand wird vorher gesichert."),
  BACKUP_TOO_LARGE(
      "backup.tooLarge",
      "Die Daten sind zu groß für eine Sicherung (höchstens {max} Dateien und 4 GB). Bitte nicht mehr benötigte Logos und Anhänge entfernen."),
  BACKUP_UNKNOWN("backup.unknown", "Unbekannte Sicherung."),
  BACKUP_WRITE_FAILED(
      "backup.writeFailed",
      "Die Sicherung konnte nicht geschrieben werden. Ist der Sicherungsordner erreichbar?"),
  BACKUP_WRITES_RUNNING(
      "backup.writesRunning",
      "Gerade wird noch gespeichert. Bitte einen Moment warten und die Wiederherstellung erneut wählen."),
  CANCEL_ALREADY_CANCELLED("cancel.alreadyCancelled", "Die Rechnung ist bereits storniert."),
  CANCEL_DRAFT("cancel.draft", "Entwürfe werden gelöscht, nicht storniert."),
  CANCEL_NOT_INVOICE(
      "cancel.notInvoice", "Nur Rechnungen werden mit einer Stornorechnung storniert."),
  CREDIT_NOTE_NOT_CLONABLE(
      "creditNote.notClonable", "Stornorechnungen können nicht dupliziert werden."),
  CREDIT_NOTE_ONLY_BY_CANCEL(
      "creditNote.onlyByCancel",
      "Stornorechnungen entstehen nur durch das Stornieren einer Rechnung."),
  CUSTOMER_HAS_DOCUMENTS(
      "customer.hasDocuments",
      "Der Kunde hat Dokumente und kann nicht gelöscht werden (Aufbewahrungspflicht)."),
  DATABASE_FAILED("database.failed", "Datenbankfehler: Die Änderung wurde nicht gespeichert."),
  DEMO_MAIL_DISABLED("demo.mailDisabled", "Der Mailversand ist in der Demo abgeschaltet."),
  DELETE_NOT_DRAFT(
      "document.deleteNotDraft",
      "Nur Entwürfe können gelöscht werden. Ausgestellte Dokumente bitte archivieren oder stornieren (Aufbewahrungspflicht)."),
  DOCUMENT_NOT_FOUND("document.notFound", "Dokument {id} wurde nicht gefunden."),
  EINVOICE_MISSING("einvoice.missing", "Für die E-Rechnung (XRechnung) fehlen: {missing}."),
  EINVOICE_NOT_ISSUED(
      "einvoice.notIssued", "Eine E-Rechnung gibt es erst für ausgestellte Rechnungen."),
  EINVOICE_ONLY_INVOICES(
      "einvoice.onlyInvoices", "E-Rechnungen gibt es nur für Rechnungen und Stornorechnungen."),
  EXCHANGE_EXPORT_EMPTY("exchange.exportEmpty", "Die Vorlage enthält keine Texte zum Exportieren."),
  EXCHANGE_EXPORT_MISSING("exchange.exportMissing", "Keine Exportdaten."),
  EXCHANGE_FORMAT_UNKNOWN(
      "exchange.formatUnknown", "Unbekanntes Format; möglich sind docx und odt."),
  EXCHANGE_IMPORT_UNREADABLE(
      "exchange.importUnreadable",
      "Das Dokument konnte nicht gelesen werden. Unterstützt werden DOCX- und ODT-Dateien bis 2 MB."),
  EXCHANGE_SECTION_INVALID("exchange.sectionInvalid", "Ungültiger Abschnitt im Export."),
  EXCHANGE_TEXT_TOO_LONG("exchange.textTooLong", "Ein Text des Exports ist zu lang."),
  EXCHANGE_TOO_MANY_PARAGRAPHS("exchange.tooManyParagraphs", "Zu viele Absätze für einen Export."),
  EXCHANGE_TOO_MANY_SECTIONS("exchange.tooManySections", "Zu viele Abschnitte für einen Export."),
  EXPORT_ALREADY_RUNNING(
      "export.alreadyRunning",
      "Es läuft bereits ein Export. Bitte in einigen Minuten erneut versuchen."),
  EXPORT_PERIOD_TOO_LONG(
      "export.periodTooLong", "Der Zeitraum ist zu lang (höchstens {max} Tage pro Export)."),
  EXPORT_TOO_LARGE(
      "export.tooLarge",
      "Der Export ist zu groß (höchstens {max} MB) und wurde abgebrochen. Bitte den Zeitraum verkleinern oder ohne Belegdateien exportieren."),
  EXPORT_TOO_MANY(
      "export.tooMany",
      "Es laufen gerade zu viele Exporte. Bitte in einigen Minuten erneut versuchen."),
  EXPORT_TOO_MANY_DOCUMENTS(
      "export.tooManyDocuments",
      "Zu viele Belege für einen Export ({count}, höchstens {max}). Bitte den Zeitraum verkleinern."),
  EXPORT_TOO_MANY_WITH_FILES(
      "export.tooManyWithFiles",
      "Zu viele Belege für einen Export mit PDF und XRechnung ({count}, höchstens {max}). Bitte den Zeitraum verkleinern oder ohne Belegdateien exportieren."),
  EXPORT_TOO_SLOW(
      "export.tooSlow",
      "Der Export hat zu lange gedauert und wurde abgebrochen. Bitte den Zeitraum verkleinern oder ohne Belegdateien exportieren."),
  INVOICE_ALREADY_ISSUED("invoice.alreadyIssued", "Die Rechnung wurde bereits ausgestellt."),
  INVOICE_DATE_MISSING("invoice.dateMissing", "Bitte ein Rechnungsdatum angeben."),
  INVOICE_EXEMPTION_REASON_MISSING(
      "invoice.exemptionReasonMissing",
      "Bitte den Grund der Steuerbefreiung angeben (z. B. \"gemäß § 4 Nr. 14 UStG\")."),
  INVOICE_ISSUE_NOT_DRAFT(
      "invoice.issueNotDraft", "Nur Rechnungsentwürfe können ausgestellt werden."),
  INVOICE_ISSUED_LOCKED(
      "invoice.issuedLocked",
      "Ausgestellte Rechnungen können nicht mehr geändert werden (GoBD). Bitte stornieren und eine korrigierte Rechnung erstellen."),
  INVOICE_LAYOUT_LOCKED(
      "invoice.layoutLocked",
      "Das Layout einer ausgestellten Rechnung kann nicht mehr geändert werden."),
  INVOICE_NO_ITEMS(
      "invoice.noItems", "Eine Rechnung ohne Positionen kann nicht ausgestellt werden."),
  INVOICE_REVERSE_CHARGE_TAX_ID(
      "invoice.reverseChargeTaxId",
      "Für das Reverse-Charge-Verfahren (§ 13b UStG) braucht der Kunde eine USt-IdNr. oder Steuernummer."),
  INVOICE_SERVICE_DATE_MISSING(
      "invoice.serviceDateMissing",
      "Bitte das Leistungsdatum angeben (Pflichtangabe nach §14 Abs. 4 UStG)."),
  INVOICE_TAX_RATE_NOT_ALLOWED(
      "invoice.taxRateNotAllowed",
      "Position \"{item}\" hat den Steuersatz {rate} % (erlaubt: 19 %, 7 %)."),
  ITEM_TAX_RATE_INVALID(
      "item.taxRateInvalid", "Ungültiger Steuersatz {rate} % für \"{item}\" (erlaubt: 19 %, 7 %)."),
  MAIL_ACCOUNT_MISSING("mail.accountMissing", "Die Angaben zum Mailkonto fehlen."),
  MAIL_ACCOUNT_NAME(
      "mail.accountName", "Bitte einen Namen für das Konto angeben (bis 100 Zeichen)."),
  MAIL_AUTH(
      "mail.auth",
      "Der Mailserver hat die Anmeldung abgelehnt. Bitte Benutzername und Passwort prüfen."),
  MAIL_CONNECT(
      "mail.connect", "Der Mailserver ist nicht erreichbar. Bitte Server und Port prüfen."),
  MAIL_ENCRYPTION_REQUIRED(
      "mail.encryptionRequired",
      "Bitte eine verschlüsselte Verbindung wählen (STARTTLS oder SSL/TLS)."),
  MAIL_FROM_INVALID("mail.fromInvalid", "Bitte eine gültige Absenderadresse angeben."),
  MAIL_FROM_NAME_INVALID(
      "mail.fromNameInvalid", "Der Absendername ist zu lang oder enthält Steuerzeichen."),
  MAIL_HOST_FORMAT(
      "mail.hostFormat",
      "Bitte den Mailserver als Namen angeben, z. B. smtp.example.com (ohne http:// und ohne Port)."),
  MAIL_HOST_INTERNAL(
      "mail.hostInternal",
      "Der Mailserver „{host}“ zeigt auf eine interne Adresse. Bitte den öffentlichen Mailserver Ihres Anbieters eintragen."),
  MAIL_HOST_UNKNOWN(
      "mail.hostUnknown", "Der Mailserver „{host}“ ist nicht bekannt. Bitte die Adresse prüfen."),
  MAIL_NO_SECRETS_KEY(
      "mail.noSecretsKey",
      "Passwörter können hier nicht gespeichert werden: der Betreiber hat keinen Schlüssel eingerichtet (BILLERY_SECRETS_KEY)."),
  MAIL_OTHER("mail.other", "Die Nachricht konnte nicht gesendet werden."),
  MAIL_PASSWORD_REENTER(
      "mail.passwordReenter",
      "Server, Port, Verschlüsselung oder Benutzername wurden geändert: bitte das Passwort erneut eingeben."),
  MAIL_PASSWORD_TOO_LONG("mail.passwordTooLong", "Das Passwort ist zu lang."),
  MAIL_PLAINTEXT_LOGIN(
      "mail.plaintextLogin",
      "Ohne Verschlüsselung würden Benutzername und Passwort lesbar übertragen: bitte STARTTLS oder SSL/TLS wählen."),
  MAIL_PORT_NOT_ALLOWED(
      "mail.portNotAllowed",
      "Als Port sind hier nur 25, 465, 587 und 2525 möglich (meist 587 mit STARTTLS oder 465 mit SSL/TLS)."),
  MAIL_PORT_RANGE("mail.portRange", "Der Port muss zwischen 1 und 65535 liegen."),
  MAIL_REFUSED_HOST("mail.refusedHost", "Dieser Mailserver ist hier nicht erlaubt."),
  MAIL_REJECTED(
      "mail.rejected", "Der Mailserver hat die Nachricht abgelehnt (Absender oder Empfänger)."),
  MAIL_SECURITY_VALUE(
      "mail.securityValue", "Die Verschlüsselung muss STARTTLS, SSL oder NONE sein."),
  MAIL_TIMEOUT("mail.timeout", "Der Mailserver hat nicht rechtzeitig geantwortet."),
  MAIL_TLS(
      "mail.tls",
      "Die verschlüsselte Verbindung zum Mailserver ist fehlgeschlagen. Bitte die Verschlüsselung (STARTTLS oder SSL/TLS) und den Port prüfen."),
  MAIL_USERNAME_INVALID(
      "mail.usernameInvalid", "Der Benutzername ist zu lang oder enthält Steuerzeichen."),
  MAIL_TEMPLATES_MISSING("mailTemplate.missing", "Die Vorlagen fehlen."),
  MAIL_TEMPLATE_SUBJECT("mailTemplate.subject", "Bitte einen Betreff angeben (bis {max} Zeichen)."),
  MAIL_TEMPLATE_SUBJECT_LINEBREAK(
      "mailTemplate.subjectLinebreak", "Der Betreff darf keinen Zeilenumbruch enthalten."),
  MAIL_TEMPLATE_TEXT_TOO_LONG(
      "mailTemplate.textTooLong", "Der Text ist zu lang (höchstens 20 000 Zeichen)."),
  MAIL_TEMPLATE_UNKNOWN("mailTemplate.unknown", "Unbekannte Mailvorlage: {kind}"),
  OWNER_EXISTS(
      "owner.exists",
      "Es gibt bereits einen Inhaber. Bitte die vorhandenen Firmendaten bearbeiten."),
  OWNER_MISSING(
      "owner.missing",
      "Bitte zuerst die Firmendaten (Inhaber) anlegen, bevor Dokumente erzeugt werden."),
  PAYMENT_ALREADY_REVERSED("payment.alreadyReversed", "Die Zahlung wurde bereits storniert."),
  PAYMENT_AMOUNT_NOT_POSITIVE(
      "payment.amountNotPositive", "Der Zahlungsbetrag muss größer als 0 sein."),
  PAYMENT_DATE_MISSING("payment.dateMissing", "Bitte das Zahlungsdatum angeben."),
  PAYMENT_EXCEEDS_OPEN(
      "payment.exceedsOpen", "Der Betrag übersteigt den offenen Betrag von {open}."),
  PAYMENT_NOT_OPEN(
      "payment.notOpen",
      "Zahlungen können nur für ausgestellte, noch offene Rechnungen erfasst werden."),
  PAYMENT_OTHER_INVOICE("payment.otherInvoice", "Die Zahlung gehört nicht zu dieser Rechnung."),
  PAYMENT_REVERSAL_NOT_REVERSIBLE(
      "payment.reversalNotReversible", "Eine Stornobuchung kann nicht storniert werden."),
  LANGUAGE_UNKNOWN("preferences.languageUnknown", "Unbekannte Sprache. Möglich sind: de, en."),
  SEND_ACCOUNT_GONE("send.accountGone", "Das gewählte Mailkonto gibt es nicht (mehr)."),
  SEND_ADDRESS_INVALID("send.addressInvalid", "Keine gültige E-Mail-Adresse: {address}"),
  SEND_DAILY_LIMIT(
      "send.dailyLimit",
      "Das Tageslimit für Mails an Kunden ist erreicht. Bitte später erneut versuchen."),
  SEND_DRAFT_NEEDS_ISSUE(
      "send.draftNeedsIssue",
      "Die Rechnung ist ein Entwurf: bitte „Ausstellen und senden“ bestätigen."),
  SEND_FAILED_ISSUED(
      "send.failedIssued",
      "{reason} Die Rechnung wurde als {number} ausgestellt und kann erneut gesendet werden."),
  SEND_NO_ACCOUNT(
      "send.noAccount",
      "Es ist noch kein Mailkonto eingerichtet: Einstellungen → Mail-Einstellungen."),
  SEND_NO_RECIPIENT("send.noRecipient", "Bitte mindestens einen Empfänger angeben."),
  SEND_NOT_RECORDED(
      "send.notRecorded",
      "Die Mail wurde gesendet, konnte aber nicht in der Historie des Dokuments vermerkt werden. Bitte nicht erneut senden; der Betreiber ist informiert."),
  SEND_REQUEST_MISSING("send.requestMissing", "Die Angaben zum Versand fehlen."),
  SEND_SUBJECT_EMPTY("send.subjectEmpty", "Der Betreff ist nach dem Einsetzen der Variablen leer."),
  SEND_TOO_MANY_RECIPIENTS(
      "send.tooManyRecipients", "Höchstens {max} Empfänger (An und Kopie zusammen) pro Nachricht."),
  SEND_XRECHNUNG_MISSING("send.xrechnungMissing", "Für die XRechnung fehlen noch: {missing}."),
  SEND_XRECHNUNG_ONLY_INVOICES(
      "send.xrechnungOnlyInvoices", "Eine XRechnung gibt es nur für Rechnungen und Stornos."),
  STATUS_CHANGE_FINAL(
      "status.changeFinal",
      "Der Status „{from}“ kann nicht in „{to}“ geändert werden. Von diesem Status aus ist kein Wechsel mehr möglich."),
  STATUS_CHANGE_REFUSED(
      "status.changeRefused",
      "Der Status „{from}“ kann nicht in „{to}“ geändert werden. Möglich: {possible}."),
  STATUS_NAME_ACCEPTED("statusName.accepted", "Angenommen"),
  STATUS_NAME_CANCELLED("statusName.cancelled", "Storniert"),
  STATUS_NAME_CONVERTED("statusName.converted", "Umgewandelt"),
  STATUS_NAME_DRAFT("statusName.draft", "Entwurf"),
  STATUS_NAME_OTHER("statusName.other", "{id}"),
  STATUS_NAME_PAID("statusName.paid", "Bezahlt"),
  STATUS_NAME_PARTIALLY_PAID("statusName.partiallyPaid", "Teilweise bezahlt"),
  STATUS_NAME_REJECTED("statusName.rejected", "Abgelehnt"),
  STATUS_NAME_SENT("statusName.sent", "Gesendet"),
  TEMPLATE_IMPORT_EMPTY(
      "template.importEmpty", "Das Dokument enthält keinen Text; es wurde keine Vorlage angelegt."),
  TEMPLATE_MISSING_FOR_TYPE(
      "template.missingForType",
      "Für diesen Dokumenttyp ist keine Vorlage vorhanden (Typ {type})."),
  TEMPLATE_NO_BLOCKS("template.noBlocks", "Die Vorlage enthält keine Blöcke."),
  UPLOAD_CONTENT_MISMATCH(
      "upload.contentMismatch", "Dateiinhalt passt nicht zur Dateiendung oder ist kein Bild"),
  UPLOAD_EMPTY("upload.empty", "Datei ist leer"),
  UPLOAD_FAILED("upload.failed", "Upload fehlgeschlagen. Bitte erneut versuchen."),
  UPLOAD_NAME_INVALID("upload.nameInvalid", "Ungültiger Dateiname"),
  UPLOAD_TOO_LARGE("upload.tooLarge", "Datei zu groß (max. 2 MB)"),
  UPLOAD_TYPE("upload.type", "Nur PNG, JPG, JPEG und GIF sind erlaubt");

  private final String key;
  private final String text;

  ErrorCode(String key, String text) {
    this.key = key;
    this.text = text;
  }

  @Override
  public String code() {
    return key;
  }

  @Override
  public String template() {
    return text;
  }
}
