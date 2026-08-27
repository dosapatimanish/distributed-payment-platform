package com.paymentplatform.orchestrator.service;

import com.paymentplatform.orchestrator.domain.UnitMaster;
import com.paymentplatform.orchestrator.repository.UnitMasterRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/** Reads / advances the unit's business date (unit_master, id MAIN). */
@Service
public class BusinessDateService {

    static final String UNIT_ID = "MAIN";

    private final UnitMasterRepository unitMasterRepository;

    public BusinessDateService(UnitMasterRepository unitMasterRepository) {
        this.unitMasterRepository = unitMasterRepository;
    }

    public LocalDate current() {
        return unit().getBusinessDate();
    }

    /** Advances the business date by one day. Called by {@code BusinessDateRollJob}. */
    public LocalDate roll() {
        UnitMaster unit = unit();
        unit.setBusinessDate(unit.getBusinessDate().plusDays(1));
        return unitMasterRepository.save(unit).getBusinessDate();
    }

    private UnitMaster unit() {
        return unitMasterRepository.findById(UNIT_ID)
                .orElseThrow(() -> new IllegalStateException("unit_master row '" + UNIT_ID + "' is missing"));
    }
}
