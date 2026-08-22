package com.paymentplatform.orchestrator.web;

import com.paymentplatform.orchestrator.domain.ConversionTransaction;
import com.paymentplatform.orchestrator.idempotency.IdempotencyGuard;
import com.paymentplatform.orchestrator.service.ConversionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * {@code POST /conversions} requires an {@code Idempotency-Key} header (design doc §6.4) and
 * routes through {@link IdempotencyGuard#runIdempotent} - a retried/double-tapped request with
 * the same key replays the saga's already-computed final result instead of running it again
 * (matches the design doc's SAGA flow diagram step 2: the idempotency check happens before any
 * state-changing call). {@code GET /conversions/{id}} is a read-only poll, no key needed.
 */
@RestController
@RequestMapping("/api/v1/conversions")
public class ConversionController {

    private final ConversionService conversionService;
    private final IdempotencyGuard idempotencyGuard;

    public ConversionController(ConversionService conversionService, IdempotencyGuard idempotencyGuard) {
        this.conversionService = conversionService;
        this.idempotencyGuard = idempotencyGuard;
    }

    @PostMapping
    public ResponseEntity<ConversionResponse> startConversion(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                                @Valid @RequestBody ConversionRequest request) {
        ConversionResponse response = idempotencyGuard.runIdempotent(idempotencyKey, ConversionResponse.class, () -> {
            ConversionTransaction txn = conversionService.startConversion(idempotencyKey, request);
            return ConversionResponse.from(txn);
        });
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/conversions/" + response.transactionId()))
                .body(response);
    }

    @GetMapping("/{transactionId}")
    public ConversionResponse getConversion(@PathVariable String transactionId) {
        return ConversionResponse.from(conversionService.getConversion(transactionId));
    }
}
