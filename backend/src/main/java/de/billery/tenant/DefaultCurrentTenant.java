package de.billery.tenant;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The single-tenant implementation used by self-host and desktop: always {@link
 * CurrentTenant#DEFAULT_TENANT_ID}. Not active in the {@code saas} profile, which provides its own.
 */
@Component
@Profile("!saas")
public class DefaultCurrentTenant implements CurrentTenant {

  @Override
  public int tenantId() {
    return DEFAULT_TENANT_ID;
  }
}
