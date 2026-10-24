package com.paymentsgrade.risk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsgrade.transfer.TransferType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Applies the fraud rules in risk_rules. Callers must already hold the account locks. */
@Service
public class RiskService {

    public record Hit(String ruleCode, String action, String detail) {
        public boolean isHold() {
            return "HOLD".equals(action);
        }
    }

    public record Flag(long transferId, String ruleCode, String action, String detail, String decision,
            OffsetDateTime createdAt) {
    }

    private record Rule(boolean enabled, String action, JsonNode params) {
    }

    private static final String OUTGOING = "transfer_type IN ('PEER_TRANSFER', 'WITHDRAWAL')";

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public RiskService(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public static boolean applies(TransferType type) {
        return type == TransferType.PEER_TRANSFER || type == TransferType.WITHDRAWAL;
    }

    public List<Hit> evaluate(long fromAccountId, long toAccountId, TransferType type, BigDecimal amount) {
        Map<String, Rule> rules = loadRules();
        List<Hit> hits = new ArrayList<>();
        velocity(rules.get("VELOCITY"), fromAccountId).ifPresent(hits::add);
        amountSpike(rules.get("AMOUNT_SPIKE"), fromAccountId, amount).ifPresent(hits::add);
        if (type == TransferType.PEER_TRANSFER) {
            newPayee(rules.get("NEW_PAYEE_LARGE"), fromAccountId, toAccountId, amount).ifPresent(hits::add);
        }
        return hits;
    }

    private java.util.Optional<Hit> velocity(Rule rule, long from) {
        if (!active(rule)) {
            return java.util.Optional.empty();
        }
        int max = rule.params().path("max_count").asInt(5);
        int windowMinutes = rule.params().path("window_minutes").asInt(10);
        long recent = jdbc.sql("""
                SELECT count(*) FROM transfers
                WHERE from_account_id = :f AND %s AND status IN ('COMPLETED', 'HELD', 'REVERSED')
                  AND created_at >= now() - make_interval(mins => :w)""".formatted(OUTGOING))
                .param("f", from).param("w", windowMinutes).query(Long.class).single();
        return recent >= max
                ? java.util.Optional.of(new Hit("VELOCITY", rule.action(),
                        "more than %d transfers in %d minutes".formatted(max, windowMinutes)))
                : java.util.Optional.empty();
    }

    private java.util.Optional<Hit> amountSpike(Rule rule, long from, BigDecimal amount) {
        if (!active(rule)) {
            return java.util.Optional.empty();
        }
        BigDecimal multiplier = rule.params().path("multiplier").decimalValue();
        if (multiplier.signum() == 0) {
            multiplier = BigDecimal.valueOf(5);
        }
        int lookbackDays = rule.params().path("lookback_days").asInt(30);
        int minHistory = rule.params().path("min_history").asInt(3);
        var history = jdbc.sql("""
                SELECT count(*) AS n, COALESCE(avg(amount), 0) AS average FROM transfers
                WHERE from_account_id = :f AND %s AND status = 'COMPLETED'
                  AND created_at >= now() - make_interval(days => :d)""".formatted(OUTGOING))
                .param("f", from).param("d", lookbackDays)
                .query((rs, i) -> new Object[] {rs.getLong("n"), rs.getBigDecimal("average")}).single();
        long n = (Long) history[0];
        BigDecimal average = (BigDecimal) history[1];
        if (n >= minHistory && amount.compareTo(average.multiply(multiplier)) > 0) {
            return java.util.Optional.of(new Hit("AMOUNT_SPIKE", rule.action(),
                    "amount %s is more than %s times the %d-day average of %s".formatted(
                            amount, multiplier.stripTrailingZeros().toPlainString(), lookbackDays,
                            average.setScale(2, java.math.RoundingMode.HALF_UP))));
        }
        return java.util.Optional.empty();
    }

    private java.util.Optional<Hit> newPayee(Rule rule, long from, long to, BigDecimal amount) {
        if (!active(rule)) {
            return java.util.Optional.empty();
        }
        BigDecimal minAmount = rule.params().path("min_amount").decimalValue();
        if (minAmount.signum() == 0) {
            minAmount = BigDecimal.valueOf(10_000);
        }
        if (amount.compareTo(minAmount) <= 0) {
            return java.util.Optional.empty();
        }
        boolean knownPayee = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM transfers
                               WHERE from_account_id = :f AND to_account_id = :t AND status = 'COMPLETED')""")
                .param("f", from).param("t", to).query(Boolean.class).single();
        return knownPayee ? java.util.Optional.empty()
                : java.util.Optional.of(new Hit("NEW_PAYEE_LARGE", rule.action(),
                        "first transfer to this payee and the amount exceeds %s".formatted(minAmount.stripTrailingZeros().toPlainString())));
    }

    private static boolean active(Rule rule) {
        return rule != null && rule.enabled();
    }

    private Map<String, Rule> loadRules() {
        return jdbc.sql("SELECT rule_code, enabled, action, params::text AS params FROM risk_rules")
                .query((rs, i) -> Map.entry(rs.getString("rule_code"),
                        new Rule(rs.getBoolean("enabled"), rs.getString("action"), parse(rs.getString("params")))))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid params in risk_rules", e);
        }
    }

    public void recordHits(long transferId, List<Hit> hits) {
        for (Hit hit : hits) {
            jdbc.sql("INSERT INTO risk_flags (transfer_id, rule_code, action, detail) VALUES (:t, :r, :a, :d)")
                    .param("t", transferId).param("r", hit.ruleCode()).param("a", hit.action())
                    .param("d", hit.detail()).update();
        }
    }

    public void recordDecision(long transferId, String decision) {
        jdbc.sql("""
                UPDATE risk_flags SET decision = :d, decided_by = 'admin', decided_at = clock_timestamp()
                WHERE transfer_id = :t""").param("d", decision).param("t", transferId).update();
    }

    public List<Flag> flagsFor(List<Long> transferIds) {
        if (transferIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT transfer_id, rule_code, action, detail, decision, created_at FROM risk_flags
                WHERE transfer_id IN (:ids) ORDER BY id""").param("ids", transferIds)
                .query((rs, i) -> new Flag(rs.getLong("transfer_id"), rs.getString("rule_code"), rs.getString("action"),
                        rs.getString("detail"), rs.getString("decision"), rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }
}
