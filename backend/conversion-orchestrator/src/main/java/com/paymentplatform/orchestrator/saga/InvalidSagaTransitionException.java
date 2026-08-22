package com.paymentplatform.orchestrator.saga;

import com.paymentplatform.orchestrator.domain.SagaState;

/** Thrown by {@link SagaStateMachine#transition} for any move not in its valid-transitions table. */
public class InvalidSagaTransitionException extends RuntimeException {

    public InvalidSagaTransitionException(SagaState current, SagaState attemptedNext) {
        super("Invalid saga transition: %s -> %s".formatted(current, attemptedNext));
    }
}
