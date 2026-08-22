package com.paymentplatform.merchantpayment.service;

import com.paymentplatform.merchantpayment.acquirer.AcquirerChargeResult;
import com.paymentplatform.merchantpayment.acquirer.AcquirerGatewayClient;
import com.paymentplatform.merchantpayment.domain.MerchantPayment;
import com.paymentplatform.merchantpayment.domain.PaymentStatus;
import com.paymentplatform.merchantpayment.event.MerchantPaymentEventPublisher;
import com.paymentplatform.merchantpayment.exception.InvalidPaymentStateException;
import com.paymentplatform.merchantpayment.exception.PaymentConflictException;
import com.paymentplatform.merchantpayment.exception.PaymentNotFoundException;
import com.paymentplatform.merchantpayment.repository.MerchantPaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantPaymentServiceTest {

    @Mock
    private MerchantPaymentRepository repository;

    @Mock
    private AcquirerGatewayClient acquirerClient;

    @Mock
    private MerchantPaymentEventPublisher eventPublisher;

    private MerchantPaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new MerchantPaymentService(repository, acquirerClient, eventPublisher);
    }

    // ------------------------------------------------------------------
    // pay
    // ------------------------------------------------------------------

    @Test
    void pay_acquirerApproves_savesCompletedAndPublishesCompleted() {
        when(acquirerClient.charge(eq("merchant-1"), any(), anyString()))
                .thenReturn(AcquirerChargeResult.approved("acq-ref-1"));
        when(repository.save(any(MerchantPayment.class))).thenAnswer(inv -> inv.getArgument(0));

        MerchantPayment payment = paymentService.pay("txn-1", "merchant-1", new BigDecimal("50.00"), "USD");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.getAcquirerRef()).isEqualTo("acq-ref-1");
        verify(eventPublisher).publishCompleted(any());
        verify(eventPublisher, never()).publishFailed(any());
    }

    @Test
    void pay_acquirerDeclines_savesFailedAndPublishesFailed() {
        when(acquirerClient.charge(eq("acct-decline"), any(), anyString()))
                .thenReturn(AcquirerChargeResult.declined("Acquirer declined the charge for merchant acct-decline"));
        when(repository.save(any(MerchantPayment.class))).thenAnswer(inv -> inv.getArgument(0));

        MerchantPayment payment = paymentService.pay("txn-1", "acct-decline", new BigDecimal("50.00"), "USD");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getAcquirerRef()).isNull();
        verify(eventPublisher).publishFailed(any());
        verify(eventPublisher, never()).publishCompleted(any());
    }

    @Test
    void pay_duplicateTransactionId_throwsConflict() {
        when(acquirerClient.charge(any(), any(), anyString())).thenReturn(AcquirerChargeResult.approved("acq-ref-1"));
        when(repository.save(any(MerchantPayment.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> paymentService.pay("txn-1", "merchant-1", BigDecimal.TEN, "USD"))
                .isInstanceOf(PaymentConflictException.class);

        // A conflicted save must not still publish an event for a payment that was never actually persisted.
        verify(eventPublisher, never()).publishCompleted(any());
        verify(eventPublisher, never()).publishFailed(any());
    }

    // ------------------------------------------------------------------
    // refund
    // ------------------------------------------------------------------

    @Test
    void refund_completedPayment_marksRefunded() {
        MerchantPayment payment = new MerchantPayment("pay-1", "txn-1", "merchant-1", BigDecimal.TEN, "USD", "acq-ref-1", PaymentStatus.COMPLETED);
        when(repository.findById("pay-1")).thenReturn(Optional.of(payment));
        when(repository.save(any(MerchantPayment.class))).thenAnswer(inv -> inv.getArgument(0));

        MerchantPayment result = paymentService.refund("pay-1");

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(acquirerClient).refund("acq-ref-1");
    }

    @Test
    void refund_alreadyRefunded_isIdempotentNoOp() {
        MerchantPayment payment = new MerchantPayment("pay-1", "txn-1", "merchant-1", BigDecimal.TEN, "USD", "acq-ref-1", PaymentStatus.REFUNDED);
        when(repository.findById("pay-1")).thenReturn(Optional.of(payment));

        MerchantPayment result = paymentService.refund("pay-1");

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(acquirerClient, never()).refund(any());
        verify(repository, never()).save(any());
    }

    @Test
    void refund_neverCompletedPayment_throwsInvalidState() {
        MerchantPayment payment = new MerchantPayment("pay-1", "txn-1", "merchant-1", BigDecimal.TEN, "USD", null, PaymentStatus.FAILED);
        when(repository.findById("pay-1")).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentService.refund("pay-1"))
                .isInstanceOf(InvalidPaymentStateException.class);
        verify(acquirerClient, never()).refund(any());
    }

    @Test
    void refund_notFound_throws() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.refund("missing"))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    // ------------------------------------------------------------------
    // getPayment
    // ------------------------------------------------------------------

    @Test
    void getPayment_found_returnsPayment() {
        MerchantPayment payment = new MerchantPayment("pay-1", "txn-1", "merchant-1", BigDecimal.TEN, "USD", "acq-ref-1", PaymentStatus.COMPLETED);
        when(repository.findById("pay-1")).thenReturn(Optional.of(payment));

        assertThat(paymentService.getPayment("pay-1")).isSameAs(payment);
    }

    @Test
    void getPayment_notFound_throws() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.getPayment("missing"))
                .isInstanceOf(PaymentNotFoundException.class);
    }
}
