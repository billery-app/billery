package de.billery.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * {@link SmtpMailProvider} against a minimal SMTP server in the test (plain SMTP, as with {@code
 * MAIL_SECURITY=none}): the message really goes over the wire, with sender, recipient, subject and
 * text, and a server that refuses it becomes a {@link MailException}.
 */
class SmtpMailProviderTest {

  /** Accepts one SMTP session and returns the DATA part, or refuses at RCPT TO. */
  private static CompletableFuture<String> serveOnce(ServerSocket server, boolean refuse) {
    return CompletableFuture.supplyAsync(
        () -> {
          try (Socket socket = server.accept();
              BufferedReader in =
                  new BufferedReader(
                      new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
              PrintWriter out =
                  new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {
            out.print("220 test ESMTP\r\n");
            out.flush();
            StringBuilder data = new StringBuilder();
            boolean inData = false;
            String line;
            while ((line = in.readLine()) != null) {
              if (inData) {
                if (".".equals(line)) {
                  inData = false;
                  out.print("250 queued\r\n");
                } else {
                  data.append(line).append('\n');
                }
              } else if (line.startsWith("EHLO") || line.startsWith("HELO")) {
                out.print("250 test\r\n");
              } else if (line.startsWith("RCPT TO") && refuse) {
                out.print("550 no such user\r\n");
              } else if (line.startsWith("DATA")) {
                inData = true;
                out.print("354 go ahead\r\n");
              } else if (line.startsWith("QUIT")) {
                out.print("221 bye\r\n");
                out.flush();
                break;
              } else {
                out.print("250 ok\r\n");
              }
              out.flush();
            }
            return data.toString();
          } catch (IOException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  private static MailMessage message() {
    MailMessage message = new MailMessage();
    message.setTo("bob@example.com");
    message.setSubject("Einladung zu „Olga AG“ in Billery");
    message.setText("Hallo,\n\nÖffnen Sie https://billery.example.com\n");
    return message;
  }

  @Test
  void sendsTheMessageOverSmtp() throws Exception {
    // A fake SMTP server on the loopback interface, for this test only.
    // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      CompletableFuture<String> received = serveOnce(server, false);
      SmtpMailProvider provider =
          new SmtpMailProvider(
              new MailSettings(
                  "127.0.0.1",
                  String.valueOf(server.getLocalPort()),
                  "",
                  "",
                  "billery@example.com",
                  "none"));

      provider.send(message());

      String data = received.get(10, TimeUnit.SECONDS);
      assertTrue(data.contains("From: billery@example.com"), data);
      assertTrue(data.contains("To: bob@example.com"), data);
      assertTrue(data.contains("Subject:"), data);
      assertTrue(data.contains("Billery"), data);
    }
  }

  @Test
  void aRefusedRecipientIsAMailException() throws Exception {
    // A fake SMTP server on the loopback interface, for this test only.
    // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      CompletableFuture<String> session = serveOnce(server, true);
      SmtpMailProvider provider =
          new SmtpMailProvider(
              new MailSettings(
                  "127.0.0.1",
                  String.valueOf(server.getLocalPort()),
                  "",
                  "",
                  "billery@example.com",
                  "none"));

      MailException e = assertThrows(MailException.class, () -> provider.send(message()));
      assertEquals("Mailversand fehlgeschlagen", e.getMessage());
      session.get(10, TimeUnit.SECONDS);
    }
  }
}
