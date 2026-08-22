package com.paymentplatform.fxrate.repository;

import com.paymentplatform.fxrate.domain.FxRate;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Plain CRUD over the {@code fx_rate} audit history. Nothing custom yet - the "current rate"
 * read path never queries this table, it goes through {@code FxRateCache} instead. This
 * repository exists purely so RateRefreshScheduler has somewhere to persist each tick.
 */
public interface FxRateRepository extends JpaRepository<FxRate, String> {
}
