package de.billery.mail;

/**
 * How many mails a company may send to its customers in 24 hours. Self-host and desktop have no
 * limit ({@link NoCustomerMailLimit}); SaaS counts per company ({@code
 * saas.provisioning.CustomerMailQuota}), so an account taken over cannot be used to send masses of
 * mail through the platform.
 */
@FunctionalInterface
public interface CustomerMailLimit {

  /**
   * Counts one mail if the limit allows it.
   *
   * @return true if the mail may be sent (and is counted), false if the limit is reached
   */
  boolean claim();
}
