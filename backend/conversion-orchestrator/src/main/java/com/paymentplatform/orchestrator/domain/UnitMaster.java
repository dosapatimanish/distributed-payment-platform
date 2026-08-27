package com.paymentplatform.orchestrator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * The bank "unit" record (backend-documents/identifier-scheme.md). One row (id {@code MAIN}).
 * {@link #businessDate} is the authoritative processing date - the transaction id's date part
 * and daily sequence both key off this, not wall-clock. {@code BusinessDateRollJob} advances it.
 */
@Entity
@Table(name = "unit_master")
public class UnitMaster {

    @Id
    @Column(name = "unit_id", length = 8, nullable = false, updatable = false)
    private String unitId;

    @Column(name = "unit_name", length = 64, nullable = false)
    private String unitName;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    protected UnitMaster() {
    }

    public String getUnitId() {
        return unitId;
    }

    public String getUnitName() {
        return unitName;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public void setBusinessDate(LocalDate businessDate) {
        this.businessDate = businessDate;
    }
}
