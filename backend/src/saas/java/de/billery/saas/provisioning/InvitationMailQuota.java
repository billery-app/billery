package de.billery.saas.provisioning;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Limits how many invitation mails go out in 24 hours: per inviting user, per recipient, per source
 * IP and for the whole system. Anyone who signs up can invite, so without limits the SMTP account
 * could be used to send mail to strangers. The limit per recipient protects one person from many
 * accounts, the one per IP one source with many accounts; the system limit is the emergency brake
 * and logs an error (and raises the {@code BilleryAbuseLimitReached} alert), so an operator notices
 * it.
 *
 * <p>The counting is {@link DailyQuota}: a refused attempt counts nothing, otherwise one account
 * could use up the system limit in a loop and stop the invitations of everybody else.
 */
@Service
@Profile("saas")
public class InvitationMailQuota {

  /** How many invitation mails one user may send in 24 hours. */
  static final int MAX_PER_USER = 50;

  /** How many invitation mails one address may receive in 24 hours. */
  static final int MAX_PER_RECIPIENT = 5;

  /** The quota's name in {@code daily_quota}. */
  static final String QUOTA = "invitation_mail";

  private static final Logger LOG = LoggerFactory.getLogger(InvitationMailQuota.class);

  private final DailyQuota quota;
  private final int systemLimit;
  private final int perSource;

  /**
   * Creates the limits.
   *
   * @param quota the counting
   * @param systemLimit how many invitation mails the whole system may send in 24 hours ({@code
   *     BILLERY_INVITATION_MAILS_PER_DAY})
   * @param perSource how many invitation mails may be sent from one source IP in 24 hours ({@code
   *     BILLERY_INVITATION_MAILS_PER_IP}, 0 switches it off)
   * @throws IllegalStateException if the system limit is not positive or the IP limit negative
   */
  public InvitationMailQuota(
      DailyQuota quota,
      @Value("${billery.invitation-mails-per-day:1000}") int systemLimit,
      @Value("${billery.invitation-mails-per-ip:100}") int perSource) {
    if (systemLimit < 1) {
      throw new IllegalStateException("BILLERY_INVITATION_MAILS_PER_DAY must be at least 1");
    }
    if (perSource < 0) {
      throw new IllegalStateException("BILLERY_INVITATION_MAILS_PER_IP must not be negative");
    }
    this.quota = quota;
    this.systemLimit = systemLimit;
    this.perSource = perSource;
    quota.declare(QUOTA, "user", "recipient", "ip", DailyQuota.SYSTEM);
  }

  /**
   * Reserves one mail if no limit is reached.
   *
   * @param user the inviting user
   * @param recipient the invited address
   * @param sourceIp the address the request came from (see {@code ClientAddress})
   * @return true if the mail may be sent; false if a limit is reached (nothing is counted then)
   */
  public boolean claim(UUID user, String recipient, String sourceIp) {
    Optional<String> refused =
        quota.claim(
            QUOTA,
            List.of(
                new DailyQuota.Limit("user", user.toString(), MAX_PER_USER),
                new DailyQuota.Limit("recipient", DailyQuota.hash(recipient), MAX_PER_RECIPIENT),
                new DailyQuota.Limit("ip", DailyQuota.hash(sourceIp), perSource),
                new DailyQuota.Limit(DailyQuota.SYSTEM, DailyQuota.SYSTEM_KEY, systemLimit)));
    refused.ifPresent(InvitationMailQuota::logRefusal);
    return refused.isEmpty();
  }

  private static void logRefusal(String scope) {
    if (DailyQuota.SYSTEM.equals(scope)) {
      LOG.error(
          "The system limit of invitation mails is reached: no invitation mails go out for up to"
              + " 24 hours. Check for abuse, or raise BILLERY_INVITATION_MAILS_PER_DAY.");
    } else {
      LOG.warn("An invitation mail was refused: the limit per {} is reached", scope);
    }
  }
}
