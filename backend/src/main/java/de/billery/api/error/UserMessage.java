package de.billery.api.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A message for the user: its code, the values of its placeholders, and the German text built from
 * the template (#15). The error JSON carries all three ({@link ErrorBody}). A parameter may itself
 * be a message (a reason inside a sentence) or a list (of messages or values, shown joined with ",
 * "); the SPA translates nested messages on their own.
 *
 * @param code the message code
 * @param params the values of the placeholders, in the order given
 */
public record UserMessage(MessageCode code, Map<String, Object> params) {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)\\}");

  /**
   * Keeps the parameters unmodifiable.
   *
   * @param code the message code
   * @param params the values of the placeholders
   */
  public UserMessage {
    params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
  }

  /**
   * Creates a message from name / value pairs.
   *
   * @param code the message code
   * @param namesAndValues name, value, name, value, …
   * @return the message
   * @throws IllegalArgumentException for an odd number of arguments or a name that is no string
   */
  public static UserMessage create(MessageCode code, Object... namesAndValues) {
    if (namesAndValues.length % 2 != 0) {
      throw new IllegalArgumentException("names and values come in pairs: " + code.code());
    }
    Map<String, Object> params = new LinkedHashMap<>();
    for (int i = 0; i < namesAndValues.length; i += 2) {
      if (!(namesAndValues[i] instanceof String name)) {
        throw new IllegalArgumentException("parameter name must be a string: " + code.code());
      }
      params.put(name, namesAndValues[i + 1]);
    }
    return new UserMessage(code, params);
  }

  /**
   * The German text: the template with every placeholder replaced by its value (a placeholder
   * without a value stays as it is, so a mistake shows instead of vanishing).
   *
   * @return the text
   */
  public String text() {
    Matcher matcher = PLACEHOLDER.matcher(code.template());
    StringBuilder text = new StringBuilder();
    while (matcher.find()) {
      Object value = params.get(matcher.group(1));
      String replacement =
          switch (value) {
            case null -> matcher.group();
            case UserMessage nested -> nested.text();
            case List<?> list -> String.join(", ", list.stream().map(UserMessage::textOf).toList());
            default -> String.valueOf(value);
          };
      matcher.appendReplacement(text, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(text);
    return text.toString();
  }

  /** The German text of a list element: a message's text, anything else as it is. */
  private static String textOf(Object value) {
    return value instanceof UserMessage nested ? nested.text() : String.valueOf(value);
  }
}
