package com.paymentplatform.ledger.exception;

import java.time.Instant;

/** Consistent JSON error body returned by every failure path in this service - same shape as the other services'. */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path
) {
}
