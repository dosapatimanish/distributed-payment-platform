package com.paymentplatform.wallet.repository;

import com.paymentplatform.wallet.domain.Wallet;
import com.paymentplatform.wallet.domain.WalletStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Integration test against a real Postgres container - no mocks, real JDBC round-trip. Unlike
 * {@code WalletServiceTest} (which mocks {@link WalletRepository} entirely), this exercises the
 * actual schema: Flyway's {@code db/migration/V1__init.sql} runs automatically at context
 * startup (same as production - see {@code application.properties}'s {@code ddl-auto=validate}
 * comment), so this doubles as a regression test for the migration file itself staying in sync
 * with {@link Wallet}'s entity mapping - exactly the kind of drift a mocked-repository unit test
 * structurally cannot catch (see testing-guide.md's "Why Testcontainers, and why only now").
 *
 * {@code @AutoConfigureTestDatabase(replace = NONE)} is required - without it {@code @DataJpaTest}
 * tries to replace the datasource with an embedded database, which doesn't exist on this
 * project's classpath (no H2), and fails outright.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Testcontainers
class WalletRepositoryIntegrationTest {

    // Testcontainers 2.x's PostgreSQLContainer is no longer generic (the old self-typed
    // PostgreSQLContainer<SELF> builder pattern was dropped) - plain, unparameterized type.
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private WalletRepository walletRepository;

    @Test
    void save_populatesCreatedAtAndUpdatedAt_onTheReturnedInstance() {
        Wallet wallet = new Wallet(UUID.randomUUID().toString(), "user-it-1", "USD", BigDecimal.ZERO, WalletStatus.ACTIVE, false);

        Wallet saved = walletRepository.save(wallet);

        // The exact assertion that would have caught this platform's recurring "merge() returns
        // a different instance than the one passed to save()" bug, had Wallet ever needed the
        // Persistable fix (it doesn't - @Version already makes isNew() correct - but every other
        // Persistable-carrying entity's own integration test asserts the same thing).
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void save_duplicateUserCurrency_violatesRealUniqueConstraint() {
        walletRepository.saveAndFlush(
                new Wallet(UUID.randomUUID().toString(), "user-it-2", "EUR", BigDecimal.ZERO, WalletStatus.ACTIVE, false));

        // uk_wallet_user_currency (design doc §6.1.1) - proves the constraint actually exists in
        // the migrated schema, not just declared on the entity.
        assertThatThrownBy(() -> walletRepository.saveAndFlush(
                new Wallet(UUID.randomUUID().toString(), "user-it-2", "EUR", BigDecimal.ZERO, WalletStatus.ACTIVE, false)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findById_returnsWalletWithCorrectPrecision() {
        Wallet wallet = walletRepository.save(
                new Wallet(UUID.randomUUID().toString(), "user-it-3", "GBP", new BigDecimal("50.0000"), WalletStatus.ACTIVE, false));

        assertThat(walletRepository.findById(wallet.getWalletId()))
                .isPresent()
                .get()
                .extracting(Wallet::getBalance)
                .isEqualTo(new BigDecimal("50.0000"));
    }
}
