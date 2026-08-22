package com.paymentplatform.fxrate.exception;

import java.time.Instant;

/** Consistent JSON error body returned by every failure path in this service - same shape as wallet-service's. */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path
) {
}
