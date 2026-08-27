package com.paymentplatform.wallet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Currency reference data (backend-documents/identifier-scheme.md). wallet-service owns this
 * table; conversion-orchestrator reads it via {@code GET /api/v1/currencies} to resolve the
 * 2-digit {@link #shortCode} used in account numbers and transaction ids.
 */
@Entity
@Table(name = "currency")
public class Currency {

    /** ISO 4217 alpha code, e.g. "USD". */
    @Id
    @Column(name = "code", length = 3, nullable = false, updatable = false)
    private String code;

    /** ISO 4217 numeric code, e.g. "840". */
    @Column(name = "numeric_code", length = 3, nullable = false)
    private String numericCode;

    /** Custom 2-digit code used as the prefix of account numbers and transaction ids, e.g. "01". */
    @Column(name = "short_code", length = 2, nullable = false)
    private String shortCode;

    @Column(name = "name", length = 64, nullable = false)
    private String name;

    @Column(name = "minor_units", nullable = false)
    private int minorUnits;

    @Column(name = "active", length = 1, nullable = false)
    private String active;

    protected Currency() {
    }

    public String getCode() {
        return code;
    }

    public String getNumericCode() {
        return numericCode;
    }

    public String getShortCode() {
        return shortCode;
    }

    public String getName() {
        return name;
    }

    public int getMinorUnits() {
        return minorUnits;
    }

    public boolean isActive() {
        return "Y".equals(active);
    }
}
