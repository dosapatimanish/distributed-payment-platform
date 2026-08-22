package com.paymentplatform.orchestrator.domain;

/** Outcome of one saga step, recorded in {@link SagaStepLog} (design doc 6.1.3). */
public enum StepStatus {
    SUCCESS,
    FAILED,
    COMPENSATED
}
