package de.billery.tenant;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Self-host and desktop: the one user manages everything. */
@Component
@Profile("!saas")
public class NoManagerAccess implements ManagerAccess {

  @Override
  public void requireManager() {
    // one user, no roles
  }
}
