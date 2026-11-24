package de.billery.service;

import de.billery.data.MailAccount;
import de.billery.mail.CompanyMailSender;
import de.billery.mail.CredentialCipher;
import de.billery.mail.MailAddresses;
import de.billery.mail.MailException;
import de.billery.mail.MailHostPolicy;
import de.billery.mail.MailMessage;
import de.billery.mail.MailSettings;
import de.billery.repository.MailAccountRepository;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The company's own SMTP accounts (Settings → Mail-Einstellungen): checks, stores and tests them.
 * Passwords are encrypted before they are stored ({@link CredentialCipher}) and never handed out
 * again: the views carry only whether one is stored.
 *
 * <p>When the server, port, security or login of an account with a stored password changes, the
 * password must be entered again. Otherwise whoever may change the settings could point the account
 * at a server of their own and receive the stored password at the next delivery.
 */
@Service
public class MailAccountService {

  private static final Logger LOG = LoggerFactory.getLogger(MailAccountService.class);

  /** Host names and IP addresses; no scheme, path, port or white space. */
  private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9.:-]{1,255}$");

  private final MailAccountRepository repository;
  private final CredentialCipher cipher;
  private final MailHostPolicy policy;
  private final CompanyMailSender sender;
  private final TransactionTemplate transactions;

  /**
   * An account as the settings page shows it: everything but the password.
   *
   * @param id the account
   * @param name its name
   * @param host the mail server
   * @param port the port
   * @param security {@code STARTTLS}, {@code SSL} or {@code NONE}
   * @param username the login
   * @param hasPassword whether a password is stored
   * @param fromAddress the sender address
   * @param fromName the sender's name
   * @param isDefault whether it is the default account
   */
  public record AccountView(
      int id,
      String name,
      String host,
      int port,
      String security,
      String username,
      boolean hasPassword,
      String fromAddress,
      String fromName,
      boolean isDefault) {

    static AccountView from(MailAccount a) {
      return new AccountView(
          a.id(),
          a.name(),
          a.host(),
          a.port(),
          a.security(),
          a.username(),
          a.hasPassword(),
          a.fromAddress(),
          a.fromName(),
          a.isDefault());
    }
  }

  /**
   * What the settings page sends.
   *
   * @param name its name
   * @param host the mail server
   * @param port the port; null or 0 picks the usual one of {@code security}
   * @param security {@code STARTTLS} (default), {@code SSL} or {@code NONE}
   * @param username the login, may be empty
   * @param password a new password; null keeps the stored one, empty removes it
   * @param fromAddress the sender address
   * @param fromName the sender's name, may be empty
   * @param isDefault whether it becomes the default account
   */
  public record AccountInput(
      String name,
      String host,
      Integer port,
      String security,
      String username,
      String password,
      String fromAddress,
      String fromName,
      Boolean isDefault) {}

  /**
   * What the settings page needs to know about this installation.
   *
   * @param passwordsStorable whether a key for the passwords exists ({@code BILLERY_SECRETS_KEY})
   * @param restricted whether only public mail servers with encryption are allowed (SaaS)
   */
  public record Capabilities(boolean passwordsStorable, boolean restricted) {}

  /**
   * @param repository the accounts
   * @param cipher encrypts the passwords
   * @param policy which servers are allowed
   * @param sender sends the test mail
   * @param transactions keeps a write and the default flag together
   */
  public MailAccountService(
      MailAccountRepository repository,
      CredentialCipher cipher,
      MailHostPolicy policy,
      CompanyMailSender sender,
      TransactionTemplate transactions) {
    this.repository = repository;
    this.cipher = cipher;
    this.policy = policy;
    this.sender = sender;
    this.transactions = transactions;
  }

  /**
   * @return what this installation allows
   */
  public Capabilities capabilities() {
    return new Capabilities(cipher.available(), policy.restricted());
  }

  /**
   * @return the company's accounts, default first
   */
  public List<AccountView> list() {
    return repository.findAll().stream().map(AccountView::from).toList();
  }

  /**
   * Stores a new account. The first account of a company becomes its default.
   *
   * @param input the settings
   * @return the stored account
   * @throws BadRequestException if a value is not acceptable
   */
  public AccountView create(AccountInput input) {
    Checked checked = check(input);
    String encrypted =
        checked.password == null || checked.password.isEmpty() ? null : encrypt(checked.password);
    MailAccount account =
        new MailAccount(
            0,
            checked.name,
            checked.host,
            checked.port,
            checked.security.name(),
            checked.username,
            encrypted,
            checked.fromAddress,
            checked.fromName,
            false);
    return transactions.execute(
        status -> {
          boolean first = repository.findAll().isEmpty();
          MailAccount stored = repository.insert(account);
          if (first || Boolean.TRUE.equals(input.isDefault())) {
            repository.makeDefault(stored.id());
          }
          return AccountView.from(repository.findById(stored.id()));
        });
  }

  /**
   * Changes an account.
   *
   * @param id the account
   * @param input the settings
   * @return the account, or null if the company has none with this id
   * @throws BadRequestException if a value is not acceptable, or the server or login changes while
   *     a password is stored and none is entered
   */
  public AccountView update(int id, AccountInput input) {
    Checked checked = check(input);
    return transactions.execute(
        status -> {
          MailAccount existing = repository.findById(id);
          if (existing == null) {
            return null;
          }
          String encrypted = storedPassword(existing, checked);
          repository.update(
              new MailAccount(
                  id,
                  checked.name,
                  checked.host,
                  checked.port,
                  checked.security.name(),
                  checked.username,
                  encrypted,
                  checked.fromAddress,
                  checked.fromName,
                  existing.isDefault()));
          if (Boolean.TRUE.equals(input.isDefault())) {
            repository.makeDefault(id);
          }
          return AccountView.from(repository.findById(id));
        });
  }

  /**
   * Deletes an account. If it was the default, the next one becomes the default.
   *
   * @param id the account
   * @return false if the company has none with this id
   */
  public boolean delete(int id) {
    return Boolean.TRUE.equals(
        transactions.execute(
            status -> {
              MailAccount existing = repository.findById(id);
              if (existing == null) {
                return false;
              }
              repository.delete(id);
              if (existing.isDefault()) {
                repository.findAll().stream()
                    .findFirst()
                    .ifPresent(next -> repository.makeDefault(next.id()));
              }
              return true;
            }));
  }

  /**
   * Makes an account the default.
   *
   * @param id the account
   * @return false if the company has none with this id
   */
  public boolean makeDefault(int id) {
    return Boolean.TRUE.equals(transactions.execute(status -> repository.makeDefault(id)));
  }

  /**
   * Sends a test mail through the account to its own sender address.
   *
   * @param id the account
   * @return false if the company has none with this id
   * @throws MailDeliveryException with the reason, when the mail could not be sent
   */
  public boolean test(int id) {
    MailAccount account = repository.findById(id);
    if (account == null) {
      return false;
    }
    MailMessage message = new MailMessage();
    message.setTo(account.fromAddress());
    message.setSubject("Testnachricht von Billery");
    message.setText(
        "Diese Nachricht wurde aus Billery über das Mailkonto „"
            + account.name()
            + "“ gesendet.\n\nDas Konto ist richtig eingerichtet: Rechnungen und Angebote können"
            + " darüber an Kunden gesendet werden.\n");
    try {
      sender.send(account, message);
    } catch (MailException e) {
      LOG.warn("Test mail of mail account {} failed: {}", id, e.reason());
      throw new MailDeliveryException(e.reason().explanation(), e);
    }
    return true;
  }

  /**
   * The password column after an update: the stored one when none is entered (refused when the
   * server or login changes), none when an empty one is entered, else the new one encrypted.
   */
  private String storedPassword(MailAccount existing, Checked checked) {
    if (checked.password == null) {
      boolean target =
          !existing.host().equalsIgnoreCase(checked.host)
              || existing.port() != checked.port
              || !existing.security().equals(checked.security.name())
              || !existing.username().equals(checked.username);
      if (target && existing.hasPassword()) {
        throw new BadRequestException(
            "Server, Port, Verschlüsselung oder Benutzername wurden geändert: bitte das"
                + " Passwort erneut eingeben.");
      }
      return existing.passwordEncrypted();
    }
    return checked.password.isEmpty() ? null : encrypt(checked.password);
  }

  private String encrypt(String password) {
    if (!cipher.available()) {
      throw new BadRequestException(
          "Passwörter können hier nicht gespeichert werden: der Betreiber hat keinen Schlüssel"
              + " eingerichtet (BILLERY_SECRETS_KEY).");
    }
    return cipher.encrypt(password);
  }

  /** The input, trimmed and checked. */
  private static final class Checked {
    String name;
    String host;
    int port;
    MailSettings.Security security;
    String username;
    String password;
    String fromAddress;
    String fromName;
  }

  private Checked check(AccountInput input) {
    if (input == null) {
      throw new BadRequestException("Die Angaben zum Mailkonto fehlen.");
    }
    Checked c = new Checked();
    c.name = trim(input.name());
    c.host = trim(input.host()).toLowerCase(Locale.ROOT);
    c.username = trim(input.username());
    c.password = input.password();
    c.fromAddress = trim(input.fromAddress());
    c.fromName = trim(input.fromName());
    if (c.name.isEmpty() || c.name.length() > 100 || MailAddresses.hasControlCharacters(c.name)) {
      throw new BadRequestException("Bitte einen Namen für das Konto angeben (bis 100 Zeichen).");
    }
    if (!HOST.matcher(c.host).matches() || c.host.startsWith(".") || c.host.startsWith("-")) {
      throw new BadRequestException(
          "Bitte den Mailserver als Namen angeben, z. B. smtp.example.com (ohne http:// und"
              + " ohne Port).");
    }
    try {
      c.security =
          MailSettings.Security.valueOf(
              trim(Objects.requireNonNullElse(input.security(), "STARTTLS"))
                  .toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new BadRequestException("Die Verschlüsselung muss STARTTLS, SSL oder NONE sein.", e);
    }
    int port = input.port() == null ? 0 : input.port();
    if (port == 0) {
      port =
          switch (c.security) {
            case SSL -> 465;
            case STARTTLS -> 587;
            case NONE -> 25;
          };
    }
    if (port < 1 || port > 65_535) {
      throw new BadRequestException("Der Port muss zwischen 1 und 65535 liegen.");
    }
    c.port = port;
    if (c.username.length() > 255 || MailAddresses.hasControlCharacters(c.username)) {
      throw new BadRequestException("Der Benutzername ist zu lang oder enthält Steuerzeichen.");
    }
    if (c.password != null && c.password.length() > 500) {
      throw new BadRequestException("Das Passwort ist zu lang.");
    }
    if (c.security == MailSettings.Security.NONE
        && (!c.username.isEmpty() || (c.password != null && !c.password.isEmpty()))) {
      throw new BadRequestException(
          "Ohne Verschlüsselung würden Benutzername und Passwort lesbar übertragen: bitte"
              + " STARTTLS oder SSL/TLS wählen.");
    }
    if (!MailAddresses.isAddress(c.fromAddress)) {
      throw new BadRequestException("Bitte eine gültige Absenderadresse angeben.");
    }
    if (c.fromName.length() > 255 || MailAddresses.hasControlCharacters(c.fromName)) {
      throw new BadRequestException("Der Absendername ist zu lang oder enthält Steuerzeichen.");
    }
    try {
      policy.checkAccount(c.host, c.port, c.security);
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(e.getMessage(), e);
    }
    return c;
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }
}
