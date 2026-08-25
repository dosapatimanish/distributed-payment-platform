package com.paymentplatform.ledger.repository;

import com.paymentplatform.ledger.domain.EntryType;
import com.paymentplatform.ledger.domain.LedgerEntry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Integration test against a real Postgres container - same pattern as wallet-service's
 * {@code WalletRepositoryIntegrationTest} (see its javadoc).
 * {@link #save_45CharReversalStyleTransactionId_fitsInTheColumn()} is a direct, permanent
 * regression test for conversion-orchestrator-implementation.md's "Bug 3" - the first live
 * compensation scenario hit "value too long for type character varying(36)" because a reversal
 * posting's {@code transactionId} ({@code {originalId}-reversal}) is 45 characters. Had this test
 * existed before that bug was hit, it would have failed immediately instead of only surfacing via
 * manual cross-service `curl` testing.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Testcontainers
class LedgerEntryRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private LedgerEntryRepository entryRepository;

    private LedgerEntry sampleEntry(String transactionId) {
        return new LedgerEntry(UUID.randomUUID().toString(), transactionId, "wallet-1", EntryType.DEBIT,
                new BigDecimal("100.00"), "USD", new BigDecimal("400.00"));
    }

    @Test
    void save_populatesCreatedAt_onTheReturnedInstance() {
        LedgerEntry saved = entryRepository.save(sampleEntry("it-txn-1"));

        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void save_45CharReversalStyleTransactionId_fitsInTheColumn() {
        String reversalStyleId = UUID.randomUUID() + "-reversal"; // 36 + 9 = 45 chars
        assertThat(reversalStyleId).hasSize(45);

        LedgerEntry saved = entryRepository.saveAndFlush(sampleEntry(reversalStyleId));

        assertThat(saved.getTransactionId()).isEqualTo(reversalStyleId);
        assertThat(entryRepository.findById(saved.getEntryId()))
                .isPresent()
                .get()
                .extracting(LedgerEntry::getTransactionId)
                .isEqualTo(reversalStyleId);
    }

    @Test
    void findByWalletId_returnsEntriesForThatWallet() {
        entryRepository.save(sampleEntry("it-txn-2"));

        assertThat(entryRepository.findByWalletIdOrderByCreatedAtAsc("wallet-1")).hasSize(1);
    }
}
