package de.billery.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.billery.TestWiring;
import de.billery.data.MailAccount;
import de.billery.data.TestDatabaseConfig;
import de.billery.mail.CredentialCipher;
import de.billery.mail.FakeSmtpServer;
import de.billery.mail.MailHostPolicy;
import de.billery.mail.MailMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link MailAccountService} against the H2 test database: passwords are stored encrypted and never
 * handed out, the default account is kept unique, a changed server needs the password again, and
 * the test mail really goes out.
 */
class MailAccountServiceTest {

  private static final byte[] KEY = new byte[32];

  private SimpleMeterRegistry meters;
  private MailAccountService service;

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
    meters = new SimpleMeterRegistry();
    service =
        TestWiring.mailAccountService(
            CredentialCipher.withKey(KEY, () -> 1),
            new MailHostPolicy(false),
            new MailMetrics(meters));
  }

  private static MailAccountService.AccountInput input(
      String name, String host, String user, String password) {
    return new MailAccountService.AccountInput(
        name, host, 587, "STARTTLS", user, password, "rechnung@firma.de", "Firma GmbH", null);
  }

  @Test
  void thePasswordIsStoredEncryptedAndNeverHandedOut() {
    MailAccountService.AccountView view =
        service.create(input("Buchhaltung", "smtp.firma.de", "rechnung", "Geheim123"));

    assertTrue(view.hasPassword());
    assertTrue(view.isDefault(), "the first account is the default");
    MailAccount stored = TestWiring.mailAccountRepository().findById(view.id());
    assertTrue(stored.passwordEncrypted().startsWith("v1:"), stored.passwordEncrypted());
    assertFalse(stored.passwordEncrypted().contains("Geheim123"));
    assertFalse(view.toString().contains("Geheim123"));
    assertEquals(
        "Geheim123", CredentialCipher.withKey(KEY, () -> 1).decrypt(stored.passwordEncrypted()));
  }

  @Test
  void aChangedServerOrLoginNeedsThePasswordAgain() {
    int id = service.create(input("Konto", "smtp.firma.de", "rechnung", "Geheim123")).id();

    BadRequestException e =
        assertThrows(
            BadRequestException.class,
            () -> service.update(id, input("Konto", "smtp.angreifer.example", "rechnung", null)));
    assertTrue(e.getMessage().contains("Passwort erneut"), e.getMessage());
    assertThrows(
        BadRequestException.class,
        () -> service.update(id, input("Konto", "smtp.firma.de", "jemand", null)));

    // Renaming keeps the stored password; a new password with a new server is fine.
    String before = TestWiring.mailAccountRepository().findById(id).passwordEncrypted();
    service.update(id, input("Neuer Name", "smtp.firma.de", "rechnung", null));
    assertEquals(before, TestWiring.mailAccountRepository().findById(id).passwordEncrypted());
    service.update(id, input("Neuer Name", "mail.firma.de", "rechnung", "Neu456"));
    assertEquals("mail.firma.de", TestWiring.mailAccountRepository().findById(id).host());
    // An empty password removes it.
    assertFalse(
        service.update(id, input("Neuer Name", "mail.firma.de", "rechnung", "")).hasPassword());
  }

  @Test
  void thereIsAlwaysOneDefaultAccount() {
    int first = service.create(input("Eins", "smtp.firma.de", "", null)).id();
    int second = service.create(input("Zwei", "smtp.firma.de", "", null)).id();
    assertFalse(
        service.list().stream().filter(a -> a.id() == second).findFirst().get().isDefault());

    assertTrue(service.makeDefault(second));
    List<MailAccountService.AccountView> list = service.list();
    assertEquals(second, list.get(0).id(), "the default comes first");
    assertEquals(1, list.stream().filter(MailAccountService.AccountView::isDefault).count());

    assertTrue(service.delete(second));
    assertTrue(service.list().get(0).isDefault(), "the next account becomes the default");
    assertEquals(first, service.list().get(0).id());
    assertFalse(service.delete(second));
    assertNull(service.update(second, input("x", "smtp.firma.de", "", null)));
  }

  @Test
  void unusableSettingsAreRefusedWithAnExplanation() {
    assertThrows(
        BadRequestException.class,
        () -> service.create(input("", "smtp.firma.de", "", null)),
        "a name is required");
    assertThrows(
        BadRequestException.class,
        () -> service.create(input("Konto", "https://smtp.firma.de/", "", null)));
    assertThrows(
        BadRequestException.class,
        () ->
            service.create(
                new MailAccountService.AccountInput(
                    "Konto", "smtp.firma.de", 25, "NONE", "user", "pw", "a@firma.de", "", null)),
        "credentials without encryption");
    assertThrows(
        BadRequestException.class,
        () ->
            service.create(
                new MailAccountService.AccountInput(
                    "Konto",
                    "smtp.firma.de",
                    587,
                    "STARTTLS",
                    "",
                    null,
                    "keine adresse",
                    "",
                    null)));
    assertThrows(
        BadRequestException.class,
        () ->
            service.create(
                new MailAccountService.AccountInput(
                    "Konto",
                    "smtp.firma.de",
                    587,
                    "STARTTLS",
                    "",
                    null,
                    "a@firma.de",
                    "Name\r\nBcc: x@y.de",
                    null)),
        "no header injection through the sender name");
  }

  @Test
  void saasRefusesAnInternalMailServer() {
    MailAccountService saas =
        TestWiring.mailAccountService(
            CredentialCipher.withKey(KEY, () -> 1),
            new MailHostPolicy(true),
            new MailMetrics(meters));
    BadRequestException e =
        assertThrows(
            BadRequestException.class, () -> saas.create(input("Intern", "127.0.0.1", "u", "p")));
    assertTrue(e.getMessage().contains("interne Adresse"), e.getMessage());
    assertTrue(saas.capabilities().restricted());
  }

  @Test
  void withoutAKeyNoPasswordIsStored() {
    MailAccountService noKey =
        TestWiring.mailAccountService(
            CredentialCipher.none(() -> 1), new MailHostPolicy(false), new MailMetrics(meters));
    assertFalse(noKey.capabilities().passwordsStorable());
    BadRequestException e =
        assertThrows(
            BadRequestException.class,
            () -> noKey.create(input("Konto", "smtp.firma.de", "u", "p")));
    assertTrue(e.getMessage().contains("BILLERY_SECRETS_KEY"), e.getMessage());
    // An account without password (a relay that needs none) works without a key.
    assertFalse(noKey.create(input("Relay", "smtp.firma.de", "", null)).hasPassword());
  }

  @Test
  void theTestMailGoesToTheSenderAddressAndIsCounted() throws Exception {
    try (FakeSmtpServer server = new FakeSmtpServer()) {
      int id =
          service
              .create(
                  new MailAccountService.AccountInput(
                      "Lokal",
                      "127.0.0.1",
                      server.port(),
                      "NONE",
                      "",
                      null,
                      "rechnung@firma.de",
                      "Firma",
                      null))
              .id();
      assertTrue(service.test(id));
      assertEquals(List.of("rechnung@firma.de"), server.received().get(0).recipients());
      assertEquals(
          1.0,
          meters
              .get("billery.mails")
              .tag("kind", "company")
              .tag("outcome", "sent")
              .counter()
              .count());

      server.refuseRecipients(true);
      MailDeliveryException e = assertThrows(MailDeliveryException.class, () -> service.test(id));
      assertTrue(e.getMessage().contains("abgelehnt"), e.getMessage());
      assertFalse(e.limitReached());
      assertEquals(
          1.0,
          meters
              .get("billery.mails")
              .tag("kind", "company")
              .tag("outcome", "failed")
              .tag("reason", "rejected")
              .counter()
              .count());
    }
  }
}
