package de.billery.data;

/**
 * A company's own SMTP account ({@code mail_account}), used to send documents to its customers. The
 * password is only ever held in its encrypted form here; {@link #toString()} leaves it out.
 *
 * @param id the row id (0 before it is stored)
 * @param name what the user calls it, e.g. "Buchhaltung"
 * @param host the mail server
 * @param port its port
 * @param security {@code STARTTLS}, {@code SSL} or {@code NONE}
 * @param username the login, empty for none
 * @param passwordEncrypted the encrypted password ({@code mail.CredentialCipher}), null for none
 * @param fromAddress the sender address
 * @param fromName the sender's display name, may be empty
 * @param isDefault whether the send dialog picks it first
 */
public record MailAccount(
    int id,
    String name,
    String host,
    int port,
    String security,
    String username,
    String passwordEncrypted,
    String fromAddress,
    String fromName,
    boolean isDefault) {

  /**
   * @return whether a password is stored
   */
  public boolean hasPassword() {
    return passwordEncrypted != null && !passwordEncrypted.isEmpty();
  }

  /**
   * @param newId the id given by the database
   * @return a copy with that id
   */
  public MailAccount withId(int newId) {
    return new MailAccount(
        newId,
        name,
        host,
        port,
        security,
        username,
        passwordEncrypted,
        fromAddress,
        fromName,
        isDefault);
  }

  /**
   * Without the encrypted password.
   *
   * @return id, name, server and sender
   */
  @Override
  public String toString() {
    return "MailAccount[" + id + ", " + name + ", " + host + ":" + port + ", " + fromAddress + "]";
  }
}
