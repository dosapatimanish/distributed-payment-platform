package com.paymentplatform.orchestrator.saga;

import com.paymentplatform.orchestrator.domain.SagaState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static com.paymentplatform.orchestrator.domain.SagaState.COMPENSATED;
import static com.paymentplatform.orchestrator.domain.SagaState.COMPENSATING;
import static com.paymentplatform.orchestrator.domain.SagaState.COMPLETED;
import static com.paymentplatform.orchestrator.domain.SagaState.CREDIT_FAILED;
import static com.paymentplatform.orchestrator.domain.SagaState.DEBIT_FAILED;
import static com.paymentplatform.orchestrator.domain.SagaState.DEST_CREDITED;
import static com.paymentplatform.orchestrator.domain.SagaState.FAILED;
import static com.paymentplatform.orchestrator.domain.SagaState.LOCK_RELEASED;
import static com.paymentplatform.orchestrator.domain.SagaState.RATE_LOCKED;
import static com.paymentplatform.orchestrator.domain.SagaState.SOURCE_CREDITED_BACK;
import static com.paymentplatform.orchestrator.domain.SagaState.SOURCE_DEBITED;
import static com.paymentplatform.orchestrator.domain.SagaState.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SagaStateMachineTest {

    // ------------------------------------------------------------------
    // Every valid transition in the happy path and both compensation paths
    // ------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "STARTED, RATE_LOCKED",
            "STARTED, FAILED",
            "RATE_LOCKED, SOURCE_DEBITED",
            "RATE_LOCKED, DEBIT_FAILED",
            "SOURCE_DEBITED, DEST_CREDITED",
            "SOURCE_DEBITED, CREDIT_FAILED",
            "DEST_CREDITED, COMPLETED",
            "DEST_CREDITED, PAYMENT_COMPLETED",
            "DEST_CREDITED, PAYMENT_FAILED",
            "PAYMENT_COMPLETED, COMPLETED",
            "DEBIT_FAILED, COMPENSATING",
            "CREDIT_FAILED, COMPENSATING",
            "PAYMENT_FAILED, COMPENSATING",
            "COMPENSATING, DEST_DEBITED_BACK",
            "COMPENSATING, SOURCE_CREDITED_BACK",
            "COMPENSATING, LOCK_RELEASED",
            "DEST_DEBITED_BACK, SOURCE_CREDITED_BACK",
            "SOURCE_CREDITED_BACK, LOCK_RELEASED",
            "LOCK_RELEASED, COMPENSATED",
    })
    void transition_validMove_returnsNextState(SagaState current, SagaState next) {
        assertThat(SagaStateMachine.transition(current, next)).isEqualTo(next);
    }

    // ------------------------------------------------------------------
    // Rejected transitions - the whole point of this class
    // ------------------------------------------------------------------

    @Test
    void transition_skippingAStep_throws() {
        // Can't go straight from STARTED to SOURCE_DEBITED without locking a rate first.
        assertThatThrownBy(() -> SagaStateMachine.transition(STARTED, SOURCE_DEBITED))
                .isInstanceOf(InvalidSagaTransitionException.class);
    }

    @Test
    void transition_reDeliveredEventAfterAlreadyTerminal_throws() {
        // A duplicate/re-delivered "completed" signal arriving after the saga is already
        // COMPLETED must be rejected, not silently re-applied - this is the class's stated
        // purpose (design doc §6.6).
        assertThatThrownBy(() -> SagaStateMachine.transition(COMPLETED, COMPLETED))
                .isInstanceOf(InvalidSagaTransitionException.class);
    }

    @Test
    void transition_backwardsMove_throws() {
        assertThatThrownBy(() -> SagaStateMachine.transition(SOURCE_DEBITED, RATE_LOCKED))
                .isInstanceOf(InvalidSagaTransitionException.class);
    }

    @Test
    void transition_debitFailedCannotSkipStraightToCompensated_throws() {
        // COMPENSATING must happen first - can't jump straight to the terminal state.
        assertThatThrownBy(() -> SagaStateMachine.transition(DEBIT_FAILED, COMPENSATED))
                .isInstanceOf(InvalidSagaTransitionException.class);
    }

    @Test
    void transition_fromTerminalState_alwaysThrows() {
        // No valid next state exists for any terminal state - VALID_TRANSITIONS deliberately
        // has no entry for COMPLETED/FAILED/COMPENSATED, so getOrDefault falls back to Set.of().
        assertThatThrownBy(() -> SagaStateMachine.transition(FAILED, STARTED))
                .isInstanceOf(InvalidSagaTransitionException.class);
    }

    // ------------------------------------------------------------------
    // isTerminal
    // ------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = SagaState.class, names = {"COMPLETED", "FAILED", "COMPENSATED"})
    void isTerminal_terminalStates_returnsTrue(SagaState state) {
        assertThat(SagaStateMachine.isTerminal(state)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = SagaState.class, names = {"COMPLETED", "FAILED", "COMPENSATED"}, mode = EnumSource.Mode.EXCLUDE)
    void isTerminal_nonTerminalStates_returnsFalse(SagaState state) {
        assertThat(SagaStateMachine.isTerminal(state)).isFalse();
    }
}
