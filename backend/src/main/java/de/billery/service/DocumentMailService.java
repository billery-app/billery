package de.billery.service;

import de.billery.data.Customer;
import de.billery.data.Invoice;
import de.billery.data.MailAccount;
import de.billery.document.DocumentStatus;
import de.billery.document.DocumentStrategyRegistry;
import de.billery.document.DocumentType;
import de.billery.document.DocumentTypeStrategy;
import de.billery.mail.CompanyMailSender;
import de.billery.mail.CustomerMailLimit;
import de.billery.mail.MailAddresses;
import de.billery.mail.MailAttachment;
import de.billery.mail.MailException;
import de.billery.mail.MailMessage;
import de.billery.pdf.TemplateBasedPdfGenerator;
import de.billery.repository.CustomerRepository;
import de.billery.repository.DocumentHistoryRepository;
import de.billery.repository.InvoiceRepository;
import de.billery.repository.MailAccountRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends a document to the customer by mail, through one of the company's own SMTP accounts (never
 * the operator's system account).
 *
 * <p>The attachment is the PDF the download hands out (ZUGFeRD for issued invoices with complete
 * data), optionally the XRechnung as a separate XML file. An invoice draft is issued first when the
 * user confirms "Ausstellen und senden"; issuing is final (GoBD), so a mail that fails afterwards
 * leaves an issued invoice that can be sent again. Every dispatch writes {@code document_history}
 * (recipients, subject, account, attachments; not the text), and an offer or cost calculation that
 * was a draft becomes "Gesendet". In SaaS the company's daily limit applies ({@link
 * CustomerMailLimit}).
 */
@Service
public class DocumentMailService {

  /** How many addresses (To and CC together) one mail may have. */
  static final int MAX_RECIPIENTS = 5;

  private static final Logger LOG = LoggerFactory.getLogger(DocumentMailService.class);

  /** Variables that a draft invoice cannot fill yet: its number comes with issuing. */
  private static final List<String> NUMBER_VARIABLES =
      List.of("rechnungsnummer", "invoice_number", "verwendungszweck");

  private final InvoiceRepository invoices;
  private final CustomerRepository customers;
  private final MailAccountRepository accounts;
  private final MailTemplateService templates;
  private final TemplateBasedPdfGenerator generator;
  private final DocumentPdfService pdfs;
  private final EInvoiceService eInvoices;
  private final InvoiceLifecycleService lifecycle;
  private final DocumentHistoryRepository history;
  private final DocumentStrategyRegistry registry;
  private final CompanyMailSender sender;
  private final CustomerMailLimit limit;
  private final TransactionTemplate transactions;

  /**
   * An account the send dialog offers.
   *
   * @param id the account
   * @param name its name
   * @param fromAddress the sender address
   * @param fromName the sender's name
   * @param isDefault whether it is preselected
   */
  public record AccountChoice(
      int id, String name, String fromAddress, String fromName, boolean isDefault) {}

  /**
   * What the send dialog starts with.
   *
   * @param to the customer's address (may be empty)
   * @param subject the subject from the template, variables resolved
   * @param text the text from the template, variables resolved; for an invoice draft the number
   *     variables stay and are filled when it is issued
   * @param accounts the company's accounts (empty: sending is not possible, the dialog links to the
   *     settings)
   * @param needsIssue true for an invoice draft: sending issues it first
   * @param attachmentName the PDF's file name (for an invoice draft: without number yet)
   * @param xrechnungPossible whether an XRechnung can be attached (invoices and credit notes)
   * @param xrechnungMissing what the XRechnung still lacks (empty when complete)
   */
  public record Draft(
      String to,
      String subject,
      String text,
      List<AccountChoice> accounts,
      boolean needsIssue,
      String attachmentName,
      boolean xrechnungPossible,
      List<String> xrechnungMissing) {}

  /**
   * What the send dialog sends.
   *
   * @param accountId the account; null for the default
   * @param to recipients
   * @param cc copies
   * @param subject the subject; variables are resolved once more (after issuing)
   * @param text the text; variables likewise
   * @param attachXRechnung whether to attach the XRechnung as XML (null: no)
   * @param copyToSelf whether the account's own address gets a blind copy (null: no)
   * @param issue the user confirmed issuing an invoice draft (null: no)
   */
  public record Request(
      Integer accountId,
      List<String> to,
      List<String> cc,
      String subject,
      String text,
      Boolean attachXRechnung,
      Boolean copyToSelf,
      Boolean issue) {

    /**
     * @return whether to attach the XRechnung (missing means no)
     */
    boolean wantsXRechnung() {
      return Boolean.TRUE.equals(attachXRechnung);
    }

    /**
     * @return whether the sender gets a blind copy (missing means no)
     */
    boolean wantsCopy() {
      return Boolean.TRUE.equals(copyToSelf);
    }

    /**
     * @return whether issuing an invoice draft is confirmed (missing means no)
     */
    boolean confirmsIssue() {
      return Boolean.TRUE.equals(issue);
    }
  }

  /**
   * The outcome.
   *
   * @param issued whether the invoice was issued by this request
   * @param documentNumber the document's number afterwards
   * @param documentStatusId its status afterwards
   */
  public record Result(boolean issued, String documentNumber, int documentStatusId) {}

  /**
   * Creates the service.
   *
   * @param invoices the documents
   * @param customers the customer's address
   * @param accounts the company's mail accounts
   * @param templates subject and text
   * @param generator resolves the variables like the PDF
   * @param pdfs the attachment
   * @param eInvoices the optional XRechnung
   * @param lifecycle issues an invoice draft
   * @param history records the dispatch
   * @param registry the status transitions of offers and cost calculations, the document names
   * @param sender delivers through the company's account
   * @param limit the company's daily limit
   * @param transactions writes history and status together
   */
  public DocumentMailService(
      InvoiceRepository invoices,
      CustomerRepository customers,
      MailAccountRepository accounts,
      MailTemplateService templates,
      TemplateBasedPdfGenerator generator,
      DocumentPdfService pdfs,
      EInvoiceService eInvoices,
      InvoiceLifecycleService lifecycle,
      DocumentHistoryRepository history,
      DocumentStrategyRegistry registry,
      CompanyMailSender sender,
      CustomerMailLimit limit,
      TransactionTemplate transactions) {
    this.invoices = invoices;
    this.customers = customers;
    this.accounts = accounts;
    this.templates = templates;
    this.generator = generator;
    this.pdfs = pdfs;
    this.eInvoices = eInvoices;
    this.lifecycle = lifecycle;
    this.history = history;
    this.registry = registry;
    this.sender = sender;
    this.limit = limit;
    this.transactions = transactions;
  }

  /**
   * Prepares the send dialog.
   *
   * @param id the document
   * @return recipients, subject, text and the choices
   * @throws DocumentNotFoundException if the document does not exist
   */
  public Draft draft(int id) {
    Invoice invoice = require(id);
    MailTemplateService.Template template =
        templates.template(MailTemplateService.Kind.forDocumentType(invoice.getDocumentTypeId()));
    Customer customer = customers.findById(invoice.getCustomerId());
    boolean needsIssue = isInvoiceDraft(invoice);
    boolean bookkeeping = DocumentType.isBookkeepingRecord(invoice.getDocumentTypeId());
    return new Draft(
        customer == null || customer.getEmail() == null ? "" : customer.getEmail().trim(),
        resolve(template.subject(), invoice, needsIssue),
        resolve(template.text(), invoice, needsIssue),
        accounts.findAll().stream()
            .map(
                a ->
                    new AccountChoice(
                        a.id(), a.name(), a.fromAddress(), a.fromName(), a.isDefault()))
            .toList(),
        needsIssue,
        pdfName(invoice),
        bookkeeping,
        bookkeeping ? eInvoices.check(id) : List.of());
  }

  /**
   * Sends the document.
   *
   * @param id the document
   * @param request recipients, subject, text and options
   * @return what happened to the document
   * @throws DocumentNotFoundException if the document does not exist
   * @throws BadRequestException for unusable recipients, subject or text, or an unknown account
   * @throws DocumentStateException if there is no account, an invoice draft is not confirmed for
   *     issuing, or the XRechnung lacks data
   * @throws MailDeliveryException if the daily limit is reached or the mail server fails
   */
  public Result send(int id, Request request) {
    Invoice invoice = require(id);
    if (request == null) {
      throw new BadRequestException("Die Angaben zum Versand fehlen.");
    }
    List<String> to = addresses(request.to());
    List<String> cc = addresses(request.cc());
    if (to.isEmpty()) {
      throw new BadRequestException("Bitte mindestens einen Empfänger angeben.");
    }
    if (to.size() + cc.size() > MAX_RECIPIENTS) {
      throw new BadRequestException("Höchstens 5 Empfänger (An und Kopie zusammen) pro Nachricht.");
    }
    MailTemplateService.Template content =
        MailTemplateService.check(
            new MailTemplateService.Template(request.subject(), request.text()));
    MailAccount account = account(request.accountId());
    boolean issueNow = isInvoiceDraft(invoice);
    if (issueNow && !request.confirmsIssue()) {
      throw new DocumentStateException(
          "Die Rechnung ist ein Entwurf: bitte „Ausstellen und senden“ bestätigen.");
    }
    if (invoice.getDocumentTypeId() == DocumentType.CREDIT_NOTE
        || DocumentType.isInvoice(invoice.getDocumentTypeId())) {
      requireXRechnungData(id, request.wantsXRechnung());
    } else if (request.wantsXRechnung()) {
      throw new BadRequestException("Eine XRechnung gibt es nur für Rechnungen und Stornos.");
    }
    if (!limit.claim()) {
      throw new MailDeliveryException(
          "Das Tageslimit für Mails an Kunden ist erreicht. Bitte später erneut versuchen.", true);
    }
    if (issueNow) {
      lifecycle.issue(id);
      invoice = require(id);
    }
    MailMessage message = message(invoice, account, to, cc, content, request);
    try {
      sender.send(account, message);
    } catch (MailException e) {
      LOG.warn(
          "Mail of document {} through mail account {} failed: {}", id, account.id(), e.reason());
      String hint =
          issueNow
              ? " Die Rechnung wurde als "
                  + invoice.getFormattedInvoiceNumber()
                  + " ausgestellt und kann erneut gesendet werden."
              : "";
      throw new MailDeliveryException(e.reason().explanation() + hint, e);
    }
    Invoice sent = invoice;
    transactions.executeWithoutResult(status -> recordDispatch(sent, account, message));
    Invoice after = require(id);
    return new Result(issueNow, after.getFormattedInvoiceNumber(), after.getDocumentStatusId());
  }

  private void requireXRechnungData(int id, boolean attach) {
    if (!attach) {
      return;
    }
    List<String> missing = eInvoices.check(id);
    if (!missing.isEmpty()) {
      throw new DocumentStateException(
          "Für die XRechnung fehlen noch: " + String.join(", ", missing) + ".");
    }
  }

  private MailMessage message(
      Invoice invoice,
      MailAccount account,
      List<String> to,
      List<String> cc,
      MailTemplateService.Template content,
      Request request) {
    MailMessage message = new MailMessage();
    message.setToAll(to);
    message.setCc(cc);
    if (request.wantsCopy()) {
      message.setBcc(List.of(account.fromAddress()));
    }
    String subject = generator.resolvePlainText(content.subject(), invoice).trim();
    if (subject.isEmpty() || MailAddresses.hasControlCharacters(subject)) {
      throw new BadRequestException("Der Betreff ist nach dem Einsetzen der Variablen leer.");
    }
    message.setSubject(subject);
    message.setText(generator.resolvePlainText(content.text(), invoice));
    try {
      message.addAttachment(
          new MailAttachment(pdfName(invoice), pdfs.pdf(invoice), "application/pdf"));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("The PDF of document " + invoice.getId() + " failed", e);
    }
    if (request.wantsXRechnung()) {
      message.addAttachment(
          new MailAttachment(
              xmlName(invoice),
              eInvoices.xrechnung(invoice.getId()).getBytes(StandardCharsets.UTF_8),
              "application/xml"));
    }
    return message;
  }

  private void recordDispatch(Invoice invoice, MailAccount account, MailMessage message) {
    StringBuilder notes = new StringBuilder(200);
    notes.append("An: ").append(String.join(", ", message.getToAll()));
    if (!message.getCc().isEmpty()) {
      notes.append("; Kopie: ").append(String.join(", ", message.getCc()));
    }
    notes.append("; Betreff: ").append(message.getSubject());
    notes
        .append("; Konto: ")
        .append(account.name())
        .append(" <")
        .append(account.fromAddress())
        .append('>');
    notes
        .append("; Anhänge: ")
        .append(
            String.join(
                ", ", message.getAttachments().stream().map(MailAttachment::fileName).toList()));
    if (!message.getBcc().isEmpty()) {
      notes.append("; Kopie an den Absender");
    }
    history.log(invoice.getId(), DocumentHistoryRepository.ACTION_MAIL_SENT, notes.toString());

    boolean quote =
        invoice.getDocumentTypeId() == DocumentType.OFFER
            || invoice.getDocumentTypeId() == DocumentType.COST_CALCULATION;
    DocumentTypeStrategy strategy = registry.getStrategy(invoice.getDocumentTypeId());
    if (quote
        && invoice.getDocumentStatusId() == DocumentStatus.DRAFT
        && strategy.isValidTransition(DocumentStatus.DRAFT, DocumentStatus.SENT)) {
      invoices.updateStatus(invoice.getId(), DocumentStatus.SENT, null);
      history.log(
          invoice.getId(),
          DocumentHistoryRepository.ACTION_STATUS_CHANGED,
          DocumentStatus.transitionNote(DocumentStatus.DRAFT, DocumentStatus.SENT));
    }
  }

  private MailAccount account(Integer accountId) {
    if (accountId != null) {
      MailAccount account = accounts.findById(accountId);
      if (account == null) {
        throw new BadRequestException("Das gewählte Mailkonto gibt es nicht (mehr).");
      }
      return account;
    }
    return accounts.findAll().stream()
        .filter(MailAccount::isDefault)
        .findFirst()
        .or(() -> accounts.findAll().stream().findFirst())
        .orElseThrow(
            () ->
                new DocumentStateException(
                    "Es ist noch kein Mailkonto eingerichtet: Einstellungen →"
                        + " Mail-Einstellungen."));
  }

  private static List<String> addresses(List<String> raw) {
    List<String> out = new ArrayList<>();
    if (raw == null) {
      return out;
    }
    for (String address : raw) {
      String trimmed = address == null ? "" : address.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      if (!MailAddresses.isAddress(trimmed)) {
        throw new BadRequestException("Keine gültige E-Mail-Adresse: " + trimmed);
      }
      if (!out.contains(trimmed)) {
        out.add(trimmed);
      }
    }
    return out;
  }

  /**
   * Resolves the variables; for an invoice draft the number variables stay, so they are filled with
   * the number it gets when it is issued.
   */
  private String resolve(String text, Invoice invoice, boolean keepNumber) {
    if (!keepNumber) {
      return generator.resolvePlainText(text, invoice);
    }
    String protectedText = text;
    Map<String, String> markers = new java.util.LinkedHashMap<>();
    for (String variable : NUMBER_VARIABLES) {
      String marker = "\u0000" + variable + "\u0000";
      markers.put(marker, "{{" + variable + "}}");
      protectedText = protectedText.replaceAll("\\{\\{\\s*" + variable + "\\s*}}", marker);
    }
    String resolved = generator.resolvePlainText(protectedText, invoice);
    for (Map.Entry<String, String> entry : markers.entrySet()) {
      resolved = resolved.replace(entry.getKey(), entry.getValue());
    }
    return resolved;
  }

  private static boolean isInvoiceDraft(Invoice invoice) {
    return DocumentType.isInvoice(invoice.getDocumentTypeId())
        && invoice.getDocumentStatusId() == DocumentStatus.DRAFT;
  }

  private String pdfName(Invoice invoice) {
    return registry.getStrategy(invoice.getDocumentTypeId()).getDisplayName()
        + "_"
        + safe(invoice.getFormattedInvoiceNumber())
        + ".pdf";
  }

  private static String xmlName(Invoice invoice) {
    return "XRechnung_" + safe(invoice.getFormattedInvoiceNumber()) + ".xml";
  }

  private static String safe(String number) {
    return (number == null ? "" : number).replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private Invoice require(int id) {
    Invoice invoice = invoices.findById(id);
    if (invoice == null) {
      throw new DocumentNotFoundException(id);
    }
    return invoice;
  }
}
