package de.billery.saas.tenant;

import de.billery.tenant.CurrentTenant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** The tenant of the current thread, as set by the request filter after authentication. */
@Component
@Profile("saas")
public class SaasCurrentTenant implements CurrentTenant {

  @Override
  public int tenantId() {
    Integer tenantId = TenantContext.tenantId();
    if (tenantId == null) {
      throw new IllegalStateException("No tenant is active on this thread");
    }
    return tenantId;
  }
}
