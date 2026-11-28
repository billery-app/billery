package de.billery.api.error;

/**
 * A message the API sends to the user, with a stable code the SPA translates (#15). The template is
 * the German text with named placeholders ({@code {max}}); it is the fallback for clients that do
 * not know the code, and the logs read it. The SPA's message files hold the same template under
 * {@code errors.<code>} ({@code locales/errorCodes.spec.ts} checks every code against both
 * languages and the German text against this template).
 */
public interface MessageCode {

  /**
   * The code, e.g. {@code invoice.issueNotDraft}; never changes once released.
   *
   * @return the code
   */
  String code();

  /**
   * The German text with {@code {name}} placeholders.
   *
   * @return the template
   */
  String template();

  /**
   * The message with its parameters.
   *
   * @param namesAndValues name, value, name, value, … (the values are shown as they are; format
   *     amounts and dates before)
   * @return the message
   */
  default UserMessage with(Object... namesAndValues) {
    return UserMessage.create(this, namesAndValues);
  }
}
