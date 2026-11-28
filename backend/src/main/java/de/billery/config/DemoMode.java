package de.billery.config;

import de.billery.api.error.ErrorCode;
import de.billery.api.error.MessageCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The public demo ({@code BILLERY_DEMO=true}): a self-hosted install with demo data that anyone can
 * open without signing in. The SPA then shows a banner and starts the tour in every new browser
 * session; the backend switches off what a stranger must not use through the server, today the mail
 * to customers (a mail account would let any visitor make the server connect to any host).
 */
@Component
public class DemoMode {

  private final boolean active;

  /**
   * @param active {@code BILLERY_DEMO}
   */
  public DemoMode(@Value("${billery.demo:false}") boolean active) {
    this.active = active;
  }

  /**
   * @return true for the public demo
   */
  public boolean isActive() {
    return active;
  }

  /**
   * Refuses a function the demo does not offer.
   *
   * @param message what the user is told (e.g. {@link ErrorCode#DEMO_MAIL_DISABLED})
   * @throws de.billery.service.DocumentStateException (HTTP 409) in the demo
   */
  public void refuse(MessageCode message) {
    if (active) {
      throw new de.billery.service.DocumentStateException(message.with());
    }
  }
}
