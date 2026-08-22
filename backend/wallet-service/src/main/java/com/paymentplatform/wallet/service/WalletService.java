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
import com.paymentplatform.wallet.repository.WalletReservationRepository;
import com.paymentplatform.wallet.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * All wallet business logic lives here, including the two concurrency-control strategies
 * described in the design doc (section 6.2.1):
 *
 * <ul>
 *   <li><b>Optimistic locking with retry</b> - the default path. Relies on {@code Wallet.version}
 *       (a JPA {@code @Version} column); a lost race throws
 *       {@link ObjectOptimisticLockingFailureException} at commit time, which we catch and retry
 *       a bounded number of times with a small backoff.</li>
 *   <li><b>Pessimistic locking</b> - for wallets flagged {@code highContention=true} (e.g. a
 *       platform fee pool hit thousands of times/sec). Uses {@code SELECT ... FOR UPDATE} via
 *       {@link WalletRepository#findByIdForUpdate}, so a request simply queues for the row lock
 *       instead of racing and retrying.</li>
 * </ul>
 */
@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private static final int MAX_OPTIMISTIC_ATTEMPTS = 5;
    private static final long BASE_BACKOFF_MS = 20;
    private static final int MONEY_SCALE = 4;

    private final WalletRepository walletRepository;
    private final WalletReservationRepository reservationRepository;
    private final TransactionTemplate transactionTemplate;
    private final Duration reservationTtl;

    public WalletService(WalletRepository walletRepository,
                          WalletReservationRepository reservationRepository,
                          PlatformTransactionManager transactionManager,
                          @Value("${wallet.reservation.ttl-minutes}") long reservationTtlMinutes) {
        this.walletRepository = walletRepository;
        this.reservationRepository = reservationRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // Force a brand-new transaction on every call, regardless of what the caller is doing -
        // this is what lets each optimistic-retry attempt start with a clean persistence context.
        this.transactionTemplate.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.reservationTtl = Duration.ofMinutes(reservationTtlMinutes);
    }

    // ------------------------------------------------------------------
    // Wallet lifecycle
    // ------------------------------------------------------------------

    public Wallet createWallet(String userId, String currency, boolean highContention) {
        walletRepository.findByUserIdAndCurrency(userId, currency).ifPresent(w -> {
            throw new DuplicateWalletException(userId, currency);
        });
        Wallet wallet = new Wallet(
                UUID.randomUUID().toString(), userId, currency,
                BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                WalletStatus.ACTIVE, highContention);
        try {
            return walletRepository.save(wallet);
        } catch (DataIntegrityViolationException ex) {
            // Defensive fallback: two concurrent create-wallet calls for the same (userId,
            // currency) could both pass the check above and race to the DB's unique constraint.
            throw new DuplicateWalletException(userId, currency);
        }
    }

    public Wallet getBalance(String walletId) {
        return walletRepository.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));
    }

    public Wallet debit(String walletId, BigDecimal amount, String transactionId) {
        log.debug("Debiting {} {} from wallet {}", amount, transactionId, walletId);
        return applyMutation(walletId, wallet -> debitMutation(wallet, amount));
    }

    public Wallet credit(String walletId, BigDecimal amount, String transactionId) {
        log.debug("Crediting {} {} to wallet {}", amount, transactionId, walletId);
        return applyMutation(walletId, wallet -> creditMutation(wallet, amount));
    }

    // ------------------------------------------------------------------
    // Reservations (holds)
    // ------------------------------------------------------------------

    public WalletReservation reserveFunds(String walletId, BigDecimal amount, String transactionId) {
        Wallet probe = walletRepository.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));

        if (probe.isHighContention()) {
            // Serialize the "is there enough balance" check + insert under the row lock, so two
            // concurrent holds against a hot wallet can't both succeed against balance that only
            // covers one of them.
            return transactionTemplate.execute(status -> {
                Wallet locked = walletRepository.findByIdForUpdate(walletId)
                        .orElseThrow(() -> new WalletNotFoundException(walletId));
                return createReservation(locked, amount, transactionId);
            });
        }
        // Low-contention wallets: a plain read-then-insert. Note this does not fully close the
        // race between two concurrent reserves on the same wallet (balance itself is never
        // decremented at hold time - see the class javadoc), which is an accepted simplification
        // for this step; closing it fully would mean tracking "held" totals separately.
        return createReservation(probe, amount, transactionId);
    }

    public Wallet captureReservation(String reservationId) {
        WalletReservation reservation = requireHeldReservation(reservationId);
        Wallet debited = debit(reservation.getWalletId(), reservation.getAmount(), reservation.getTransactionId());
        reservation.setStatus(ReservationStatus.CAPTURED);
        reservationRepository.save(reservation);
        return debited;
    }

    public Wallet releaseReservation(String reservationId) {
        WalletReservation reservation = requireHeldReservation(reservationId);
        reservation.setStatus(ReservationStatus.RELEASED);
        reservationRepository.save(reservation);
        // No balance mutation - the hold never touched wallet.balance in the first place.
        return walletRepository.findById(reservation.getWalletId())
                .orElseThrow(() -> new WalletNotFoundException(reservation.getWalletId()));
    }

    private WalletReservation requireHeldReservation(String reservationId) {
        WalletReservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        if (reservation.getStatus() != ReservationStatus.HELD) {
            throw new InvalidReservationStateException(reservationId, reservation.getStatus(), ReservationStatus.HELD);
        }
        return reservation;
    }

    private WalletReservation createReservation(Wallet wallet, BigDecimal amount, String transactionId) {
        requireActive(wallet);
        requireSufficientFunds(wallet, amount);
        WalletReservation reservation = new WalletReservation(
                UUID.randomUUID().toString(), wallet.getWalletId(), transactionId,
                amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                ReservationStatus.HELD, Instant.now().plus(reservationTtl));
        return reservationRepository.save(reservation);
    }

    // ------------------------------------------------------------------
    // Concurrency-control dispatch (design doc 6.2.1)
    // ------------------------------------------------------------------

    private Wallet applyMutation(String walletId, UnaryOperator<Wallet> mutation) {
        Wallet probe = walletRepository.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));
        return probe.isHighContention()
                ? applyWithPessimisticLock(walletId, mutation)
                : applyWithOptimisticRetry(walletId, mutation);
    }

    /**
     * Retries a mutation up to {@link #MAX_OPTIMISTIC_ATTEMPTS} times on optimistic-lock
     * conflicts, with a linear backoff (20ms, 40ms, 60ms, 80ms). Each attempt runs in its own,
     * brand-new transaction via {@link #transactionTemplate} - deliberately NOT a
     * {@code @Transactional} method on {@code this}, because a self-invoked {@code @Transactional}
     * call bypasses Spring's proxy and silently would not open a new transaction at all, which
     * would let a failed attempt's stale persistence context poison the next retry.
     */
    private Wallet applyWithOptimisticRetry(String walletId, UnaryOperator<Wallet> mutation) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transactionTemplate.execute(status -> {
                    Wallet wallet = walletRepository.findById(walletId)
                            .orElseThrow(() -> new WalletNotFoundException(walletId));
                    return walletRepository.save(mutation.apply(wallet));
                });
            } catch (ObjectOptimisticLockingFailureException ex) {
                if (attempt >= MAX_OPTIMISTIC_ATTEMPTS) {
                    throw new WalletConflictException(walletId, attempt);
                }
                log.info("Optimistic lock conflict on wallet {} (attempt {}/{}), retrying",
                        walletId, attempt, MAX_OPTIMISTIC_ATTEMPTS);
                sleepBackoff(BASE_BACKOFF_MS * attempt);
            }
        }
    }

    private Wallet applyWithPessimisticLock(String walletId, UnaryOperator<Wallet> mutation) {
        log.debug("Acquiring pessimistic lock on wallet {}", walletId);
        // Same reasoning as applyWithOptimisticRetry: this must go through transactionTemplate,
        // not a plain @Transactional method on this class. applyMutation() calls this method via
        // a direct "this." call, not through the Spring proxy, so an @Transactional annotation
        // here would silently never take effect (proven the hard way: the first version of this
        // method used @Transactional and failed every call with
        // "jakarta.persistence.TransactionRequiredException: No active transaction", because
        // findByIdForUpdate's SELECT ... FOR UPDATE ran with no transaction open at all).
        return transactionTemplate.execute(status -> {
            Wallet wallet = walletRepository.findByIdForUpdate(walletId)
                    .orElseThrow(() -> new WalletNotFoundException(walletId));
            return walletRepository.save(mutation.apply(wallet));
            // If the lock can't be acquired within the configured timeout, Spring translates the
            // driver's error into a PessimisticLockingFailureException, which
            // GlobalExceptionHandler maps to 409 - a stalled caller fails fast instead of
            // queuing forever.
        });
    }

    private void sleepBackoff(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying a wallet mutation", e);
        }
    }

    // ------------------------------------------------------------------
    // Business-rule mutation functions
    // ------------------------------------------------------------------

    private Wallet debitMutation(Wallet wallet, BigDecimal amount) {
        requireActive(wallet);
        requireSufficientFunds(wallet, amount);
        wallet.setBalance(wallet.getBalance().subtract(amount).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        return wallet;
    }

    private Wallet creditMutation(Wallet wallet, BigDecimal amount) {
        requireNotClosed(wallet);
        wallet.setBalance(wallet.getBalance().add(amount).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        return wallet;
    }

    private void requireActive(Wallet wallet) {
        if (wallet.getStatus() != WalletStatus.ACTIVE) {
            throw new WalletNotActiveException(wallet.getWalletId(), wallet.getStatus());
        }
    }

    private void requireNotClosed(Wallet wallet) {
        if (wallet.getStatus() == WalletStatus.CLOSED) {
            throw new WalletNotActiveException(wallet.getWalletId(), wallet.getStatus());
        }
    }

    private void requireSufficientFunds(Wallet wallet, BigDecimal amount) {
        if (amount.compareTo(wallet.getBalance()) > 0) {
            throw new InsufficientFundsException(wallet.getWalletId(), amount, wallet.getBalance());
        }
    }
}
