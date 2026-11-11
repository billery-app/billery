package de.billery.saas.api;

import de.billery.saas.identity.IdentityRepository;
import de.billery.saas.identity.IdentityService;
import de.billery.saas.identity.Membership;
import de.billery.saas.provisioning.InvitationService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Who is signed in and which tenants they can act in (the data of the tenant switcher). */
@RestController
@Profile("saas")
public class MeController {

  private final IdentityService identities;
  private final IdentityRepository repository;
  private final InvitationService invitations;

  public MeController(
      IdentityService identities, IdentityRepository repository, InvitationService invitations) {
    this.identities = identities;
    this.repository = repository;
    this.invitations = invitations;
  }

  /**
   * Returns the signed-in user and their memberships. Needs no {@code X-Tenant-Id}: the filter runs
   * this request with the user only, so the tenant table shows just the user's own tenants.
   *
   * @param token the validated token
   * @return user id, e-mail, the list of tenants with the role in each and the open invitations
   *     addressed to the user
   */
  @GetMapping("/api/me")
  public Map<String, Object> currentUser(@AuthenticationPrincipal Jwt token) {
    UUID user = identities.resolve(token);
    List<Membership> memberships = repository.findMemberships(user);
    return Map.of(
        "userId", user.toString(),
        "email", String.valueOf(token.getClaimAsString("email")),
        "memberships", memberships,
        "invitations", invitations.pendingForCurrentUser());
  }
}
