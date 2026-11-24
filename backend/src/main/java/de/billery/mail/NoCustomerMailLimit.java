package de.billery.mail;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Self-host and desktop: the company's own installation, no limit. */
@Component
@Profile("!saas")
public class NoCustomerMailLimit implements CustomerMailLimit {

  @Override
  public boolean claim() {
    return true;
  }
}
