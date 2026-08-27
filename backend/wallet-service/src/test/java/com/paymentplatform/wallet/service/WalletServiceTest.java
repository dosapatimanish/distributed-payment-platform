package com.paymentplatform.wallet.service;

import com.paymentplatform.wallet.domain.Currency;
import com.paymentplatform.wallet.domain.ReservationStatus;
import com.paymentplatform.wallet.domain.Wallet;
import com.paymentplatform.wallet.domain.WalletReservation;
import com.paymentplatform.wallet.domain.WalletStatus;
import com.paymentplatform.wallet.exception.DuplicateWalletException;
import com.paymentplatform.wallet.exception.InsufficientFundsException;
import com.paymentplatform.wallet.exception.InvalidReservationStateException;
import com.paymentplatform.wallet.exception.ReservationNotFoundException;
import com.paymentplatform.wallet.exception.WalletConflictException;
import com.paymentplatform.wallet.exception.WalletNotActiveException;
import com.paymentplatform.wallet.exception.WalletNotFoundException;
import com.paymentplatform.wallet.event.WalletEventPublisher;
import com.paymentplatform.wallet.repository.WalletReservationRepository;
import com.paymentplatform.wallet.repository.WalletRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for WalletService - no Spring context, collaborators mocked with Mockito. Ids here
 * are placeholders; the account-number / currency lookups are mocked, so no format rules apply.
 */
@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    private static final long RESERVATION_TTL_MINUTES = 15;
    private static final String CIF = "1000000042";
    private static final String ACC = "011000000001";

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private WalletReservationRepository reservationRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private WalletEventPublisher eventPublisher;

    @Mock
    private CurrencyService currencyService;

    @Mock
    private AccountNumberGenerator accountNumberGenerator;

    @Mock
    private SequenceIds sequenceIds;

    private SimpleMeterRegistry meterRegistry;
    private WalletService walletService;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(mock(TransactionStatus.class));
        Currency usd = mock(Currency.class);
        lenient().when(usd.getShortCode()).thenReturn("01");
        lenient().when(currencyService.requireActive("USD")).thenReturn(usd);
        lenient().when(accountNumberGenerator.nextAccountNumber(anyString(), anyString())).thenReturn(ACC);
        lenient().when(sequenceIds.next(anyString(), anyString())).thenReturn("RS0000000001");
        meterRegistry = new SimpleMeterRegistry();
        walletService = new WalletService(
                walletRepository, reservationRepository, eventPublisher, currencyService, accountNumberGenerator,
                sequenceIds, transactionManager, RESERVATION_TTL_MINUTES, meterRegistry);
    }

    private Wallet activeWallet(String accountNo, BigDecimal balance, boolean highContention) {
        return new Wallet(accountNo, CIF, "USD", balance, WalletStatus.ACTIVE, highContention);
    }

    // ------------------------------------------------------------------
    // createWallet
    // ------------------------------------------------------------------

    @Test
    void createWallet_savesNewWalletWithZeroBalance() {
        when(walletRepository.findByCifAndCurrency(CIF, "USD")).thenReturn(Optional.empty());
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));

        Wallet wallet = walletService.createWallet(CIF, "USD", false);

        assertThat(wallet.getCif()).isEqualTo(CIF);
        assertThat(wallet.getCurrency()).isEqualTo("USD");
        assertThat(wallet.getBalance()).isEqualByComparingTo("0.0000");
        assertThat(wallet.getStatus()).isEqualTo(WalletStatus.ACTIVE);
        assertThat(wallet.getAccountNo()).isEqualTo(ACC);
    }

    @Test
    void createWallet_existingWalletForCifAndCurrency_throwsDuplicate() {
        when(walletRepository.findByCifAndCurrency(CIF, "USD"))
                .thenReturn(Optional.of(activeWallet("011000000099", BigDecimal.ZERO, false)));

        assertThatThrownBy(() -> walletService.createWallet(CIF, "USD", false))
                .isInstanceOf(DuplicateWalletException.class);

        verify(walletRepository, never()).save(any());
    }

    @Test
    void createWallet_concurrentRaceHitsUniqueConstraint_throwsDuplicate() {
        when(walletRepository.findByCifAndCurrency(CIF, "USD")).thenReturn(Optional.empty());
        when(walletRepository.save(any(Wallet.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> walletService.createWallet(CIF, "USD", false))
                .isInstanceOf(DuplicateWalletException.class);
    }

    // ------------------------------------------------------------------
    // getBalance
    // ------------------------------------------------------------------

    @Test
    void getBalance_found_returnsWallet() {
        Wallet wallet = activeWallet(ACC, new BigDecimal("100.0000"), false);
        when(walletRepository.findById(ACC)).thenReturn(Optional.of(wallet));

        assertThat(walletService.getBalance(ACC)).isSameAs(wallet);
    }

    @Test
    void getBalance_notFound_throws() {
        when(walletRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> walletService.getBalance("missing"))
                .isInstanceOf(WalletNotFoundException.class);
    }

    // ------------------------------------------------------------------
    // debit / credit - business rules
    // ------------------------------------------------------------------

    @Test
    void debit_sufficientFunds_reducesBalance() {
        Wallet wallet = activeWallet(ACC, new BigDecimal("100.0000"), false);
        when(walletRepository.findById(ACC)).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));

        Wallet result = walletService.debit(ACC, new BigDecimal("40.00"), "0120260827000001");

        assertThat(result.getBalance()).isEqualByComparingTo("60.0000");
    }

    @Test
    void debit_insufficientFunds_throwsAndDoesNotSave() {
        Wallet wallet = activeWallet(ACC, new BigDecimal("10.0000"), false);
        when(walletRepository.findById(ACC)).thenReturn(Optional.of(wallet));

        assertThatThrownBy(() -> walletService.debit(ACC, new BigDecimal("50.00"), "0120260827000001"))
                .isInstanceOf(InsufficientFundsException.class);

        verify(walletRepository, never()).save(any());
    }

    @Test
    void debit_frozenWallet_throwsNotActive() {
        Wallet wallet = new Wallet(ACC, CIF, "USD", new BigDecimal("100.0000"), WalletStatus.FROZEN, false);
        when(walletRepository.findById(ACC)).thenReturn(Optional.of(wallet));

        assertThatThrownBy(() -> walletService.debit(ACC, new BigDecimal("10.00"), "0120260827000001"))
                .isInstanceOf(WalletNotActiveException.class);
    }

    @Test
    void credit_frozenWallet_stillAllowed() {
        Wallet wallet = new Wallet(ACC, CIF, "USD", new BigDecimal("100.0000"), WalletStatus.FROZEN, false);
        when(walletRepository.findById(ACC)).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));

        Wallet result = walletService.credit(ACC, new BigDecimal("25.00"), "0120260827000001");

        assertThat(result.getBalance()).isEqualByComparingTo("125.0000");
    }

    @Test
    void debit_success_publishesDebitedEvent() {
        Wallet wallet = activeWallet(ACC, new BigDecimal("100.0000"), false);
        when(walletRepository.findById(ACC)).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));

        walletService.debit(ACC, new BigDecimal("10.00"), "0120260827000001");

        verify(eventPublisher).publishDebited(any());
        verify(eventPublisher, never()).publishDebitFailed(any());
    }

    @Test
    void debit_failure_publishesDebitFailedEvent_notDebited() {
        Wallet wallet = activeWallet(ACC, new BigDecimal("10.0000"), false);
        when(walletRepository.findById(ACC)).thenReturn(Optional.of(wallet));

        assertThatThrownBy(() -> walletService.debit(ACC, new BigDecimal("50.00"), "0120260827000001"))
                .isInstanceOf(InsufficientFundsException.class);

        verify(eventPublisher).publishDebitFailed(any());
        verify(eventPublisher, never()).publishDebited(any());
    }

    @Test
    void credit_success_publishesCreditedEvent() {
        Wallet wallet = activeWallet(ACC, new BigDecimal("100.0000"), false);
        when(walletRepository.findById(ACC)).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));

        walletService.credit(ACC, new BigDecimal("25.00"), "0120260827000001");

        verify(eventPublisher).publishCredited(any());
    }

    @Test
    void credit_closedWallet_throwsNotActive() {
        Wallet wallet = new Wallet(ACC, CIF, "USD", new BigDecimal("100.0000"), WalletStatus.CLOSED, false);
        when(walletRepository.findById(ACC)).thenReturn(Optional.of(wallet));

        assertThatThrownBy(() -> walletService.credit(ACC, new BigDecimal("10.00"), "0120260827000001"))
                .isInstanceOf(WalletNotActiveException.class);
    }

    // ------------------------------------------------------------------
    // Concurrency-control dispatch (design doc §6.2.1)
    // ------------------------------------------------------------------

    @Test
    void debit_highContentionWallet_usesPessimisticLockPath() {
        Wallet wallet = activeWallet(ACC, new BigDecimal("100.0000"), true);
        when(walletRepository.findById(ACC)).thenReturn(Optional.of(wallet));
        when(walletRepository.findByIdForUpdate(ACC)).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));

        walletService.debit(ACC, new BigDecimal("10.00"), "0120260827000001");

        verify(walletRepository).findByIdForUpdate(ACC);
    }

    @Test
    void debit_lowContentionWallet_neverUsesPessimisticLock() {
        Wallet wallet = activeWallet(ACC, new BigDecimal("100.0000"), false);
        when(walletRepository.findById(ACC)).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));

        walletService.debit(ACC, new BigDecimal("10.00"), "0120260827000001");

        verify(walletRepository, never()).findByIdForUpdate(anyString());
    }

    @Test
    void debit_optimisticLockConflict_retriesThenSucceeds() {
        when(walletRepository.findById(ACC))
                .thenAnswer(inv -> Optional.of(activeWallet(ACC, new BigDecimal("100.0000"), false)));
        when(walletRepository.save(any(Wallet.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Wallet.class, ACC))
                .thenThrow(new ObjectOptimisticLockingFailureException(Wallet.class, ACC))
                .thenAnswer(inv -> inv.getArgument(0));

        Wallet result = walletService.debit(ACC, new BigDecimal("10.00"), "0120260827000001");

        assertThat(result.getBalance()).isEqualByComparingTo("90.0000");
        verify(walletRepository, times(3)).save(any(Wallet.class));
        assertThat(meterRegistry.counter("wallet.optimistic.lock.retries").count()).isEqualTo(2.0);
    }

    @Test
    void debit_optimisticLockConflict_exhaustsRetries_throwsWalletConflict() {
        Wallet wallet = activeWallet(ACC, new BigDecimal("100.0000"), false);
        when(walletRepository.findById(ACC)).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(Wallet.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Wallet.class, ACC));

        assertThatThrownBy(() -> walletService.debit(ACC, new BigDecimal("10.00"), "0120260827000001"))
                .isInstanceOf(WalletConflictException.class);

        verify(walletRepository, times(5)).save(any(Wallet.class));
    }

    // ------------------------------------------------------------------
    // Reservations
    // ------------------------------------------------------------------

    @Test
    void reserveFunds_sufficientBalance_createsHeldReservation() {
        Wallet wallet = activeWallet(ACC, new BigDecimal("100.0000"), false);
        when(walletRepository.findById(ACC)).thenReturn(Optional.of(wallet));
        when(reservationRepository.save(any(WalletReservation.class))).thenAnswer(inv -> inv.getArgument(0));

        WalletReservation reservation = walletService.reserveFunds(ACC, new BigDecimal("30.00"), "0120260827000009");

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.HELD);
        assertThat(reservation.getAmount()).isEqualByComparingTo("30.0000");
        assertThat(reservation.getAccountNo()).isEqualTo(ACC);
        assertThat(wallet.getBalance()).isEqualByComparingTo("100.0000");
    }

    @Test
    void reserveFunds_insufficientBalance_throws() {
        Wallet wallet = activeWallet(ACC, new BigDecimal("10.0000"), false);
        when(walletRepository.findById(ACC)).thenReturn(Optional.of(wallet));

        assertThatThrownBy(() -> walletService.reserveFunds(ACC, new BigDecimal("30.00"), "0120260827000009"))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void reserveFunds_highContentionWallet_locksBeforeChecking() {
        Wallet wallet = activeWallet(ACC, new BigDecimal("100.0000"), true);
        when(walletRepository.findById(ACC)).thenReturn(Optional.of(wallet));
        when(walletRepository.findByIdForUpdate(ACC)).thenReturn(Optional.of(wallet));
        when(reservationRepository.save(any(WalletReservation.class))).thenAnswer(inv -> inv.getArgument(0));

        walletService.reserveFunds(ACC, new BigDecimal("30.00"), "0120260827000009");

        verify(walletRepository).findByIdForUpdate(ACC);
    }

    @Test
    void captureReservation_heldReservation_debitsWalletAndMarksCaptured() {
        WalletReservation reservation = new WalletReservation(
                "r-1", ACC, "0120260827000009", new BigDecimal("30.0000"), ReservationStatus.HELD,
                Instant.now().plusSeconds(600));
        Wallet wallet = activeWallet(ACC, new BigDecimal("100.0000"), false);
        when(reservationRepository.findById("r-1")).thenReturn(Optional.of(reservation));
        when(walletRepository.findById(ACC)).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reservationRepository.save(any(WalletReservation.class))).thenAnswer(inv -> inv.getArgument(0));

        Wallet result = walletService.captureReservation("r-1");

        assertThat(result.getBalance()).isEqualByComparingTo("70.0000");
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CAPTURED);
    }

    @Test
    void captureReservation_notFound_throws() {
        when(reservationRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> walletService.captureReservation("missing"))
                .isInstanceOf(ReservationNotFoundException.class);
    }

    @Test
    void captureReservation_alreadyCaptured_throwsInvalidState() {
        WalletReservation reservation = new WalletReservation(
                "r-1", ACC, "0120260827000009", new BigDecimal("30.0000"), ReservationStatus.CAPTURED,
                Instant.now().plusSeconds(600));
        when(reservationRepository.findById("r-1")).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> walletService.captureReservation("r-1"))
                .isInstanceOf(InvalidReservationStateException.class);
    }

    @Test
    void releaseReservation_held_marksReleasedWithoutTouchingBalance() {
        WalletReservation reservation = new WalletReservation(
                "r-1", ACC, "0120260827000009", new BigDecimal("30.0000"), ReservationStatus.HELD,
                Instant.now().plusSeconds(600));
        Wallet wallet = activeWallet(ACC, new BigDecimal("100.0000"), false);
        when(reservationRepository.findById("r-1")).thenReturn(Optional.of(reservation));
        when(reservationRepository.save(any(WalletReservation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(walletRepository.findById(ACC)).thenReturn(Optional.of(wallet));

        Wallet result = walletService.releaseReservation("r-1");

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(result.getBalance()).isEqualByComparingTo("100.0000");
        verify(walletRepository, never()).save(any());
    }

    @Test
    void releaseReservation_alreadyReleased_throwsInvalidState() {
        WalletReservation reservation = new WalletReservation(
                "r-1", ACC, "0120260827000009", new BigDecimal("30.0000"), ReservationStatus.RELEASED,
                Instant.now().plusSeconds(600));
        when(reservationRepository.findById("r-1")).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> walletService.releaseReservation("r-1"))
                .isInstanceOf(InvalidReservationStateException.class);
    }
}
