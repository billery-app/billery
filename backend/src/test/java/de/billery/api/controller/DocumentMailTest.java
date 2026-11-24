package de.billery.api.controller;

import static de.billery.TestMoney.bd;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.billery.TestWiring;
import de.billery.api.dto.CustomerDto;
import de.billery.api.dto.InvoiceDto;
import de.billery.api.dto.InvoiceItemDto;
import de.billery.api.dto.StatusChangeRequest;
import de.billery.data.DocumentHistoryEntry;
import de.billery.data.InvoiceTemplate;
import de.billery.data.Owner;
import de.billery.data.TestDatabaseConfig;
import de.billery.mail.CompanyMailSender;
import de.billery.mail.CredentialCipher;
import de.billery.mail.FakeSmtpServer;
import de.billery.mail.MailHostPolicy;
import de.billery.mail.MailMetrics;
import de.billery.repository.DocumentHistoryRepository;
import de.billery.repository.OwnerRepository;
import de.billery.service.BadRequestException;
import de.billery.service.DocumentMailService;
import de.billery.service.DocumentStateException;
import de.billery.service.MailAccountService;
import de.billery.service.MailDeliveryException;
import de.billery.service.MailNotRecordedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Sending documents to customers ({@link DocumentMailService}) against the H2 test database and a
 * fake SMTP server: the PDF the download hands out is attached, variables are filled, an invoice
 * draft is issued only when confirmed, an offer becomes "Gesendet", the dispatch is in the history,
 * and nothing is recorded when the server refuses the mail.
 */
class DocumentMailTest {

  private static final int INVOICE = 1;
  private static final int OFFER = 2;
  private static final int DRAFT = 1;
  private static final int SENT = 2;
  private static final String YEAR = String.valueOf(LocalDate.now().getYear());
  private static final byte[] KEY = new byte[32];

  private InvoiceController invoices;
  private DocumentMailService mail;
  private SimpleMeterRegistry meters;
  private MailMetrics metrics;
  private CompanyMailSender sender;
  private FakeSmtpServer server;
  private final AtomicBoolean limitOpen = new AtomicBoolean(true);
  private int customerId;

  @BeforeAll
  static void setup() throws Exception {
    TestDatabaseConfig.setupH2Database();
  }

  @AfterAll
  static void tearDown() {
    TestDatabaseConfig.resetDatabaseConfig();
  }

  @BeforeEach
  void fresh() throws Exception {
    TestDatabaseConfig.clearData();
    for (int type = 1; type <= 4; type++) {
      InvoiceTemplate template = new InvoiceTemplate();
      template.setName("Default " + type);
      template.setDocumentTypeId(type);
      template.setDefault(true);
      template.setBlocks(BLOCKS);
      TestWiring.templateRepository().save(template);
    }
    saveOwner();
    invoices = TestWiring.invoiceController();
    customerId =
        TestWiring.customerController()
            .createCustomer(
                new CustomerDto(
                    null,
                    "Max",
                    "Müller",
                    "12345",
                    "Ort",
                    "Weg",
                    1,
                    "",
                    "max@kunde.de",
                    "",
                    "",
                    null))
            .getId();
    server = new FakeSmtpServer();
    CredentialCipher cipher = CredentialCipher.withKey(KEY, () -> 1);
    MailHostPolicy policy = new MailHostPolicy(false);
    meters = new SimpleMeterRegistry();
    metrics = new MailMetrics(meters);
    sender = TestWiring.companyMailSender(cipher, policy, metrics);
    limitOpen.set(true);
    mail = TestWiring.documentMailService(sender, limitOpen::get, metrics);
    TestWiring.mailAccountService(cipher, policy, metrics)
        .create(
            new MailAccountService.AccountInput(
                "Rechnungen",
                "127.0.0.1",
                server.port(),
                "NONE",
                "",
                null,
                "rechnung@firma.de",
                "Muster & Partner",
                null));
  }

  @AfterEach
  void stop() throws Exception {
    server.close();
  }

  @Test
  void anIssuedInvoiceGoesOutWithItsPdfAndTheDispatchIsRecorded() {
    int id = issue(create(INVOICE)).id();

    DocumentMailService.Draft draft = mail.draft(id);
    assertEquals("max@kunde.de", draft.to());
    assertEquals("Rechnung RE-" + YEAR + "-001 von Muster & Partner", draft.subject());
    assertTrue(draft.text().contains("Guten Tag Max Müller"), draft.text());
    assertTrue(draft.text().contains("119,00"), draft.text());
    assertFalse(draft.needsIssue());
    assertEquals(1, draft.accounts().size());

    DocumentMailService.Result result =
        mail.send(id, request(List.of("max@kunde.de"), draft.subject(), draft.text(), false, true));

    assertFalse(result.issued());
    FakeSmtpServer.Received received = server.received().get(0);
    assertEquals(
        List.of("max@kunde.de", "rechnung@firma.de"), received.recipients(), "bcc to self");
    assertTrue(
        received.data().contains("From: Muster & Partner <rechnung@firma.de>"), received.data());
    assertTrue(received.data().contains("Rechnung_RE-" + YEAR + "-001.pdf"), "the PDF is attached");
    assertFalse(received.data().contains("Bcc:"), "the blind copy is not in the header");
    DocumentHistoryEntry entry = last(id);
    assertEquals("MAIL_SENT", entry.action());
    assertTrue(entry.notes().contains("An: max@kunde.de"), entry.notes());
    assertTrue(entry.notes().contains("Betreff: Rechnung RE-" + YEAR + "-001"), entry.notes());
    assertTrue(entry.notes().contains("Konto: Rechnungen <rechnung@firma.de>"), entry.notes());
    assertTrue(entry.notes().contains("Kopie an den Absender"), entry.notes());
    assertFalse(entry.notes().contains("Guten Tag"), "the text is not stored");
  }

  @Test
  void aDraftInvoiceIsIssuedOnlyWhenConfirmedAndTheNumberGoesIntoTheMail() {
    int id = create(INVOICE).id();
    DocumentMailService.Draft draft = mail.draft(id);
    assertTrue(draft.needsIssue());
    assertTrue(
        draft.subject().contains("{{rechnungsnummer}}"), "filled when issued: " + draft.subject());

    DocumentStateException refused =
        assertThrows(
            DocumentStateException.class,
            () ->
                mail.send(
                    id,
                    request(List.of("max@kunde.de"), draft.subject(), draft.text(), false, false)));
    assertTrue(refused.getMessage().contains("Ausstellen und senden"));
    assertEquals(DRAFT, invoices.getInvoice(id).getBody().documentStatusId());
    assertTrue(server.received().isEmpty());

    DocumentMailService.Result result =
        mail.send(id, request(List.of("max@kunde.de"), draft.subject(), draft.text(), true, false));

    assertTrue(result.issued());
    assertEquals("RE-" + YEAR + "-001", result.documentNumber());
    assertEquals(SENT, result.documentStatusId());
    assertTrue(
        server.received().get(0).data().contains("Rechnung RE-" + YEAR + "-001 von"),
        server.received().get(0).data());
  }

  @Test
  void aDraftOfferBecomesSent() {
    int id = create(OFFER).id();
    DocumentMailService.Draft draft = mail.draft(id);
    assertFalse(draft.needsIssue());
    assertFalse(draft.xrechnungPossible());

    mail.send(id, request(List.of("max@kunde.de"), draft.subject(), draft.text(), false, false));

    assertEquals(SENT, invoices.getInvoice(id).getBody().documentStatusId());
    List<String> actions =
        invoices.getHistory(id).stream().map(DocumentHistoryEntry::action).toList();
    assertTrue(actions.containsAll(List.of("MAIL_SENT", "STATUS_CHANGED")), actions.toString());
  }

  @Test
  void aRefusedMailIsReportedAndNotRecorded() {
    int id = issue(create(INVOICE)).id();
    server.refuseRecipients(true);

    MailDeliveryException e =
        assertThrows(
            MailDeliveryException.class,
            () -> mail.send(id, request(List.of("max@kunde.de"), "Betreff", "Text", false, false)));

    assertFalse(e.limitReached());
    assertTrue(e.getMessage().contains("abgelehnt"), e.getMessage());
    assertFalse(e.getMessage().contains("max@kunde.de"), "no address in the message");
    assertTrue(
        invoices.getHistory(id).stream().noneMatch(h -> "MAIL_SENT".equals(h.action())),
        "a failed dispatch is not in the history");
  }

  @Test
  void aMailThatWentOutButCannotBeRecordedSaysSoAndIsCounted() {
    int id = issue(create(INVOICE)).id();
    DocumentHistoryRepository failingHistory =
        new DocumentHistoryRepository(TestWiring.jdbcClient()) {
          @Override
          public void log(int invoiceId, String action, String notes) {
            if (ACTION_MAIL_SENT.equals(action)) {
              throw new IllegalStateException("database gone");
            }
            super.log(invoiceId, action, notes);
          }
        };
    DocumentMailService failing =
        TestWiring.documentMailService(sender, limitOpen::get, metrics, failingHistory);

    MailNotRecordedException e =
        assertThrows(
            MailNotRecordedException.class,
            () ->
                failing.send(
                    id, request(List.of("max@kunde.de"), "Betreff", "Text", false, false)));

    assertEquals(1, server.received().size(), "the mail went out");
    assertTrue(e.getMessage().contains("nicht erneut senden"), e.getMessage());
    assertEquals(1.0, meters.get("billery.mail.dispatch.unrecorded").counter().count());
  }

  @Test
  void theDailyLimitStopsTheMailBeforeAnythingHappens() {
    int id = create(INVOICE).id();
    limitOpen.set(false);

    MailDeliveryException e =
        assertThrows(
            MailDeliveryException.class,
            () -> mail.send(id, request(List.of("max@kunde.de"), "Betreff", "Text", true, false)));

    assertTrue(e.limitReached());
    assertEquals(DRAFT, invoices.getInvoice(id).getBody().documentStatusId(), "not issued");
    assertTrue(server.received().isEmpty());
  }

  @Test
  void recipientsAndSubjectAreChecked() {
    int id = issue(create(INVOICE)).id();
    assertThrows(
        BadRequestException.class,
        () -> mail.send(id, request(List.of(), "Betreff", "Text", false, false)));
    assertThrows(
        BadRequestException.class,
        () ->
            mail.send(
                id, request(List.of("max@kunde.de, eve@x.de"), "Betreff", "Text", false, false)));
    assertThrows(
        BadRequestException.class,
        () ->
            mail.send(
                id,
                request(
                    List.of("a@x.de", "b@x.de", "c@x.de", "d@x.de", "e@x.de", "f@x.de"),
                    "Betreff",
                    "Text",
                    false,
                    false)));
    assertThrows(
        BadRequestException.class,
        () ->
            mail.send(
                id,
                request(
                    List.of("max@kunde.de"), "Betreff\r\nBcc: eve@x.de", "Text", false, false)));
    assertThrows(
        BadRequestException.class,
        () ->
            mail.send(
                id,
                new DocumentMailService.Request(
                    987654,
                    List.of("max@kunde.de"),
                    List.of(),
                    "Betreff",
                    "Text",
                    false,
                    false,
                    false)),
        "an account of nobody");
    assertTrue(server.received().isEmpty());
  }

  @Test
  void withoutAnAccountTheDialogKnowsAndSendingIsRefused() {
    int id = issue(create(INVOICE)).id();
    TestWiring.mailAccountRepository()
        .findAll()
        .forEach(a -> TestWiring.mailAccountRepository().delete(a.id()));

    assertTrue(mail.draft(id).accounts().isEmpty());
    DocumentStateException e =
        assertThrows(
            DocumentStateException.class,
            () -> mail.send(id, request(List.of("max@kunde.de"), "Betreff", "Text", false, false)));
    assertTrue(e.getMessage().contains("Mail-Einstellungen"), e.getMessage());
  }

  // ---- helpers ----

  private static DocumentMailService.Request request(
      List<String> to, String subject, String text, boolean issue, boolean copyToSelf) {
    return new DocumentMailService.Request(
        null, to, List.of(), subject, text, false, copyToSelf, issue);
  }

  private DocumentHistoryEntry last(int id) {
    List<DocumentHistoryEntry> history = invoices.getHistory(id);
    return history.get(history.size() - 1);
  }

  private InvoiceDto create(int type) {
    return invoices.createInvoice(
        new InvoiceDto(
            null,
            customerId,
            null,
            null,
            null,
            LocalDate.now(),
            LocalDate.now().plusDays(14),
            null,
            BigDecimal.valueOf(19),
            null,
            null,
            null,
            null,
            type,
            List.of(
                new InvoiceItemDto(
                    null,
                    null,
                    "article",
                    0,
                    "Position",
                    null,
                    "Stk",
                    bd(1),
                    bd(100),
                    null,
                    null,
                    List.of(),
                    null)),
            false,
            LocalDate.now(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null));
  }

  private InvoiceDto issue(InvoiceDto draft) {
    return invoices
        .updateInvoiceStatus(draft.id(), new StatusChangeRequest(SENT, null, null))
        .getBody();
  }

  private static void saveOwner() {
    OwnerRepository owners = TestWiring.ownerRepository();
    owners.findAll().forEach(o -> owners.delete(o.getId()));
    owners.save(
        new Owner(
            0,
            "Erika",
            "Muster",
            "Muster & Partner",
            "GmbH",
            "12345",
            "Ort",
            "Weg",
            1,
            "",
            "",
            "",
            "DE1",
            "DE02120300000000202051",
            false));
  }

  private static final String BLOCKS =
      "[{\"id\":\"h\",\"type\":\"header\",\"order\":0,\"data\":{\"companyName\":\"{{firma}}\"},"
          + "\"config\":{}},"
          + "{\"id\":\"m\",\"type\":\"invoice-meta\",\"order\":1,\"data\":{},\"config\":{}},"
          + "{\"id\":\"i\",\"type\":\"line-items\",\"order\":2,\"data\":{\"columns\":["
          + "{\"key\":\"description\",\"label\":\"Beschreibung\",\"show\":true},"
          + "{\"key\":\"amount\",\"label\":\"Gesamt\",\"show\":true}]},\"config\":{}},"
          + "{\"id\":\"t\",\"type\":\"totals\",\"order\":3,\"data\":{},\"config\":{}}]";
}
