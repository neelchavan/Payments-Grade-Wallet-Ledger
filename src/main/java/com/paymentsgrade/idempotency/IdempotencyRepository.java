package com.paymentsgrade.idempotency;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {

    public record Row(long id, String requestHash, String status, Integer responseStatus, String responseBody) {
    }

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // Blocks while another transaction holds the same uncommitted key, then returns empty.
    public Optional<Long> claim(Long userId, UUID key, String requestHash) {
        return jdbc.sql("""
                INSERT INTO idempotency_keys (user_id, key, request_hash) VALUES (:u, :k, :h)
                ON CONFLICT DO NOTHING RETURNING id""")
                .param("u", userId).param("k", key).param("h", requestHash)
                .query(Long.class).optional();
    }

    public Row find(Long userId, UUID key) {
        return jdbc.sql("""
                SELECT id, request_hash, status, response_status, response_body FROM idempotency_keys
                WHERE COALESCE(user_id, 0) = COALESCE(:u, 0) AND key = :k""")
                .param("u", userId).param("k", key)
                .query((rs, i) -> new Row(rs.getLong("id"), rs.getString("request_hash"), rs.getString("status"),
                        (Integer) rs.getObject("response_status"), rs.getString("response_body")))
                .single();
    }

    public void complete(long id, int responseStatus, String responseBody) {
        jdbc.sql("""
                UPDATE idempotency_keys
                SET status = 'COMPLETED', response_status = :s, response_body = :b, completed_at = clock_timestamp()
                WHERE id = :id""")
                .param("s", responseStatus).param("b", responseBody).param("id", id).update();
    }
}
