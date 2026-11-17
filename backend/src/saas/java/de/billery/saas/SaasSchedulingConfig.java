package de.billery.saas;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduled tasks of the SaaS profile: the hourly removal of sign-in records past their retention
 * ({@code SignInLog.prune}). Self-host and desktop schedule nothing.
 */
@Configuration
@Profile("saas")
@EnableScheduling
public class SaasSchedulingConfig {}
