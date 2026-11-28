package de.billery.api;

import de.billery.api.error.ErrorBody;
import de.billery.api.error.ErrorCode;
import de.billery.data.DatabaseException;
import de.billery.pdf.MissingConfigurationException;
import de.billery.service.BadRequestException;
import de.billery.service.DocumentNotFoundException;
import de.billery.service.DocumentStateException;
import de.billery.service.ExportBusyException;
import de.billery.service.ExportLimitException;
import de.billery.service.InvalidStatusChangeException;
import de.billery.service.MailDeliveryException;
import de.billery.service.MailNotRecordedException;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns exceptions that escape a controller into a JSON error body ({@link ErrorBody}: {@code
 * {"message": "...", "code": "...", "params": {...}}}, code and params when the message has one,
 * #15) that the SPA's axios error handling can display. Without it a failed repository call would
 * surface as Spring's default HTML error page.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger LOGGER = Logger.getLogger(ApiExceptionHandler.class.getName());

  /**
   * A database operation failed (already logged with its cause by the repository).
   *
   * @param e the failure
   * @return HTTP 500 with a generic message
   */
  @ExceptionHandler(DatabaseException.class)
  public ResponseEntity<Map<String, Object>> handleDatabase(DatabaseException e) {
    return databaseFailure();
  }

  /**
   * A transaction could not be opened, committed or rolled back. Nothing of the unit of work was
   * stored.
   *
   * @param e the failure
   * @return HTTP 500 with the same generic message as {@link #handleDatabase}
   */
  @ExceptionHandler(TransactionException.class)
  public ResponseEntity<Map<String, Object>> handleTransaction(TransactionException e) {
    LOGGER.log(Level.SEVERE, "Transaction failed", e);
    return databaseFailure();
  }

  private static ResponseEntity<Map<String, Object>> databaseFailure() {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ErrorBody.from(ErrorCode.DATABASE_FAILED.with()));
  }

  /**
   * A second owner was to be created; there is one per company. The message is meant for the user.
   *
   * @param e the refusal
   * @return HTTP 409 with the exception message
   */
  @ExceptionHandler(OwnerAlreadyExistsException.class)
  public ResponseEntity<Map<String, Object>> handleOwnerAlreadyExists(
      OwnerAlreadyExistsException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorBody.from(e));
  }

  /**
   * A document cannot be rendered until the user configures something (company data, template). The
   * message is meant for the user.
   *
   * @param e the failure
   * @return HTTP 409 with the exception message
   */
  @ExceptionHandler(MissingConfigurationException.class)
  public ResponseEntity<Map<String, Object>> handleMissingConfiguration(
      MissingConfigurationException e) {
    LOGGER.log(Level.WARNING, e.getMessage(), e);
    return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorBody.from(e));
  }

  /**
   * The operation is not allowed in the document's current state (e.g. editing an issued invoice).
   * The message is meant for the user.
   *
   * @param e the failure
   * @return HTTP 409 with the exception message
   */
  @ExceptionHandler(DocumentStateException.class)
  public ResponseEntity<Map<String, Object>> handleDocumentState(DocumentStateException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorBody.from(e));
  }

  /**
   * An offer or cost calculation cannot take the requested status. The message names the statuses
   * that are possible.
   *
   * @param e the failure
   * @return HTTP 400 with the exception message
   */
  @ExceptionHandler(InvalidStatusChangeException.class)
  public ResponseEntity<Map<String, Object>> handleInvalidStatusChange(
      InvalidStatusChangeException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorBody.from(e));
  }

  /**
   * An export would exceed a configured limit. The message names the limit.
   *
   * @param e the failure
   * @return HTTP 413 with the exception message
   */
  @ExceptionHandler(ExportLimitException.class)
  public ResponseEntity<Map<String, Object>> handleExportLimit(ExportLimitException e) {
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ErrorBody.from(e));
  }

  /**
   * Another export is already running and the concurrency limit is reached.
   *
   * @param e the failure
   * @return HTTP 429 with the exception message and a retry hint
   */
  @ExceptionHandler(ExportBusyException.class)
  public ResponseEntity<Map<String, Object>> handleExportBusy(ExportBusyException e) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, "60")
        .body(ErrorBody.from(e));
  }

  /**
   * The document does not exist.
   *
   * @param e the failure
   * @return HTTP 404 with the exception message
   */
  @ExceptionHandler(DocumentNotFoundException.class)
  public ResponseEntity<Map<String, Object>> handleDocumentNotFound(DocumentNotFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorBody.from(e));
  }

  /**
   * The input is not acceptable (mail account settings, recipients). The message is meant for the
   * user.
   *
   * @param e the failure
   * @return HTTP 400 with the exception message
   */
  @ExceptionHandler(BadRequestException.class)
  public ResponseEntity<Map<String, Object>> handleBadRequest(BadRequestException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorBody.from(e));
  }

  /**
   * A mail to a customer was not sent: the company's mail server failed (502) or its daily limit is
   * reached (429).
   *
   * @param e the failure
   * @return HTTP 502 or 429 with the exception message
   */
  @ExceptionHandler(MailDeliveryException.class)
  public ResponseEntity<Map<String, Object>> handleMailDelivery(MailDeliveryException e) {
    if (e.limitReached()) {
      return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
          .header(HttpHeaders.RETRY_AFTER, "3600")
          .body(ErrorBody.from(e));
    }
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorBody.from(e));
  }

  /**
   * A mail went out but could not be recorded in the document's history. The message tells the user
   * not to send it again.
   *
   * @param e the failure
   * @return HTTP 500 with the exception message
   */
  @ExceptionHandler(MailNotRecordedException.class)
  public ResponseEntity<Map<String, Object>> handleMailNotRecorded(MailNotRecordedException e) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorBody.from(e));
  }
}
