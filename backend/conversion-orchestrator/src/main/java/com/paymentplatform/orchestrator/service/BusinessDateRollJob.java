package com.paymentplatform.orchestrator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rolls the unit's business date forward by one day on a cron (default midnight). The cron is a
 * property ({@code unit.business-date.roll-cron}) so a demo can roll it faster. Note there is no
 * fast-forward-on-startup - a stack left down for days stays behind wall-clock until the job
 * fires, which is how a real end-of-day batch behaves (identifier-scheme.md "Known limitations").
 */
@Component
public class BusinessDateRollJob {

    private static final Logger log = LoggerFactory.getLogger(BusinessDateRollJob.class);

    private final BusinessDateService businessDateService;

    public BusinessDateRollJob(BusinessDateService businessDateService) {
        this.businessDateService = businessDateService;
    }

    @Scheduled(cron = "${unit.business-date.roll-cron:0 0 0 * * *}")
    @Transactional
    public void roll() {
        log.info("Business date rolled to {}", businessDateService.roll());
    }
}
