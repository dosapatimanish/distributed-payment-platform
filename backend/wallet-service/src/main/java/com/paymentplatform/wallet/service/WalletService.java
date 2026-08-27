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
import com.paymentplatform.wallet.event.WalletCreditedEvent;
import com.paymentplatform.wallet.event.WalletDebitFailedEvent;
import com.paymentplatform.wallet.event.WalletDebitedEvent;
import com.paymentplatform.wallet.event.WalletEventPublisher;
import com.paymentplatform.wallet.repository.WalletReservationRepository;
import com.paymentplatform.wallet.repository.WalletRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * All wallet business logic, including the two concurrency-control strategies from the design
 * doc (§6.2.1): optimistic locking with retry (default) and pessimistic {@code SELECT ... FOR
 * UPDATE} for wallets flagged {@code highContention}.
 */
@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private static final int MAX_OPTIMISTIC_ATTEMPTS = 5;
    private static final long BASE_BACKOFF_MS = 20;
    private static final int MONEY_SCALE = 4;

    private final WalletRepository walletRepository;
    private final WalletReservationRepository reservationRepository;
    private final WalletEventPublisher eventPublisher;
    private final CurrencyService currencyService;
    private final AccountNumberGenerator accountNumberGenerator;
    private final SequenceIds sequenceIds;
    private final TransactionTemplate transactionTemplate;
    private final Duration reservationTtl;
    private final MeterRegistry meterRegistry;

    public WalletService(WalletRepository walletRepository,
                          WalletReservationRepository reservationRepository,
                          WalletEventPublisher eventPublisher,
                          CurrencyService currencyService,
                          AccountNumberGenerator accountNumberGenerator,
                          SequenceIds sequenceIds,
                          PlatformTransactionManager transactionManager,
                          @Value("${wallet.reservation.ttl-minutes}") long reservationTtlMinutes,
                          MeterRegistry meterRegistry) {
        this.walletRepository = walletRepository;
        this.reservationRepository = reservationRepository;
        this.eventPublisher = eventPublisher;
        this.currencyService = currencyService;
        this.accountNumberGenerator = accountNumberGenerator;
        this.sequenceIds = sequenceIds;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.meterRegistry = meterRegistry;
        this.transactionTemplate.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.reservationTtl = Duration.ofMinutes(reservationTtlMinutes);
    }

    // ------------------------------------------------------------------
    // Wallet lifecycle
    // ------------------------------------------------------------------

    /**
     * Creates an ACTIVE wallet for {@code (cif, currency)} with a freshly minted account number.
     * {@code @Transactional} so the currency check, duplicate check, account-number sequence
     * bump and insert are one unit - {@link AccountNumberGenerator}'s MERGE row lock is held to
     * this method's commit.
     */
    @Transactional
    public Wallet createWallet(String cif, String currency, boolean highContention) {
        Currency ccy = currencyService.requireActive(currency);
        walletRepository.findByCifAndCurrency(cif, currency).ifPresent(w -> {
            throw new DuplicateWalletException(cif, currency);
        });
        String accountNo = accountNumberGenerator.nextAccountNumber(ccy.getShortCode(), cif);
        Wallet wallet = new Wallet(
                accountNo, cif, currency,
                BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                WalletStatus.ACTIVE, highContention);
        try {
            return walletRepository.save(wallet);
        } catch (DataIntegrityViolationException ex) {
            // Two concurrent create-wallet calls for the same (cif, currency) could both pass the
            // check above and race to uk_wallet_cif_currency; a genuine account-number collision
            // (two CIFs sharing a 5-digit prefix) also lands here on uk on account_no.
            throw new DuplicateWalletException(cif, currency);
        }
    }

    public Wallet getBalance(String accountNo) {
        return walletRepository.findById(accountNo)
                .orElseThrow(() -> new WalletNotFoundException(accountNo));
    }

    public Wallet debit(String accountNo, BigDecimal amount, String transactionId) {
        log.debug("Debiting {} ({}) from account {}", amount, transactionId, accountNo);
        try {
            Wallet result = applyMutation(accountNo, wallet -> debitMutation(wallet, amount));
            eventPublisher.publishDebited(
                    new WalletDebitedEvent(accountNo, transactionId, amount, result.getBalance(), Instant.now()));
            return result;
        } catch (RuntimeException ex) {
            eventPublisher.publishDebitFailed(
                    new WalletDebitFailedEvent(accountNo, transactionId, amount, ex.getMessage(), Instant.now()));
            throw ex;
        }
    }

    public Wallet credit(String accountNo, BigDecimal amount, String transactionId) {
        log.debug("Crediting {} ({}) to account {}", amount, transactionId, accountNo);
        Wallet result = applyMutation(accountNo, wallet -> creditMutation(wallet, amount));
        eventPublisher.publishCredited(
                new WalletCreditedEvent(accountNo, transactionId, amount, result.getBalance(), Instant.now()));
        return result;
    }

    // ------------------------------------------------------------------
    // Reservations (holds)
    // ------------------------------------------------------------------

    public WalletReservation reserveFunds(String accountNo, BigDecimal amount, String transactionId) {
        Wallet probe = walletRepository.findById(accountNo)
                .orElseThrow(() -> new WalletNotFoundException(accountNo));

        if (probe.isHighContention()) {
            return transactionTemplate.execute(status -> {
                Wallet locked = walletRepository.findByIdForUpdate(accountNo)
                        .orElseThrow(() -> new WalletNotFoundException(accountNo));
                return createReservation(locked, amount, transactionId);
            });
        }
        return createReservation(probe, amount, transactionId);
    }

    public Wallet captureReservation(String reservationId) {
        WalletReservation reservation = requireHeldReservation(reservationId);
        Wallet debited = debit(reservation.getAccountNo(), reservation.getAmount(), reservation.getTransactionId());
        reservation.setStatus(ReservationStatus.CAPTURED);
        reservationRepository.save(reservation);
        return debited;
    }

    public Wallet releaseReservation(String reservationId) {
        WalletReservation reservation = requireHeldReservation(reservationId);
        reservation.setStatus(ReservationStatus.RELEASED);
        reservationRepository.save(reservation);
        return walletRepository.findById(reservation.getAccountNo())
                .orElseThrow(() -> new WalletNotFoundException(reservation.getAccountNo()));
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
                sequenceIds.next("wallet_reservation_seq", "RS"), wallet.getAccountNo(), transactionId,
                amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                ReservationStatus.HELD, Instant.now().plus(reservationTtl));
        return reservationRepository.save(reservation);
    }

    // ------------------------------------------------------------------
    // Concurrency-control dispatch (design doc §6.2.1)
    // ------------------------------------------------------------------

    private Wallet applyMutation(String accountNo, UnaryOperator<Wallet> mutation) {
        Wallet probe = walletRepository.findById(accountNo)
                .orElseThrow(() -> new WalletNotFoundException(accountNo));
        return probe.isHighContention()
                ? applyWithPessimisticLock(accountNo, mutation)
                : applyWithOptimisticRetry(accountNo, mutation);
    }

    private Wallet applyWithOptimisticRetry(String accountNo, UnaryOperator<Wallet> mutation) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transactionTemplate.execute(status -> {
                    Wallet wallet = walletRepository.findById(accountNo)
                            .orElseThrow(() -> new WalletNotFoundException(accountNo));
                    return walletRepository.save(mutation.apply(wallet));
                });
            } catch (ObjectOptimisticLockingFailureException ex) {
                meterRegistry.counter("wallet.optimistic.lock.retries").increment();
                if (attempt >= MAX_OPTIMISTIC_ATTEMPTS) {
                    throw new WalletConflictException(accountNo, attempt);
                }
                log.info("Optimistic lock conflict on account {} (attempt {}/{}), retrying",
                        accountNo, attempt, MAX_OPTIMISTIC_ATTEMPTS);
                sleepBackoff(BASE_BACKOFF_MS * attempt);
            }
        }
    }

    private Wallet applyWithPessimisticLock(String accountNo, UnaryOperator<Wallet> mutation) {
        log.debug("Acquiring pessimistic lock on account {}", accountNo);
        return transactionTemplate.execute(status -> {
            Wallet wallet = walletRepository.findByIdForUpdate(accountNo)
                    .orElseThrow(() -> new WalletNotFoundException(accountNo));
            return walletRepository.save(mutation.apply(wallet));
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
            throw new WalletNotActiveException(wallet.getAccountNo(), wallet.getStatus());
        }
    }

    private void requireNotClosed(Wallet wallet) {
        if (wallet.getStatus() == WalletStatus.CLOSED) {
            throw new WalletNotActiveException(wallet.getAccountNo(), wallet.getStatus());
        }
    }

    private void requireSufficientFunds(Wallet wallet, BigDecimal amount) {
        if (amount.compareTo(wallet.getBalance()) > 0) {
            throw new InsufficientFundsException(wallet.getAccountNo(), amount, wallet.getBalance());
        }
    }
}
