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
import org.testcontainers.oracle.OracleContainer;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Integration test against a real Oracle container - real JDBC round-trip. Flyway's
 * {@code db/migration/V1__init.sql} runs at context startup, so this doubles as a regression
 * test for the migration staying in sync with {@link Wallet}'s entity mapping (bank-style
 * account_no / cif columns since backend-documents/identifier-scheme.md).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Testcontainers
class WalletRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static OracleContainer oracle = new OracleContainer("gvenzl/oracle-free:23-slim");

    @Autowired
    private WalletRepository walletRepository;

    @Test
    void save_populatesCreatedAtAndUpdatedAt_onTheReturnedInstance() {
        Wallet wallet = new Wallet("011000010001", "1000010001", "USD", BigDecimal.ZERO, WalletStatus.ACTIVE, false);

        Wallet saved = walletRepository.save(wallet);

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void save_duplicateCifCurrency_violatesRealUniqueConstraint() {
        walletRepository.saveAndFlush(
                new Wallet("021000020001", "1000020002", "EUR", BigDecimal.ZERO, WalletStatus.ACTIVE, false));

        // uk_wallet_cif_currency - proves the constraint exists in the migrated schema.
        assertThatThrownBy(() -> walletRepository.saveAndFlush(
                new Wallet("021000020002", "1000020002", "EUR", BigDecimal.ZERO, WalletStatus.ACTIVE, false)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findById_returnsWalletWithCorrectPrecision() {
        Wallet wallet = walletRepository.save(
                new Wallet("041000030001", "1000030003", "GBP", new BigDecimal("50.0000"), WalletStatus.ACTIVE, false));

        assertThat(walletRepository.findById(wallet.getAccountNo()))
                .isPresent()
                .get()
                .extracting(Wallet::getBalance)
                .isEqualTo(new BigDecimal("50.0000"));
    }
}
