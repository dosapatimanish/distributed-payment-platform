package com.paymentplatform.wallet.service;

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
 * Unit tests for WalletService - no Spring context, repositories mocked with Mockito.
 *
 * WalletService builds its own {@code TransactionTemplate} from the injected
 * {@link PlatformTransactionManager} (see its constructor javadoc: deliberately not
 * {@code @Transactional}, to survive self-invocation). To exercise that code path here without
 * a real database, {@code transactionManager} is mocked so {@code getTransaction(...)} hands
 * back a stub {@link TransactionStatus} - that's enough for TransactionTemplate.execute() to
 * run the real callback synchronously, which is all these tests need.
 */
@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    private static final long RESERVATION_TTL_MINUTES = 15;

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private WalletReservationRepository reservationRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private WalletEventPublisher eventPublisher;

    private WalletService walletService;

    @BeforeEach
    void setUp() {
        // lenient: only the tests that actually reach applyMutation/reserveFunds' pessimistic
        // path exercise the TransactionTemplate - e.g. createWallet, getBalance, and most
        // error-path tests never touch it, and Mockito's strict stubbing would otherwise fail
        // this stub as "unnecessary" for those.
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(mock(TransactionStatus.class));
        walletService = new WalletService(
                walletRepository, reservationRepository, eventPublisher, transactionManager, RESERVATION_TTL_MINUTES);
    }

    private Wallet activeWallet(String walletId, BigDecimal balance, boolean highContention) {
        return new Wallet(walletId, "user-1", "USD", balance, WalletStatus.ACTIVE, highContention);
    }

    // ------------------------------------------------------------------
    // createWallet
    // ------------------------------------------------------------------

    @Test
    void createWallet_savesNewWalletWithZeroBalance() {
        when(walletRepository.findByUserIdAndCurrency("user-1", "USD")).thenReturn(Optional.empty());
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));

        Wallet wallet = walletService.createWallet("user-1", "USD", false);

        assertThat(wallet.getUserId()).isEqualTo("user-1");
        assertThat(wallet.getCurrency()).isEqualTo("USD");
        assertThat(wallet.getBalance()).isEqualByComparingTo("0.0000");
        assertThat(wallet.getStatus()).isEqualTo(WalletStatus.ACTIVE);
        assertThat(wallet.getWalletId()).isNotBlank();
    }

    @Test
    void createWallet_existingWalletForUserAndCurrency_throwsDuplicate() {
        when(walletRepository.findByUserIdAndCurrency("user-1", "USD"))
                .thenReturn(Optional.of(activeWallet("existing", BigDecimal.ZERO, false)));

        assertThatThrownBy(() -> walletService.createWallet("user-1", "USD", false))
                .isInstanceOf(DuplicateWalletException.class);

        verify(walletRepository, never()).save(any());
    }

    @Test
    void createWallet_concurrentRaceHitsUniqueConstraint_throwsDuplicate() {
        // Pre-check passes (no existing wallet seen), but the DB unique constraint catches a
        // concurrent insert for the same (userId, currency) - see WalletService javadoc.
        when(walletRepository.findByUserIdAndCurrency("user-1", "USD")).thenReturn(Optional.empty());
        when(walletRepository.save(any(Wallet.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> walletService.createWallet("user-1", "USD", false))
                .isInstanceOf(DuplicateWalletException.class);
    }

    // ------------------------------------------------------------------
    // getBalance
    // ------------------------------------------------------------------

    @Test
    void getBalance_found_returnsWallet() {
        Wallet wallet = activeWallet("w-1", new BigDecimal("100.0000"), false);
        when(walletRepository.findById("w-1")).thenReturn(Optional.of(wallet));

        assertThat(walletService.getBalance("w-1")).isSameAs(wallet);
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
        Wallet wallet = activeWallet("w-1", new BigDecimal("100.0000"), false);
        when(walletRepository.findById("w-1")).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));

        Wallet result = walletService.debit("w-1", new BigDecimal("40.00"), "txn-1");

        assertThat(result.getBalance()).isEqualByComparingTo("60.0000");
    }

    @Test
    void debit_insufficientFunds_throwsAndDoesNotSave() {
        Wallet wallet = activeWallet("w-1", new BigDecimal("10.0000"), false);
        when(walletRepository.findById("w-1")).thenReturn(Optional.of(wallet));

        assertThatThrownBy(() -> walletService.debit("w-1", new BigDecimal("50.00"), "txn-1"))
                .isInstanceOf(InsufficientFundsException.class);

        verify(walletRepository, never()).save(any());
    }

    @Test
    void debit_frozenWallet_throwsNotActive() {
        Wallet wallet = new Wallet("w-1", "user-1", "USD", new BigDecimal("100.0000"), WalletStatus.FROZEN, false);
        when(walletRepository.findById("w-1")).thenReturn(Optional.of(wallet));

        assertThatThrownBy(() -> walletService.debit("w-1", new BigDecimal("10.00"), "txn-1"))
                .isInstanceOf(WalletNotActiveException.class);
    }

    @Test
    void credit_frozenWallet_stillAllowed() {
        // WalletStatus javadoc: FROZEN blocks debits but still allows credits.
        Wallet wallet = new Wallet("w-1", "user-1", "USD", new BigDecimal("100.0000"), WalletStatus.FROZEN, false);
        when(walletRepository.findById("w-1")).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));

        Wallet result = walletService.credit("w-1", new BigDecimal("25.00"), "txn-1");

        assertThat(result.getBalance()).isEqualByComparingTo("125.0000");
    }

    @Test
    void debit_success_publishesDebitedEvent() {
        Wallet wallet = activeWallet("w-1", new BigDecimal("100.0000"), false);
        when(walletRepository.findById("w-1")).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));

        walletService.debit("w-1", new BigDecimal("10.00"), "txn-1");

        verify(eventPublisher).publishDebited(any());
        verify(eventPublisher, never()).publishDebitFailed(any());
    }

    @Test
    void debit_failure_publishesDebitFailedEvent_notDebited() {
        Wallet wallet = activeWallet("w-1", new BigDecimal("10.0000"), false);
        when(walletRepository.findById("w-1")).thenReturn(Optional.of(wallet));

        assertThatThrownBy(() -> walletService.debit("w-1", new BigDecimal("50.00"), "txn-1"))
                .isInstanceOf(InsufficientFundsException.class);

        verify(eventPublisher).publishDebitFailed(any());
        verify(eventPublisher, never()).publishDebited(any());
    }

    @Test
    void credit_success_publishesCreditedEvent() {
        Wallet wallet = activeWallet("w-1", new BigDecimal("100.0000"), false);
        when(walletRepository.findById("w-1")).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));

        walletService.credit("w-1", new BigDecimal("25.00"), "txn-1");

        verify(eventPublisher).publishCredited(any());
    }

    @Test
    void credit_closedWallet_throwsNotActive() {
        Wallet wallet = new Wallet("w-1", "user-1", "USD", new BigDecimal("100.0000"), WalletStatus.CLOSED, false);
        when(walletRepository.findById("w-1")).thenReturn(Optional.of(wallet));

        assertThatThrownBy(() -> walletService.credit("w-1", new BigDecimal("10.00"), "txn-1"))
                .isInstanceOf(WalletNotActiveException.class);
    }

    // ------------------------------------------------------------------
    // Concurrency-control dispatch (design doc 6.2.1)
    // ------------------------------------------------------------------

    @Test
    void debit_highContentionWallet_usesPessimisticLockPath() {
        Wallet wallet = activeWallet("w-1", new BigDecimal("100.0000"), true);
        when(walletRepository.findById("w-1")).thenReturn(Optional.of(wallet));
        when(walletRepository.findByIdForUpdate("w-1")).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));

        walletService.debit("w-1", new BigDecimal("10.00"), "txn-1");

        verify(walletRepository).findByIdForUpdate("w-1");
    }

    @Test
    void debit_lowContentionWallet_neverUsesPessimisticLock() {
        Wallet wallet = activeWallet("w-1", new BigDecimal("100.0000"), false);
        when(walletRepository.findById("w-1")).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));

        walletService.debit("w-1", new BigDecimal("10.00"), "txn-1");

        verify(walletRepository, never()).findByIdForUpdate(anyString());
    }

    @Test
    void debit_optimisticLockConflict_retriesThenSucceeds() {
        // Each attempt re-reads a fresh copy at balance 100 - a failed save() rolls back the
        // transaction, so the *next* attempt's findById sees the same committed balance again,
        // not whatever debitMutation mutated the in-memory entity to before the failed commit.
        when(walletRepository.findById("w-1"))
                .thenAnswer(inv -> Optional.of(activeWallet("w-1", new BigDecimal("100.0000"), false)));
        // First two attempts lose the optimistic-lock race, third one wins.
        when(walletRepository.save(any(Wallet.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Wallet.class, "w-1"))
                .thenThrow(new ObjectOptimisticLockingFailureException(Wallet.class, "w-1"))
                .thenAnswer(inv -> inv.getArgument(0));

        Wallet result = walletService.debit("w-1", new BigDecimal("10.00"), "txn-1");

        assertThat(result.getBalance()).isEqualByComparingTo("90.0000");
        verify(walletRepository, times(3)).save(any(Wallet.class));
    }

    @Test
    void debit_optimisticLockConflict_exhaustsRetries_throwsWalletConflict() {
        Wallet wallet = activeWallet("w-1", new BigDecimal("100.0000"), false);
        when(walletRepository.findById("w-1")).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(Wallet.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Wallet.class, "w-1"));

        assertThatThrownBy(() -> walletService.debit("w-1", new BigDecimal("10.00"), "txn-1"))
                .isInstanceOf(WalletConflictException.class);

        // MAX_OPTIMISTIC_ATTEMPTS = 5 in WalletService.
        verify(walletRepository, times(5)).save(any(Wallet.class));
    }

    // ------------------------------------------------------------------
    // Reservations
    // ------------------------------------------------------------------

    @Test
    void reserveFunds_sufficientBalance_createsHeldReservation() {
        Wallet wallet = activeWallet("w-1", new BigDecimal("100.0000"), false);
        when(walletRepository.findById("w-1")).thenReturn(Optional.of(wallet));
        when(reservationRepository.save(any(WalletReservation.class))).thenAnswer(inv -> inv.getArgument(0));

        WalletReservation reservation = walletService.reserveFunds("w-1", new BigDecimal("30.00"), "txn-r1");

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.HELD);
        assertThat(reservation.getAmount()).isEqualByComparingTo("30.0000");
        assertThat(reservation.getWalletId()).isEqualTo("w-1");
        // Reserving does not itself touch the wallet's balance (see WalletReservation javadoc).
        assertThat(wallet.getBalance()).isEqualByComparingTo("100.0000");
    }

    @Test
    void reserveFunds_insufficientBalance_throws() {
        Wallet wallet = activeWallet("w-1", new BigDecimal("10.0000"), false);
        when(walletRepository.findById("w-1")).thenReturn(Optional.of(wallet));

        assertThatThrownBy(() -> walletService.reserveFunds("w-1", new BigDecimal("30.00"), "txn-r1"))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void reserveFunds_highContentionWallet_locksBeforeChecking() {
        Wallet wallet = activeWallet("w-1", new BigDecimal("100.0000"), true);
        when(walletRepository.findById("w-1")).thenReturn(Optional.of(wallet));
        when(walletRepository.findByIdForUpdate("w-1")).thenReturn(Optional.of(wallet));
        when(reservationRepository.save(any(WalletReservation.class))).thenAnswer(inv -> inv.getArgument(0));

        walletService.reserveFunds("w-1", new BigDecimal("30.00"), "txn-r1");

        verify(walletRepository).findByIdForUpdate("w-1");
    }

    @Test
    void captureReservation_heldReservation_debitsWalletAndMarksCaptured() {
        WalletReservation reservation = new WalletReservation(
                "r-1", "w-1", "txn-r1", new BigDecimal("30.0000"), ReservationStatus.HELD,
                Instant.now().plusSeconds(600));
        Wallet wallet = activeWallet("w-1", new BigDecimal("100.0000"), false);
        when(reservationRepository.findById("r-1")).thenReturn(Optional.of(reservation));
        when(walletRepository.findById("w-1")).thenReturn(Optional.of(wallet));
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
                "r-1", "w-1", "txn-r1", new BigDecimal("30.0000"), ReservationStatus.CAPTURED,
                Instant.now().plusSeconds(600));
        when(reservationRepository.findById("r-1")).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> walletService.captureReservation("r-1"))
                .isInstanceOf(InvalidReservationStateException.class);
    }

    @Test
    void releaseReservation_held_marksReleasedWithoutTouchingBalance() {
        WalletReservation reservation = new WalletReservation(
                "r-1", "w-1", "txn-r1", new BigDecimal("30.0000"), ReservationStatus.HELD,
                Instant.now().plusSeconds(600));
        Wallet wallet = activeWallet("w-1", new BigDecimal("100.0000"), false);
        when(reservationRepository.findById("r-1")).thenReturn(Optional.of(reservation));
        when(reservationRepository.save(any(WalletReservation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(walletRepository.findById("w-1")).thenReturn(Optional.of(wallet));

        Wallet result = walletService.releaseReservation("r-1");

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(result.getBalance()).isEqualByComparingTo("100.0000");
        verify(walletRepository, never()).save(any());
    }

    @Test
    void releaseReservation_alreadyReleased_throwsInvalidState() {
        WalletReservation reservation = new WalletReservation(
                "r-1", "w-1", "txn-r1", new BigDecimal("30.0000"), ReservationStatus.RELEASED,
                Instant.now().plusSeconds(600));
        when(reservationRepository.findById("r-1")).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> walletService.releaseReservation("r-1"))
                .isInstanceOf(InvalidReservationStateException.class);
    }
}
