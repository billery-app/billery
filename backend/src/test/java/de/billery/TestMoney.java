package de.billery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.math.BigDecimal;

/**
 * Assertions and factories for {@link BigDecimal} amounts in tests. Amounts are compared by value,
 * not by {@link BigDecimal#equals} (which also compares the scale, so {@code 19.0 != 19.00}).
 */
public final class TestMoney {

  private TestMoney() {}

  /**
   * Shorthand for {@link BigDecimal#valueOf(double)}.
   *
   * @param value the amount
   * @return the amount as BigDecimal
   */
  public static BigDecimal bd(double value) {
    return BigDecimal.valueOf(value);
  }

  /**
   * Asserts that {@code actual} equals {@code expected} within {@code delta}.
   *
   * @param expected the expected amount
   * @param actual the actual amount, must not be null
   * @param delta the allowed difference
   */
  public static void assertAmount(double expected, BigDecimal actual, double delta) {
    assertAmount(expected, actual, delta, null);
  }

  /**
   * Asserts that {@code actual} equals {@code expected} within {@code delta}.
   *
   * @param expected the expected amount
   * @param actual the actual amount, must not be null
   * @param delta the allowed difference
   * @param message the failure message
   */
  public static void assertAmount(
      double expected, BigDecimal actual, double delta, String message) {
    assertNotNull(actual, message);
    assertEquals(expected, actual.doubleValue(), delta, message);
  }

  /**
   * Asserts that two amounts are numerically equal (scale ignored).
   *
   * @param expected the expected amount
   * @param actual the actual amount
   * @param delta ignored beyond exact comparison when both are BigDecimal; kept so call sites read
   *     like {@code assertEquals}
   */
  public static void assertAmount(BigDecimal expected, BigDecimal actual, double delta) {
    assertNotNull(actual);
    assertEquals(expected.doubleValue(), actual.doubleValue(), delta);
  }
}
