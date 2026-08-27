package com.paymentplatform.orchestrator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Append-only audit trail of every step attempted for a saga (design doc §6.1.3) - one row per
 * step attempt. Composite PK {@code (transaction_id, step_no)}: {@code stepNo} is a 2-digit
 * running number ({@code 01}, {@code 02}, ...) showing the step's order within its saga
 * (identifier-scheme.md). {@code accountNo} carries the account a step acted on (source for
 * {@code DEBIT} / {@code COMPENSATE_DEBIT}, destination for {@code CREDIT} /
 * {@code DEBIT_FOR_PAYMENT} / {@code COMPENSATE_CREDIT}), null for steps that touch no account.
 */
@Entity
@Table(name = "saga_step_log")
@IdClass(SagaStepLogId.class)
public class SagaStepLog {

    @Id
    @Column(name = "transaction_id", length = 16, nullable = false, updatable = false)
    private String transactionId;

    @Id
    @Column(name = "step_no", length = 4, nullable = false, updatable = false)
    private String stepNo;

    @Column(name = "step_name", length = 50, nullable = false, updatable = false)
    private String stepName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false, updatable = false)
    private StepStatus status;

    @Column(name = "account_no", length = 12, updatable = false)
    private String accountNo;

    /** Request/response snapshot for audit - the downstream call's summary or error message. */
    @Lob
    @Column(name = "payload")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SagaStepLog() {
    }

    public SagaStepLog(String transactionId, String stepNo, String stepName, StepStatus status,
                        String payload, String accountNo) {
        this.transactionId = transactionId;
        this.stepNo = stepNo;
        this.stepName = stepName;
        this.status = status;
        this.payload = payload;
        this.accountNo = accountNo;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getStepNo() {
        return stepNo;
    }

    public String getStepName() {
        return stepName;
    }

    public StepStatus getStatus() {
        return status;
    }

    public String getAccountNo() {
        return accountNo;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
