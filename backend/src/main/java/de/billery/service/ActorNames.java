package de.billery.service;

import java.util.Collection;
import java.util.Map;

/**
 * Turns the actor ids stored with history rows ({@code document_history.performed_by}) into names
 * people recognise. Self-host has no users, so its implementation knows none; the SaaS build
 * provides one that reads the users of the identity tables.
 */
@FunctionalInterface
public interface ActorNames {

  /**
   * Names of the given actors.
   *
   * @param actorIds stored actor ids; unknown or malformed ones are left out of the answer
   * @return actor id to display name (or e-mail address)
   */
  Map<String, String> namesOf(Collection<String> actorIds);
}
