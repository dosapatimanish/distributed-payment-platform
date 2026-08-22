package com.paymentplatform.fxrate.repository;

import com.paymentplatform.fxrate.domain.FxRateLock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FxRateLockRepository extends JpaRepository<FxRateLock, String> {

    Optional<FxRateLock> findByTransactionId(String transactionId);
}
