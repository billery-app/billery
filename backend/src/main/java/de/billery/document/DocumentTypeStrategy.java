package de.billery.document;

import java.util.List;

public interface DocumentTypeStrategy {

  int getDocumentTypeId();

  int getDefaultStatus();

  /** German document name, e.g. "Rechnung" or "Angebot" (PDF file names, titles). */
  String getDisplayName();

  /** German label of the document number on the PDF, e.g. "Rechnungsnummer". */
  String getNumberLabel();

  boolean isValidTransition(int fromStatus, int toStatus);

  List<Integer> getAvailableStatuses(int currentStatus);
}
