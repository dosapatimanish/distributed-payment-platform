package com.paymentplatform.orchestrator.repository;

import com.paymentplatform.orchestrator.domain.SagaStepLog;
import com.paymentplatform.orchestrator.domain.SagaStepLogId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SagaStepLogRepository extends JpaRepository<SagaStepLog, SagaStepLogId> {

    long countByTransactionId(String transactionId);
}
