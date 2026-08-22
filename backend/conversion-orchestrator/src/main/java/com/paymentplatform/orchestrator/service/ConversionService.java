package com.paymentplatform.orchestrator.service;

import com.paymentplatform.orchestrator.client.FxRateServiceClient;
import com.paymentplatform.orchestrator.client.WalletServiceClient;
import com.paymentplatform.orchestrator.client.dto.RateLockResponse;
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
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.RoundingMode;
import java.util.UUID;

/**
 * Drives the wallet-to-wallet conversion saga (design doc §5.3, reduced scope - see
 * {@link SagaState}'s javadoc for exactly what changed and why). Each step is a synchronous
 * REST call to wallet-service or fx-rate-service ("Synchronous REST calls" scope decision for
 * this pass - see implementation notes for the deferred async-Kafka-driven alternative), and
 * every state transition + step outcome is persisted immediately after that step resolves - not
 * batched, not deferred - so {@code conversion_transaction}/{@code saga_step_log} always
 * reflect exactly how far the saga actually got.
 *
 * <b>Deliberate gap</b>: no crash-recovery/resume. If this process dies mid-saga, the persisted
 * state accurately records where it stopped, but nothing automatically picks it back up or
 * retries the next step on restart - that requires the async-Kafka-driven architecture (an
 * {@code @KafkaListener} re-entering the flow) this pass deliberately deferred.
 */
@Service
public class ConversionService {

    private static final Logger log = LoggerFactory.getLogger(ConversionService.class);
    private static final int MONEY_SCALE = 4;

    private final ConversionTransactionRepository transactionRepository;
    private final SagaStepLogRepository stepLogRepository;
    private final WalletServiceClient walletClient;
    private final FxRateServiceClient fxRateClient;

    public ConversionService(ConversionTransactionRepository transactionRepository,
                              SagaStepLogRepository stepLogRepository,
                              WalletServiceClient walletClient,
                              FxRateServiceClient fxRateClient) {
        this.transactionRepository = transactionRepository;
        this.stepLogRepository = stepLogRepository;
        this.walletClient = walletClient;
        this.fxRateClient = fxRateClient;
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
        return runSaga(txn, idempotencyKey);
    }

    private ConversionTransaction runSaga(ConversionTransaction txn, String idempotencyKey) {
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

        try {
            walletClient.debit(txn.getSourceWalletId(), txn.getSourceAmount(), txn.getTransactionId(), idempotencyKey + "-debit");
        } catch (RestClientException ex) {
            logStep(txn, "DEBIT", StepStatus.FAILED, describe(ex));
            txn = transition(txn, SagaState.DEBIT_FAILED);
            return compensate(txn, idempotencyKey, false);
        }
        logStep(txn, "DEBIT", StepStatus.SUCCESS, "amount=" + txn.getSourceAmount());
        txn = transition(txn, SagaState.SOURCE_DEBITED);

        try {
            walletClient.credit(txn.getDestWalletId(), txn.getDestAmount(), txn.getTransactionId(), idempotencyKey + "-credit");
        } catch (RestClientException ex) {
            logStep(txn, "CREDIT", StepStatus.FAILED, describe(ex));
            txn = transition(txn, SagaState.CREDIT_FAILED);
            return compensate(txn, idempotencyKey, true);
        }
        logStep(txn, "CREDIT", StepStatus.SUCCESS, "amount=" + txn.getDestAmount());
        txn = transition(txn, SagaState.DEST_CREDITED);

        // Best-effort: whether this succeeds or fails does not change the saga's outcome - the
        // money already moved correctly at the rate captured in txn.lockedRate. See class javadoc.
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

    private ConversionTransaction compensate(ConversionTransaction txn, String idempotencyKey, boolean reverseDebit) {
        txn = transition(txn, SagaState.COMPENSATING);
        if (reverseDebit) {
            try {
                walletClient.credit(txn.getSourceWalletId(), txn.getSourceAmount(), txn.getTransactionId(),
                        idempotencyKey + "-compensate-debit");
                logStep(txn, "COMPENSATE_DEBIT", StepStatus.COMPENSATED, "amount=" + txn.getSourceAmount());
                txn = transition(txn, SagaState.SOURCE_CREDITED_BACK);
            } catch (RestClientException ex) {
                // Deliberate gap: no automatic retry of a failed compensation step in this pass -
                // see implementation notes. Logged loudly - this is the one failure mode that
                // actually leaves money in the wrong place if nobody follows up on it.
                logStep(txn, "COMPENSATE_DEBIT", StepStatus.FAILED, describe(ex));
                log.error("COMPENSATION FAILED for transaction {}: could not reverse the source debit - manual intervention needed: {}",
                        txn.getTransactionId(), ex.getMessage());
                return txn; // stuck at COMPENSATING, not silently marked COMPENSATED when it isn't
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
        return transition(txn, SagaState.COMPENSATED);
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
