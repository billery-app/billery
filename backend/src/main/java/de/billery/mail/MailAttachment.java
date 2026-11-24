package de.billery.mail;

/**
 * A file attached to a {@link MailMessage}, held in memory (a document's PDF or XRechnung).
 *
 * @param fileName the name the recipient sees
 * @param content the bytes
 * @param mimeType e.g. {@code application/pdf}
 */
public record MailAttachment(String fileName, byte[] content, String mimeType) {

  /**
   * @param fileName the name the recipient sees
   * @param content the bytes (copied)
   * @param mimeType e.g. {@code application/pdf}
   */
  public MailAttachment {
    content = content.clone();
  }

  /**
   * @return a copy of the bytes
   */
  @Override
  public byte[] content() {
    return content.clone();
  }
}
