package de.billery.api.dto;

public record InvoiceTemplateDto(
    Integer id,
    String name,
    Integer documentTypeId,
    String htmlContent,
    String cssContent,
    String headerHtml,
    String footerHtml,
    Boolean isDefault,
    Integer marginTopMm,
    Integer marginBottomMm,
    Integer marginLeftMm,
    Integer marginRightMm,
    String createdAt,
    String updatedAt,
    String blocks,
    Boolean documentSpecific) {}
