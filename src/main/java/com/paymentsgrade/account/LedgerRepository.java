package com.paymentsgrade.account;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LedgerRepository {

    public record StatementLine(long entryId, long transferId, String direction, BigDecimal amount,
            String transferType, String transferStatus, String note, OffsetDateTime createdAt) {
    }

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertPair(long transferId, long fromAccountId, long toAccountId, BigDecimal amount) {
        insert(transferId, fromAccountId, "DEBIT", amount);
        insert(transferId, toAccountId, "CREDIT", amount);
    }

    private void insert(long transferId, long accountId, String direction, BigDecimal amount) {
        jdbc.sql("INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (:t, :a, :d, :m)")
                .param("t", transferId).param("a", accountId).param("d", direction).param("m", amount).update();
    }

    public List<StatementLine> statement(long accountId, int limit, int offset) {
        return jdbc.sql("""
                SELECT e.id, e.transfer_id, e.direction, e.amount, e.created_at,
                       t.transfer_type, t.status, t.note
                FROM ledger_entries e JOIN transfers t ON t.id = e.transfer_id
                WHERE e.account_id = :a
                ORDER BY e.created_at DESC, e.id DESC
                LIMIT :l OFFSET :o""")
                .param("a", accountId).param("l", limit).param("o", offset)
                .query((rs, i) -> new StatementLine(rs.getLong("id"), rs.getLong("transfer_id"),
                        rs.getString("direction"), rs.getBigDecimal("amount"), rs.getString("transfer_type"),
                        rs.getString("status"), rs.getString("note"), rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }
}
