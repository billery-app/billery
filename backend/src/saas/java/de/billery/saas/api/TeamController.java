package de.billery.saas.api;

import de.billery.saas.identity.Role;
import de.billery.saas.provisioning.InvitationMailer;
import de.billery.saas.provisioning.InvitationService;
import de.billery.saas.provisioning.MemberService;
import de.billery.saas.provisioning.TenantAuditLog;
import de.billery.saas.provisioning.TenantProvisioningService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-up, members, invitations and closing, for the active tenant ({@code X-Tenant-Id}) except
 * where noted. Roles are checked by the services; the filter has already verified membership.
 */
@RestController
@Profile("saas")
public class TeamController {

  private final TenantProvisioningService provisioning;
  private final InvitationService invitations;
  private final MemberService members;
  private final InvitationMailer mailer;
  private final TenantAuditLog audit;

  public TeamController(
      TenantProvisioningService provisioning,
      InvitationService invitations,
      MemberService members,
      InvitationMailer mailer,
      TenantAuditLog audit) {
    this.provisioning = provisioning;
    this.invitations = invitations;
    this.members = members;
    this.mailer = mailer;
    this.audit = audit;
  }

  /**
   * The administration log of the active tenant: invitations, members, roles, sign-up, closing.
   * Owners and administrators only.
   *
   * @return the newest entries first
   */
  @GetMapping("/api/tenant/audit")
  public List<TenantAuditLog.Entry> auditLog() {
    return audit.list();
  }

  /** Body of {@code POST /api/tenants}. */
  public record CreateTenantRequest(String name) {}

  /** Body of {@code POST /api/tenant/invitations}. */
  public record InviteRequest(String email, String role) {}

  /**
   * Answer of {@code POST /api/tenant/invitations}: the invitation and whether its mail went out.
   *
   * @param id the invitation
   * @param email the invited address
   * @param role the role offered
   * @param createdAt when it was created
   * @param expiresAt until when it can be accepted
   * @param mailSent whether the SMTP server accepted the invitation mail
   * @param mailProblem why no mail went out (German), null when it did
   */
  public record InviteResponse(
      UUID id,
      String email,
      Role role,
      Instant createdAt,
      Instant expiresAt,
      boolean mailSent,
      String mailProblem) {}

  /** Body of {@code PUT /api/tenant/members/{userId}}. */
  public record RoleRequest(String role) {}

  private static Role role(String value) {
    try {
      return Role.valueOf(value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ApiError(HttpStatus.BAD_REQUEST, "Unknown role: " + value, e);
    }
  }

  /**
   * Sign-up: creates a tenant with the acting user as its owner. Needs no {@code X-Tenant-Id}.
   *
   * @param request the company's name
   * @param http the request; its client address counts against the sign-up limit per source (behind
   *     a proxy the address from {@code X-Forwarded-For}, which the proxy must overwrite)
   * @return the new tenant's id
   */
  @PostMapping("/api/tenants")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Integer> createTenant(
      @RequestBody CreateTenantRequest request, HttpServletRequest http) {
    return Map.of("tenantId", provisioning.createTenant(request.name(), http.getRemoteAddr()));
  }

  /** Closes the active tenant: read-only from now on (owner only). */
  @PostMapping("/api/tenant/close")
  public Map<String, String> closeTenant() {
    provisioning.closeTenant();
    return Map.of("status", "CLOSED");
  }

  /**
   * Lists the members of the active tenant.
   *
   * @return the members
   */
  @GetMapping("/api/tenant/members")
  public List<MemberService.Member> listMembers() {
    return members.list();
  }

  /**
   * Changes a member's role.
   *
   * @param userId the member
   * @param request the new role
   */
  @PutMapping("/api/tenant/members/{userId}")
  public void changeRole(@PathVariable UUID userId, @RequestBody RoleRequest request) {
    members.changeRole(userId, role(request.role()));
  }

  /**
   * Removes a member (or lets the acting user leave).
   *
   * @param userId the member
   */
  @DeleteMapping("/api/tenant/members/{userId}")
  public void removeMember(@PathVariable UUID userId) {
    members.remove(userId);
  }

  /**
   * Lists the open invitations of the active tenant.
   *
   * @return the invitations
   */
  @GetMapping("/api/tenant/invitations")
  public List<InvitationService.Invitation> listInvitations() {
    return invitations.openInvitations();
  }

  /**
   * Invites an e-mail address to the active tenant and mails the invitation to it. A mail that
   * cannot be sent does not undo the invitation; the answer says so.
   *
   * @param request address and role
   * @param http the request; its client address counts against the mail limit per source
   * @return the invitation and the mail outcome
   */
  @PostMapping("/api/tenant/invitations")
  @ResponseStatus(HttpStatus.CREATED)
  public InviteResponse invite(@RequestBody InviteRequest request, HttpServletRequest http) {
    InvitationService.Invitation invitation =
        invitations.invite(request.email(), role(request.role()));
    InvitationMailer.Outcome mail = mailer.send(invitation, http.getRemoteAddr());
    return new InviteResponse(
        invitation.id(),
        invitation.email(),
        invitation.role(),
        invitation.createdAt(),
        invitation.expiresAt(),
        mail.sent(),
        mail.problem());
  }

  /**
   * Withdraws an open invitation.
   *
   * @param id the invitation
   */
  @DeleteMapping("/api/tenant/invitations/{id}")
  public void revokeInvitation(@PathVariable UUID id) {
    invitations.revoke(id);
  }

  /**
   * Accepts an invitation addressed to the signed-in user's verified address. Needs no {@code
   * X-Tenant-Id}.
   *
   * @param id the invitation
   * @return the tenant joined
   */
  @PostMapping("/api/invitations/{id}/accept")
  public Map<String, Integer> acceptInvitation(@PathVariable UUID id) {
    return Map.of("tenantId", invitations.accept(id));
  }
}
