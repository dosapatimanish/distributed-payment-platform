package com.paymentplatform.ledger.service;

import com.paymentplatform.ledger.domain.LedgerEntry;
import com.paymentplatform.ledger.exception.LedgerConflictException;
import com.paymentplatform.ledger.repository.LedgerEntryRepository;
import com.paymentplatform.ledger.web.LedgerLineRequest;
import com.paymentplatform.ledger.web.PostEntriesRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Writes double-entry ledger postings (design doc §6.3.5 {@code recordDoubleEntry}) and serves
 * per-wallet statements. Every posting is validated by {@link DoubleEntryValidator} before any
 * row is persisted, and all lines of one posting are written in a single transaction - either the
 * whole posting lands or none of it does.
 */
@Service
public class LedgerService {

    private final LedgerEntryRepository repository;
    private final DoubleEntryValidator validator;

    public LedgerService(LedgerEntryRepository repository, DoubleEntryValidator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    @Transactional
    public List<LedgerEntry> postEntries(PostEntriesRequest request) {
        if (repository.existsByTransactionId(request.transactionId())) {
            throw new LedgerConflictException(request.transactionId());
        }
        validator.validate(request.entries());

        List<LedgerEntry> saved = request.entries().stream()
                .map(line -> toEntity(request.transactionId(), line))
                .map(repository::save)
                .toList();
        return saved;
    }

    public List<LedgerEntry> getStatement(String walletId) {
        return repository.findByWalletIdOrderByCreatedAtAsc(walletId);
    }

    private LedgerEntry toEntity(String transactionId, LedgerLineRequest line) {
        return new LedgerEntry(
                UUID.randomUUID().toString(),
                transactionId,
                line.walletId(),
                line.entryType(),
                line.amount(),
                line.currency(),
                line.balanceAfter());
    }
}
