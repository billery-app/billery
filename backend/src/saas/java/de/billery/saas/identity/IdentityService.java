package de.billery.saas.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Maps a validated token to an application user. Only {@code iss}, {@code sub}, {@code email},
 * {@code email_verified} and {@code name} are read, so the identity provider can be exchanged: a
 * new provider's users are linked to the existing users by their verified e-mail address.
 */
@Service
@Profile("saas")
public class IdentityService {

  private final IdentityRepository identities;
  private final TransactionTemplate transactions;

  public IdentityService(IdentityRepository identities, TransactionTemplate transactions) {
    this.identities = identities;
    this.transactions = transactions;
  }

  /**
   * Returns the user for a token, creating the user on the first sign-in.
   *
   * <p>A known identity is resolved without looking at the e-mail address. An unknown one needs a
   * <b>verified</b> address: with it the identity is linked to the existing user of that address
   * (the identity-provider switch) or a new user is created; without it nobody could claim another
   * person's account just by registering with their address.
   *
   * @param token the validated token
   * @return the user id
   * @throws IdentityException if the token has no verified e-mail address and the identity is new
   */
  public UUID resolve(Jwt token) {
    String issuer = token.getIssuer() == null ? null : token.getIssuer().toString();
    String subject = token.getSubject();
    if (issuer == null || subject == null) {
      throw new IdentityException("The token has no issuer or subject");
    }
    Optional<UUID> known = identities.findUserByIdentity(issuer, subject);
    if (known.isPresent()) {
      return known.get();
    }
    String email = token.getClaimAsString("email");
    if (email == null
        || email.isBlank()
        || !Boolean.TRUE.equals(token.getClaimAsBoolean("email_verified"))) {
      throw new IdentityException("A verified e-mail address is required for the first sign-in");
    }
    String name = token.getClaimAsString("name");
    return transactions.execute(
        status -> {
          UUID user =
              identities
                  .findUserByEmail(email)
                  .or(() -> identities.createUser(email, name))
                  .or(() -> identities.findUserByEmail(email))
                  .orElseThrow(() -> new IdentityException("The user could not be created"));
          identities.linkIdentity(issuer, subject, user);
          // The link may have been created by a concurrent first sign-in: its user wins.
          return identities.findUserByIdentity(issuer, subject).orElse(user);
        });
  }
}
