package com.paymentsgrade.recon;

import com.paymentsgrade.recon.ReconciliationChecks.Issue;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReconciliationRepository {

    public static final String JOB = "reconciliation";
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    public record Run(long id, String status, String triggeredBy, OffsetDateTime startedAt,
            OffsetDateTime finishedAt, Integer issueCount, String explanation) {
    }

    public record IssueRow(long id, String checkName, String entityType, Long entityId, String detail,
            String explanation) {
    }

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public ReconciliationRepository(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    public long createRun(String triggeredBy) {
        return jdbc.sql("""
                INSERT INTO reconciliation_runs (status, triggered_by, started_at)
                VALUES ('RUNNING', :t, clock_timestamp()) RETURNING id""")
                .param("t", triggeredBy).query(Long.class).single();
    }

    public void finishRun(long id, String status, Integer issueCount) {
        jdbc.sql("""
                UPDATE reconciliation_runs SET status = :s, issue_count = :c, finished_at = clock_timestamp() WHERE id = :id""")
                .param("s", status).param("c", issueCount).param("id", id).update();
    }

    // False when another run holds the lock. The failed statement aborts the transaction, so callers must roll back.
    public boolean tryLock() {
        try {
            jdbc.sql("SELECT name FROM job_locks WHERE name = :n FOR UPDATE NOWAIT").param("n", JOB)
                    .query(String.class).optional();
            return true;
        } catch (DataAccessException e) {
            // Spring does not map PostgreSQL's lock_not_available to a lock exception, so match the SQL state.
            if (e.getRootCause() instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
                return false;
            }
            throw e;
        }
    }

    public void insertIssues(long runId, List<Issue> issues) {
        template.batchUpdate("""
                INSERT INTO reconciliation_issues (run_id, check_name, entity_type, entity_id, detail)
                VALUES (?, ?, ?, ?, ?)""", issues, 500, (ps, issue) -> {
            ps.setLong(1, runId);
            ps.setString(2, issue.check());
            ps.setString(3, issue.entityType());
            ps.setObject(4, issue.entityId());
            ps.setString(5, issue.detail());
        });
    }

    public int purgeIdempotencyKeys() {
        return jdbc.sql("DELETE FROM idempotency_keys WHERE created_at < now() - interval '24 hours'").update();
    }

    public Optional<Run> findRun(long id) {
        return jdbc.sql("""
                SELECT id, status, triggered_by, started_at, finished_at, issue_count, explanation
                FROM reconciliation_runs WHERE id = :id""").param("id", id)
                .query((rs, i) -> new Run(rs.getLong("id"), rs.getString("status"), rs.getString("triggered_by"),
                        rs.getObject("started_at", OffsetDateTime.class),
                        rs.getObject("finished_at", OffsetDateTime.class), (Integer) rs.getObject("issue_count"),
                        rs.getString("explanation")))
                .optional();
    }

    public List<IssueRow> issues(long runId) {
        return jdbc.sql("""
                SELECT id, check_name, entity_type, entity_id, detail, explanation FROM reconciliation_issues
                WHERE run_id = :r ORDER BY id""").param("r", runId)
                .query((rs, i) -> new IssueRow(rs.getLong("id"), rs.getString("check_name"), rs.getString("entity_type"),
                        (Long) rs.getObject("entity_id"), rs.getString("detail"), rs.getString("explanation")))
                .list();
    }

    public void setIssueExplanation(long issueId, String explanation) {
        jdbc.sql("UPDATE reconciliation_issues SET explanation = :e WHERE id = :id")
                .param("e", explanation).param("id", issueId).update();
    }

    public void setRunExplanation(long runId, String explanation) {
        jdbc.sql("UPDATE reconciliation_runs SET explanation = :e WHERE id = :id")
                .param("e", explanation).param("id", runId).update();
    }

    public String transferNote(long transferId) {
        return jdbc.sql("SELECT note FROM transfers WHERE id = :id").param("id", transferId)
                .query(String.class).optional().orElse(null);
    }
}
