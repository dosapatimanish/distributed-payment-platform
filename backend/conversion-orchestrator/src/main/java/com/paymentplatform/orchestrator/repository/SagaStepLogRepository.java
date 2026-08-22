package com.paymentplatform.orchestrator.repository;

import com.paymentplatform.orchestrator.domain.SagaStepLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SagaStepLogRepository extends JpaRepository<SagaStepLog, String> {
}
