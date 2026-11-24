package de.billery.service;

import de.billery.document.DocumentType;
import de.billery.mail.MailAddresses;
import de.billery.repository.UserSettingsRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Subject and text of the mails to customers, one pair per kind of document, with {@code
 * {{variables}}} (the same as in the document templates). Kept in {@code user_settings} ({@code
 * mail_<kind>_subject}, {@code mail_<kind>_text}); a kind without stored text uses the defaults
 * below. The send dialog starts from them, and the user may change the text before sending.
 */
@Service
public class MailTemplateService {

  /** Longest subject. */
  static final int MAX_SUBJECT = 200;

  /** Longest text. */
  static final int MAX_TEXT = 20_000;

  private static final String GREETING = "Guten Tag {{kunde}},\n\n";
  private static final String CLOSING = "\n\nMit freundlichen Grüßen\n{{firma}}";

  private final UserSettingsRepository settings;

  /** The kinds of document that have a template of their own. */
  public enum Kind {
    /** Invoices, partial and final invoices. */
    INVOICE(
        "invoice",
        "Rechnung {{rechnungsnummer}} von {{firma}}",
        GREETING
            + "anbei erhalten Sie unsere Rechnung {{rechnungsnummer}} vom {{rechnungsdatum}} über"
            + " {{betrag}}.\nBitte überweisen Sie den Betrag bis zum {{faelligkeitsdatum}}."
            + CLOSING),
    /** Credit notes (cancellations). */
    CREDIT_NOTE(
        "credit_note",
        "Stornorechnung {{rechnungsnummer}} von {{firma}}",
        GREETING
            + "anbei erhalten Sie die Stornorechnung {{rechnungsnummer}} vom {{rechnungsdatum}}."
            + CLOSING),
    /** Offers. */
    OFFER(
        "offer",
        "Angebot {{rechnungsnummer}} von {{firma}}",
        GREETING
            + "anbei erhalten Sie unser Angebot {{rechnungsnummer}} vom {{rechnungsdatum}} über"
            + " {{betrag}}.\nWir freuen uns auf Ihre Rückmeldung."
            + CLOSING),
    /** Cost calculations. */
    COST_CALCULATION(
        "cost_calculation",
        "Kostenberechnung {{rechnungsnummer}} von {{firma}}",
        GREETING
            + "anbei erhalten Sie unsere Kostenberechnung {{rechnungsnummer}} vom"
            + " {{rechnungsdatum}} über {{betrag}}."
            + CLOSING);

    private final String apiKey;
    private final String defaultSubject;
    private final String defaultText;

    Kind(String key, String defaultSubject, String defaultText) {
      this.apiKey = key;
      this.defaultSubject = defaultSubject;
      this.defaultText = defaultText;
    }

    /**
     * @return the name in the API and in the setting keys
     */
    public String key() {
      return apiKey;
    }

    /**
     * @param documentTypeId a document type
     * @return the kind of its template
     */
    public static Kind forDocumentType(int documentTypeId) {
      return switch (documentTypeId) {
        case DocumentType.OFFER -> OFFER;
        case DocumentType.COST_CALCULATION -> COST_CALCULATION;
        case DocumentType.CREDIT_NOTE -> CREDIT_NOTE;
        default -> INVOICE;
      };
    }
  }

  /**
   * One template.
   *
   * @param subject the subject with variables
   * @param text the text with variables
   */
  public record Template(String subject, String text) {}

  /**
   * @param settings where the templates are stored
   */
  public MailTemplateService(UserSettingsRepository settings) {
    this.settings = settings;
  }

  /**
   * @param kind the kind of document
   * @return its template (stored, or the default)
   */
  public Template template(Kind kind) {
    return new Template(
        settings.getSetting(subjectKey(kind), kind.defaultSubject),
        settings.getSetting(textKey(kind), kind.defaultText));
  }

  /**
   * @return every kind's template, keyed by {@link Kind#key()}
   */
  public Map<String, Template> all() {
    Map<String, Template> all = new LinkedHashMap<>();
    for (Kind kind : Kind.values()) {
      all.put(kind.key(), template(kind));
    }
    return all;
  }

  /**
   * Stores the templates that are given; kinds that are missing stay as they are.
   *
   * @param templates templates keyed by {@link Kind#key()}
   * @return every kind's template afterwards
   * @throws BadRequestException for an unknown kind or a subject or text that is too long
   */
  public Map<String, Template> save(Map<String, Template> templates) {
    if (templates == null) {
      throw new BadRequestException("Die Vorlagen fehlen.");
    }
    Map<Kind, Template> checked = new LinkedHashMap<>();
    for (Map.Entry<String, Template> entry : templates.entrySet()) {
      Kind kind = null;
      for (Kind k : Kind.values()) {
        if (k.key().equals(entry.getKey())) {
          kind = k;
        }
      }
      if (kind == null || entry.getValue() == null) {
        throw new BadRequestException("Unbekannte Mailvorlage: " + entry.getKey());
      }
      checked.put(kind, check(entry.getValue()));
    }
    checked.forEach(
        (kind, template) -> {
          settings.updateSetting(subjectKey(kind), template.subject());
          settings.updateSetting(textKey(kind), template.text());
        });
    return all();
  }

  /**
   * Checks a subject and text typed by the user (template or the send dialog).
   *
   * @param template subject and text
   * @return the same, trimmed
   * @throws BadRequestException if the subject is empty, too long or has line breaks, or the text
   *     is too long
   */
  static Template check(Template template) {
    String subject = template.subject() == null ? "" : template.subject().trim();
    String text = template.text() == null ? "" : template.text();
    if (subject.isEmpty() || subject.length() > MAX_SUBJECT) {
      throw new BadRequestException("Bitte einen Betreff angeben (bis 200 Zeichen).");
    }
    if (MailAddresses.hasControlCharacters(subject)) {
      throw new BadRequestException("Der Betreff darf keinen Zeilenumbruch enthalten.");
    }
    if (text.length() > MAX_TEXT) {
      throw new BadRequestException("Der Text ist zu lang (höchstens 20 000 Zeichen).");
    }
    return new Template(subject, text);
  }

  private static String subjectKey(Kind kind) {
    return "mail_" + kind.key() + "_subject";
  }

  private static String textKey(Kind kind) {
    return "mail_" + kind.key() + "_text";
  }
}
