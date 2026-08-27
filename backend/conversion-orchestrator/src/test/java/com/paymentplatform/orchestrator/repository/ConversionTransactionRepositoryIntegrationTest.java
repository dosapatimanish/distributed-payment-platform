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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Integration test against a real Oracle container - same pattern as wallet-service's
 * {@code WalletRepositoryIntegrationTest} (see its javadoc). Particularly worth having here
 * specifically: {@link ConversionTransaction} is the entity whose {@code createdAt}/
 * {@code updatedAt}-comes-back-null bug (see its own javadoc) was the original discovery of the
 * {@code Persistable} lesson this whole platform now applies from the start on every
 * application-assigned-id entity. {@link #save_populatesCreatedAtAndUpdatedAt_onTheReturnedInstance()}
 * is a direct, permanent regression test for that exact bug - had this test existed at the time,
 * it would have failed immediately instead of the bug being caught by a manual {@code curl}.
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

    private ConversionTransaction sampleTransaction(String idempotencyKey) {
        return new ConversionTransaction(UUID.randomUUID().toString(), "user-1", "src-wallet", "dst-wallet",
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

        // uk_conversion_transaction_idempotency_key (design doc §6.1.3) - proves the constraint
        // actually exists in the migrated schema, not just declared on the entity.
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
