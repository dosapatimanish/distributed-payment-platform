package com.paymentplatform.orchestrator.service;

import com.paymentplatform.orchestrator.client.FxRateServiceClient;
import com.paymentplatform.orchestrator.client.WalletServiceClient;
import com.paymentplatform.orchestrator.client.dto.RateLockResponse;
import com.paymentplatform.orchestrator.client.dto.WalletResponse;
import com.paymentplatform.orchestrator.domain.ConversionTransaction;
import com.paymentplatform.orchestrator.domain.SagaState;
import com.paymentplatform.orchestrator.exception.ConversionNotFoundException;
import com.paymentplatform.orchestrator.repository.ConversionTransactionRepository;
import com.paymentplatform.orchestrator.repository.SagaStepLogRepository;
import com.paymentplatform.orchestrator.web.ConversionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
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
 * Unit test for ConversionService - WalletServiceClient/FxRateServiceClient/both repositories
 * mocked with Mockito, no Spring context, no real HTTP calls. Each test drives the saga through
 * one specific path (happy path, or one specific failure) and asserts the final persisted
 * SagaState and which downstream calls actually happened.
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

    private ConversionService conversionService;

    @BeforeEach
    void setUp() {
        conversionService = new ConversionService(transactionRepository, stepLogRepository, walletClient, fxRateClient);
        // save() just needs to hand back what it was given, like every other service's tests.
        // lenient: getConversion tests never call save() at all.
        lenient().when(transactionRepository.save(any(ConversionTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ConversionRequest sampleRequest() {
        return new ConversionRequest("user-1", "src-wallet", "dst-wallet", "USD", "INR", new BigDecimal("100.00"));
    }

    // ------------------------------------------------------------------
    // Happy path
    // ------------------------------------------------------------------

    @Test
    void startConversion_allStepsSucceed_endsCompleted() {
        when(fxRateClient.lockRate(eq("USD"), eq("INR"), any(), anyString(), anyString()))
                .thenReturn(new RateLockResponse("lock-1", new BigDecimal("83.0000"), "ACTIVE"));
        when(walletClient.debit(eq("src-wallet"), any(), anyString(), anyString()))
                .thenReturn(new WalletResponse("src-wallet", BigDecimal.ZERO, "ACTIVE"));
        when(walletClient.credit(eq("dst-wallet"), any(), anyString(), anyString()))
                .thenReturn(new WalletResponse("dst-wallet", new BigDecimal("8300.00"), "ACTIVE"));

        ConversionTransaction txn = conversionService.startConversion("idem-1", sampleRequest());

        assertThat(txn.getSagaState()).isEqualTo(SagaState.COMPLETED);
        assertThat(txn.getLockedRate()).isEqualByComparingTo("83.0000");
        assertThat(txn.getDestAmount()).isEqualByComparingTo("8300.0000");
        verify(fxRateClient).consumeLock(eq("lock-1"), anyString());
        verify(fxRateClient, never()).releaseLock(any());
    }

    @Test
    void startConversion_consumeLockFails_stillCompletes() {
        // Consuming the lock is best-effort - the money already moved correctly, see
        // ConversionService's class javadoc.
        when(fxRateClient.lockRate(any(), any(), any(), anyString(), anyString()))
                .thenReturn(new RateLockResponse("lock-1", new BigDecimal("83.0000"), "ACTIVE"));
        when(walletClient.debit(any(), any(), anyString(), anyString()))
                .thenReturn(new WalletResponse("src-wallet", BigDecimal.ZERO, "ACTIVE"));
        when(walletClient.credit(any(), any(), anyString(), anyString()))
                .thenReturn(new WalletResponse("dst-wallet", new BigDecimal("8300.00"), "ACTIVE"));
        org.mockito.Mockito.doThrow(new RestClientException("lock expired"))
                .when(fxRateClient).consumeLock(anyString(), anyString());

        ConversionTransaction txn = conversionService.startConversion("idem-1", sampleRequest());

        assertThat(txn.getSagaState()).isEqualTo(SagaState.COMPLETED);
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
    // Credit fails after debit succeeded - full reversal
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
        verify(fxRateClient).releaseLock("lock-1");
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
