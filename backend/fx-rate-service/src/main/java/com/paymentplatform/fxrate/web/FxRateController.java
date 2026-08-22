package com.paymentplatform.fxrate.web;

import com.paymentplatform.fxrate.domain.FxRateLock;
import com.paymentplatform.fxrate.idempotency.IdempotencyGuard;
import com.paymentplatform.fxrate.service.FxRateCache;
import com.paymentplatform.fxrate.service.FxRateService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * {@code lockRate} and {@code consumeLock} require an {@code Idempotency-Key} header (design
 * doc 6.2.3) and route through {@link IdempotencyGuard#runIdempotent} - a retried/double-tapped
 * call with the same key replays the first attempt's exact result instead of hitting the
 * business-layer conflict a plain retry would otherwise get (a duplicate transactionId on
 * lockRate, or consuming an already-CONSUMED lock). {@code getCurrentRate} is read-only, and
 * {@code releaseLock} is already idempotent by design at the business layer (see
 * FxRateService.releaseLock) - neither needs a key.
 */
@RestController
@RequestMapping("/api/v1/fx")
public class FxRateController {

    private final FxRateService fxRateService;
    private final IdempotencyGuard idempotencyGuard;

    public FxRateController(FxRateService fxRateService, IdempotencyGuard idempotencyGuard) {
        this.fxRateService = fxRateService;
        this.idempotencyGuard = idempotencyGuard;
    }

    @GetMapping("/rates/{base}/{quote}")
    public RateResponse getCurrentRate(@PathVariable String base, @PathVariable String quote) {
        FxRateCache.RateSnapshot snapshot = fxRateService.getCurrentRate(base, quote);
        return RateResponse.of(base, quote, snapshot);
    }

    @PostMapping("/rate-lock")
    public ResponseEntity<RateLockResponse> lockRate(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                       @Valid @RequestBody RateLockRequest request) {
        RateLockResponse response = idempotencyGuard.runIdempotent(idempotencyKey, RateLockResponse.class, () -> {
            FxRateLock lock = fxRateService.lockRate(
                    request.transactionId(), request.baseCurrency(), request.quoteCurrency(), request.amount());
            return RateLockResponse.from(lock);
        });
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/fx/rate-lock/" + response.lockId()))
                .body(response);
    }

    /**
     * Not in the design doc's REST contract table, but needed to complete the lock's state
     * machine: something has to move ACTIVE -> CONSUMED once the locked rate is actually used
     * (e.g. by the Conversion Orchestrator, once it exists, right after the source-wallet debit
     * succeeds). Without this the lock only ever reaches a terminal state via release or TTL
     * expiry, and CONSUMED - the success path - would be unreachable.
     */
    @PostMapping("/rate-lock/{lockId}/consume")
    public RateLockResponse consumeLock(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                         @PathVariable String lockId) {
        return idempotencyGuard.runIdempotent(idempotencyKey, RateLockResponse.class,
                () -> RateLockResponse.from(fxRateService.consumeLock(lockId)));
    }

    /** Idempotent release (design doc 6.4) - see FxRateService.releaseLock. */
    @DeleteMapping("/rate-lock/{lockId}")
    public RateLockResponse releaseLock(@PathVariable String lockId) {
        return RateLockResponse.from(fxRateService.releaseLock(lockId));
    }
}
