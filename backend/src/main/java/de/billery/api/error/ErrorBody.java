package de.billery.api.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON body of an error answer: {@code {"message": "...", "code": "...", "params": {...}}}.
 * {@code message} is always the German text (older clients, logs); {@code code} and {@code params}
 * are there when the message has a code, and the SPA shows {@code errors.<code>} in the interface
 * language (#15).
 */
public final class ErrorBody {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ErrorBody() {}

  /**
   * The body for a coded message.
   *
   * @param message the message
   * @return the body
   */
  public static Map<String, Object> from(UserMessage message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("message", message.text());
    body.put("code", message.code().code());
    body.put("params", params(message));
    return body;
  }

  /**
   * The parameters for JSON; a nested message becomes {@code {"code": ..., "params": ...}}, a list
   * an array of such values.
   */
  private static Map<String, Object> params(UserMessage message) {
    Map<String, Object> params = new LinkedHashMap<>();
    message.params().forEach((name, value) -> params.put(name, json(value)));
    return params;
  }

  private static Object json(Object value) {
    if (value instanceof UserMessage nested) {
      Map<String, Object> inner = new LinkedHashMap<>();
      inner.put("code", nested.code().code());
      inner.put("params", params(nested));
      return inner;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(ErrorBody::json).toList();
    }
    return value;
  }

  /**
   * The body for an exception: with code when it carries a {@link UserMessage}, else its message.
   *
   * @param exception the failure
   * @return the body
   */
  public static Map<String, Object> from(Throwable exception) {
    if (exception instanceof UserFacing facing && facing.userMessage() != null) {
      return from(facing.userMessage());
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("message", exception.getMessage());
    return body;
  }

  /**
   * Writes the body of a coded message as the answer of a servlet filter (filters answer before
   * Spring's exception handlers).
   *
   * @param response the response, nothing written yet
   * @param status the HTTP status
   * @param message the message
   * @throws IOException if the answer cannot be written
   */
  public static void write(HttpServletResponse response, int status, UserMessage message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(MAPPER.writeValueAsString(from(message)));
  }

  /**
   * A body with a plain German message and no code (for texts no client needs to translate).
   *
   * @param message the text
   * @return the body
   */
  public static Map<String, Object> plain(String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("message", message);
    return body;
  }
}
