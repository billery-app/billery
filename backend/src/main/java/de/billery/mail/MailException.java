package de.billery.mail;

import de.billery.api.error.ErrorCode;
import de.billery.api.error.UserMessage;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import javax.net.ssl.SSLException;

/** A message could not be delivered to the SMTP server; {@link #reason()} says why, roughly. */
public class MailException extends Exception {

  private static final long serialVersionUID = 1L;

  /** Why a delivery failed: the metric's label and the user's explanation. */
  public enum Reason {
    /** The server refused the login. */
    AUTH(ErrorCode.MAIL_AUTH),
    /** The server could not be reached (name, port, firewall). */
    CONNECT(ErrorCode.MAIL_CONNECT),
    /** The server did not answer in time. */
    TIMEOUT(ErrorCode.MAIL_TIMEOUT),
    /** The encrypted connection failed (certificate, wrong security setting for the port). */
    TLS(ErrorCode.MAIL_TLS),
    /** The server refused sender or recipient. */
    REJECTED(ErrorCode.MAIL_REJECTED),
    /** The address is not allowed for a mail server here (an internal address, a port). */
    REFUSED_HOST(ErrorCode.MAIL_REFUSED_HOST),
    /** Anything else. */
    OTHER(ErrorCode.MAIL_OTHER);

    private final ErrorCode code;

    Reason(ErrorCode code) {
      this.code = code;
    }

    /**
     * @return what the user is told (German)
     */
    public String explanation() {
      return code.template();
    }

    /**
     * @return what the user is told, with its code (#15)
     */
    public UserMessage message() {
      return code.with();
    }

    /**
     * @return the metric label, e.g. {@code auth}
     */
    public String label() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  private final Reason failureReason;

  /**
   * @param message what failed (for the log; never contains an address or mail content)
   * @param cause the underlying failure; its chain decides the {@link #reason()}
   */
  public MailException(String message, Throwable cause) {
    this(message, cause, classify(cause));
  }

  /**
   * @param message what failed
   * @param cause the underlying failure, may be null
   * @param reason why
   */
  public MailException(String message, Throwable cause, Reason reason) {
    super(message, cause);
    this.failureReason = reason;
  }

  /**
   * @return why the delivery failed
   */
  public Reason reason() {
    return failureReason;
  }

  /**
   * Reads the reason out of a failure's cause chain (the mail library wraps the JavaMail and socket
   * exceptions).
   *
   * @param failure the failure, may be null
   * @return the first reason found, {@link Reason#OTHER} if none
   */
  static Reason classify(Throwable failure) {
    // getCause() never returns the exception itself (Throwable refuses a self-cause); the depth
    // limit only guards against an unusual cycle further down.
    int depth = 0;
    for (Throwable t = failure; t != null && depth < 20; t = t.getCause()) {
      depth++;
      if (t instanceof MailHostRefusedException) {
        return Reason.REFUSED_HOST;
      }
      if (t instanceof AuthenticationFailedException) {
        return Reason.AUTH;
      }
      if (t instanceof SocketTimeoutException) {
        return Reason.TIMEOUT;
      }
      if (t instanceof UnknownHostException
          || t instanceof ConnectException
          || t instanceof NoRouteToHostException) {
        return Reason.CONNECT;
      }
      if (t instanceof SSLException) {
        return Reason.TLS;
      }
      if (t instanceof SendFailedException) {
        return Reason.REJECTED;
      }
      if (t instanceof MessagingException messaging && messaging.getNextException() != null) {
        Reason next = classify(messaging.getNextException());
        if (next != Reason.OTHER) {
          return next;
        }
      }
    }
    return Reason.OTHER;
  }
}
