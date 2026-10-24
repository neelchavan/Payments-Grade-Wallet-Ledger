package com.paymentsgrade;

import com.paymentsgrade.account.AccountRepository;
import com.paymentsgrade.user.User;
import com.paymentsgrade.user.UserRepository;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

public abstract class TransferTestSupport extends AbstractPostgresTest {

    private static final AtomicInteger SEQ = new AtomicInteger(100_000);

    public record Party(long userId, long walletId) {
    }

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected UserRepository users;

    @Autowired
    protected AccountRepository accounts;

    protected Party newParty() {
        int n = SEQ.incrementAndGet();
        User user = users.insert("Party " + n, "party" + n + "@example.com", String.format("+9199%08d", n));
        return new Party(user.id(), accounts.createWallet(user.id()).id());
    }

    protected BigDecimal storedBalance(long accountId) {
        return jdbc.sql("SELECT balance FROM accounts WHERE id = :a").param("a", accountId)
                .query(BigDecimal.class).single();
    }

    // Credits minus debits, independent of the stored balance column.
    protected BigDecimal ledgerBalance(long accountId) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0)
                FROM ledger_entries WHERE account_id = :a""").param("a", accountId)
                .query(BigDecimal.class).single();
    }

    protected long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    protected static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
