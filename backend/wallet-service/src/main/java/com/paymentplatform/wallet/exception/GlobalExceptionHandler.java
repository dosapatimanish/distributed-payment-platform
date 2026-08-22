package com.paymentplatform.wallet.exception;

import com.paymentplatform.wallet.idempotency.IdempotencyKeyInProgressException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/**
 * Translates every exception this service throws into one consistent {@link ErrorResponse}
 * JSON body, so callers never have to guess the shape of an error.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(WalletNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleWalletNotFound(WalletNotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", ex.getMessage(), req);
    }

    @ExceptionHandler(ReservationNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleReservationNotFound(ReservationNotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", ex.getMessage(), req);
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientFunds(InsufficientFundsException ex, HttpServletRequest req) {
        return build(HttpStatus.valueOf(422), "INSUFFICIENT_FUNDS", ex.getMessage(), req);
    }

    @ExceptionHandler({
            WalletNotActiveException.class,
            InvalidReservationStateException.class,
            WalletConflictException.class,
            DuplicateWalletException.class,
            ObjectOptimisticLockingFailureException.class,
            PessimisticLockingFailureException.class,
            DataIntegrityViolationException.class
    })
    public ResponseEntity<ErrorResponse> handleConflict(Exception ex, HttpServletRequest req) {
        String code;
        String message = ex.getMessage();
        if (ex instanceof WalletNotActiveException) {
            code = "WALLET_NOT_ACTIVE";
        } else if (ex instanceof InvalidReservationStateException) {
            code = "INVALID_RESERVATION_STATE";
        } else if (ex instanceof WalletConflictException || ex instanceof ObjectOptimisticLockingFailureException) {
            code = "WALLET_CONFLICT";
        } else if (ex instanceof DuplicateWalletException) {
            code = "DUPLICATE_WALLET";
        } else if (ex instanceof PessimisticLockingFailureException) {
            code = "WALLET_LOCK_TIMEOUT";
        } else if (ex instanceof DataIntegrityViolationException) {
            code = "DATA_CONFLICT";
            message = "The request conflicts with an existing record";
        } else {
            code = "DATA_CONFLICT";
        }
        return build(HttpStatus.CONFLICT, code, message, req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, req);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", ex.getMessage(), req);
    }

    @ExceptionHandler(IdempotencyKeyInProgressException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyInProgress(IdempotencyKeyInProgressException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_IN_PROGRESS", ex.getMessage(), req);
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
