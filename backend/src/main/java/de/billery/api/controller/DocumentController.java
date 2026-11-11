package de.billery.api.controller;

import de.billery.api.dto.InvoiceTemplateDto;
import de.billery.data.InvoiceTemplate;
import de.billery.repository.InvoiceTemplateRepository;
import de.billery.service.DocumentConversionService;
import de.billery.service.DocumentConversionService.EditorDocument;
import de.billery.service.EmptyDocumentException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Controller for handling document import/export operations including DOCX and ODT file upload and
 * template conversion.
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

  private final DocumentConversionService conversionService;
  private final InvoiceTemplateRepository templateRepository;
  private static final Logger logger = Logger.getLogger(DocumentController.class.getName());

  public DocumentController(
      DocumentConversionService conversionService, InvoiceTemplateRepository templateRepository) {
    this.conversionService = conversionService;
    this.templateRepository = templateRepository;
  }

  /** Import DOCX/ODT file and convert to template */
  @PostMapping("/import")
  public ResponseEntity<?> importDocument(
      @RequestParam("file") MultipartFile file,
      @RequestParam("templateName") String templateName,
      @RequestParam("documentTypeId") Integer documentTypeId) {

    try {
      // Convert uploaded file to EditorDocument
      EditorDocument editorDoc = conversionService.importDocument(file);

      // Create new template from imported document
      InvoiceTemplate template = new InvoiceTemplate();
      template.setName(templateName.isEmpty() ? editorDoc.getName() : templateName);
      template.setDocumentTypeId(documentTypeId != null ? documentTypeId : 1);
      template.setHtmlContent(editorDoc.getHtmlContent());
      template.setCssContent(""); // Start with empty CSS
      template.setHeaderHtml("");
      template.setFooterHtml("");
      template.setDefault(false);
      template.setMarginTopMm(10);
      template.setMarginBottomMm(10);
      template.setMarginLeftMm(10);
      template.setMarginRightMm(10);

      // Save template to database
      InvoiceTemplate saved = templateRepository.save(template);

      if (saved == null) {
        return ResponseEntity.badRequest().build();
      }

      // Return as DTO
      return ResponseEntity.ok(convertToDto(saved));

    } catch (EmptyDocumentException e) {
      // A readable document without text: say so instead of saving an empty template.
      return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    } catch (Exception e) {
      if (logger.isLoggable(Level.SEVERE)) {
        logger.log(Level.SEVERE, "Document import error", e);
      }
      return ResponseEntity.badRequest()
          .body(Map.of("message", "Dokument konnte nicht importiert werden."));
    }
  }

  /** Export template to DOCX format */
  @PostMapping("/export/{templateId}")
  public ResponseEntity<byte[]> exportDocument(
      @PathVariable Integer templateId, @RequestParam("format") String format) {

    try {
      // Load template from database
      InvoiceTemplate template = templateRepository.findById(templateId);
      if (template == null) {
        return ResponseEntity.notFound().build();
      }

      // Create EditorDocument from template
      EditorDocument editorDoc =
          DocumentConversionService.EditorDocument.builder()
              .name(template.getName())
              .htmlContent(template.getHtmlContent())
              .originalFormat("html")
              .build();

      // Convert to requested format
      byte[] documentBytes;
      String contentType;
      String filename;

      switch (format.toLowerCase(Locale.ROOT)) {
        case "docx":
          documentBytes = conversionService.exportToDocx(editorDoc);
          contentType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
          filename = template.getName() + ".docx";
          break;
        case "odt":
          documentBytes = conversionService.exportToOdt(editorDoc);
          contentType = "application/vnd.oasis.opendocument.text";
          filename = template.getName() + ".odt";
          break;
        default:
          return ResponseEntity.badRequest().build();
      }

      return ResponseEntity.ok()
          .header("Content-Type", contentType)
          .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
          .body(documentBytes);

    } catch (Exception e) {
      logger.log(Level.SEVERE, "Document export failed", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /** Export new template (not yet saved) to DOCX/ODT */
  @PostMapping("/export/new")
  public ResponseEntity<byte[]> exportNewDocument(@RequestBody ExportRequest request) {

    try {
      // Create EditorDocument from request
      EditorDocument editorDoc =
          DocumentConversionService.EditorDocument.builder()
              .name(request.getName())
              .htmlContent(request.getContent())
              .originalFormat("html")
              .build();

      // Convert to requested format
      byte[] documentBytes;
      String contentType;
      String filename;

      switch (request.getFormat().toLowerCase(Locale.ROOT)) {
        case "docx":
          documentBytes = conversionService.exportToDocx(editorDoc);
          contentType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
          filename = request.getName() + ".docx";
          break;
        case "odt":
          documentBytes = conversionService.exportToOdt(editorDoc);
          contentType = "application/vnd.oasis.opendocument.text";
          filename = request.getName() + ".odt";
          break;
        default:
          return ResponseEntity.badRequest().build();
      }

      return ResponseEntity.ok()
          .header("Content-Type", contentType)
          .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
          .body(documentBytes);

    } catch (Exception e) {
      logger.log(Level.SEVERE, "Document export failed", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /** Get list of available template variables */
  @GetMapping("/variables/list")
  public ResponseEntity<List<TemplateVariableDto>> getAvailableVariables() {
    List<TemplateVariableDto> variables =
        List.of(
            new TemplateVariableDto("OWNER_COMPANY", "Firma", "Ihr Firmenname"),
            new TemplateVariableDto(
                "OWNER_FIRST_NAME OWNER_LAST_NAME", "Name", "Ihr vollständiger Name"),
            new TemplateVariableDto("OWNER_ADDRESS", "Adresse", "Ihre Straßenadresse"),
            new TemplateVariableDto(
                "OWNER_POSTAL_CODE OWNER_CITY", "PLZ/Ort", "Ihre Postleitzahl und Stadt"),
            new TemplateVariableDto("OWNER_PHONE", "Telefon", "Ihre Telefonnummer"),
            new TemplateVariableDto("OWNER_EMAIL", "Email", "Ihre Email-Adresse"),
            new TemplateVariableDto("OWNER_IBAN", "IBAN", "Ihre IBAN"),
            new TemplateVariableDto("OWNER_BIC", "BIC", "Ihre BIC/SWIFT"),
            new TemplateVariableDto("OWNER_BANK_NAME", "Bank", "Ihr Kreditinstitut"),
            new TemplateVariableDto("CUSTOMER_FIRST_NAME CUSTOMER_LAST_NAME", "Name", "Kundenname"),
            new TemplateVariableDto(
                "CUSTOMER_ADDRESS CUSTOMER_HOUSE_NUMBER", "Adresse", "Kundenadresse"),
            new TemplateVariableDto(
                "CUSTOMER_POSTAL_CODE CUSTOMER_CITY", "PLZ/Ort", "Kunden PLZ/Ort"),
            new TemplateVariableDto("INVOICE_NUMBER", "Rechnungsnr.", "Eindeutige Rechnungsnummer"),
            new TemplateVariableDto("INVOICE_DATE", "Datum", "Rechnungsdatum"),
            new TemplateVariableDto("DUE_DATE", "Fällig", "Fälligkeitsdatum"),
            new TemplateVariableDto("NET_AMOUNT", "Netto", "Nettobetrag"),
            new TemplateVariableDto("TAX_RATE", "MwSt%", "Steuersatz"),
            new TemplateVariableDto("TAX_AMOUNT", "MwSt", "Steuerbetrag"),
            new TemplateVariableDto("GROSS_AMOUNT", "Brutto", "Gesamtbetrag"),
            new TemplateVariableDto("NOTES", "Notizen", "Zusätzliche Hinweise"),
            new TemplateVariableDto("INVOICE_ITEMS", "Positionen", "Rechnungspositionen"));

    return ResponseEntity.ok(variables);
  }

  /** Validate template variables in content */
  @PostMapping("/variables/validate")
  public ResponseEntity<ValidationResponse> validateVariables(
      @RequestBody ValidationRequest request) {

    try {
      List<DocumentConversionService.TemplateVariable> variables =
          conversionService.extractTemplateVariables(request.getContent());

      // Basic validation - check for malformed variables
      List<String> errors = new ArrayList<>();
      String content = request.getContent();

      // Check for unclosed variables
      int openCount = countOccurrences(content, "{{");
      int closeCount = countOccurrences(content, "}}");
      if (openCount != closeCount) {
        errors.add("Unclosed template variables detected");
      }

      // Check for empty variables
      Pattern emptyPattern = Pattern.compile("\\{\\{\\s*\\}\\}");
      if (emptyPattern.matcher(content).find()) {
        errors.add("Empty template variables detected");
      }

      boolean isValid = errors.isEmpty();
      String message = isValid ? "Template variables are valid" : "Validation errors found";

      return ResponseEntity.ok(new ValidationResponse(isValid, message, errors, variables));

    } catch (Exception e) {
      logger.log(Level.SEVERE, "Variable validation failed", e);
      return ResponseEntity.ok(
          new ValidationResponse(false, "Validation failed", List.of("Internal error"), List.of()));
    }
  }

  private InvoiceTemplateDto convertToDto(InvoiceTemplate template) {
    return new InvoiceTemplateDto(
        template.getId(),
        template.getName(),
        template.getDocumentTypeId(),
        template.getHtmlContent(),
        template.getCssContent(),
        template.getHeaderHtml(),
        template.getFooterHtml(),
        template.isDefault(),
        template.getMarginTopMm(),
        template.getMarginBottomMm(),
        template.getMarginLeftMm(),
        template.getMarginRightMm(),
        template.getCreatedAt(),
        template.getUpdatedAt(),
        template.getBlocks(),
        template.isDocumentSpecific());
  }

  private int countOccurrences(String text, String substring) {
    int count = 0;
    int index = text.indexOf(substring);
    while (index != -1) {
      count++;
      index = text.indexOf(substring, index + substring.length());
    }
    return count;
  }

  // DTOs for request/response
  public static class ExportRequest {
    private String name;
    private String content;
    private String format;

    // Getters and setters
    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getContent() {
      return content;
    }

    public void setContent(String content) {
      this.content = content;
    }

    public String getFormat() {
      return format;
    }

    public void setFormat(String format) {
      this.format = format;
    }
  }

  public static class ValidationRequest {
    private String content;

    public String getContent() {
      return content;
    }

    public void setContent(String content) {
      this.content = content;
    }
  }

  public static class ValidationResponse {
    private boolean valid;
    private String message;
    private List<String> errors;
    private List<DocumentConversionService.TemplateVariable> variables;

    public ValidationResponse(
        boolean valid,
        String message,
        List<String> errors,
        List<DocumentConversionService.TemplateVariable> variables) {
      this.valid = valid;
      this.message = message;
      this.errors = errors;
      this.variables = variables;
    }

    // Getters
    public boolean isValid() {
      return valid;
    }

    public String getMessage() {
      return message;
    }

    public List<String> getErrors() {
      return errors;
    }

    public List<DocumentConversionService.TemplateVariable> getVariables() {
      return variables;
    }
  }

  public static class TemplateVariableDto {
    private String name;
    private String label;
    private String description;

    public TemplateVariableDto(String name, String label, String description) {
      this.name = name;
      this.label = label;
      this.description = description;
    }

    // Getters
    public String getName() {
      return name;
    }

    public String getLabel() {
      return label;
    }

    public String getDescription() {
      return description;
    }
  }
}
