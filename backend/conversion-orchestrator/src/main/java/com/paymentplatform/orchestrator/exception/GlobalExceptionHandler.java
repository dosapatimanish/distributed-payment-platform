package com.paymentplatform.orchestrator.exception;

import com.paymentplatform.orchestrator.idempotency.IdempotencyKeyInProgressException;
import com.paymentplatform.orchestrator.saga.InvalidSagaTransitionException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/** Translates every exception this service throws into one consistent {@link ErrorResponse} JSON body - same role as the other two services'. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ConversionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ConversionNotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "CONVERSION_NOT_FOUND", ex.getMessage(), req);
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

    @ExceptionHandler(InvalidSagaTransitionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTransition(InvalidSagaTransitionException ex, HttpServletRequest req) {
        // Should never actually surface to a caller in normal operation - ConversionService only
        // ever attempts transitions its own flow logic already knows are legal. A safety net,
        // not an expected error path.
        log.error("Saga state machine rejected a transition - this indicates a bug in ConversionService: {}", ex.getMessage());
        return build(HttpStatus.CONFLICT, "INVALID_SAGA_TRANSITION", ex.getMessage(), req);
    }

    @ExceptionHandler(DownstreamServiceException.class)
    public ResponseEntity<ErrorResponse> handleDownstream(DownstreamServiceException ex, HttpServletRequest req) {
        // Normally caught and handled inside ConversionService (triggers compensation); reaching
        // here means it escaped that handling somewhere - still worth a clean response, not a 500.
        return build(HttpStatus.BAD_GATEWAY, "DOWNSTREAM_SERVICE_ERROR", ex.getMessage(), req);
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
