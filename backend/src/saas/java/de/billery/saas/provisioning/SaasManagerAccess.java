package de.billery.saas.provisioning;

import de.billery.saas.identity.Role;
import de.billery.tenant.ManagerAccess;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * SaaS: company settings such as the SMTP accounts are changed by the owners and administrators of
 * the active company, the same group that manages the team ({@code SaasRoleMatrixTest}: TEAM).
 */
@Component
@Profile("saas")
public class SaasManagerAccess implements ManagerAccess {

  @Override
  public void requireManager() {
    Permissions.require(Role.OWNER, Role.ADMIN);
  }
}
