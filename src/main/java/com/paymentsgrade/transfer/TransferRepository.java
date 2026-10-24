package com.paymentsgrade.transfer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TransferRepository {

    private static final String COLUMNS = """
            id, transfer_type, status, from_account_id, to_account_id, amount, note, reverses_transfer_id, created_at""";

    private static final RowMapper<Transfer> MAPPER = (rs, i) -> new Transfer(
            rs.getLong("id"),
            TransferType.valueOf(rs.getString("transfer_type")),
            TransferStatus.valueOf(rs.getString("status")),
            rs.getLong("from_account_id"),
            rs.getLong("to_account_id"),
            rs.getBigDecimal("amount"),
            rs.getString("note"),
            (Long) rs.getObject("reverses_transfer_id"),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    public TransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // Read by the status-history trigger; is_local=true scopes it to this transaction.
    public void setActor(String actor) {
        jdbc.sql("SELECT set_config('app.actor', :a, true)").param("a", actor).query().singleRow();
    }

    public Transfer insert(TransferType type, TransferStatus status, long fromId, long toId, BigDecimal amount,
            String note, Long reverses) {
        return jdbc.sql("""
                INSERT INTO transfers (transfer_type, status, from_account_id, to_account_id, amount, note, reverses_transfer_id)
                VALUES (:type, :status, :from, :to, :amount, :note, :reverses)
                RETURNING\s""" + COLUMNS)
                .param("type", type.name()).param("status", status.name()).param("from", fromId).param("to", toId)
                .param("amount", amount).param("note", note).param("reverses", reverses)
                .query(MAPPER).single();
    }

    public Optional<Transfer> lockById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM transfers WHERE id = :id FOR UPDATE")
                .param("id", id).query(MAPPER).optional();
    }

    public Optional<Transfer> findVisibleTo(long id, long userId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM transfers t
                WHERE t.id = :id AND EXISTS (
                    SELECT 1 FROM accounts a
                    WHERE a.user_id = :u AND a.id IN (t.from_account_id, t.to_account_id))""")
                .param("id", id).param("u", userId).query(MAPPER).optional();
    }

    public void updateStatus(long id, TransferStatus status) {
        jdbc.sql("UPDATE transfers SET status = :s WHERE id = :id").param("s", status.name()).param("id", id).update();
    }

    public Transfer findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM transfers WHERE id = :id").param("id", id).query(MAPPER).single();
    }

    public java.util.List<Transfer> findHeld(int limit, int offset) {
        return jdbc.sql("SELECT " + COLUMNS
                + " FROM transfers WHERE status = 'HELD' ORDER BY created_at, id LIMIT :l OFFSET :o")
                .param("l", limit).param("o", offset).query(MAPPER).list();
    }
}
