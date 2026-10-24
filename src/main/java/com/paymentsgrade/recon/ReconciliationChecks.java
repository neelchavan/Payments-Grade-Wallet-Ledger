package com.paymentsgrade.recon;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The five consistency checks. They only read and report; nothing is ever fixed automatically.
 * scripts/explain_checks.sql repeats these queries for EXPLAIN ANALYZE; keep the two in sync.
 */
@Component
public class ReconciliationChecks {

    public record Issue(String check, String entityType, Long entityId, String detail) {
    }

    // Caps memory if a bug corrupts millions of rows.
    static final int MAX_PER_CHECK = 1000;

    static final String GLOBAL_BALANCE_SQL = """
            SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0) AS debits,
                   COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0) AS credits
            FROM ledger_entries""";

    static final String TRANSFER_BALANCE_SQL = """
            SELECT transfer_id,
                   COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0) AS debits,
                   COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0) AS credits
            FROM ledger_entries
            GROUP BY transfer_id
            HAVING COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0)
                <> COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
            ORDER BY transfer_id
            LIMIT :cap""";

    static final String ACCOUNT_BALANCE_SQL = """
            SELECT a.id, a.balance AS stored, COALESCE(l.net, 0) AS computed
            FROM accounts a
            LEFT JOIN (SELECT account_id,
                              SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END) AS net
                       FROM ledger_entries GROUP BY account_id) l ON l.account_id = a.id
            WHERE a.balance <> COALESCE(l.net, 0)
            ORDER BY a.id
            LIMIT :cap""";

    static final String OVERDRAFT_SQL = """
            SELECT a.id, a.balance AS stored, COALESCE(l.net, 0) AS computed
            FROM accounts a
            LEFT JOIN (SELECT account_id,
                              SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END) AS net
                       FROM ledger_entries GROUP BY account_id) l ON l.account_id = a.id
            WHERE a.account_type = 'USER_WALLET' AND (a.balance < 0 OR COALESCE(l.net, 0) < 0)
            ORDER BY a.id
            LIMIT :cap""";

    static final String ORPHAN_ENTRY_TRANSFER_SQL = """
            SELECT DISTINCT e.transfer_id AS id
            FROM ledger_entries e
            WHERE NOT EXISTS (SELECT 1 FROM transfers t WHERE t.id = e.transfer_id)
            ORDER BY 1
            LIMIT :cap""";

    static final String ORPHAN_ENTRY_ACCOUNT_SQL = """
            SELECT DISTINCT e.account_id AS id
            FROM ledger_entries e
            WHERE NOT EXISTS (SELECT 1 FROM accounts a WHERE a.id = e.account_id)
            ORDER BY 1
            LIMIT :cap""";

    // HELD and REJECTED transfers legitimately have no entries.
    static final String ORPHAN_TRANSFER_SQL = """
            SELECT t.id
            FROM transfers t
            WHERE t.status IN ('COMPLETED', 'REVERSED')
              AND NOT EXISTS (SELECT 1 FROM ledger_entries e WHERE e.transfer_id = t.id)
            ORDER BY t.id
            LIMIT :cap""";

    private final JdbcClient jdbc;
    private final TransactionTemplate snapshot;

    public ReconciliationChecks(JdbcClient jdbc, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        // One snapshot for all checks, so concurrent transfers cannot make them disagree with each other.
        this.snapshot = new TransactionTemplate(tm);
        this.snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshot.setReadOnly(true);
    }

    public List<Issue> runAll() {
        return snapshot.execute(status -> {
            List<Issue> issues = new ArrayList<>();
            issues.addAll(globalBalance());
            issues.addAll(transferBalance());
            issues.addAll(accountBalance());
            issues.addAll(overdrafts());
            issues.addAll(orphans());
            return issues;
        });
    }

    List<Issue> globalBalance() {
        BigDecimal[] totals = jdbc.sql(GLOBAL_BALANCE_SQL)
                .query((rs, i) -> new BigDecimal[] {rs.getBigDecimal("debits"), rs.getBigDecimal("credits")}).single();
        if (totals[0].compareTo(totals[1]) == 0) {
            return List.of();
        }
        return List.of(new Issue("GLOBAL_BALANCE", "GLOBAL", null,
                "total debits %s differ from total credits %s".formatted(totals[0], totals[1])));
    }

    List<Issue> transferBalance() {
        return jdbc.sql(TRANSFER_BALANCE_SQL).param("cap", MAX_PER_CHECK)
                .query((rs, i) -> new Issue("TRANSFER_BALANCE", "TRANSFER", rs.getLong("transfer_id"),
                        "debits %s differ from credits %s".formatted(rs.getBigDecimal("debits"), rs.getBigDecimal("credits"))))
                .list();
    }

    List<Issue> accountBalance() {
        return jdbc.sql(ACCOUNT_BALANCE_SQL).param("cap", MAX_PER_CHECK)
                .query((rs, i) -> new Issue("ACCOUNT_BALANCE", "ACCOUNT", rs.getLong("id"),
                        "stored balance %s differs from ledger balance %s".formatted(rs.getBigDecimal("stored"), rs.getBigDecimal("computed"))))
                .list();
    }

    List<Issue> overdrafts() {
        return jdbc.sql(OVERDRAFT_SQL).param("cap", MAX_PER_CHECK)
                .query((rs, i) -> new Issue("OVERDRAFT", "ACCOUNT", rs.getLong("id"),
                        "wallet is negative: stored %s, ledger %s".formatted(rs.getBigDecimal("stored"), rs.getBigDecimal("computed"))))
                .list();
    }

    List<Issue> orphans() {
        List<Issue> issues = new ArrayList<>();
        issues.addAll(jdbc.sql(ORPHAN_ENTRY_TRANSFER_SQL).param("cap", MAX_PER_CHECK)
                .query((rs, i) -> new Issue("ORPHAN", "TRANSFER", rs.getLong("id"),
                        "ledger entries reference a transfer that does not exist")).list());
        issues.addAll(jdbc.sql(ORPHAN_ENTRY_ACCOUNT_SQL).param("cap", MAX_PER_CHECK)
                .query((rs, i) -> new Issue("ORPHAN", "ACCOUNT", rs.getLong("id"),
                        "ledger entries reference an account that does not exist")).list());
        issues.addAll(jdbc.sql(ORPHAN_TRANSFER_SQL).param("cap", MAX_PER_CHECK)
                .query((rs, i) -> new Issue("ORPHAN", "TRANSFER", rs.getLong("id"),
                        "completed transfer has no ledger entries")).list());
        return issues;
    }
}
