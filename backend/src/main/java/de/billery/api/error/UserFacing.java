package de.billery.api.error;

/**
 * An exception whose message is meant for the user and has a code the SPA translates (#15).
 * Exceptions that do not carry one (null) answer with their German message only.
 */
// not meant as a lambda target: exceptions implement it next to their other members
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface UserFacing {

  /**
   * The message with its code.
   *
   * @return the message, or null when the exception has none
   */
  UserMessage userMessage();
}
