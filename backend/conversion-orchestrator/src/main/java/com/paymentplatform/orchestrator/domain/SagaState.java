package com.paymentplatform.orchestrator.domain;

/**
 * States for the conversion saga this service drives (design doc §6.6, adapted - see
 * {@code conversion-orchestrator-implementation.md}'s "Reduced scope vs the design doc's state
 * table" for exactly what changed and why). Still no Ledger service, so there is no
 * {@code LEDGER_POSTED} state; {@code PAYMENT_COMPLETED} transitions straight to
 * {@code COMPLETED}. Unlike the design doc's table, the merchant-payment leg is optional here -
 * a request without a {@code merchantId} skips it entirely (wallet-to-wallet conversion only,
 * no merchant involved) and goes straight from {@code DEST_CREDITED} to {@code COMPLETED}.
 *
 * <pre>
 * STARTED -&gt; RATE_LOCKED -&gt; SOURCE_DEBITED -&gt; DEST_CREDITED -&gt; COMPLETED                      (no merchantId - wallet-to-wallet only)
 * STARTED -&gt; RATE_LOCKED -&gt; SOURCE_DEBITED -&gt; DEST_CREDITED -&gt; PAYMENT_COMPLETED -&gt; COMPLETED   (merchantId present, charge approved)
 * STARTED -&gt; FAILED                                                                            (rate lock itself failed - nothing to compensate)
 * RATE_LOCKED -&gt; DEBIT_FAILED -&gt; COMPENSATING -&gt; LOCK_RELEASED -&gt; COMPENSATED                                                (debit failed - only the lock needs releasing)
 * SOURCE_DEBITED -&gt; CREDIT_FAILED -&gt; COMPENSATING -&gt; SOURCE_CREDITED_BACK -&gt; LOCK_RELEASED -&gt; COMPENSATED                     (credit failed - reverse the debit too)
 * DEST_CREDITED -&gt; PAYMENT_FAILED -&gt; COMPENSATING -&gt; DEST_DEBITED_BACK -&gt; SOURCE_CREDITED_BACK -&gt; LOCK_RELEASED -&gt; COMPENSATED (charge declined - reverse both the credit and the debit)
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
    PAYMENT_COMPLETED,
    COMPLETED,
    FAILED,
    DEBIT_FAILED,
    CREDIT_FAILED,
    PAYMENT_FAILED,
    COMPENSATING,
    DEST_DEBITED_BACK,
    SOURCE_CREDITED_BACK,
    LOCK_RELEASED,
    COMPENSATED
}
