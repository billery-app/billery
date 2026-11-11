package de.billery.mail;

import java.util.ArrayList;
import java.util.List;

public class MailMessage {

  private String from;
  private String to;
  private String subject;
  private String text;

  private final List<MailAttachment> attachments = new ArrayList<>();

  public void addAttachment(MailAttachment attachment) {
    attachments.add(attachment);
  }

  public List<MailAttachment> getAttachments() {
    return attachments;
  }

  public String getFrom() {
    return from;
  }

  public String getTo() {
    return to;
  }

  public String getSubject() {
    return subject;
  }

  public String getText() {
    return text;
  }

  public void setFrom(String from) {
    this.from = from;
  }

  public void setTo(String to) {
    this.to = to;
  }

  public void setSubject(String subject) {
    this.subject = subject;
  }

  public void setText(String text) {
    this.text = text;
  }
}
