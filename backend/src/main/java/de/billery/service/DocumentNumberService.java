package de.billery.service;

import de.billery.repository.DocumentNumberSeriesRepository;
import de.billery.repository.UserSettingsRepository;
import java.time.LocalDate;
import org.springframework.stereotype.Service;

/**
 * Service for generating document numbers with configurable prefixes and versioning. Generates
 * numbers like: RE-2024-001, RE-2024-001/2 (for version 2)
 */
@Service
public class DocumentNumberService {

  private static final int INITIAL_VERSION = 1;
  private static final int DOCUMENT_NUMBER_PARTS = 3;

  private final UserSettingsRepository settingsRepository;
  private final DocumentNumberSeriesRepository seriesRepository;

  /**
   * Creates the service.
   *
   * @param settingsRepository reads the per-type number prefix
   * @param seriesRepository reserves the per-year numbers
   */
  public DocumentNumberService(
      UserSettingsRepository settingsRepository, DocumentNumberSeriesRepository seriesRepository) {
    this.settingsRepository = settingsRepository;
    this.seriesRepository = seriesRepository;
  }

  /**
   * Generates a new document number for a new document. Format: {PREFIX}-{YEAR}-{NUMBER} Example:
   * RE-2024-001
   *
   * @param documentTypeId the document type ID (1-5)
   * @return the generated document number
   */
  public String generateDocumentNumber(int documentTypeId) {
    return generateDocumentNumber(documentTypeId, LocalDate.now().getYear());
  }

  /**
   * Reserves the next number of the given year's series. Invoices are numbered when they are
   * issued, in the series of their invoice date's year, so a backdated invoice does not carry next
   * year's number.
   *
   * @param documentTypeId the document type ID
   * @param year the year of the series (and of the number)
   * @return the generated document number, e.g. {@code RE-2026-007}
   */
  public String generateDocumentNumber(int documentTypeId, int year) {
    String prefix = settingsRepository.getPrefixForDocumentType(documentTypeId);
    int nextNumber = seriesRepository.getNextNumber(documentTypeId, year);

    return String.format("%s-%d-%03d", prefix, year, nextNumber);
  }

  /**
   * Generates a document number for a new version of an existing document. Format:
   * {PREFIX}-{YEAR}-{NUMBER}/{VERSION} Example: RE-2024-001/2
   *
   * @param baseDocumentNumber the original document number (e.g., RE-2024-001)
   * @param version the version number (1, 2, 3...)
   * @return the versioned document number
   */
  public String generateVersionedNumber(String baseDocumentNumber, int version) {
    if (version <= INITIAL_VERSION) {
      return baseDocumentNumber;
    }
    return String.format("%s/%d", baseDocumentNumber, version);
  }

  /**
   * Extracts the base number from a versioned number. RE-2024-001/2 → RE-2024-001
   *
   * @param versionedNumber the versioned number
   * @return the base number
   */
  public String extractBaseNumber(String versionedNumber) {
    if (versionedNumber == null) {
      return null;
    }
    int slashIndex = versionedNumber.lastIndexOf('/');
    if (slashIndex > 0) {
      return versionedNumber.substring(0, slashIndex);
    }
    return versionedNumber;
  }

  /**
   * Extracts the version from a versioned number. RE-2024-001/2 → 2
   *
   * @param versionedNumber the versioned number
   * @return the version number (1 if no version)
   */
  public int extractVersion(String versionedNumber) {
    if (versionedNumber == null) {
      return 1;
    }
    int slashIndex = versionedNumber.lastIndexOf('/');
    if (slashIndex > 0 && slashIndex < versionedNumber.length() - 1) {
      try {
        return Integer.parseInt(versionedNumber.substring(slashIndex + 1));
      } catch (NumberFormatException e) {
        return 1;
      }
    }
    return 1;
  }

  /**
   * Generates the next version number for an existing document. Finds the current version and
   * increments it.
   *
   * @param documentNumber the current document number
   * @return the next version number string
   */
  public String generateNextVersion(String documentNumber) {
    String baseNumber = extractBaseNumber(documentNumber);
    int currentVersion = extractVersion(documentNumber);
    return generateVersionedNumber(baseNumber, currentVersion + 1);
  }

  /**
   * Checks if a document number is a versioned number.
   *
   * @param documentNumber the document number to check
   * @return true if it contains a version suffix
   */
  public boolean isVersioned(String documentNumber) {
    return documentNumber != null && documentNumber.contains("/");
  }

  /**
   * Formats a document number for display (adds zero-padding if needed). Ensures consistent
   * formatting.
   *
   * @param prefix the prefix (RE, AN, etc.)
   * @param year the year
   * @param number the sequential number
   * @return formatted number like RE-2024-001
   */
  public String formatNumber(String prefix, int year, int number) {
    return String.format("%s-%d-%03d", prefix, year, number);
  }

  /**
   * Parses a document number into its components.
   *
   * @param documentNumber the document number to parse
   * @return array of [prefix, year, number] or null if invalid
   */
  public String[] parseDocumentNumber(String documentNumber) {
    if (documentNumber == null || documentNumber.isEmpty()) {
      return new String[0];
    }

    // Remove version suffix if present
    String baseNumber = extractBaseNumber(documentNumber);

    // Split by hyphen
    String[] parts = baseNumber.split("-");
    if (parts.length != DOCUMENT_NUMBER_PARTS) {
      return new String[0];
    }

    return parts;
  }
}
