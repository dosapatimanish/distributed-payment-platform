package com.paymentplatform.ledger.repository;

import com.paymentplatform.ledger.domain.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, String> {

    List<LedgerEntry> findByWalletIdOrderByCreatedAtAsc(String walletId);

    List<LedgerEntry> findByTransactionId(String transactionId);

    boolean existsByTransactionId(String transactionId);
}
