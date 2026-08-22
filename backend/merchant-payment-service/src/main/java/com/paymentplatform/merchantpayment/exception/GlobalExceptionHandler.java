package com.paymentplatform.merchantpayment.exception;

import com.paymentplatform.merchantpayment.idempotency.IdempotencyKeyInProgressException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/** Translates every exception this service throws into one consistent {@link ErrorResponse} JSON body - same role as the other services'. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(PaymentNotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", ex.getMessage(), req);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", ex.getMessage(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, req);
    }

    @ExceptionHandler(IdempotencyKeyInProgressException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyInProgress(IdempotencyKeyInProgressException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_IN_PROGRESS", ex.getMessage(), req);
    }

    @ExceptionHandler({PaymentConflictException.class, InvalidPaymentStateException.class, DataIntegrityViolationException.class})
    public ResponseEntity<ErrorResponse> handleConflict(Exception ex, HttpServletRequest req) {
        String code;
        if (ex instanceof PaymentConflictException) {
            code = "PAYMENT_CONFLICT";
        } else if (ex instanceof InvalidPaymentStateException) {
            code = "INVALID_PAYMENT_STATE";
        } else {
            code = "DATA_CONFLICT";
        }
        return build(HttpStatus.CONFLICT, code, ex.getMessage(), req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred", req);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message, HttpServletRequest req) {
        ErrorResponse body = new ErrorResponse(
                Instant.now(), status.value(), status.getReasonPhrase(), code, message, req.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
