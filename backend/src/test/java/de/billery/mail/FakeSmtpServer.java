package de.billery.mail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal SMTP server on the loopback interface for tests (plain SMTP, no login): accepts any
 * number of sessions one after another and keeps what it received. Can refuse every recipient.
 */
public final class FakeSmtpServer implements AutoCloseable {

  /** One received message: the envelope recipients and the DATA part. */
  public record Received(List<String> recipients, String data) {}

  private final ServerSocket server;
  private final Thread thread;
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private final AtomicInteger connections = new AtomicInteger();
  private volatile boolean refuseRecipients;

  /**
   * Starts the server on a free port.
   *
   * @throws IOException if no port is free
   */
  public FakeSmtpServer() throws IOException {
    // A fake SMTP server on the loopback interface, for tests only.
    // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
    server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
    thread = new Thread(this::serve, "fake-smtp");
    thread.setDaemon(true);
    thread.start();
  }

  /**
   * @return the port
   */
  public int port() {
    return server.getLocalPort();
  }

  /**
   * @param refuse whether RCPT TO is answered with 550
   */
  public void refuseRecipients(boolean refuse) {
    this.refuseRecipients = refuse;
  }

  /**
   * @return the messages received so far
   */
  public List<Received> received() {
    return List.copyOf(received);
  }

  /**
   * @return how many connections were accepted
   */
  public int connections() {
    return connections.get();
  }

  private void serve() {
    while (!server.isClosed()) {
      try (Socket socket = server.accept()) {
        connections.incrementAndGet();
        session(socket);
      } catch (IOException e) {
        // closed, or the client went away
      }
    }
  }

  private void session(Socket socket) throws IOException {
    BufferedReader in =
        new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
    PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
    out.print("220 test ESMTP\r\n");
    out.flush();
    List<String> recipients = new CopyOnWriteArrayList<>();
    StringBuilder data = new StringBuilder();
    boolean inData = false;
    String line;
    while ((line = in.readLine()) != null) {
      if (inData) {
        if (".".equals(line)) {
          inData = false;
          received.add(new Received(List.copyOf(recipients), data.toString()));
          recipients.clear();
          data.setLength(0);
          out.print("250 queued\r\n");
        } else {
          data.append(line).append('\n');
        }
      } else if (line.startsWith("EHLO") || line.startsWith("HELO")) {
        out.print("250 test\r\n");
      } else if (line.startsWith("RCPT TO")) {
        if (refuseRecipients) {
          out.print("550 no such user\r\n");
        } else {
          recipients.add(line.substring(line.indexOf('<') + 1, line.indexOf('>')));
          out.print("250 ok\r\n");
        }
      } else if (line.startsWith("DATA")) {
        inData = true;
        out.print("354 go ahead\r\n");
      } else if (line.startsWith("QUIT")) {
        out.print("221 bye\r\n");
        out.flush();
        return;
      } else {
        out.print("250 ok\r\n");
      }
      out.flush();
    }
  }

  @Override
  public void close() throws IOException {
    server.close();
  }
}
