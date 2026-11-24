package de.billery.mail;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link MailHostPolicy}: in SaaS a company's mail server must be public, on a submission port and
 * encrypted, checked when the account is saved and again when the socket connects.
 */
class MailHostPolicyTest {

  private static final MailHostPolicy RESTRICTED = new MailHostPolicy(true);
  private static final MailHostPolicy OPEN = new MailHostPolicy(false);

  @ParameterizedTest
  @ValueSource(
      strings = {
        "127.0.0.1",
        "10.1.2.3",
        "172.16.0.1",
        "192.168.1.1",
        "169.254.169.254",
        "100.64.0.1",
        "0.0.0.0",
        "224.0.0.1",
        "255.255.255.255",
        "::1",
        "fe80::1",
        "fd00::1",
        "::ffff:10.0.0.1",
        "64:ff9b::a00:1",
        "192.0.2.10",
        "198.51.100.10",
        "203.0.113.10",
        "2001:db8::1",
        "3fff::1",
        "2002:a00:1::1",
        "2001::1",
        "2001:2::1",
        "100::1",
        "::10.0.0.1",
        "64:ff9b:1::a00:1",
        "4000::1"
      })
  void internalAddressesAreNotPublic(String address) throws UnknownHostException {
    assertFalse(MailHostPolicy.isPublic(InetAddress.getByName(address)), address);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"8.8.8.8", "193.99.144.80", "2a00:1450:4001:80b::2005", "64:ff9b::808:808"})
  void publicAddressesArePublic(String address) throws UnknownHostException {
    assertTrue(MailHostPolicy.isPublic(InetAddress.getByName(address)), address);
  }

  @Test
  void restrictedAccountsNeedASubmissionPortEncryptionAndAPublicServer() {
    assertDoesNotThrow(
        () -> RESTRICTED.checkAccount("8.8.8.8", 587, MailSettings.Security.STARTTLS));
    assertDoesNotThrow(() -> RESTRICTED.checkAccount("8.8.8.8", 465, MailSettings.Security.SSL));
    assertThrows(
        IllegalArgumentException.class,
        () -> RESTRICTED.checkAccount("8.8.8.8", 5432, MailSettings.Security.STARTTLS));
    assertThrows(
        IllegalArgumentException.class,
        () -> RESTRICTED.checkAccount("8.8.8.8", 25, MailSettings.Security.NONE));
    IllegalArgumentException internal =
        assertThrows(
            IllegalArgumentException.class,
            () -> RESTRICTED.checkAccount("127.0.0.1", 587, MailSettings.Security.STARTTLS));
    assertTrue(internal.getMessage().contains("interne Adresse"), internal.getMessage());
  }

  @Test
  void selfHostAllowsAnyServer() {
    assertDoesNotThrow(() -> OPEN.checkAccount("127.0.0.1", 2526, MailSettings.Security.NONE));
    assertNull(OPEN.socketFactory());
  }

  @Test
  void theSocketChecksTheResolvedAddressWhenItConnects() {
    assertThrows(
        MailHostRefusedException.class,
        () -> MailHostPolicy.checkConnect(new InetSocketAddress("127.0.0.1", 587)));
    assertThrows(
        MailHostRefusedException.class,
        () -> MailHostPolicy.checkConnect(new InetSocketAddress("8.8.8.8", 5432)));
    assertThrows(
        MailHostRefusedException.class,
        () -> MailHostPolicy.checkConnect(InetSocketAddress.createUnresolved("smtp.example", 587)));
    assertDoesNotThrow(() -> MailHostPolicy.checkConnect(new InetSocketAddress("8.8.8.8", 587)));
  }

  /**
   * The mail library really connects through the guarded socket, whatever the security setting: the
   * fake server on the loopback interface is never reached.
   */
  @ParameterizedTest
  @EnumSource(MailSettings.Security.class)
  void theMailLibraryConnectsThroughTheGuard(MailSettings.Security security) throws Exception {
    try (FakeSmtpServer server = new FakeSmtpServer()) {
      MailMessage message = new MailMessage();
      message.setTo("bob@example.com");
      message.setSubject("Test");
      message.setText("Text");
      SmtpEndpoint endpoint =
          new SmtpEndpoint("127.0.0.1", server.port(), security, "", "", "billery@example.com", "");

      MailException e =
          assertThrows(
              MailException.class, () -> SmtpTransport.send(endpoint, message, RESTRICTED));

      assertEquals(MailException.Reason.REFUSED_HOST, e.reason(), chain(e));
      assertEquals(0, server.connections(), "no connection reached the internal server");
    }
  }

  @Test
  void withoutRestrictionTheSameServerIsReached() throws Exception {
    try (FakeSmtpServer server = new FakeSmtpServer()) {
      MailMessage message = new MailMessage();
      message.setTo("bob@example.com");
      message.setSubject("Test");
      message.setText("Text");
      SmtpTransport.send(
          new SmtpEndpoint(
              "127.0.0.1",
              server.port(),
              MailSettings.Security.NONE,
              "",
              "",
              "billery@example.com",
              "Billery"),
          message,
          OPEN);
      assertEquals(1, server.received().size());
      assertTrue(server.received().get(0).data().contains("From: Billery <billery@example.com>"));
    }
  }

  private static String chain(Throwable e) {
    StringBuilder out = new StringBuilder();
    for (Throwable t = e; t != null; t = t.getCause()) {
      out.append(t.getClass().getName()).append(": ").append(t.getMessage()).append(" | ");
    }
    return out.toString();
  }
}
