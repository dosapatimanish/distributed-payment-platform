package com.paymentplatform.ledger.repository;

import com.paymentplatform.ledger.domain.EntryType;
import com.paymentplatform.ledger.domain.LedgerEntry;
import com.paymentplatform.ledger.domain.LedgerEntryId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Integration test against a real Oracle container. Exercises the composite PK
 * {@code (transaction_id, entry_no)} and the wide {@code transaction_id} column a
 * {@code -reversal} posting needs (identifier-scheme.md).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Testcontainers
class LedgerEntryRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static OracleContainer oracle = new OracleContainer("gvenzl/oracle-free:23-slim");

    @Autowired
    private LedgerEntryRepository entryRepository;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private LedgerEntry sampleEntry(String transactionId) {
        String entryNo = String.format("%02d", SEQ.incrementAndGet());
        return new LedgerEntry(transactionId, entryNo, "011000000001", EntryType.DEBIT,
                new BigDecimal("100.00"), "USD", new BigDecimal("400.00"));
    }

    @Test
    void save_populatesCreatedAt_onTheReturnedInstance() {
        LedgerEntry saved = entryRepository.save(sampleEntry("0120260827000001"));

        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void save_reversalStyleTransactionId_fitsInTheColumn() {
        String reversalId = "0120260827000001-reversal"; // 25 chars

        LedgerEntry saved = entryRepository.saveAndFlush(sampleEntry(reversalId));

        assertThat(entryRepository.findById(new LedgerEntryId(reversalId, saved.getEntryNo())))
                .isPresent()
                .get()
                .extracting(LedgerEntry::getTransactionId)
                .isEqualTo(reversalId);
    }

    @Test
    void findByAccountNo_returnsEntriesForThatAccount() {
        entryRepository.save(sampleEntry("0120260827000002"));

        assertThat(entryRepository.findByAccountNoOrderByCreatedAtAsc("011000000001")).isNotEmpty();
    }
}
