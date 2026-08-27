package com.paymentplatform.orchestrator.domain;

import java.io.Serializable;
import java.util.Objects;

/** Composite key for {@link SagaStepLog}: the saga's transaction id + the 2-digit step number. */
public class SagaStepLogId implements Serializable {

    private String transactionId;
    private String stepNo;

    public SagaStepLogId() {
    }

    public SagaStepLogId(String transactionId, String stepNo) {
        this.transactionId = transactionId;
        this.stepNo = stepNo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SagaStepLogId other)) return false;
        return Objects.equals(transactionId, other.transactionId) && Objects.equals(stepNo, other.stepNo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transactionId, stepNo);
    }
}
