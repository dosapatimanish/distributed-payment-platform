package com.paymentplatform.fxrate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One ingested rate snapshot for a currency pair (design doc 6.1.2). Append-only history/audit
 * trail - a new row every refresh tick, never updated. The value actually served to callers
 * lives in {@link com.paymentplatform.fxrate.service.FxRateCache}'s in-memory snapshot; this
 * table is what {@code RateRefreshScheduler} writes alongside that cache update, and what a
 * later "what was the rate at time T" audit query would read.
 */
@Entity
@Table(
        name = "fx_rate",
        indexes = @Index(name = "idx_fx_rate_pair_effective_at", columnList = "base_currency, quote_currency, effective_at")
)
public class FxRate {

    @Id
    @Column(name = "rate_id", length = 20, nullable = false, updatable = false)
    private String rateId;

    @Column(name = "base_currency", length = 3, nullable = false, updatable = false)
    private String baseCurrency;

    @Column(name = "quote_currency", length = 3, nullable = false, updatable = false)
    private String quoteCurrency;

    @Column(name = "rate", precision = 18, scale = 8, nullable = false, updatable = false)
    private BigDecimal rate;

    /** Feed provider identifier - "SIMULATED_FEED" until a real provider is wired in. */
    @Column(name = "source", length = 50, nullable = false, updatable = false)
    private String source;

    @Column(name = "effective_at", nullable = false, updatable = false)
    private Instant effectiveAt;

    protected FxRate() {
    }

    public FxRate(String rateId, String baseCurrency, String quoteCurrency, BigDecimal rate,
                  String source, Instant effectiveAt) {
        this.rateId = rateId;
        this.baseCurrency = baseCurrency;
        this.quoteCurrency = quoteCurrency;
        this.rate = rate;
        this.source = source;
        this.effectiveAt = effectiveAt;
    }

    public String getRateId() {
        return rateId;
    }

    public String getBaseCurrency() {
        return baseCurrency;
    }

    public String getQuoteCurrency() {
        return quoteCurrency;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public String getSource() {
        return source;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }
}
