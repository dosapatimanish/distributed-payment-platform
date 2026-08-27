package com.paymentplatform.orchestrator.repository;

import com.paymentplatform.orchestrator.domain.ConversionTransaction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Integration test against a real Oracle container. Regression test for the original
 * {@code Persistable}/merge-vs-persist bug (see {@link ConversionTransaction}'s javadoc), and
 * for the bank-style 16-char transaction_id / cif / account_no columns staying in sync with the
 * migration.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Testcontainers
class ConversionTransactionRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static OracleContainer oracle = new OracleContainer("gvenzl/oracle-free:23-slim");

    @Autowired
    private ConversionTransactionRepository transactionRepository;

    private static String txnId() {
        return String.format("%016d", ThreadLocalRandom.current().nextLong(0L, 10_000_000_000_000_000L));
    }

    private ConversionTransaction sampleTransaction(String idempotencyKey) {
        return new ConversionTransaction(txnId(), "1000000042", "011000000001", "031000000001",
                "USD", "INR", new BigDecimal("100.00"), idempotencyKey);
    }

    @Test
    void save_populatesCreatedAtAndUpdatedAt_onTheReturnedInstance() {
        ConversionTransaction saved = transactionRepository.save(sampleTransaction("it-idem-1"));

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void save_duplicateIdempotencyKey_violatesRealUniqueConstraint() {
        transactionRepository.saveAndFlush(sampleTransaction("it-idem-2"));

        // uk_conversion_transaction_idempotency_key - proves the constraint exists in the schema
        // (distinct transaction ids, same idempotency key).
        assertThatThrownBy(() -> transactionRepository.saveAndFlush(sampleTransaction("it-idem-2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findById_returnsTransactionWithCorrectState() {
        ConversionTransaction txn = transactionRepository.save(sampleTransaction("it-idem-3"));

        assertThat(transactionRepository.findById(txn.getTransactionId()))
                .isPresent()
                .get()
                .extracting(ConversionTransaction::getSagaState)
                .isEqualTo(com.paymentplatform.orchestrator.domain.SagaState.STARTED);
    }
}
