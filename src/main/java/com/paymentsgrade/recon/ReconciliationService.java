package com.paymentsgrade.recon;

import com.paymentsgrade.recon.ReconciliationChecks.Issue;
import com.paymentsgrade.recon.ReconciliationRepository.IssueRow;
import com.paymentsgrade.recon.ReconciliationRepository.Run;
import com.paymentsgrade.web.ApiException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReconciliationService {

    public record Started(long runId, String status) {
    }

    public record RunView(long id, String status, String triggeredBy, java.time.OffsetDateTime startedAt,
            java.time.OffsetDateTime finishedAt, Integer issueCount, String explanation, List<IssueRow> issues) {
    }

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final ReconciliationRepository repo;
    private final ReconciliationChecks checks;
    private final IssueExplainer explainer;
    private final ExecutorService executor;
    private final TransactionTemplate lockTx;

    public ReconciliationService(ReconciliationRepository repo, ReconciliationChecks checks, IssueExplainer explainer,
            @Qualifier("reconciliationExecutor") ExecutorService executor, PlatformTransactionManager tm) {
        this.repo = repo;
        this.checks = checks;
        this.explainer = explainer;
        this.executor = executor;
        this.lockTx = new TransactionTemplate(tm);
    }

    // Creates the run row now and executes in the background once the caller's transaction commits.
    public Started startAsync(String triggeredBy) {
        long runId = repo.createRun(triggeredBy);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.submit(() -> execute(runId));
                }
            });
        } else {
            executor.submit(() -> execute(runId));
        }
        return new Started(runId, "RUNNING");
    }

    // Used by the nightly job: runs on the calling thread and returns the run id.
    public long runNow(String triggeredBy) {
        long runId = repo.createRun(triggeredBy);
        execute(runId);
        return runId;
    }

    public RunView get(long runId) {
        Run run = repo.findRun(runId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "Reconciliation run not found"));
        return new RunView(run.id(), run.status(), run.triggeredBy(), run.startedAt(), run.finishedAt(),
                run.issueCount(), run.explanation(), repo.issues(runId));
    }

    private void execute(long runId) {
        try {
            // The lock, the issues and the final status commit together, so a COMPLETED run is always whole.
            Boolean ran = lockTx.execute(status -> {
                if (!repo.tryLock()) {
                    status.setRollbackOnly();
                    return false;
                }
                List<Issue> issues = checks.runAll();
                repo.insertIssues(runId, issues);
                repo.purgeIdempotencyKeys();
                repo.finishRun(runId, "COMPLETED", issues.size());
                return true;
            });
            if (!Boolean.TRUE.equals(ran)) {
                repo.finishRun(runId, "SKIPPED", null);
            } else {
                explain(runId);
            }
        } catch (RuntimeException e) {
            log.error("Reconciliation run {} failed", runId, e);
            repo.finishRun(runId, "FAILED", null);
        }
    }

    // Runs after the commit so a slow or broken model never holds the job lock or fails the run.
    private void explain(long runId) {
        try {
            explainer.explainRun(runId);
        } catch (RuntimeException e) {
            log.warn("Explain step failed for run {}", runId, e);
        }
    }
}
