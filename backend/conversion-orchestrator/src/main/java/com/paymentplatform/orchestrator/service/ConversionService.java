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
import com.paymentplatform.orchestrator.domain.SagaStepLog;
import com.paymentplatform.orchestrator.domain.StepStatus;
import com.paymentplatform.orchestrator.exception.ConversionNotFoundException;
import com.paymentplatform.orchestrator.repository.ConversionTransactionRepository;
import com.paymentplatform.orchestrator.repository.SagaStepLogRepository;
import com.paymentplatform.orchestrator.saga.SagaStateMachine;
import com.paymentplatform.orchestrator.web.ConversionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Drives the wallet-to-wallet conversion saga, optionally followed by a merchant charge (design
 * doc §5.3, reduced scope - see {@link SagaState}'s javadoc for exactly what changed and why).
 * Each step is a synchronous REST call to wallet-service, fx-rate-service, or
 * merchant-payment-service ("Synchronous REST calls" scope decision for this pass - see
 * implementation notes for the deferred async-Kafka-driven alternative), and every state
 * transition + step outcome is persisted immediately after that step resolves - not batched, not
 * deferred - so {@code conversion_transaction}/{@code saga_step_log} always reflect exactly how
 * far the saga actually got.
 *
 * <b>Deliberate gap</b>: no crash-recovery/resume. If this process dies mid-saga, the persisted
 * state accurately records where it stopped, but nothing automatically picks it back up or
 * retries the next step on restart - that requires the async-Kafka-driven architecture (an
 * {@code @KafkaListener} re-entering the flow) this pass deliberately deferred.
 *
 * <p>Recording to ledger-service (design doc §5.3 steps 10a/11b) is wired in the same
 * best-effort way as {@code consumeLock} below: attempted once the saga's own wallet/FX/payment
 * outcome is already final, failures are logged (not thrown), and the saga still reaches
 * {@code COMPLETED}/{@code COMPENSATED} either way - a missing ledger row is an audit-trail gap
 * to notice and fix, not a reason to leave real, already-moved money in a stuck saga state. No
 * new {@link SagaState} values were added for this (see its javadoc) - same reasoning as
 * {@code consumeLock} having none either.
 */
@Service
public class ConversionService {

    private static final Logger log = LoggerFactory.getLogger(ConversionService.class);
    private static final int MONEY_SCALE = 4;

    private final ConversionTransactionRepository transactionRepository;
    private final SagaStepLogRepository stepLogRepository;
    private final WalletServiceClient walletClient;
    private final FxRateServiceClient fxRateClient;
    private final MerchantPaymentServiceClient merchantPaymentClient;
    private final LedgerServiceClient ledgerClient;
    private final String clearingAccountId;

    public ConversionService(ConversionTransactionRepository transactionRepository,
                              SagaStepLogRepository stepLogRepository,
                              WalletServiceClient walletClient,
                              FxRateServiceClient fxRateClient,
                              MerchantPaymentServiceClient merchantPaymentClient,
                              LedgerServiceClient ledgerClient,
                              @Value("${orchestrator.ledger.clearing-account-id}") String clearingAccountId) {
        this.transactionRepository = transactionRepository;
        this.stepLogRepository = stepLogRepository;
        this.walletClient = walletClient;
        this.fxRateClient = fxRateClient;
        this.merchantPaymentClient = merchantPaymentClient;
        this.ledgerClient = ledgerClient;
        this.clearingAccountId = clearingAccountId;
    }

    public ConversionTransaction getConversion(String transactionId) {
        return transactionRepository.findById(transactionId)
                .orElseThrow(() -> new ConversionNotFoundException(transactionId));
    }

    public ConversionTransaction startConversion(String idempotencyKey, ConversionRequest request) {
        ConversionTransaction txn = new ConversionTransaction(
                UUID.randomUUID().toString(), request.userId(), request.sourceWalletId(), request.destWalletId(),
                request.sourceCurrency(), request.destCurrency(), request.sourceAmount(), idempotencyKey);
        // Use save()'s return value, not the object just passed in - see ConversionTransaction's
        // Persistable javadoc for why the two aren't reliably the same instance.
        txn = transactionRepository.save(txn);
        return runSaga(txn, request, idempotencyKey);
    }

    private ConversionTransaction runSaga(ConversionTransaction txn, ConversionRequest request, String idempotencyKey) {
        RateLockResponse lock;
        try {
            lock = fxRateClient.lockRate(txn.getSourceCurrency(), txn.getDestCurrency(), txn.getSourceAmount(),
                    txn.getTransactionId(), idempotencyKey + "-lock");
        } catch (RestClientException ex) {
            logStep(txn, "RATE_LOCK", StepStatus.FAILED, describe(ex));
            return transition(txn, SagaState.FAILED);
        }
        txn.setLockedRate(lock.lockedRate());
        txn.setFxLockId(lock.lockId());
        txn.setDestAmount(txn.getSourceAmount().multiply(lock.lockedRate()).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        logStep(txn, "RATE_LOCK", StepStatus.SUCCESS, "lockId=%s, rate=%s".formatted(lock.lockId(), lock.lockedRate()));
        txn = transition(txn, SagaState.RATE_LOCKED);

        WalletResponse debitResult;
        try {
            debitResult = walletClient.debit(txn.getSourceWalletId(), txn.getSourceAmount(), txn.getTransactionId(), idempotencyKey + "-debit");
        } catch (RestClientException ex) {
            logStep(txn, "DEBIT", StepStatus.FAILED, describe(ex));
            txn = transition(txn, SagaState.DEBIT_FAILED);
            return compensate(txn, idempotencyKey, false, false);
        }
        logStep(txn, "DEBIT", StepStatus.SUCCESS, "amount=" + txn.getSourceAmount());
        txn = transition(txn, SagaState.SOURCE_DEBITED);

        WalletResponse creditResult;
        try {
            creditResult = walletClient.credit(txn.getDestWalletId(), txn.getDestAmount(), txn.getTransactionId(), idempotencyKey + "-credit");
        } catch (RestClientException ex) {
            logStep(txn, "CREDIT", StepStatus.FAILED, describe(ex));
            txn = transition(txn, SagaState.CREDIT_FAILED);
            return compensate(txn, idempotencyKey, false, true);
        }
        logStep(txn, "CREDIT", StepStatus.SUCCESS, "amount=" + txn.getDestAmount());
        txn = transition(txn, SagaState.DEST_CREDITED);

        if (request.hasMerchantId()) {
            txn = chargeMerchant(txn, request.merchantId(), idempotencyKey);
            if (txn.getSagaState() != SagaState.PAYMENT_COMPLETED) {
                // The charge was declined (or the call itself failed) and compensate() already
                // ran - whatever state it left the saga in (COMPENSATED, or stuck partway
                // through if a reversal step itself failed), that's the final answer here. Do
                // NOT fall through to consuming the lock or SagaState.COMPLETED below.
                return txn;
            }
        }

        // Records the conversion's own double-entry effect (design doc §5.3 step 10a) - the
        // balances used here are each wallet's balance immediately after its own debit/credit
        // call above, not affected by a later merchant-charge "spend" debit (see chargeMerchant's
        // javadoc) - that subsequent movement isn't captured in the ledger yet, a deliberately
        // deferred follow-up (see implementation notes).
        recordLedgerEntries(txn, idempotencyKey, debitResult.balance(), creditResult.balance());

        // Only consume the lock once the saga is definitively not going to be compensated -
        // consuming it any earlier (e.g. right after DEST_CREDITED, before knowing whether a
        // merchant charge afterward would succeed) was a real bug caught in manual testing: a
        // declined charge needs to release the lock during compensation, but fx-rate-service
        // correctly refuses to release an already-CONSUMED lock ("can't un-consume" - see its
        // own docs), so compensation got stuck one step short of COMPENSATED even though both
        // wallet reversals had already succeeded correctly. Consuming here, after every step
        // that could still trigger compensation has already succeeded, closes that gap. Still
        // best-effort itself: whether *this* call succeeds or fails does not change the saga's
        // outcome - the money already moved correctly at the rate captured in txn.lockedRate.
        try {
            fxRateClient.consumeLock(lock.lockId(), idempotencyKey + "-consume");
            logStep(txn, "CONSUME_LOCK", StepStatus.SUCCESS, "lockId=" + lock.lockId());
        } catch (RestClientException ex) {
            logStep(txn, "CONSUME_LOCK", StepStatus.FAILED, describe(ex));
            log.warn("Failed to mark rate lock {} consumed for transaction {} (saga still completes): {}",
                    lock.lockId(), txn.getTransactionId(), ex.getMessage());
        }

        return transition(txn, SagaState.COMPLETED);
    }

    /**
     * Charges the merchant for the just-converted amount, then actually spends the credited
     * funds to pay for it - debits the destination wallet by the same amount it was just
     * credited, so the money passes through to the merchant rather than sitting in the
     * customer's wallet. Net effect on that wallet from this whole saga is zero once a payment
     * is involved: +destAmount (the conversion credit) then -destAmount (spent on the charge).
     *
     * Unlike wallet-service/fx-rate-service, merchant-payment-service's {@code pay} always
     * returns 2xx regardless of outcome (see {@link PaymentResponse}'s javadoc) - a decline is
     * read from the response body, not caught as an exception, and triggers full compensation
     * (both the credit and the debit reversed) since nothing was ever actually spent.
     */
    private ConversionTransaction chargeMerchant(ConversionTransaction txn, String merchantId, String idempotencyKey) {
        PaymentResponse payment;
        try {
            payment = merchantPaymentClient.pay(txn.getTransactionId(), merchantId, txn.getDestAmount(),
                    txn.getDestCurrency(), idempotencyKey + "-pay");
        } catch (RestClientException ex) {
            logStep(txn, "PAYMENT", StepStatus.FAILED, describe(ex));
            txn = transition(txn, SagaState.PAYMENT_FAILED);
            return compensate(txn, idempotencyKey, true, true);
        }
        if (!payment.isCompleted()) {
            logStep(txn, "PAYMENT", StepStatus.FAILED, "paymentId=%s, status=%s".formatted(payment.paymentId(), payment.status()));
            txn = transition(txn, SagaState.PAYMENT_FAILED);
            return compensate(txn, idempotencyKey, true, true);
        }
        logStep(txn, "PAYMENT", StepStatus.SUCCESS, "paymentId=" + payment.paymentId());

        try {
            walletClient.debit(txn.getDestWalletId(), txn.getDestAmount(), txn.getTransactionId(), idempotencyKey + "-spend");
        } catch (RestClientException ex) {
            // The acquirer has already been charged for real at this point - refund it before
            // falling back to the normal compensation path, so a saga we're about to unwind
            // never leaves a real external charge standing.
            logStep(txn, "DEBIT_FOR_PAYMENT", StepStatus.FAILED, describe(ex));
            log.error("Charged the merchant but could not debit the destination wallet for transaction {} - refunding the charge: {}",
                    txn.getTransactionId(), ex.getMessage());
            try {
                merchantPaymentClient.refund(payment.paymentId());
                logStep(txn, "REFUND_PAYMENT", StepStatus.COMPENSATED, "paymentId=" + payment.paymentId());
            } catch (RestClientException refundEx) {
                logStep(txn, "REFUND_PAYMENT", StepStatus.FAILED, describe(refundEx));
                log.error("COMPENSATION FAILED for transaction {}: could not refund payment {} - manual intervention needed: {}",
                        txn.getTransactionId(), payment.paymentId(), refundEx.getMessage());
            }
            txn = transition(txn, SagaState.PAYMENT_FAILED);
            return compensate(txn, idempotencyKey, true, true);
        }
        logStep(txn, "DEBIT_FOR_PAYMENT", StepStatus.SUCCESS, "amount=" + txn.getDestAmount());
        return transition(txn, SagaState.PAYMENT_COMPLETED);
    }

    /**
     * @param reverseCredit reverse the destination-wallet credit first (only relevant once a payment attempt has run)
     * @param reverseDebit  reverse the source-wallet debit (irrelevant only when the debit itself never succeeded)
     */
    private ConversionTransaction compensate(ConversionTransaction txn, String idempotencyKey,
                                              boolean reverseCredit, boolean reverseDebit) {
        txn = transition(txn, SagaState.COMPENSATING);
        BigDecimal destBalanceAfterReversal = null;
        if (reverseCredit) {
            try {
                WalletResponse result = walletClient.debit(txn.getDestWalletId(), txn.getDestAmount(), txn.getTransactionId(),
                        idempotencyKey + "-compensate-credit");
                destBalanceAfterReversal = result.balance();
                logStep(txn, "COMPENSATE_CREDIT", StepStatus.COMPENSATED, "amount=" + txn.getDestAmount());
                txn = transition(txn, SagaState.DEST_DEBITED_BACK);
            } catch (RestClientException ex) {
                logStep(txn, "COMPENSATE_CREDIT", StepStatus.FAILED, describe(ex));
                log.error("COMPENSATION FAILED for transaction {}: could not reverse the destination credit - manual intervention needed: {}",
                        txn.getTransactionId(), ex.getMessage());
                return txn; // stuck at COMPENSATING, not silently marked COMPENSATED when it isn't
            }
        }
        BigDecimal sourceBalanceAfterReversal = null;
        if (reverseDebit) {
            try {
                WalletResponse result = walletClient.credit(txn.getSourceWalletId(), txn.getSourceAmount(), txn.getTransactionId(),
                        idempotencyKey + "-compensate-debit");
                sourceBalanceAfterReversal = result.balance();
                logStep(txn, "COMPENSATE_DEBIT", StepStatus.COMPENSATED, "amount=" + txn.getSourceAmount());
                txn = transition(txn, SagaState.SOURCE_CREDITED_BACK);
            } catch (RestClientException ex) {
                // Deliberate gap: no automatic retry of a failed compensation step in this pass -
                // see implementation notes. Logged loudly - this is the one failure mode that
                // actually leaves money in the wrong place if nobody follows up on it.
                logStep(txn, "COMPENSATE_DEBIT", StepStatus.FAILED, describe(ex));
                log.error("COMPENSATION FAILED for transaction {}: could not reverse the source debit - manual intervention needed: {}",
                        txn.getTransactionId(), ex.getMessage());
                return txn;
            }
        }
        try {
            fxRateClient.releaseLock(txn.getFxLockId());
            logStep(txn, "RELEASE_LOCK", StepStatus.COMPENSATED, "lockId=" + txn.getFxLockId());
            txn = transition(txn, SagaState.LOCK_RELEASED);
        } catch (RestClientException ex) {
            logStep(txn, "RELEASE_LOCK", StepStatus.FAILED, describe(ex));
            log.error("Could not release rate lock {} for transaction {} during compensation: {}",
                    txn.getFxLockId(), txn.getTransactionId(), ex.getMessage());
            return txn; // stuck one state short of COMPENSATED
        }
        // Records that this transaction touched real money before being unwound (design doc §5.3
        // step 11b "record REVERSED ledger entry") - there was never a forward posting to reverse
        // (that only happens once the saga reaches COMPLETED, which it never does on this path),
        // so this is its own, independent audit record of the reversal itself, best-effort same
        // as the forward posting.
        if (reverseCredit || reverseDebit) {
            recordLedgerReversal(txn, idempotencyKey, reverseCredit, reverseDebit, sourceBalanceAfterReversal, destBalanceAfterReversal);
        }
        return transition(txn, SagaState.COMPENSATED);
    }

    /**
     * Builds the double-entry legs for one posting (design doc §6.1.5's net-to-zero invariant)
     * using a synthetic FX clearing account for the currency conversion - standard double-entry
     * technique for a cross-currency movement, where the source-currency debit and
     * destination-currency credit can never net against each other by amount alone. Always uses
     * the clearing account, even when {@code sourceCurrency == destCurrency} (a same-currency
     * "conversion", e.g. rate 1.0) - one code path, always correct, the extra pair of clearing
     * legs is harmless. See ledger-service-implementation.md's "Double-entry validator - current
     * scope" for the gap this closes.
     */
    private void recordLedgerEntries(ConversionTransaction txn, String idempotencyKey,
                                      BigDecimal sourceBalanceAfter, BigDecimal destBalanceAfter) {
        List<LedgerLineRequest> entries = List.of(
                new LedgerLineRequest(txn.getSourceWalletId(), LedgerEntryType.DEBIT, txn.getSourceAmount(), txn.getSourceCurrency(), sourceBalanceAfter),
                new LedgerLineRequest(clearingAccountId, LedgerEntryType.CREDIT, txn.getSourceAmount(), txn.getSourceCurrency(), BigDecimal.ZERO),
                new LedgerLineRequest(clearingAccountId, LedgerEntryType.DEBIT, txn.getDestAmount(), txn.getDestCurrency(), BigDecimal.ZERO),
                new LedgerLineRequest(txn.getDestWalletId(), LedgerEntryType.CREDIT, txn.getDestAmount(), txn.getDestCurrency(), destBalanceAfter)
        );
        try {
            ledgerClient.postEntries(txn.getTransactionId(), entries, idempotencyKey + "-ledger");
            logStep(txn, "RECORD_LEDGER", StepStatus.SUCCESS, "entries=" + entries.size());
        } catch (RestClientException ex) {
            logStep(txn, "RECORD_LEDGER", StepStatus.FAILED, describe(ex));
            log.warn("Failed to record ledger entries for transaction {} (saga still completes): {}",
                    txn.getTransactionId(), ex.getMessage());
        }
    }

    /**
     * Mirror image of {@link #recordLedgerEntries} - only the legs for whichever side(s) actually
     * got reversed (matching {@code compensate}'s own {@code reverseCredit}/{@code reverseDebit}
     * flags), each still paired with its own clearing-account leg so every currency group still
     * nets to zero independently, whether one side reversed or both.
     */
    private void recordLedgerReversal(ConversionTransaction txn, String idempotencyKey,
                                       boolean reverseCredit, boolean reverseDebit,
                                       BigDecimal sourceBalanceAfterReversal, BigDecimal destBalanceAfterReversal) {
        List<LedgerLineRequest> entries = new ArrayList<>();
        if (reverseDebit) {
            entries.add(new LedgerLineRequest(txn.getSourceWalletId(), LedgerEntryType.CREDIT, txn.getSourceAmount(), txn.getSourceCurrency(), sourceBalanceAfterReversal));
            entries.add(new LedgerLineRequest(clearingAccountId, LedgerEntryType.DEBIT, txn.getSourceAmount(), txn.getSourceCurrency(), BigDecimal.ZERO));
        }
        if (reverseCredit) {
            entries.add(new LedgerLineRequest(txn.getDestWalletId(), LedgerEntryType.DEBIT, txn.getDestAmount(), txn.getDestCurrency(), destBalanceAfterReversal));
            entries.add(new LedgerLineRequest(clearingAccountId, LedgerEntryType.CREDIT, txn.getDestAmount(), txn.getDestCurrency(), BigDecimal.ZERO));
        }
        String reversalTransactionId = txn.getTransactionId() + "-reversal";
        try {
            ledgerClient.postEntries(reversalTransactionId, entries, idempotencyKey + "-ledger-reversal");
            logStep(txn, "RECORD_LEDGER_REVERSAL", StepStatus.SUCCESS, "entries=" + entries.size());
        } catch (RestClientException ex) {
            logStep(txn, "RECORD_LEDGER_REVERSAL", StepStatus.FAILED, describe(ex));
            log.warn("Failed to record ledger reversal entries for transaction {} (compensation still completes): {}",
                    txn.getTransactionId(), ex.getMessage());
        }
    }

    /** @return the saved, fully-populated instance - see ConversionTransaction's Persistable javadoc for why this must be reassigned, not the passed-in {@code txn} relied on afterward. */
    private ConversionTransaction transition(ConversionTransaction txn, SagaState next) {
        SagaStateMachine.transition(txn.getSagaState(), next);
        txn.setSagaState(next);
        return transactionRepository.save(txn);
    }

    private void logStep(ConversionTransaction txn, String stepName, StepStatus status, String payload) {
        stepLogRepository.save(new SagaStepLog(UUID.randomUUID().toString(), txn.getTransactionId(), stepName, status, payload));
    }

    private String describe(RestClientException ex) {
        if (ex instanceof RestClientResponseException responseEx) {
            return "HTTP %d - %s".formatted(responseEx.getStatusCode().value(), responseEx.getResponseBodyAsString());
        }
        return ex.getMessage();
    }
}
