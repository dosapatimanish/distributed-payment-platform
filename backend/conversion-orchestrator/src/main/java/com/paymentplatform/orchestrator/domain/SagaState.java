package com.paymentplatform.orchestrator.domain;

/**
 * States for the reduced-scope, wallet-to-wallet conversion saga this service drives (design
 * doc §6.6, adapted - see {@code implementation-notes.md}'s "Reduced scope vs the design doc's
 * state table" for exactly what changed and why). No Merchant Payment or Ledger service exists
 * yet, so {@code PAYMENT_*} and {@code LEDGER_POSTED} are not part of this state list, and
 * {@code DEST_CREDITED} transitions straight to {@code COMPLETED}.
 *
 * <pre>
 * STARTED -&gt; RATE_LOCKED -&gt; SOURCE_DEBITED -&gt; DEST_CREDITED -&gt; COMPLETED   (happy path)
 * STARTED -&gt; FAILED                                                       (rate lock itself failed - nothing to compensate)
 * RATE_LOCKED -&gt; DEBIT_FAILED -&gt; COMPENSATING -&gt; LOCK_RELEASED -&gt; COMPENSATED              (debit failed - only the lock needs releasing)
 * SOURCE_DEBITED -&gt; CREDIT_FAILED -&gt; COMPENSATING -&gt; SOURCE_CREDITED_BACK -&gt; LOCK_RELEASED -&gt; COMPENSATED  (credit failed - reverse the debit too)
 * </pre>
 *
 * {@link com.paymentplatform.orchestrator.saga.SagaStateMachine} is the single place that
 * enforces exactly these transitions and rejects any other.
 */
public enum SagaState {
    STARTED,
    RATE_LOCKED,
    SOURCE_DEBITED,
    DEST_CREDITED,
    COMPLETED,
    FAILED,
    DEBIT_FAILED,
    CREDIT_FAILED,
    COMPENSATING,
    SOURCE_CREDITED_BACK,
    LOCK_RELEASED,
    COMPENSATED
}
