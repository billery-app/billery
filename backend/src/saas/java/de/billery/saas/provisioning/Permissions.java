package de.billery.saas.provisioning;

import de.billery.saas.api.ApiError;
import de.billery.saas.identity.Role;
import de.billery.saas.tenant.TenantContext;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** What the acting user (from the {@link TenantContext}) may do in the active tenant. */
final class Permissions {

  private Permissions() {}

  /** The acting user's id. */
  static UUID user() {
    String id = TenantContext.userId();
    if (id == null) {
      throw new ApiError(HttpStatus.UNAUTHORIZED, "Not signed in");
    }
    return UUID.fromString(id);
  }

  /** The active tenant's id. */
  static int tenant() {
    Integer id = TenantContext.tenantId();
    if (id == null) {
      throw new ApiError(HttpStatus.BAD_REQUEST, "Name the tenant in the X-Tenant-Id header");
    }
    return id;
  }

  /** The acting user's role in the active tenant. */
  static Role role() {
    Role role = TenantContext.role();
    if (role == null) {
      throw new ApiError(HttpStatus.FORBIDDEN, "No role in this tenant");
    }
    return role;
  }

  /** Requires one of the given roles. */
  static void require(Role... allowed) {
    Role actual = role();
    for (Role role : allowed) {
      if (role == actual) {
        return;
      }
    }
    throw new ApiError(HttpStatus.FORBIDDEN, "Your role is not allowed to do this");
  }

  /**
   * Tells whether the acting user may hand out, change or take away {@code target}: an owner may
   * manage every role, an admin only accountants and readers.
   */
  static boolean mayManage(Role target) {
    Role actual = role();
    if (actual == Role.OWNER) {
      return true;
    }
    return actual == Role.ADMIN && (target == Role.ACCOUNTANT || target == Role.READER);
  }

  /** Requires that the acting user may manage {@code target}. */
  static void requireManage(Role target) {
    if (!mayManage(target)) {
      throw new ApiError(HttpStatus.FORBIDDEN, "Your role may not manage the role " + target);
    }
  }
}
