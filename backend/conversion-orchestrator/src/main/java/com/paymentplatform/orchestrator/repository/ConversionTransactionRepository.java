package com.paymentplatform.orchestrator.repository;

import com.paymentplatform.orchestrator.domain.ConversionTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversionTransactionRepository extends JpaRepository<ConversionTransaction, String> {
}
