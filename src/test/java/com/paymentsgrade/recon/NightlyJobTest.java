package com.paymentsgrade.recon;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentsgrade.AbstractPostgresTest;
import com.paymentsgrade.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("worker")
class NightlyJobTest extends AbstractPostgresTest {

    @Autowired
    NightlyReconciliationJob job;

    @Autowired
    ScheduledAnnotationBeanPostProcessor scheduler;

    @Autowired
    ReconciliationRepository repo;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Test
    void workerProfileSchedulesTheJobAndRunsIt() {
        TestDatabase.reset(jdbc, tx);

        assertThat(scheduler.getScheduledTasks()).isNotEmpty();
        job.run();

        long runId = jdbc.sql("SELECT max(id) FROM reconciliation_runs").query(Long.class).single();
        var run = repo.findRun(runId).orElseThrow();
        assertThat(run.triggeredBy()).isEqualTo("SCHEDULE");
        assertThat(run.status()).isEqualTo("COMPLETED");
        assertThat(run.issueCount()).isZero();
    }
}
