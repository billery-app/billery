package de.billery.saas.provisioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.billery.mail.MailException;
import de.billery.mail.MailMessage;
import de.billery.mail.MailProvider;
import de.billery.saas.identity.Role;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The invitation mail: its text, what user-typed names cannot do, and the outcomes. */
class InvitationMailerTest {

  private static final InvitationService.Invitation INVITATION =
      new InvitationService.Invitation(
          UUID.randomUUID(),
          "bob@example.com",
          Role.ACCOUNTANT,
          Instant.parse("2026-11-05T10:00:00Z"),
          Instant.parse("2026-11-19T10:00:00Z"));

  @Test
  void tellsWhoInvitesToWhichCompanyWithWhichRoleWhereAndUntilWhen() {
    MailMessage mail =
        InvitationMailer.compose(
            INVITATION, "Olga AG", "Olga Owner", "https://billery.example.com");

    assertEquals("bob@example.com", mail.getTo());
    assertEquals("Einladung zu „Olga AG“ in Billery", mail.getSubject());
    String text = mail.getText();
    assertTrue(text.contains("Olga Owner hat Sie eingeladen"), text);
    assertTrue(text.contains("„Olga AG“"), text);
    assertTrue(text.contains("Rolle: Buchhaltung / Steuerberatung"), text);
    assertTrue(text.contains("Öffnen Sie https://billery.example.com\n"), text);
    assertTrue(text.contains("mit genau dieser E-Mail-Adresse an: bob@example.com"), text);
    assertTrue(text.contains("gilt bis zum 19.11.2026"), text);
  }

  @Test
  void aCompanyNameCannotAddHeaderLinesOrTextBlocks() {
    MailMessage mail =
        InvitationMailer.compose(
            INVITATION, "Evil\r\nBcc: victim@example.com", "A\nB", "https://app.test");

    assertEquals("Einladung zu „Evil Bcc: victim@example.com“ in Billery", mail.getSubject());
    assertFalse(mail.getSubject().contains("\n"));
    assertTrue(mail.getText().startsWith("Hallo,\n\nA B hat Sie eingeladen"), mail.getText());
  }

  @Test
  void withoutAnInviterNameTheTextStillReads() {
    String text = InvitationMailer.compose(INVITATION, "X", " ", "https://app.test").getText();
    assertTrue(text.startsWith("Hallo,\n\nSie wurden eingeladen, dem Unternehmen „X“"), text);
  }

  @Test
  void withoutMailSettingsNothingIsSentAndTheReasonIsGiven() {
    List<MailMessage> sent = new ArrayList<>();
    MailProvider unconfigured =
        new MailProvider() {
          @Override
          public void send(MailMessage message) {
            sent.add(message);
          }

          @Override
          public boolean configured() {
            return false;
          }
        };

    InvitationMailer.Outcome outcome =
        new InvitationMailer(unconfigured, null, null, "https://app.test")
            .send(INVITATION, "127.0.0.1");

    assertFalse(outcome.sent());
    assertEquals("Der Mailversand ist nicht eingerichtet.", outcome.problem());
    assertTrue(sent.isEmpty());
  }

  @Test
  void aFailedDeliveryIsReportedNotThrown() {
    MailProvider failing =
        message -> {
          throw new MailException("Mailversand fehlgeschlagen", new IllegalStateException("535"));
        };
    InvitationMailer.Outcome outcome =
        new InvitationMailer(failing, null, null, "https://app.test")
            .deliver(InvitationMailer.compose(INVITATION, "X", "Y", "https://app.test"));

    assertFalse(outcome.sent());
    assertEquals("Die E-Mail konnte nicht verschickt werden.", outcome.problem());
  }

  @Test
  void anAcceptedDeliveryIsReportedAsSent() {
    List<MailMessage> sent = new ArrayList<>();
    InvitationMailer.Outcome outcome =
        new InvitationMailer(sent::add, null, null, "https://app.test")
            .deliver(InvitationMailer.compose(INVITATION, "X", "Y", "https://app.test"));

    assertTrue(outcome.sent());
    assertNull(outcome.problem());
    assertEquals(1, sent.size());
  }

  @Test
  void withoutThePublicAddressNoMailGoesOutEvenWithAnSmtpAccount() {
    List<MailMessage> sent = new ArrayList<>();

    InvitationMailer.Outcome outcome =
        new InvitationMailer(sent::add, null, null, " ").send(INVITATION, "127.0.0.1");

    assertFalse(outcome.sent());
    assertEquals("Der Mailversand ist nicht eingerichtet.", outcome.problem());
    assertTrue(sent.isEmpty());
  }

  @Test
  void theLogKeepsTheServersAnswerButNotTheAddress() {
    String summary =
        InvitationMailer.failureSummary(
            new MailException(
                "Mailversand fehlgeschlagen",
                new IllegalStateException(
                    "Third party error",
                    new IllegalArgumentException(
                        "550 5.1.1 <bob@example.com>: Recipient address rejected, also Eve.X@y.de"))));

    assertEquals(
        "IllegalArgumentException: 550 5.1.1 <<address>>: Recipient address rejected, also"
            + " <address>",
        summary);
  }

  @Test
  void thePublicAddressIsAPlainHttpUrl() {
    assertEquals("https://app.test", InvitationMailer.normalizeUrl(" https://app.test/ "));
    assertEquals("http://localhost:8080", InvitationMailer.normalizeUrl("http://localhost:8080"));
    assertEquals("http://127.0.0.1:8080", InvitationMailer.normalizeUrl("http://127.0.0.1:8080"));
    assertEquals("http://[::1]:8080", InvitationMailer.normalizeUrl("http://[::1]:8080"));
    assertEquals("", InvitationMailer.normalizeUrl(null));
    for (String bad :
        List.of(
            "app.test",
            "ftp://app.test",
            "http://app.test",
            "http://localhost.evil.test",
            "https://user:pw@app.test",
            "https://app.test/?a=b",
            "https://app.test/#x",
            "https://bad host")) {
      assertThrows(IllegalStateException.class, () -> InvitationMailer.normalizeUrl(bad), bad);
    }
  }
}
