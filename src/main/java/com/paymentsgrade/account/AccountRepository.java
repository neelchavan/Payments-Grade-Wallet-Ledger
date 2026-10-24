package com.paymentsgrade.account;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    public record Wallet(long id, long userId, BigDecimal balance, String currency, OffsetDateTime createdAt) {
    }

    // Snapshot of a row locked FOR UPDATE.
    public record Locked(long id, String accountType, Long userId, BigDecimal balance) {
        public boolean isWallet() {
            return "USER_WALLET".equals(accountType);
        }
    }

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long systemAccountId(String code) {
        return jdbc.sql("SELECT id FROM accounts WHERE system_code = :c").param("c", code).query(Long.class).single();
    }

    public Wallet createWallet(long userId) {
        return jdbc.sql("""
                INSERT INTO accounts (account_type, user_id) VALUES ('USER_WALLET', :u)
                RETURNING id, user_id, balance, currency, created_at""")
                .param("u", userId).query(WALLET_MAPPER).single();
    }

    public List<Wallet> walletsOf(long userId) {
        return jdbc.sql("""
                SELECT id, user_id, balance, currency, created_at FROM accounts
                WHERE user_id = :u ORDER BY id""").param("u", userId).query(WALLET_MAPPER).list();
    }

    public Optional<Wallet> findOwnedWallet(long walletId, long userId) {
        return jdbc.sql("""
                SELECT id, user_id, balance, currency, created_at FROM accounts
                WHERE id = :id AND user_id = :u""").param("id", walletId).param("u", userId)
                .query(WALLET_MAPPER).optional();
    }

    // Ascending id order is the global lock order that prevents deadlocks.
    public Map<Long, Locked> lockForUpdate(Collection<Long> ids) {
        List<Locked> rows = jdbc.sql("""
                SELECT id, account_type, user_id, balance FROM accounts
                WHERE id IN (:ids) ORDER BY id FOR UPDATE""").param("ids", ids)
                .query((rs, i) -> new Locked(rs.getLong("id"), rs.getString("account_type"),
                        (Long) rs.getObject("user_id"), rs.getBigDecimal("balance")))
                .list();
        Map<Long, Locked> byId = new LinkedHashMap<>();
        rows.forEach(r -> byId.put(r.id(), r));
        return byId;
    }

    public void adjustBalance(long accountId, BigDecimal delta) {
        jdbc.sql("UPDATE accounts SET balance = balance + :d WHERE id = :id")
                .param("d", delta).param("id", accountId).update();
    }

    private static final org.springframework.jdbc.core.RowMapper<Wallet> WALLET_MAPPER = (rs, i) -> new Wallet(
            rs.getLong("id"), rs.getLong("user_id"), rs.getBigDecimal("balance"), rs.getString("currency"),
            rs.getObject("created_at", OffsetDateTime.class));
}
