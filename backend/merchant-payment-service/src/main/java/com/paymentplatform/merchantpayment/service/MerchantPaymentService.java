package com.paymentplatform.merchantpayment.service;

import com.paymentplatform.merchantpayment.acquirer.AcquirerChargeResult;
import com.paymentplatform.merchantpayment.acquirer.AcquirerGatewayClient;
import com.paymentplatform.merchantpayment.domain.MerchantPayment;
import com.paymentplatform.merchantpayment.domain.PaymentStatus;
import com.paymentplatform.merchantpayment.event.MerchantPaymentEventPublisher;
import com.paymentplatform.merchantpayment.event.PaymentCompletedEvent;
import com.paymentplatform.merchantpayment.event.PaymentFailedEvent;
import com.paymentplatform.merchantpayment.exception.InvalidPaymentStateException;
import com.paymentplatform.merchantpayment.exception.PaymentConflictException;
import com.paymentplatform.merchantpayment.exception.PaymentNotFoundException;
import com.paymentplatform.merchantpayment.repository.MerchantPaymentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Charges (and refunds) a merchant payment via {@link AcquirerGatewayClient} (design doc
 * §6.3.4). Synchronous end to end: {@link #pay} calls the acquirer, learns the outcome, and
 * persists the payment already in its final state - there is no intermediate stored
 * {@code PENDING} row, since the mock acquirer never actually leaves a charge pending.
 */
@Service
public class MerchantPaymentService {

    private final MerchantPaymentRepository repository;
    private final AcquirerGatewayClient acquirerClient;
    private final MerchantPaymentEventPublisher eventPublisher;
    private final SequenceIds sequenceIds;

    public MerchantPaymentService(MerchantPaymentRepository repository, AcquirerGatewayClient acquirerClient,
                                   MerchantPaymentEventPublisher eventPublisher, SequenceIds sequenceIds) {
        this.repository = repository;
        this.acquirerClient = acquirerClient;
        this.eventPublisher = eventPublisher;
        this.sequenceIds = sequenceIds;
    }

    public MerchantPayment pay(String transactionId, String merchantId, BigDecimal amount, String currency) {
        AcquirerChargeResult result = acquirerClient.charge(merchantId, amount, currency);
        PaymentStatus status = result.approved() ? PaymentStatus.COMPLETED : PaymentStatus.FAILED;
        MerchantPayment payment = new MerchantPayment(
                sequenceIds.next("merchant_payment_seq", "PM"), transactionId, merchantId, amount, currency, result.acquirerRef(), status);
        try {
            payment = repository.save(payment);
        } catch (DataIntegrityViolationException ex) {
            // transaction_id UNIQUE - a duplicate/retried charge attempt for a transaction that
            // already has a payment record lands here (a genuinely different Idempotency-Key
            // pointing at the same transactionId, not a retry with the same key - that's caught
            // upstream by IdempotencyGuard instead).
            throw new PaymentConflictException(transactionId);
        }

        if (result.approved()) {
            eventPublisher.publishCompleted(new PaymentCompletedEvent(
                    transactionId, payment.getPaymentId(), amount, currency, result.acquirerRef(), Instant.now()));
        } else {
            eventPublisher.publishFailed(new PaymentFailedEvent(
                    transactionId, payment.getPaymentId(), amount, currency, result.declineReason(), Instant.now()));
        }
        return payment;
    }

    /** Idempotent: refunding an already-REFUNDED payment is a no-op success (design doc §6.4). */
    public MerchantPayment refund(String paymentId) {
        MerchantPayment payment = repository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            return payment;
        }
        if (payment.getStatus() != PaymentStatus.COMPLETED) {
            throw new InvalidPaymentStateException(paymentId, payment.getStatus());
        }
        acquirerClient.refund(payment.getAcquirerRef());
        payment.setStatus(PaymentStatus.REFUNDED);
        return repository.save(payment);
    }

    public MerchantPayment getPayment(String paymentId) {
        return repository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }
}
