package com.paymentplatform.merchantpayment.repository;

import com.paymentplatform.merchantpayment.domain.MerchantPayment;
import com.paymentplatform.merchantpayment.domain.PaymentStatus;
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
 * {@code WalletRepositoryIntegrationTest} (see its javadoc). {@link MerchantPayment} applied the
 * {@code Persistable} lesson from the start (see its own javadoc), so
 * {@link #save_populatesCreatedAtAndUpdatedAt_onTheReturnedInstance()} here confirms that held up
 * against a real database, not just against Mockito's stand-in for one.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Testcontainers
class MerchantPaymentRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static OracleContainer oracle = new OracleContainer("gvenzl/oracle-free:23-slim");

    @Autowired
    private MerchantPaymentRepository paymentRepository;

    private MerchantPayment samplePayment(String transactionId) {
        return new MerchantPayment("PM" + String.format("%010d", java.util.concurrent.ThreadLocalRandom.current().nextLong(1, 9999999999L)), transactionId, "merchant-1",
                new BigDecimal("50.00"), "USD", "acq-ref-1", PaymentStatus.COMPLETED);
    }

    @Test
    void save_populatesCreatedAtAndUpdatedAt_onTheReturnedInstance() {
        MerchantPayment saved = paymentRepository.save(samplePayment("it-txn-1"));

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void save_duplicateTransactionId_violatesRealUniqueConstraint() {
        paymentRepository.saveAndFlush(samplePayment("it-txn-2"));

        // uk_merchant_payment_transaction_id (design doc §6.1.4) - proves the constraint
        // actually exists in the migrated schema, not just declared on the entity.
        assertThatThrownBy(() -> paymentRepository.saveAndFlush(samplePayment("it-txn-2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findById_returnsPaymentWithCorrectStatus() {
        MerchantPayment payment = paymentRepository.save(samplePayment("it-txn-3"));

        assertThat(paymentRepository.findById(payment.getPaymentId()))
                .isPresent()
                .get()
                .extracting(MerchantPayment::getStatus)
                .isEqualTo(PaymentStatus.COMPLETED);
    }
}
