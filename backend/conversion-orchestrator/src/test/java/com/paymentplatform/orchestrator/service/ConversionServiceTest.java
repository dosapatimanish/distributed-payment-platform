package com.paymentplatform.orchestrator.service;

import com.paymentplatform.orchestrator.client.FxRateServiceClient;
import com.paymentplatform.orchestrator.client.LedgerServiceClient;
import com.paymentplatform.orchestrator.client.MerchantPaymentServiceClient;
import com.paymentplatform.orchestrator.client.WalletServiceClient;
import com.paymentplatform.orchestrator.client.dto.LedgerEntryType;
import com.paymentplatform.orchestrator.client.dto.LedgerLineRequest;
import com.paymentplatform.orchestrator.client.dto.PaymentResponse;
import com.paymentplatform.orchestrator.client.dto.RateLockResponse;
import com.paymentplatform.orchestrator.client.dto.WalletResponse;
import com.paymentplatform.orchestrator.domain.ConversionTransaction;
import com.paymentplatform.orchestrator.domain.SagaState;
import com.paymentplatform.orchestrator.exception.ConversionNotFoundException;
import com.paymentplatform.orchestrator.repository.ConversionTransactionRepository;
import com.paymentplatform.orchestrator.repository.SagaStepLogRepository;
import com.paymentplatform.orchestrator.web.ConversionRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for ConversionService - WalletServiceClient/FxRateServiceClient/
 * MerchantPaymentServiceClient/both repositories mocked with Mockito, no Spring context, no real
 * HTTP calls. Each test drives the saga through one specific path (happy path, or one specific
 * failure) and asserts the final persisted SagaState and which downstream calls actually
 * happened.
 */
@ExtendWith(MockitoExtension.class)
class ConversionServiceTest {

    @Mock
    private ConversionTransactionRepository transactionRepository;

    @Mock
    private SagaStepLogRepository stepLogRepository;

    @Mock
    private WalletServiceClient walletClient;

    @Mock
    private FxRateServiceClient fxRateClient;

    @Mock
    private MerchantPaymentServiceClient merchantPaymentClient;

    @Mock
    private LedgerServiceClient ledgerClient;

    private SimpleMeterRegistry meterRegistry;
    private ConversionService conversionService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        conversionService = new ConversionService(
                transactionRepository, stepLogRepository, walletClient, fxRateClient, merchantPaymentClient,
                ledgerClient, "SYSTEM-FX-CLEARING", meterRegistry);
        // save() just needs to hand back what it was given, like every other service's tests.
        // lenient: getConversion tests never call save() at all.
        lenient().when(transactionRepository.save(any(ConversionTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ConversionRequest sampleRequest() {
        return new ConversionRequest("user-1", "src-wallet", "dst-wallet", "USD", "INR", new BigDecimal("100.00"), null);
    }

    private ConversionRequest sampleRequestWithMerchant(String merchantId) {
        return new ConversionRequest("user-1", "src-wallet", "dst-wallet", "USD", "INR", new BigDecimal("100.00"), merchantId);
    }

    private void stubHappyPathThroughCredit() {
        when(fxRateClient.lockRate(eq("USD"), eq("INR"), any(), anyString(), anyString()))
                .thenReturn(new RateLockResponse("lock-1", new BigDecimal("83.0000"), "ACTIVE"));
        when(walletClient.debit(eq("src-wallet"), any(), anyString(), anyString()))
                .thenReturn(new WalletResponse("src-wallet", BigDecimal.ZERO, "ACTIVE"));
        when(walletClient.credit(eq("dst-wallet"), any(), anyString(), anyString()))
                .thenReturn(new WalletResponse("dst-wallet", new BigDecimal("8300.00"), "ACTIVE"));
    }

    // ------------------------------------------------------------------
    // Happy path - no merchant
    // ------------------------------------------------------------------

    @Test
    void startConversion_allStepsSucceed_endsCompleted() {
        stubHappyPathThroughCredit();

        ConversionTransaction txn = conversionService.startConversion("idem-1", sampleRequest());

        assertThat(txn.getSagaState()).isEqualTo(SagaState.COMPLETED);
        assertThat(txn.getLockedRate()).isEqualByComparingTo("83.0000");
        assertThat(txn.getDestAmount()).isEqualByComparingTo("8300.0000");
        verify(fxRateClient).consumeLock(eq("lock-1"), anyString());
        verify(fxRateClient, never()).releaseLock(any());
        verify(merchantPaymentClient, never()).pay(any(), any(), any(), any(), any());

        // Records the conversion via the clearing-account pattern - 4 legs, two per currency,
        // each currency group nets to zero (see ConversionService.recordLedgerEntries's javadoc).
        org.mockito.ArgumentCaptor<List<LedgerLineRequest>> entriesCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(ledgerClient).postEntries(eq(txn.getTransactionId()), entriesCaptor.capture(), anyString());
        List<LedgerLineRequest> entries = entriesCaptor.getValue();
        assertThat(entries).hasSize(4);
        assertThat(entries.get(0)).isEqualTo(new LedgerLineRequest("src-wallet", LedgerEntryType.DEBIT, new BigDecimal("100.00"), "USD", BigDecimal.ZERO));
        assertThat(entries.get(1)).isEqualTo(new LedgerLineRequest("SYSTEM-FX-CLEARING", LedgerEntryType.CREDIT, new BigDecimal("100.00"), "USD", BigDecimal.ZERO));
        assertThat(entries.get(2)).isEqualTo(new LedgerLineRequest("SYSTEM-FX-CLEARING", LedgerEntryType.DEBIT, new BigDecimal("8300.0000"), "INR", BigDecimal.ZERO));
        assertThat(entries.get(3)).isEqualTo(new LedgerLineRequest("dst-wallet", LedgerEntryType.CREDIT, new BigDecimal("8300.0000"), "INR", new BigDecimal("8300.00")));

        // design doc 5.4's "saga state" dashboard metric - one increment per transition actually
        // taken on this run: RATE_LOCKED, SOURCE_DEBITED, DEST_CREDITED, COMPLETED.
        assertThat(meterRegistry.counter("saga.state.transitions", "state", "COMPLETED").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("saga.state.transitions", "state", "DEST_CREDITED").count()).isEqualTo(1.0);
    }

    @Test
    void startConversion_consumeLockFails_stillCompletes() {
        stubHappyPathThroughCredit();
        org.mockito.Mockito.doThrow(new RestClientException("lock expired"))
                .when(fxRateClient).consumeLock(anyString(), anyString());

        ConversionTransaction txn = conversionService.startConversion("idem-1", sampleRequest());

        assertThat(txn.getSagaState()).isEqualTo(SagaState.COMPLETED);
    }

    @Test
    void startConversion_ledgerPostingFails_stillCompletes() {
        stubHappyPathThroughCredit();
        org.mockito.Mockito.doThrow(new RestClientException("ledger-service unreachable"))
                .when(ledgerClient).postEntries(anyString(), any(), anyString());

        ConversionTransaction txn = conversionService.startConversion("idem-1", sampleRequest());

        // Same "already-moved money, best-effort" reasoning as consumeLock above - a ledger
        // outage doesn't strand a saga that already succeeded at the wallet/FX layer.
        assertThat(txn.getSagaState()).isEqualTo(SagaState.COMPLETED);
        verify(fxRateClient).consumeLock(eq("lock-1"), anyString());
    }

    // ------------------------------------------------------------------
    // Rate lock fails - nothing to compensate
    // ------------------------------------------------------------------

    @Test
    void startConversion_rateLockFails_endsFailedWithNoCompensation() {
        when(fxRateClient.lockRate(any(), any(), any(), anyString(), anyString()))
                .thenThrow(new RestClientException("No rate available for pair USD/INR"));

        ConversionTransaction txn = conversionService.startConversion("idem-1", sampleRequest());

        assertThat(txn.getSagaState()).isEqualTo(SagaState.FAILED);
        verify(walletClient, never()).debit(any(), any(), anyString(), anyString());
        verify(walletClient, never()).credit(any(), any(), anyString(), anyString());
    }

    // ------------------------------------------------------------------
    // Debit fails - only the lock needs releasing (nothing was ever debited)
    // ------------------------------------------------------------------

    @Test
    void startConversion_debitFails_compensatesByReleasingLockOnly() {
        when(fxRateClient.lockRate(any(), any(), any(), anyString(), anyString()))
                .thenReturn(new RateLockResponse("lock-1", new BigDecimal("83.0000"), "ACTIVE"));
        when(walletClient.debit(any(), any(), anyString(), anyString()))
                .thenThrow(new RestClientException("Insufficient funds"));

        ConversionTransaction txn = conversionService.startConversion("idem-1", sampleRequest());

        assertThat(txn.getSagaState()).isEqualTo(SagaState.COMPENSATED);
        verify(fxRateClient).releaseLock("lock-1");
        // No reversal credit - nothing was ever debited.
        verify(walletClient, never()).credit(eq("src-wallet"), any(), anyString(), anyString());
        verify(walletClient, never()).credit(eq("dst-wallet"), any(), anyString(), anyString());
    }

    // ------------------------------------------------------------------
    // Credit fails after debit succeeded - reverse the debit
    // ------------------------------------------------------------------

    @Test
    void startConversion_creditFails_reversesDebitThenReleasesLock() {
        when(fxRateClient.lockRate(any(), any(), any(), anyString(), anyString()))
                .thenReturn(new RateLockResponse("lock-1", new BigDecimal("83.0000"), "ACTIVE"));
        when(walletClient.debit(eq("src-wallet"), any(), anyString(), anyString()))
                .thenReturn(new WalletResponse("src-wallet", BigDecimal.ZERO, "ACTIVE"));
        when(walletClient.credit(eq("dst-wallet"), any(), anyString(), anyString()))
                .thenThrow(new RestClientException("Wallet not found"));
        when(walletClient.credit(eq("src-wallet"), any(), anyString(), anyString()))
                .thenReturn(new WalletResponse("src-wallet", new BigDecimal("100.00"), "ACTIVE"));

        ConversionTransaction txn = conversionService.startConversion("idem-1", sampleRequest());

        assertThat(txn.getSagaState()).isEqualTo(SagaState.COMPENSATED);
        verify(walletClient).credit(eq("src-wallet"), eq(new BigDecimal("100.00")), anyString(), anyString());
        verify(walletClient, never()).debit(eq("dst-wallet"), any(), anyString(), anyString());
        verify(fxRateClient).releaseLock("lock-1");

        // Only the source-wallet side ever moved (and got reversed) - dest wallet was never
        // touched, so the reversal posting is just the 2 source-currency legs, not 4.
        org.mockito.ArgumentCaptor<List<LedgerLineRequest>> entriesCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(ledgerClient).postEntries(eq(txn.getTransactionId() + "-reversal"), entriesCaptor.capture(), anyString());
        List<LedgerLineRequest> entries = entriesCaptor.getValue();
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0)).isEqualTo(new LedgerLineRequest("src-wallet", LedgerEntryType.CREDIT, new BigDecimal("100.00"), "USD", new BigDecimal("100.00")));
        assertThat(entries.get(1)).isEqualTo(new LedgerLineRequest("SYSTEM-FX-CLEARING", LedgerEntryType.DEBIT, new BigDecimal("100.00"), "USD", BigDecimal.ZERO));
    }

    @Test
    void startConversion_debitFails_postsNoLedgerReversal_nothingEverMoved() {
        when(fxRateClient.lockRate(any(), any(), any(), anyString(), anyString()))
                .thenReturn(new RateLockResponse("lock-1", new BigDecimal("83.0000"), "ACTIVE"));
        when(walletClient.debit(any(), any(), anyString(), anyString()))
                .thenThrow(new RestClientException("Insufficient funds"));

        conversionService.startConversion("idem-1", sampleRequest());

        // DEBIT_FAILED compensates with reverseCredit=false, reverseDebit=false - nothing was
        // ever debited, so there's nothing to record either.
        verify(ledgerClient, never()).postEntries(any(), any(), any());
    }

    @Test
    void startConversion_creditFailsAndReversalAlsoFails_staysStuckAtCompensating_notFalselyCompensated() {
        when(fxRateClient.lockRate(any(), any(), any(), anyString(), anyString()))
                .thenReturn(new RateLockResponse("lock-1", new BigDecimal("83.0000"), "ACTIVE"));
        when(walletClient.debit(eq("src-wallet"), any(), anyString(), anyString()))
                .thenReturn(new WalletResponse("src-wallet", BigDecimal.ZERO, "ACTIVE"));
        when(walletClient.credit(eq("dst-wallet"), any(), anyString(), anyString()))
                .thenThrow(new RestClientException("Wallet not found"));
        when(walletClient.credit(eq("src-wallet"), any(), anyString(), anyString()))
                .thenThrow(new RestClientException("wallet-service unreachable"));

        ConversionTransaction txn = conversionService.startConversion("idem-1", sampleRequest());

        // Must NOT be COMPENSATED - the reversal itself failed, so money is still in the wrong
        // place. Staying at COMPENSATING (not silently advancing) is the correct, honest state.
        assertThat(txn.getSagaState()).isEqualTo(SagaState.COMPENSATING);
        verify(fxRateClient, never()).releaseLock(any());
    }

    // ------------------------------------------------------------------
    // Merchant payment - approved
    // ------------------------------------------------------------------

    @Test
    void startConversion_merchantPaymentApproved_debitsDestWalletToPay_endsCompleted() {
        stubHappyPathThroughCredit();
        when(merchantPaymentClient.pay(anyString(), eq("merchant-1"), any(), eq("INR"), anyString()))
                .thenReturn(new PaymentResponse("pay-1", "COMPLETED"));
        when(walletClient.debit(eq("dst-wallet"), any(), anyString(), anyString()))
                .thenReturn(new WalletResponse("dst-wallet", BigDecimal.ZERO, "ACTIVE"));

        ConversionTransaction txn = conversionService.startConversion("idem-1", sampleRequestWithMerchant("merchant-1"));

        assertThat(txn.getSagaState()).isEqualTo(SagaState.COMPLETED);
        verify(merchantPaymentClient).pay(anyString(), eq("merchant-1"), eq(new BigDecimal("8300.0000")), eq("INR"), anyString());
        // The credited amount is spent right back out to pay the merchant - net zero on the dest wallet.
        verify(walletClient).debit(eq("dst-wallet"), eq(new BigDecimal("8300.0000")), anyString(), anyString());
        verify(merchantPaymentClient, never()).refund(any());
    }

    @Test
    void startConversion_paymentApprovedButDestDebitFails_refundsPaymentThenFullyCompensates() {
        stubHappyPathThroughCredit();
        when(merchantPaymentClient.pay(anyString(), eq("merchant-1"), any(), eq("INR"), anyString()))
                .thenReturn(new PaymentResponse("pay-1", "COMPLETED"));
        // First call: the post-charge "spend" debit - fails (why we end up here at all).
        // Second call: compensate()'s reverseCredit attempt, moments later - succeeds, so this
        // test isolates "refund the charge, then compensation completes normally" as its own
        // scenario, distinct from the already-covered "compensation itself gets stuck" case.
        when(walletClient.debit(eq("dst-wallet"), any(), anyString(), anyString()))
                .thenThrow(new RestClientException("wallet-service unreachable"))
                .thenReturn(new WalletResponse("dst-wallet", BigDecimal.ZERO, "ACTIVE"));
        when(walletClient.credit(eq("src-wallet"), any(), anyString(), anyString()))
                .thenReturn(new WalletResponse("src-wallet", new BigDecimal("100.00"), "ACTIVE"));

        ConversionTransaction txn = conversionService.startConversion("idem-1", sampleRequestWithMerchant("merchant-1"));

        // The acquirer already charged for real - must be refunded before unwinding the rest.
        verify(merchantPaymentClient).refund("pay-1");
        assertThat(txn.getSagaState()).isEqualTo(SagaState.COMPENSATED);
        verify(walletClient, org.mockito.Mockito.times(2)).debit(eq("dst-wallet"), any(), anyString(), anyString());
        verify(walletClient).credit(eq("src-wallet"), any(), anyString(), anyString());
        verify(fxRateClient).releaseLock("lock-1");
    }

    // ------------------------------------------------------------------
    // Merchant payment - declined or failed to call - full reversal
    // ------------------------------------------------------------------

    @Test
    void startConversion_merchantPaymentDeclined_reversesCreditThenDebitThenReleasesLock() {
        stubHappyPathThroughCredit();
        when(merchantPaymentClient.pay(anyString(), eq("acct-decline"), any(), eq("INR"), anyString()))
                .thenReturn(new PaymentResponse("pay-1", "FAILED"));
        when(walletClient.debit(eq("dst-wallet"), any(), anyString(), anyString()))
                .thenReturn(new WalletResponse("dst-wallet", BigDecimal.ZERO, "ACTIVE"));
        when(walletClient.credit(eq("src-wallet"), any(), anyString(), anyString()))
                .thenReturn(new WalletResponse("src-wallet", new BigDecimal("100.00"), "ACTIVE"));

        ConversionTransaction txn = conversionService.startConversion("idem-1", sampleRequestWithMerchant("acct-decline"));

        assertThat(txn.getSagaState()).isEqualTo(SagaState.COMPENSATED);
        // Reverse order: the credit (later step) is undone before the debit (earlier step).
        verify(walletClient).debit(eq("dst-wallet"), eq(new BigDecimal("8300.0000")), anyString(), anyString());
        verify(walletClient).credit(eq("src-wallet"), eq(new BigDecimal("100.00")), anyString(), anyString());
        verify(fxRateClient).releaseLock("lock-1");

        // Both sides reversed - the reversal posting is the full 4-leg mirror.
        org.mockito.ArgumentCaptor<List<LedgerLineRequest>> entriesCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(ledgerClient).postEntries(eq(txn.getTransactionId() + "-reversal"), entriesCaptor.capture(), anyString());
        assertThat(entriesCaptor.getValue()).hasSize(4);
    }

    @Test
    void startConversion_merchantPaymentCallFails_alsoTriggersFullReversal() {
        stubHappyPathThroughCredit();
        when(merchantPaymentClient.pay(anyString(), any(), any(), eq("INR"), anyString()))
                .thenThrow(new RestClientException("merchant-payment-service unreachable"));
        when(walletClient.debit(eq("dst-wallet"), any(), anyString(), anyString()))
                .thenReturn(new WalletResponse("dst-wallet", BigDecimal.ZERO, "ACTIVE"));
        when(walletClient.credit(eq("src-wallet"), any(), anyString(), anyString()))
                .thenReturn(new WalletResponse("src-wallet", new BigDecimal("100.00"), "ACTIVE"));

        ConversionTransaction txn = conversionService.startConversion("idem-1", sampleRequestWithMerchant("merchant-1"));

        assertThat(txn.getSagaState()).isEqualTo(SagaState.COMPENSATED);
        verify(walletClient).debit(eq("dst-wallet"), any(), anyString(), anyString());
        verify(walletClient).credit(eq("src-wallet"), any(), anyString(), anyString());
    }

    // ------------------------------------------------------------------
    // getConversion
    // ------------------------------------------------------------------

    @Test
    void getConversion_found_returnsTransaction() {
        ConversionTransaction txn = new ConversionTransaction(
                "txn-1", "user-1", "src", "dst", "USD", "INR", BigDecimal.TEN, "idem-1");
        when(transactionRepository.findById("txn-1")).thenReturn(Optional.of(txn));

        assertThat(conversionService.getConversion("txn-1")).isSameAs(txn);
    }

    @Test
    void getConversion_notFound_throws() {
        when(transactionRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> conversionService.getConversion("missing"))
                .isInstanceOf(ConversionNotFoundException.class);
    }
}
