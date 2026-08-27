package com.paymentplatform.fxrate.repository;

import com.paymentplatform.fxrate.domain.FxRateLock;
import com.paymentplatform.fxrate.domain.RateLockStatus;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Integration test against a real Oracle container - same pattern and reasoning as
 * wallet-service's {@code WalletRepositoryIntegrationTest} (see its javadoc); Flyway's
 * {@code db/migration/V1__init.sql} runs automatically at context startup.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Testcontainers
class FxRateLockRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static OracleContainer oracle = new OracleContainer("gvenzl/oracle-free:23-slim");

    @Autowired
    private FxRateLockRepository lockRepository;

    private FxRateLock sampleLock(String transactionId) {
        return new FxRateLock("LK" + String.format("%010d", java.util.concurrent.ThreadLocalRandom.current().nextLong(1, 9999999999L)), transactionId, "USD", "INR",
                new BigDecimal("83.0000"), new BigDecimal("100.00"), RateLockStatus.ACTIVE,
                Instant.now().plus(10, ChronoUnit.SECONDS));
    }

    @Test
    void save_populatesCreatedAt_onTheReturnedInstance() {
        FxRateLock saved = lockRepository.save(sampleLock("it-txn-1"));

        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void save_duplicateTransactionId_violatesRealUniqueConstraint() {
        lockRepository.saveAndFlush(sampleLock("it-txn-2"));

        // uk_fx_rate_lock_transaction_id (design doc §6.1.2) - proves the constraint actually
        // exists in the migrated schema, not just declared on the entity.
        assertThatThrownBy(() -> lockRepository.saveAndFlush(sampleLock("it-txn-2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findById_returnsLockWithCorrectRatePrecision() {
        FxRateLock lock = lockRepository.save(sampleLock("it-txn-3"));

        assertThat(lockRepository.findById(lock.getLockId()))
                .isPresent()
                .get()
                .extracting(FxRateLock::getLockedRate)
                .isEqualTo(new BigDecimal("83.0000"));
    }
}
