package com.paymentsgrade.qa;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Every query is parameterized and limited to wallets owned by the caller. */
@Repository
public class QaRepository {

    public record Answer(BigDecimal value, long count, List<Long> transferIds) {
        static Answer none() {
            return new Answer(BigDecimal.ZERO, 0, List.of());
        }
    }

    static final int MAX_IDS = 100;

    private static final String MY_WALLETS = "(SELECT id FROM accounts WHERE user_id = :u)";
    private static final String IN_RANGE = "t.created_at >= :from AND t.created_at < :to";
    private static final String OUTGOING = "t.from_account_id IN " + MY_WALLETS
            + " AND t.transfer_type IN ('PEER_TRANSFER', 'WITHDRAWAL') AND t.status = 'COMPLETED' AND " + IN_RANGE;
    private static final String INVOLVING_ME = "(t.from_account_id IN " + MY_WALLETS + " OR t.to_account_id IN "
            + MY_WALLETS + ") AND t.transfer_type <> 'REVERSAL' AND t.status = 'COMPLETED' AND " + IN_RANGE;

    private final JdbcClient jdbc;

    public QaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Answer totalSpent(long userId, OffsetDateTime from, OffsetDateTime to) {
        return sum(OUTGOING, params(userId, from, to));
    }

    public Answer sentTo(long userId, List<Long> counterpartyUserIds, OffsetDateTime from, OffsetDateTime to) {
        if (counterpartyUserIds.isEmpty()) {
            return Answer.none();
        }
        Map<String, Object> params = params(userId, from, to);
        params.put("cp", counterpartyUserIds);
        return sum(OUTGOING + " AND t.transfer_type = 'PEER_TRANSFER'"
                + " AND t.to_account_id IN (SELECT id FROM accounts WHERE user_id IN (:cp))", params);
    }

    public Answer transferCount(long userId, OffsetDateTime from, OffsetDateTime to) {
        Map<String, Object> params = params(userId, from, to);
        Answer sum = sum(INVOLVING_ME, params);
        return new Answer(BigDecimal.valueOf(sum.count()), sum.count(), sum.transferIds());
    }

    public Answer largest(long userId, OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("SELECT t.id, t.amount FROM transfers t WHERE " + OUTGOING
                + " ORDER BY t.amount DESC, t.id DESC LIMIT 1")
                .params(params(userId, from, to))
                .query((rs, i) -> new Answer(rs.getBigDecimal("amount"), 1, List.of(rs.getLong("id"))))
                .optional().orElse(Answer.none());
    }

    // Only counterparties the caller has actually paid can be resolved, so names of other users never leak.
    public List<Long> resolveCounterparties(long userId, String name) {
        return jdbc.sql("""
                SELECT DISTINCT u.id FROM transfers t
                JOIN accounts a ON a.id = t.to_account_id
                JOIN users u ON u.id = a.user_id
                WHERE t.from_account_id IN %s AND t.transfer_type = 'PEER_TRANSFER' AND t.status = 'COMPLETED'
                  AND (lower(u.name) = lower(:n) OR lower(split_part(u.name, ' ', 1)) = lower(:n))
                ORDER BY u.id""".formatted(MY_WALLETS))
                .param("u", userId).param("n", name).query(Long.class).list();
    }

    private Answer sum(String where, Map<String, Object> params) {
        var totals = jdbc.sql("SELECT COALESCE(SUM(t.amount), 0) AS total, COUNT(*) AS n FROM transfers t WHERE " + where)
                .params(params)
                .query((rs, i) -> new Object[] {rs.getBigDecimal("total"), rs.getLong("n")}).single();
        List<Long> ids = jdbc.sql("SELECT t.id FROM transfers t WHERE " + where
                + " ORDER BY t.created_at DESC, t.id DESC LIMIT " + MAX_IDS)
                .params(params).query(Long.class).list();
        return new Answer((BigDecimal) totals[0], (Long) totals[1], ids);
    }

    private static Map<String, Object> params(long userId, OffsetDateTime from, OffsetDateTime to) {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("u", userId);
        params.put("from", from);
        params.put("to", to);
        return params;
    }
}
