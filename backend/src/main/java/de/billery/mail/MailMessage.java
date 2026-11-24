package de.billery.mail;

import java.util.ArrayList;
import java.util.List;

/**
 * One plain-text message: sender, recipients, subject, text and attachments. The sender may be left
 * empty for the system account (its configured address is used).
 */
public class MailMessage {

  private String from;
  private String fromName;
  private final List<String> to = new ArrayList<>();
  private final List<String> cc = new ArrayList<>();
  private final List<String> bcc = new ArrayList<>();
  private String subject;
  private String text;

  private final List<MailAttachment> attachments = new ArrayList<>();

  /**
   * @param attachment a file to attach
   */
  public void addAttachment(MailAttachment attachment) {
    attachments.add(attachment);
  }

  /**
   * @return the attachments, in the order they were added
   */
  public List<MailAttachment> getAttachments() {
    return List.copyOf(attachments);
  }

  /**
   * @return the sender address, or null for the account's own
   */
  public String getFrom() {
    return from;
  }

  /**
   * @return the sender's display name, or null
   */
  public String getFromName() {
    return fromName;
  }

  /**
   * @return the first recipient, or null (the invitation mails have exactly one)
   */
  public String getTo() {
    return to.isEmpty() ? null : to.get(0);
  }

  /**
   * @return all recipients of the To header
   */
  public List<String> getToAll() {
    return List.copyOf(to);
  }

  /**
   * @return the CC recipients
   */
  public List<String> getCc() {
    return List.copyOf(cc);
  }

  /**
   * @return the BCC recipients
   */
  public List<String> getBcc() {
    return List.copyOf(bcc);
  }

  public String getSubject() {
    return subject;
  }

  public String getText() {
    return text;
  }

  /**
   * @param from the sender address
   */
  public void setFrom(String from) {
    this.from = from;
  }

  /**
   * @param fromName the sender's display name
   */
  public void setFromName(String fromName) {
    this.fromName = fromName;
  }

  /**
   * Replaces the recipients with one address.
   *
   * @param to the recipient
   */
  public void setTo(String to) {
    this.to.clear();
    this.to.add(to);
  }

  /**
   * @param recipients the recipients of the To header (replaces the previous ones)
   */
  public void setToAll(List<String> recipients) {
    to.clear();
    to.addAll(recipients);
  }

  /**
   * @param recipients the CC recipients (replaces the previous ones)
   */
  public void setCc(List<String> recipients) {
    cc.clear();
    cc.addAll(recipients);
  }

  /**
   * @param recipients the BCC recipients (replaces the previous ones)
   */
  public void setBcc(List<String> recipients) {
    bcc.clear();
    bcc.addAll(recipients);
  }

  public void setSubject(String subject) {
    this.subject = subject;
  }

  public void setText(String text) {
    this.text = text;
  }
}
