package de.billery.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The MAIL_* settings: off without host or sender, the usual ports, a clear error for typos. */
class MailSettingsTest {

  @Test
  void mailIsOffWithoutHostOrSender() {
    assertFalse(new MailSettings("", "", "", "", "", "").configured());
    assertFalse(new MailSettings("smtp.example.com", "", "", "", " ", "").configured());
    assertFalse(new MailSettings(" ", "", "", "", "app@example.com", "").configured());
    assertTrue(
        new MailSettings("smtp.example.com", "", "", "", "app@example.com", "").configured());
    assertFalse(new SmtpMailProvider(new MailSettings("", "", "", "", "", "")).configured());
  }

  @Test
  void startTlsOnPort587IsTheDefaultAndEachModeHasItsUsualPort() {
    MailSettings plain = new MailSettings("h", "", "", "", "f@x.de", "");
    assertEquals(MailSettings.Security.STARTTLS, plain.getSecurity());
    assertEquals(587, plain.getPort());
    assertEquals(465, new MailSettings("h", "", "", "", "f@x.de", "SSL").getPort());
    assertEquals(25, new MailSettings("h", "", "", "", "f@x.de", "none").getPort());
    assertEquals(2525, new MailSettings("h", "2525", "", "", "f@x.de", "starttls").getPort());
  }

  @Test
  void anEmptyPortFromComposeMeansTheUsualPortAndGarbageStopsTheStart() {
    assertEquals(587, new MailSettings("h", " ", "", "", "f@x.de", "").getPort());
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class, () -> new MailSettings("h", "smtp", "", "", "f@x.de", ""));
    assertTrue(e.getMessage().contains("MAIL_PORT"), e.getMessage());
  }

  @Test
  void aLoginWithoutEncryptionStopsTheStart() {
    for (String[] login : new String[][] {{"user", "pw"}, {"user", ""}, {"", "pw"}}) {
      IllegalStateException e =
          assertThrows(
              IllegalStateException.class,
              () -> new MailSettings("h", "", login[0], login[1], "f@x.de", "none"));
      assertTrue(e.getMessage().contains("MAIL_SECURITY=none"), e.getMessage());
    }
    assertEquals(
        MailSettings.Security.STARTTLS,
        new MailSettings("h", "", "user", "pw", "f@x.de", "starttls").getSecurity());
    assertEquals(
        MailSettings.Security.SSL,
        new MailSettings("h", "", "user", "pw", "f@x.de", "ssl").getSecurity());
  }

  @Test
  void anUnknownSecurityModeStopsTheStartWithItsName() {
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class, () -> new MailSettings("h", "", "", "", "f@x.de", "tls"));
    assertTrue(e.getMessage().contains("'tls'"), e.getMessage());
  }

  @Test
  void sendingWithoutSettingsFailsInsteadOfConnecting() {
    SmtpMailProvider provider = new SmtpMailProvider(new MailSettings("", "", "", "", "", ""));
    MailMessage message = new MailMessage();
    message.setTo("a@example.com");
    assertThrows(MailException.class, () -> provider.send(message));
  }
}
