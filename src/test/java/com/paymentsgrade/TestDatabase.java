package com.paymentsgrade;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

// Reconciliation looks at the whole ledger, so its tests need an empty one. Needs a superuser, as in Testcontainers.
public final class TestDatabase {

    private TestDatabase() {
    }

    public static void setRulesEnabled(JdbcClient jdbc, boolean enabled) {
        jdbc.sql("UPDATE risk_rules SET enabled = :e").param("e", enabled).update();
    }

    // Back to the values seeded by V3, for tests that change thresholds.
    public static void restoreRiskRules(JdbcClient jdbc) {
        jdbc.sql("UPDATE risk_rules SET enabled = TRUE").update();
        jdbc.sql("UPDATE risk_rules SET params = '{\"max_count\": 5, \"window_minutes\": 10}' WHERE rule_code = 'VELOCITY'").update();
        jdbc.sql("UPDATE risk_rules SET params = '{\"multiplier\": 5, \"lookback_days\": 30, \"min_history\": 3}' WHERE rule_code = 'AMOUNT_SPIKE'").update();
        jdbc.sql("UPDATE risk_rules SET params = '{\"min_amount\": 10000}' WHERE rule_code = 'NEW_PAYEE_LARGE'").update();
    }

    public static void reset(JdbcClient jdbc, TransactionTemplate tx) {
        tx.executeWithoutResult(status -> {
            // Replica mode skips the append-only triggers that normally forbid TRUNCATE.
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql("""
                    TRUNCATE ledger_entries, transfer_status_history, risk_flags, reconciliation_issues,
                             reconciliation_runs, idempotency_keys, transfers""").update();
            jdbc.sql("DELETE FROM accounts WHERE account_type = 'USER_WALLET'").update();
            jdbc.sql("DELETE FROM users").update();
            jdbc.sql("UPDATE accounts SET balance = 0").update();
        });
    }
}
