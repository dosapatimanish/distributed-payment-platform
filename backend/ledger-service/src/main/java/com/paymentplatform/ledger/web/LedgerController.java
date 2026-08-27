package com.paymentplatform.ledger.web;

import com.paymentplatform.ledger.domain.LedgerEntry;
import com.paymentplatform.ledger.idempotency.IdempotencyGuard;
import com.paymentplatform.ledger.service.LedgerService;
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

import java.util.List;

/**
 * {@code postEntries} requires an {@code Idempotency-Key} header, same reasoning as every other
 * write endpoint in this platform - a retried posting (e.g. the orchestrator retrying after a
 * timeout) must replay the original result, not attempt a second posting and hit {@code
 * LedgerConflictException}. {@code getStatement} is read-only, so it doesn't need one.
 */
@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerController {

    private final LedgerService ledgerService;
    private final IdempotencyGuard idempotencyGuard;

    public LedgerController(LedgerService ledgerService, IdempotencyGuard idempotencyGuard) {
        this.ledgerService = ledgerService;
        this.idempotencyGuard = idempotencyGuard;
    }

    @PostMapping("/entries")
    public ResponseEntity<List<LedgerEntryResponse>> postEntries(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PostEntriesRequest request) {
        List<LedgerEntryResponse> response = idempotencyGuard.runIdempotent(
                idempotencyKey, LedgerEntryResponseList.class,
                () -> new LedgerEntryResponseList(toResponses(ledgerService.postEntries(request)))
        ).entries();
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/wallets/{accountNo}/statement")
    public StatementResponse getStatement(@PathVariable String accountNo) {
        return new StatementResponse(accountNo, toResponses(ledgerService.getStatement(accountNo)));
    }

    private List<LedgerEntryResponse> toResponses(List<LedgerEntry> entries) {
        return entries.stream().map(LedgerEntryResponse::from).toList();
    }

    /**
     * Wrapper so {@link IdempotencyGuard#runIdempotent} - which caches/replays a single JSON
     * value per Idempotency-Key - has one object to serialize instead of a bare {@code List}
     * (generic list types don't round-trip cleanly through {@code Class<T>}-based
     * deserialization).
     */
    private record LedgerEntryResponseList(List<LedgerEntryResponse> entries) {
    }
}
