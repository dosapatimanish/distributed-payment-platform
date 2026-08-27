package com.paymentplatform.ledger.service;

import com.paymentplatform.ledger.domain.LedgerEntry;
import com.paymentplatform.ledger.exception.LedgerConflictException;
import com.paymentplatform.ledger.repository.LedgerEntryRepository;
import com.paymentplatform.ledger.web.LedgerLineRequest;
import com.paymentplatform.ledger.web.PostEntriesRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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

        List<LedgerLineRequest> lines = request.entries();
        List<LedgerEntry> saved = new java.util.ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            // entry_no = 2-digit leg number within the posting (identifier-scheme.md).
            String entryNo = String.format("%02d", i + 1);
            saved.add(repository.save(toEntity(request.transactionId(), entryNo, lines.get(i))));
        }
        return saved;
    }

    public List<LedgerEntry> getStatement(String accountNo) {
        return repository.findByAccountNoOrderByCreatedAtAsc(accountNo);
    }

    private LedgerEntry toEntity(String transactionId, String entryNo, LedgerLineRequest line) {
        return new LedgerEntry(
                transactionId,
                entryNo,
                line.accountNo(),
                line.entryType(),
                line.amount(),
                line.currency(),
                line.balanceAfter());
    }
}
