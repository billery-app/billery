package de.billery.repository;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.junit.jupiter.api.Test;

/** ISO date strings of the entities become typed dates for the {@code DATE} columns. */
class BaseRepositoryDatesTest {

  @Test
  void emptyOrMissingMeansNoDate() {
    assertNull(BaseRepository.dateOrNull(null));
    assertNull(BaseRepository.dateOrNull(""));
  }

  @Test
  void isoDateBecomesALocalDate() {
    assertEquals(LocalDate.of(2026, 10, 26), BaseRepository.dateOrNull("2026-10-26"));
  }

  @Test
  void textThatIsNoIsoDateIsRejectedAtTheBoundary() {
    assertThrows(DateTimeParseException.class, () -> BaseRepository.dateOrNull("26.10.2026"));
    assertThrows(DateTimeParseException.class, () -> BaseRepository.dateOrNull("2026-13-45"));
  }
}
