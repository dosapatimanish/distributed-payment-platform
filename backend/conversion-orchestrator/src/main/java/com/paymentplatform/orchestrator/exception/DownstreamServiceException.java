package com.paymentplatform.orchestrator.exception;

/**
 * Wraps any failure (non-2xx response, timeout, connection refused) from a call to
 * wallet-service or fx-rate-service, so {@code ConversionService} has one exception type to
 * catch regardless of cause, and a human-readable message to record in {@code saga_step_log}.
 */
public class DownstreamServiceException extends RuntimeException {

    public DownstreamServiceException(String service, String operation, String cause) {
        super("%s %s failed: %s".formatted(service, operation, cause));
    }
}
