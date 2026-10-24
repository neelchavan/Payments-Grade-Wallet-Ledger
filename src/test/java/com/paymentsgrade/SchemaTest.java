package com.paymentsgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

class SchemaTest extends AbstractPostgresTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    long newUser() {
        int n = SEQ.incrementAndGet();
        return jdbc.sql("INSERT INTO users (name, email, phone) VALUES (:n, :e, :p) RETURNING id")
                .param("n", "User " + n)
                .param("e", "user" + n + "@example.com")
                .param("p", String.format("+91%010d", n))
                .query(Long.class).single();
    }

    long newWallet(long userId, String balance) {
        return jdbc.sql("INSERT INTO accounts (account_type, user_id, balance) VALUES ('USER_WALLET', :u, :b) RETURNING id")
                .param("u", userId).param("b", new BigDecimal(balance))
                .query(Long.class).single();
    }

    long systemAccount(String code) {
        return jdbc.sql("SELECT id FROM accounts WHERE system_code = :c").param("c", code).query(Long.class).single();
    }

    long newTransfer(long from, long to, String amount, String type, String status) {
        return jdbc.sql("""
                INSERT INTO transfers (transfer_type, status, from_account_id, to_account_id, amount)
                VALUES (:t, :s, :f, :to, :a) RETURNING id""")
                .param("t", type).param("s", status).param("f", from).param("to", to)
                .param("a", new BigDecimal(amount)).query(Long.class).single();
    }

    void entry(long transferId, long accountId, String direction, String amount) {
        jdbc.sql("INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (:t, :a, :d, :m)")
                .param("t", transferId).param("a", accountId).param("d", direction)
                .param("m", new BigDecimal(amount)).update();
    }

    long committedTopup(long walletId, String amount) {
        return tx.execute(s -> {
            long cashIn = systemAccount("CASH_IN");
            long t = newTransfer(cashIn, walletId, amount, "TOPUP", "COMPLETED");
            entry(t, cashIn, "DEBIT", amount);
            entry(t, walletId, "CREDIT", amount);
            return t;
        });
    }

    @Test
    void migrationsSeedReferenceData() {
        List<String> systemCodes = jdbc.sql("SELECT system_code FROM accounts WHERE system_code IS NOT NULL ORDER BY 1")
                .query(String.class).list();
        assertThat(systemCodes).containsExactly("CASH_IN", "CASH_OUT", "FEE_REVENUE");
        assertThat(jdbc.sql("SELECT count(*) FROM job_locks WHERE name = 'reconciliation'").query(Long.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT rule_code FROM risk_rules ORDER BY 1").query(String.class).list())
                .containsExactly("AMOUNT_SPIKE", "NEW_PAYEE_LARGE", "VELOCITY");
        assertThat(jdbc.sql("SELECT (params->>'max_count')::int FROM risk_rules WHERE rule_code = 'VELOCITY'")
                .query(Integer.class).single()).isEqualTo(5);
    }

    @Test
    void ledgerEntriesCannotBeUpdatedDeletedOrTruncated() {
        long wallet = newWallet(newUser(), "0");
        long t = committedTopup(wallet, "100.00");

        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger_entries SET amount = 1 WHERE transfer_id = :t")
                .param("t", t).update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM ledger_entries WHERE transfer_id = :t")
                .param("t", t).update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE ledger_entries CASCADE").update())
                .hasMessageContaining("append-only");
    }

    @Test
    void statusHistoryCannotBeModified() {
        long wallet = newWallet(newUser(), "0");
        long t = committedTopup(wallet, "10.00");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM transfer_status_history WHERE transfer_id = :t")
                .param("t", t).update()).hasMessageContaining("append-only");
    }

    @Test
    void walletBalanceCannotGoNegativeButSystemAccountsCan() {
        long wallet = newWallet(newUser(), "5.00");
        assertThatThrownBy(() -> jdbc.sql("UPDATE accounts SET balance = -0.01 WHERE id = :id")
                .param("id", wallet).update()).isInstanceOf(DataIntegrityViolationException.class);

        long cashIn = systemAccount("CASH_IN");
        assertThat(jdbc.sql("UPDATE accounts SET balance = balance - 1000 WHERE id = :id")
                .param("id", cashIn).update()).isEqualTo(1);
        jdbc.sql("UPDATE accounts SET balance = balance + 1000 WHERE id = :id").param("id", cashIn).update();
    }

    @Test
    void unbalancedTransferFailsAtCommit() {
        long wallet = newWallet(newUser(), "0");
        long cashIn = systemAccount("CASH_IN");

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            long t = newTransfer(cashIn, wallet, "50.00", "TOPUP", "COMPLETED");
            entry(t, cashIn, "DEBIT", "50.00");
            entry(t, wallet, "CREDIT", "49.00");
        })).hasMessageContaining("unbalanced");
    }

    @Test
    void balancedTransferCommits() {
        long wallet = newWallet(newUser(), "0");
        long t = committedTopup(wallet, "50.00");
        assertThat(jdbc.sql("SELECT count(*) FROM ledger_entries WHERE transfer_id = :t").param("t", t)
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void statusChangesAreAuditedWithActor() {
        long wallet = newWallet(newUser(), "0");
        long cashIn = systemAccount("CASH_IN");
        long t = newTransfer(cashIn, wallet, "20.00", "TOPUP", "HELD");

        tx.executeWithoutResult(s -> {
            jdbc.sql("SELECT set_config('app.actor', 'admin', true)").query().singleRow();
            jdbc.sql("UPDATE transfers SET status = 'REJECTED' WHERE id = :t").param("t", t).update();
        });

        List<String> history = jdbc.sql("""
                SELECT coalesce(old_status, '-') || '>' || new_status || '@' || actor
                FROM transfer_status_history WHERE transfer_id = :t ORDER BY id""")
                .param("t", t).query(String.class).list();
        assertThat(history).containsExactly("->HELD@system", "HELD>REJECTED@admin");
    }

    @Test
    void secondReversalOfSameTransferIsRejected() {
        long wallet = newWallet(newUser(), "0");
        long cashIn = systemAccount("CASH_IN");
        long original = committedTopup(wallet, "30.00");

        jdbc.sql("""
                INSERT INTO transfers (transfer_type, status, from_account_id, to_account_id, amount, reverses_transfer_id)
                VALUES ('REVERSAL', 'HELD', :f, :t, 30, :r)""")
                .param("f", wallet).param("t", cashIn).param("r", original).update();

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO transfers (transfer_type, status, from_account_id, to_account_id, amount, reverses_transfer_id)
                VALUES ('REVERSAL', 'HELD', :f, :t, 30, :r)""")
                .param("f", wallet).param("t", cashIn).param("r", original).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void idempotencyKeysAreScopedPerUserIncludingAnonymous() {
        UUID key = UUID.randomUUID();
        long u1 = newUser();
        long u2 = newUser();

        insertKey(null, key);
        assertThatThrownBy(() -> insertKey(null, key)).isInstanceOf(DataIntegrityViolationException.class);

        insertKey(u1, key);
        insertKey(u2, key);
        assertThatThrownBy(() -> insertKey(u1, key)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertKey(Long userId, UUID key) {
        jdbc.sql("INSERT INTO idempotency_keys (user_id, key, request_hash) VALUES (:u, :k, 'h')")
                .param("u", userId).param("k", key).update();
    }

    @Test
    void emailIsUniqueCaseInsensitivelyAndPhoneIsUnique() {
        jdbc.sql("INSERT INTO users (name, email, phone) VALUES ('A', 'Dup@Example.com', '+911111111111')").update();
        assertThatThrownBy(() -> jdbc.sql(
                "INSERT INTO users (name, email, phone) VALUES ('B', 'dup@example.com', '+912222222222')").update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql(
                "INSERT INTO users (name, email, phone) VALUES ('C', 'c@example.com', '+911111111111')").update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void transferConstraintsAreEnforced() {
        long wallet = newWallet(newUser(), "0");
        long other = newWallet(newUser(), "0");

        assertThatThrownBy(() -> newTransfer(wallet, other, "0.00", "PEER_TRANSFER", "COMPLETED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> newTransfer(wallet, wallet, "1.00", "PEER_TRANSFER", "COMPLETED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO transfers (transfer_type, status, from_account_id, to_account_id, amount, note)
                VALUES ('PEER_TRANSFER', 'COMPLETED', :f, :t, 1, :n)""")
                .param("f", wallet).param("t", other).param("n", "x".repeat(201)).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> newTransfer(wallet, other, "1.00", "PEER_TRANSFER", "BOGUS"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void accountOwnershipRulesAreEnforced() {
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO accounts (account_type) VALUES ('USER_WALLET')").update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql(
                "INSERT INTO accounts (account_type, user_id, system_code) VALUES ('CASH_IN', :u, 'X')")
                .param("u", newUser()).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO accounts (account_type, system_code) VALUES ('CASH_IN', 'CASH_IN')")
                .update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO accounts (account_type, system_code, currency) VALUES ('CASH_OUT', 'Y', 'USD')")
                .update()).isInstanceOf(DataIntegrityViolationException.class);
    }
}
