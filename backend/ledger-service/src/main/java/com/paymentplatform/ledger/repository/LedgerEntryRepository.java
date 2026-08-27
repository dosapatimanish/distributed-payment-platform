package com.paymentplatform.ledger.repository;

import com.paymentplatform.ledger.domain.LedgerEntry;
import com.paymentplatform.ledger.domain.LedgerEntryId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, LedgerEntryId> {

    List<LedgerEntry> findByAccountNoOrderByCreatedAtAsc(String accountNo);

    List<LedgerEntry> findByTransactionId(String transactionId);

    boolean existsByTransactionId(String transactionId);
}
