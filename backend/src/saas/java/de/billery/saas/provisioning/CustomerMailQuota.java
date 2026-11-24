package de.billery.saas.provisioning;

import de.billery.mail.CustomerMailLimit;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * SaaS: how many mails a company may send to its customers in 24 hours. The default is {@code
 * BILLERY_CUSTOMER_MAILS_PER_DAY}; the operator can give one company its own limit ({@code
 * tenant.mail_daily_limit}, e.g. for a larger company; the company cannot change it). Counted in
 * {@code daily_quota} like the invitation mails ({@link DailyQuota}): a refused attempt counts
 * nothing, and every refusal raises {@code billery_quota_refused_total{quota="customer_mail"}}.
 */
@Service
@Profile("saas")
public class CustomerMailQuota implements CustomerMailLimit {

  /** The quota's name in {@code daily_quota}. */
  static final String QUOTA = "customer_mail";

  /** The scope: one company. */
  static final String TENANT = "tenant";

  private static final Logger LOG = LoggerFactory.getLogger(CustomerMailQuota.class);

  private final DailyQuota quota;
  private final JdbcClient jdbc;
  private final int defaultLimit;

  /**
   * @param quota the counting
   * @param jdbc reads the company's own limit
   * @param defaultLimit mails per company and 24 hours ({@code BILLERY_CUSTOMER_MAILS_PER_DAY}, 0
   *     switches the limit off)
   * @throws IllegalStateException if the default is negative
   */
  public CustomerMailQuota(
      DailyQuota quota,
      JdbcClient jdbc,
      @Value("${billery.customer-mails-per-day:200}") int defaultLimit) {
    if (defaultLimit < 0) {
      throw new IllegalStateException("BILLERY_CUSTOMER_MAILS_PER_DAY must not be negative");
    }
    this.quota = quota;
    this.jdbc = jdbc;
    this.defaultLimit = defaultLimit;
    quota.declare(QUOTA, TENANT);
  }

  @Override
  public boolean claim() {
    int tenant = Permissions.tenant();
    Optional<String> refused =
        quota.claim(
            QUOTA, List.of(new DailyQuota.Limit(TENANT, Integer.toString(tenant), limit(tenant))));
    if (refused.isPresent()) {
      LOG.warn("A mail to a customer was refused: company {} reached its daily limit", tenant);
    }
    return refused.isEmpty();
  }

  /** The company's own limit, else the default. */
  int limit(int tenant) {
    Integer own =
        jdbc.sql("SELECT mail_daily_limit FROM tenant WHERE id = :id")
            .param("id", tenant)
            .query((rs, row) -> (Integer) rs.getObject("mail_daily_limit"))
            .optional()
            .orElse(null);
    return own != null ? own : defaultLimit;
  }
}
