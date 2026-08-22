package com.paymentplatform.orchestrator.saga;

import com.paymentplatform.orchestrator.domain.SagaState;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.paymentplatform.orchestrator.domain.SagaState.COMPENSATED;
import static com.paymentplatform.orchestrator.domain.SagaState.COMPENSATING;
import static com.paymentplatform.orchestrator.domain.SagaState.COMPLETED;
import static com.paymentplatform.orchestrator.domain.SagaState.CREDIT_FAILED;
import static com.paymentplatform.orchestrator.domain.SagaState.DEBIT_FAILED;
import static com.paymentplatform.orchestrator.domain.SagaState.DEST_CREDITED;
import static com.paymentplatform.orchestrator.domain.SagaState.DEST_DEBITED_BACK;
import static com.paymentplatform.orchestrator.domain.SagaState.FAILED;
import static com.paymentplatform.orchestrator.domain.SagaState.LOCK_RELEASED;
import static com.paymentplatform.orchestrator.domain.SagaState.PAYMENT_COMPLETED;
import static com.paymentplatform.orchestrator.domain.SagaState.PAYMENT_FAILED;
import static com.paymentplatform.orchestrator.domain.SagaState.RATE_LOCKED;
import static com.paymentplatform.orchestrator.domain.SagaState.SOURCE_CREDITED_BACK;
import static com.paymentplatform.orchestrator.domain.SagaState.SOURCE_DEBITED;
import static com.paymentplatform.orchestrator.domain.SagaState.STARTED;

/**
 * Pure state-transition logic (design doc §6.6), independently unit-testable - no Spring, no
 * I/O. {@link #transition} rejects any move not in {@link #VALID_TRANSITIONS}, so an
 * out-of-order or duplicate call (e.g. a retried step handler re-applying an already-applied
 * transition) is safely rejected rather than corrupting saga state - a second safeguard on top
 * of Idempotency-Key, per the design doc's stated reasoning for this class.
 *
 * See {@link SagaState}'s javadoc for the full transition diagram and how it differs from the
 * design doc's full (Ledger-inclusive) state table.
 */
public final class SagaStateMachine {

    private static final Map<SagaState, Set<SagaState>> VALID_TRANSITIONS = new EnumMap<>(SagaState.class);

    static {
        VALID_TRANSITIONS.put(STARTED, EnumSet.of(RATE_LOCKED, FAILED));
        VALID_TRANSITIONS.put(RATE_LOCKED, EnumSet.of(SOURCE_DEBITED, DEBIT_FAILED));
        VALID_TRANSITIONS.put(SOURCE_DEBITED, EnumSet.of(DEST_CREDITED, CREDIT_FAILED));
        // DEST_CREDITED forks three ways: no merchantId -> straight to COMPLETED; a merchantId
        // present -> PAYMENT_COMPLETED or PAYMENT_FAILED depending on the charge outcome.
        VALID_TRANSITIONS.put(DEST_CREDITED, EnumSet.of(COMPLETED, PAYMENT_COMPLETED, PAYMENT_FAILED));
        VALID_TRANSITIONS.put(PAYMENT_COMPLETED, EnumSet.of(COMPLETED));
        VALID_TRANSITIONS.put(DEBIT_FAILED, EnumSet.of(COMPENSATING));
        VALID_TRANSITIONS.put(CREDIT_FAILED, EnumSet.of(COMPENSATING));
        VALID_TRANSITIONS.put(PAYMENT_FAILED, EnumSet.of(COMPENSATING));
        // COMPENSATING forks three ways depending on how far the saga got before it failed:
        // DEBIT_FAILED's path has nothing to reverse (skips straight to LOCK_RELEASED);
        // CREDIT_FAILED's path reverses the debit only (SOURCE_CREDITED_BACK);
        // PAYMENT_FAILED's path reverses both, credit first (DEST_DEBITED_BACK).
        VALID_TRANSITIONS.put(COMPENSATING, EnumSet.of(DEST_DEBITED_BACK, SOURCE_CREDITED_BACK, LOCK_RELEASED));
        VALID_TRANSITIONS.put(DEST_DEBITED_BACK, EnumSet.of(SOURCE_CREDITED_BACK));
        VALID_TRANSITIONS.put(SOURCE_CREDITED_BACK, EnumSet.of(LOCK_RELEASED));
        VALID_TRANSITIONS.put(LOCK_RELEASED, EnumSet.of(COMPENSATED));
        // COMPLETED, FAILED, COMPENSATED are terminal - deliberately absent from the map, so
        // isTerminal() and the empty Set.of() default both agree they have no valid next state.
    }

    private static final Set<SagaState> TERMINAL_STATES = EnumSet.of(COMPLETED, FAILED, COMPENSATED);

    private SagaStateMachine() {
    }

    /**
     * @return {@code next}, if the move from {@code current} to {@code next} is valid
     * @throws InvalidSagaTransitionException if it is not
     */
    public static SagaState transition(SagaState current, SagaState next) {
        Set<SagaState> allowed = VALID_TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowed.contains(next)) {
            throw new InvalidSagaTransitionException(current, next);
        }
        return next;
    }

    public static boolean isTerminal(SagaState state) {
        return TERMINAL_STATES.contains(state);
    }
}
