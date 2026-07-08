package com.abel.ledger.api.exception;

import com.abel.ledger.api.dto.response.ErrorResponse;
import com.abel.ledger.exception.AccountNotFoundException;
import com.abel.ledger.exception.CurrencyMismatchException;
import com.abel.ledger.exception.IdempotencyKeyConflictException;
import com.abel.ledger.exception.InvalidPostingRequestException;
import com.abel.ledger.exception.JournalEntryNotFoundException;
import com.abel.ledger.exception.LedgerException;
import com.abel.ledger.exception.UnbalancedJournalEntryException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Central mapping from exceptions to HTTP responses. Controllers never
 * catch these — every failure mode below is either a {@link LedgerException}
 * subtype raised by the service layer or a framework-level validation
 * failure on the API request DTOs.
 *
 * 422 is used specifically for {@link UnbalancedJournalEntryException} and
 * {@link CurrencyMismatchException}: both describe requests that are
 * syntactically well-formed (they pass Jakarta Validation) but violate a
 * business rule. 400 is reserved for requests that are malformed —
 * missing/blank fields, wrong types, bean-validation failures — which is
 * what {@link InvalidPostingRequestException} and
 * {@link MethodArgumentNotValidException} represent.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(
            IdempotencyKeyConflictException ex, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler({UnbalancedJournalEntryException.class, CurrencyMismatchException.class})
    public ResponseEntity<ErrorResponse> handleUnprocessable(LedgerException ex, HttpServletRequest request) {
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request);
    }

    @ExceptionHandler({AccountNotFoundException.class, JournalEntryNotFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(LedgerException ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidPostingRequestException.class)
    public ResponseEntity<ErrorResponse> handleMalformed(
            InvalidPostingRequestException ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return respond(HttpStatus.BAD_REQUEST, message.isBlank() ? "Validation failed" : message, request);
    }

    @ExceptionHandler(LedgerException.class)
    public ResponseEntity<ErrorResponse> handleOtherLedgerExceptions(LedgerException ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request);
    }

    private ResponseEntity<ErrorResponse> respond(HttpStatus status, String message, HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(
                Instant.now(), status.value(), status.getReasonPhrase(), message, request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
