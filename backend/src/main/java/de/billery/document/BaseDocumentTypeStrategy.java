package de.billery.document;

import java.util.ArrayList;
import java.util.List;

public abstract class BaseDocumentTypeStrategy implements DocumentTypeStrategy {

  protected final int documentTypeId;
  private final String displayName;
  private final String numberLabel;

  /**
   * Creates the strategy.
   *
   * @param documentTypeId the {@code document_type.id} this strategy handles
   * @param displayName German name shown on the document and in file names, e.g. "Angebot"
   * @param numberLabel German label of the document number on the PDF, e.g. "Angebotsnummer"
   */
  protected BaseDocumentTypeStrategy(int documentTypeId, String displayName, String numberLabel) {
    this.documentTypeId = documentTypeId;
    this.displayName = displayName;
    this.numberLabel = numberLabel;
  }

  @Override
  public String getDisplayName() {
    return displayName;
  }

  @Override
  public String getNumberLabel() {
    return numberLabel;
  }

  @Override
  public int getDocumentTypeId() {
    return documentTypeId;
  }

  @Override
  public int getDefaultStatus() {
    return 1;
  }

  protected boolean isAllowed(int from, int to, int[]... rules) {
    for (int[] rule : rules) {
      if (rule[0] == from && rule[1] == to) {
        return true;
      }
    }
    return false;
  }

  protected List<Integer> buildStatusList(int current, int[]... rules) {
    List<Integer> result = new ArrayList<>();
    result.add(current);
    for (int[] rule : rules) {
      if (rule[0] == current && rule[1] != current) {
        result.add(rule[1]);
      }
    }
    return result.stream().distinct().sorted().toList();
  }
}
