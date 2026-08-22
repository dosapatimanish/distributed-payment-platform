package com.paymentplatform.orchestrator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Append-only audit trail of every step attempted for a saga (design doc 6.1.3
 * {@code saga_step_log}) - one row per step attempt, written right after that step's outcome is
 * known. {@code stepName} values used by this service: {@code RATE_LOCK}, {@code DEBIT},
 * {@code CREDIT}, {@code CONSUME_LOCK}, {@code COMPENSATE_DEBIT}, {@code RELEASE_LOCK}.
 */
@Entity
@Table(name = "saga_step_log")
public class SagaStepLog {

    @Id
    @Column(name = "step_id", length = 36, nullable = false, updatable = false)
    private String stepId;

    @Column(name = "transaction_id", length = 36, nullable = false, updatable = false)
    private String transactionId;

    @Column(name = "step_name", length = 50, nullable = false, updatable = false)
    private String stepName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false, updatable = false)
    private StepStatus status;

    /** Request/response snapshot for audit and replay - typically the downstream call's JSON body or error message. */
    @Lob
    @Column(name = "payload")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SagaStepLog() {
    }

    public SagaStepLog(String stepId, String transactionId, String stepName, StepStatus status, String payload) {
        this.stepId = stepId;
        this.transactionId = transactionId;
        this.stepName = stepName;
        this.status = status;
        this.payload = payload;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public String getStepId() {
        return stepId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getStepName() {
        return stepName;
    }

    public StepStatus getStatus() {
        return status;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
