package de.billery.mail;

import de.billery.api.error.ErrorCode;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.Set;
import javax.net.SocketFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Which mail servers a company's SMTP account may name.
 *
 * <p>Self-host and desktop: any (the company's own relay on the same host or in the local network
 * is normal). SaaS: the host is a value typed by a customer, and the server connects to it; without
 * a limit a customer could make the server open connections into the operator's own network (the
 * database, a cloud metadata service) and read the answers out of the error messages. So in the
 * {@code saas} profile only public addresses and the usual submission ports (25, 465, 587, 2525)
 * are allowed, with an encrypted connection.
 *
 * <p>The check runs twice: when an account is saved ({@link #checkAccount}, with an explanation),
 * and on every connection, at the moment the socket connects to the resolved address ({@link
 * #socketFactory()}), so a host name that resolves to a public address when it is saved and to an
 * internal one later (DNS rebinding) is refused too.
 */
@Component
public class MailHostPolicy {

  /** The ports of SMTP submission and relay, the only ones allowed when restricted. */
  static final Set<Integer> ALLOWED_PORTS = Set.of(25, 465, 587, 2525);

  private final boolean restrictedHosts;

  /**
   * Restricted in the {@code saas} profile, unless {@code billery.mail.allow-private-hosts} is set
   * (the SaaS test suite, whose fake mail server runs on the loopback interface).
   *
   * @param env the Spring environment
   */
  @Autowired
  public MailHostPolicy(Environment env) {
    this(
        env.acceptsProfiles(Profiles.of("saas"))
            && !env.getProperty("billery.mail.allow-private-hosts", Boolean.class, false));
  }

  /**
   * @param restricted whether only public addresses and the submission ports are allowed
   */
  public MailHostPolicy(boolean restricted) {
    this.restrictedHosts = restricted;
  }

  /**
   * @return true when only public mail servers are allowed (SaaS)
   */
  public boolean restricted() {
    return restrictedHosts;
  }

  /**
   * Checks the settings of an account before they are stored.
   *
   * @param host the mail server
   * @param port its port
   * @param security how the connection is secured
   * @throws MailHostNotAllowedException with a coded explanation if the server is not allowed
   */
  public void checkAccount(String host, int port, MailSettings.Security security) {
    if (!restrictedHosts) {
      return;
    }
    if (!ALLOWED_PORTS.contains(port)) {
      throw new MailHostNotAllowedException(ErrorCode.MAIL_PORT_NOT_ALLOWED.with());
    }
    if (security == MailSettings.Security.NONE) {
      throw new MailHostNotAllowedException(ErrorCode.MAIL_ENCRYPTION_REQUIRED.with());
    }
    InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(host);
    } catch (UnknownHostException e) {
      throw new MailHostNotAllowedException(ErrorCode.MAIL_HOST_UNKNOWN.with("host", host), e);
    }
    for (InetAddress address : addresses) {
      if (!isPublic(address)) {
        throw new MailHostNotAllowedException(ErrorCode.MAIL_HOST_INTERNAL.with("host", host));
      }
    }
  }

  /**
   * The socket factory the mail library connects with: it refuses a connection to an address or
   * port that is not allowed, at the moment of connecting.
   *
   * @return the guarding factory, or null when nothing is restricted
   */
  public SocketFactory socketFactory() {
    return restrictedHosts ? new GuardedSocketFactory() : null;
  }

  /**
   * Tells whether an address is reachable on the public internet: not loopback, private,
   * link-local, carrier-grade NAT, unique-local, multicast or otherwise reserved.
   *
   * @param address the resolved address
   * @return true if a connection to it is allowed
   */
  static boolean isPublic(InetAddress address) {
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return false;
    }
    byte[] b = address.getAddress();
    if (address instanceof Inet4Address) {
      int first = b[0] & 0xff;
      int second = b[1] & 0xff;
      return first != 0 // "this network"
          && !(first == 100 && second >= 64 && second <= 127) // carrier-grade NAT
          && !(first == 192 && second == 0 && (b[2] & 0xff) == 0) // IETF protocol assignments
          && !(first == 198 && (second == 18 || second == 19)) // benchmarking
          && !(first == 192 && second == 0 && (b[2] & 0xff) == 2) // documentation
          && !(first == 198 && second == 51 && (b[2] & 0xff) == 100) // documentation
          && !(first == 203 && second == 0 && (b[2] & 0xff) == 113) // documentation
          && first < 240; // reserved and broadcast
    }
    if (address instanceof Inet6Address) {
      if (isNat64(b)) { // 64:ff9b::/96 carries an IPv4 address
        try {
          return isPublic(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
        } catch (UnknownHostException e) {
          return false;
        }
      }
      return isGlobalUnicast6(b);
    }
    return true;
  }

  /**
   * IPv6 is checked by an allow-list: only global unicast ({@code 2000::/3}), without the IETF
   * protocol assignments ({@code 2001::/23}: Teredo, benchmarking, ORCHID), the documentation
   * ranges ({@code 2001:db8::/32}, {@code 3fff::/20}) and 6to4 ({@code 2002::/16}, which embeds an
   * IPv4 address). Everything outside {@code 2000::/3} (unique local, link-local, discard,
   * IPv4-compatible, local NAT64 and anything assigned later) is refused.
   */
  private static boolean isGlobalUnicast6(byte[] b) {
    if ((b[0] & 0xe0) != 0x20) {
      return false;
    }
    int first = ((b[0] & 0xff) << 8) | (b[1] & 0xff);
    if (first == 0x2002) {
      return false;
    }
    int second = ((b[2] & 0xff) << 8) | (b[3] & 0xff);
    if (first == 0x2001 && (second < 0x0200 || second == 0x0db8)) {
      return false;
    }
    return !(first == 0x3fff && (second & 0xf000) == 0);
  }

  private static boolean isNat64(byte[] b) {
    if (b.length != 16
        || b[0] != 0
        || b[1] != 0x64
        || (b[2] & 0xff) != 0xff
        || (b[3] & 0xff) != 0x9b) {
      return false;
    }
    for (int i = 4; i < 12; i++) {
      if (b[i] != 0) {
        return false;
      }
    }
    return true;
  }

  /** Checks the address a socket is about to connect to. */
  static void checkConnect(SocketAddress endpoint) throws IOException {
    if (!(endpoint instanceof InetSocketAddress inet) || inet.isUnresolved()) {
      throw new MailHostRefusedException("Mail server address could not be resolved");
    }
    if (!ALLOWED_PORTS.contains(inet.getPort()) || !isPublic(inet.getAddress())) {
      throw new MailHostRefusedException(
          "Connection to " + inet.getHostString() + ":" + inet.getPort() + " is not allowed");
    }
  }

  /** A socket that checks its destination before it connects. */
  static final class GuardedSocket extends Socket {

    @Override
    public void connect(SocketAddress endpoint, int timeout) throws IOException {
      checkConnect(endpoint);
      super.connect(endpoint, timeout);
    }
  }

  /** Creates {@link GuardedSocket}s; the mail library connects them itself. */
  static final class GuardedSocketFactory extends SocketFactory {

    @Override
    public Socket createSocket() {
      return new GuardedSocket();
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
      Socket socket = createSocket();
      socket.connect(new InetSocketAddress(host, port));
      return socket;
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
        throws IOException {
      Socket socket = createSocket();
      socket.bind(new InetSocketAddress(localHost, localPort));
      socket.connect(new InetSocketAddress(host, port));
      return socket;
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
      Socket socket = createSocket();
      socket.connect(new InetSocketAddress(host, port));
      return socket;
    }

    @Override
    public Socket createSocket(
        InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
      Socket socket = createSocket();
      socket.bind(new InetSocketAddress(localAddress, localPort));
      socket.connect(new InetSocketAddress(address, port));
      return socket;
    }
  }
}
