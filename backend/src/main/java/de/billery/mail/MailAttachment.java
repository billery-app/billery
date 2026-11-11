package de.billery.mail;

import java.io.File;

public class MailAttachment {

  private final File file;
  private final String mimeType;

  public MailAttachment(File file, String mimeType) {
    this.file = file;
    this.mimeType = mimeType;
  }

  public File getFile() {
    return file;
  }

  public String getMimeType() {
    return mimeType;
  }
}
