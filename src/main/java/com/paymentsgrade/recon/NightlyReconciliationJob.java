package com.paymentsgrade.recon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("worker")
public class NightlyReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(NightlyReconciliationJob.class);

    private final ReconciliationService service;

    public NightlyReconciliationJob(ReconciliationService service) {
        this.service = service;
    }

    @Scheduled(cron = "${app.reconciliation.cron:0 0 2 * * *}")
    public void run() {
        long runId = service.runNow("SCHEDULE");
        log.info("Nightly reconciliation finished as run {}", runId);
    }
}
