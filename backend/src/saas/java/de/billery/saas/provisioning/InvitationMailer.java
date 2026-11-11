package de.billery.saas.provisioning;

import de.billery.mail.MailException;
import de.billery.mail.MailMessage;
import de.billery.mail.MailProvider;
import de.billery.saas.identity.Role;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Tells the invited person about an invitation by e-mail (SMTP account from {@code MAIL_*}). The
 * invitation itself is the database row: a mail that cannot be sent leaves it in place, and the
 * caller reports that the person has to be told another way. Plain text only; names typed by users
 * (company, inviter) are stripped of control characters so they cannot add header lines.
 *
 * <p>Anyone who signs up can invite, so two things keep this from being a way to send mail to
 * strangers through our SMTP account: the app's address in the mail comes only from {@code
 * BILLERY_PUBLIC_URL}, never from the request (whose host the caller controls), and {@link
 * InvitationMailQuota} limits the mails per user, per recipient and for the whole system.
 */
@Service
@Profile("saas")
public class InvitationMailer {

  private static final Logger LOG = LoggerFactory.getLogger(InvitationMailer.class);

  private static final DateTimeFormatter GERMAN_DATE =
      DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMAN).withZone(ZoneId.of("Europe/Berlin"));

  /** The role names shown to users, as in the app ({@code frontend/src/utils/roles.js}). */
  private static final Map<Role, String> ROLE_LABELS =
      Map.of(
          Role.OWNER, "Inhaber",
          Role.ADMIN, "Administrator",
          Role.ACCOUNTANT, "Buchhaltung / Steuerberatung",
          Role.READER, "Nur lesen");

  /** The hosts {@code BILLERY_PUBLIC_URL} may name with plain http. */
  @SuppressWarnings("PMD.AvoidUsingHardCodedIP") // the loopback address, not a deployment
  private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

  private final MailProvider mail;
  private final JdbcClient jdbc;
  private final InvitationMailQuota quota;
  private final String publicUrl;

  /**
   * What happened to the mail.
   *
   * @param sent whether the SMTP server accepted it
   * @param problem why not (German, shown to the inviting user), null when sent
   */
  public record Outcome(boolean sent, String problem) {}

  /**
   * @param mail the SMTP sender
   * @param jdbc reads the company name and the inviting user's name
   * @param quota the limits of invitation mails
   * @param publicUrl where the app is reached ({@code BILLERY_PUBLIC_URL}); empty switches the
   *     invitation mail off
   * @throws IllegalStateException if the address is set but not a plain https URL (http only for
   *     this machine)
   */
  public InvitationMailer(
      MailProvider mail,
      JdbcClient jdbc,
      InvitationMailQuota quota,
      @Value("${billery.public-url:}") String publicUrl) {
    this.mail = mail;
    this.jdbc = jdbc;
    this.quota = quota;
    this.publicUrl = normalizeUrl(publicUrl);
  }

  /**
   * Checks the configured address and removes a trailing slash.
   *
   * @param value the configured value
   * @return the address, empty when none is configured
   * @throws IllegalStateException if it is not an https URL (http only for this machine) with a
   *     host and nothing else
   */
  static String normalizeUrl(String value) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) {
      return "";
    }
    try {
      URI uri = new URI(trimmed);
      if (allowedScheme(uri)
          && uri.getHost() != null
          && uri.getUserInfo() == null
          && uri.getQuery() == null
          && uri.getFragment() == null) {
        return trimmed.replaceAll("/+$", "");
      }
    } catch (URISyntaxException e) {
      throw new IllegalStateException("BILLERY_PUBLIC_URL is not a URL: '" + trimmed + "'", e);
    }
    throw new IllegalStateException(
        "BILLERY_PUBLIC_URL must look like https://app.example.com (http only for this machine),"
            + " not '"
            + trimmed
            + "'");
  }

  /** https everywhere, http only for this machine: the sign-in must not travel unencrypted. */
  private static boolean allowedScheme(URI uri) {
    return "https".equalsIgnoreCase(uri.getScheme())
        || "http".equalsIgnoreCase(uri.getScheme())
            && uri.getHost() != null
            && LOOPBACK_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT));
  }

  /**
   * Sends the invitation mail for an invitation of the active tenant.
   *
   * @param invitation the invitation just created
   * @param sourceIp the address the request came from (counted per source)
   * @return whether it was sent, and why not
   */
  public Outcome send(InvitationService.Invitation invitation, String sourceIp) {
    if (!mail.configured() || publicUrl.isEmpty()) {
      return new Outcome(false, "Der Mailversand ist nicht eingerichtet.");
    }
    if (!quota.claim(Permissions.user(), invitation.email(), sourceIp)) {
      return new Outcome(false, "Das Tageslimit für Einladungsmails ist erreicht.");
    }
    String company =
        jdbc.sql("SELECT name FROM tenant WHERE id = :tenant")
            .param("tenant", Permissions.tenant())
            .query(String.class)
            .optional()
            .orElse("");
    String inviter =
        jdbc.sql("SELECT COALESCE(NULLIF(display_name, ''), email) FROM app_user WHERE id = :id")
            .param("id", Permissions.user())
            .query(String.class)
            .optional()
            .orElse("");
    return deliver(compose(invitation, company, inviter, publicUrl));
  }

  /**
   * Hands a composed mail to the SMTP server.
   *
   * @param message the mail
   * @return sent, or the reason shown to the inviting user
   */
  Outcome deliver(MailMessage message) {
    try {
      mail.send(message);
      return new Outcome(true, null);
    } catch (MailException e) {
      LOG.warn("Invitation mail could not be sent: {}", failureSummary(e));
      return new Outcome(false, "Die E-Mail konnte nicht verschickt werden.");
    }
  }

  /**
   * The mail for an invitation: German, plain text, sender left to the mail settings.
   *
   * @param invitation the invitation (address, role, expiry)
   * @param company the inviting company's name
   * @param inviter the inviting user's name or address
   * @param appUrl where the app is reached
   * @return the message
   */
  static MailMessage compose(
      InvitationService.Invitation invitation, String company, String inviter, String appUrl) {
    String name = clean(company);
    String by = clean(inviter);
    MailMessage message = new MailMessage();
    message.setTo(invitation.email());
    message.setSubject("Einladung zu „" + name + "“ in Billery");
    message.setText(
        "Hallo,\n\n"
            + (by.isEmpty() ? "Sie wurden" : by + " hat Sie")
            + " eingeladen, dem Unternehmen „"
            + name
            + "“ in Billery beizutreten (Rolle: "
            + ROLE_LABELS.getOrDefault(invitation.role(), invitation.role().name())
            + ").\n\n"
            + "So nehmen Sie die Einladung an:\n"
            + "1. Öffnen Sie "
            + appUrl
            + "\n"
            + "2. Melden Sie sich mit genau dieser E-Mail-Adresse an: "
            + invitation.email()
            + "\n"
            + "3. Klicken Sie bei der Einladung auf „Annehmen“.\n\n"
            + "Die Einladung gilt bis zum "
            + GERMAN_DATE.format(invitation.expiresAt())
            + ".\n\n"
            + "Wenn Sie diese Einladung nicht erwartet haben, können Sie diese E-Mail ignorieren.\n");
    return message;
  }

  /**
   * What the log says about a failed delivery: the innermost cause, which holds the SMTP server's
   * answer, without the stack trace and without e-mail addresses. Servers often quote the refused
   * recipient, and the address is personal data.
   *
   * @param failure the failure
   * @return the type and message of the innermost cause, addresses replaced
   */
  static String failureSummary(Throwable failure) {
    Throwable root = failure;
    while (root.getCause() != null) {
      root = root.getCause();
    }
    String message = root.getMessage() == null ? "" : root.getMessage();
    return root.getClass().getSimpleName()
        + ": "
        + clean(message).replaceAll("[^\\s<>\"'(),;:]+@[^\\s<>\"'(),;:]+", "<address>");
  }

  /** Removes line breaks and other control characters and collapses spaces. */
  static String clean(String value) {
    if (value == null) {
      return "";
    }
    return value.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
  }
}
