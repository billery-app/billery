package de.billery.data;

import java.sql.Connection;
import org.junit.jupiter.api.*;

public class DatabaseTest {

  @BeforeAll
  public static void setup() throws Exception {
    TestDatabaseConfig.setupH2Database();
  }

  @AfterAll
  public static void tearDown() {
    TestDatabaseConfig.resetDatabaseConfig();
  }

  @Test
  public void testGetConnection() throws Exception {
    try (Connection conn = Database.getConnection()) {
      Assertions.assertFalse(conn.isClosed());
    }
  }
}
