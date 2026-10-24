package com.paymentsgrade.recon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsgrade.AbstractPostgresTest;
import com.paymentsgrade.TestDatabase;
import com.paymentsgrade.recon.ReconciliationRepository.IssueRow;
import com.paymentsgrade.seed.SeedService;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
class ReconciliationTest extends AbstractPostgresTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    SeedService seeder;

    @Autowired
    ReconciliationService service;

    @Autowired
    ReconciliationRepository repo;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @BeforeEach
    void emptyDatabase() {
        TestDatabase.reset(jdbc, tx);
    }

    private long scalar(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private static String pair(String check, Long id) {
        return check + ":" + id;
    }

    private Set<String> pairs(long runId) {
        Set<String> out = new HashSet<>();
        for (IssueRow issue : repo.issues(runId)) {
            out.add(pair(issue.checkName(), issue.entityId()));
        }
        return out;
    }

    @Test
    void cleanSeedHasTheRequestedRowCountsAndNoIssues() {
        var summary = seeder.seed(100, 200, 10_000);

        assertThat(summary.ledgerEntries()).isEqualTo(20_000);
        assertThat(scalar("SELECT count(*) FROM users")).isEqualTo(100);
        assertThat(scalar("SELECT count(*) FROM accounts WHERE account_type = 'USER_WALLET'")).isEqualTo(200);
        assertThat(scalar("SELECT count(*) FROM transfers")).isEqualTo(10_000);
        assertThat(scalar("SELECT count(*) FROM ledger_entries")).isEqualTo(20_000);
        assertThat(scalar("SELECT count(DISTINCT transfer_type) FROM transfers")).isEqualTo(3);

        long runId = service.runNow("MANUAL");

        var run = repo.findRun(runId).orElseThrow();
        assertThat(run.status()).isEqualTo("COMPLETED");
        assertThat(run.issueCount()).isZero();
        assertThat(repo.issues(runId)).isEmpty();
    }

    @Test
    void threeCorruptionsProduceExactlyTheExpectedIssues() throws Exception {
        seeder.seed(100, 200, 10_000);
        long peerTransfer = scalar("SELECT id FROM transfers WHERE transfer_type = 'PEER_TRANSFER' ORDER BY id LIMIT 1 OFFSET 500");
        long sender = scalar("SELECT from_account_id FROM transfers WHERE id = " + peerTransfer);
        long recipient = scalar("SELECT to_account_id FROM transfers WHERE id = " + peerTransfer);
        long tampered = scalar("SELECT id FROM accounts WHERE account_type = 'USER_WALLET' AND id NOT IN ("
                + sender + ", " + recipient + ") ORDER BY id LIMIT 1");
        long[] orphanTransfer = new long[1];

        tx.executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            // 1. A stored balance edited by hand.
            jdbc.sql("UPDATE accounts SET balance = balance + 100 WHERE id = :a").param("a", tampered).update();
            // 2. One ledger entry deleted (the append-only trigger is bypassed).
            jdbc.sql("DELETE FROM ledger_entries WHERE transfer_id = :t AND direction = 'DEBIT'")
                    .param("t", peerTransfer).update();
            // 3. A completed transfer with no ledger entries.
            orphanTransfer[0] = jdbc.sql("""
                    INSERT INTO transfers (transfer_type, status, from_account_id, to_account_id, amount)
                    VALUES ('PEER_TRANSFER', 'COMPLETED', :f, :t, 5) RETURNING id""")
                    .param("f", sender).param("t", recipient).query(Long.class).single();
        });

        MockHttpServletResponse started = mvc.perform(post("/admin/reconciliation/run")
                .header("X-Admin-Key", "dev-admin-key").header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn().getResponse();
        assertThat(started.getStatus()).isEqualTo(202);
        long runId = json.readTree(started.getContentAsString()).get("run_id").asLong();

        JsonNode run = awaitRun(runId);

        assertThat(run.get("status").asText()).isEqualTo("COMPLETED");
        Set<String> actual = new HashSet<>();
        run.get("issues").forEach(i -> actual.add(
                i.get("check_name").asText() + ":" + (i.get("entity_id").isNull() ? null : i.get("entity_id").asLong())));
        assertThat(actual).containsExactlyInAnyOrder(
                pair("ACCOUNT_BALANCE", tampered),
                pair("GLOBAL_BALANCE", null),
                pair("TRANSFER_BALANCE", peerTransfer),
                pair("ACCOUNT_BALANCE", sender),
                pair("ORPHAN", orphanTransfer[0]));
        assertThat(run.get("issue_count").asInt()).isEqualTo(5);
    }

    private JsonNode awaitRun(long runId) throws Exception {
        for (int i = 0; i < 100; i++) {
            MockHttpServletResponse r = mvc.perform(get("/admin/reconciliation/runs/" + runId)
                    .header("X-Admin-Key", "dev-admin-key")).andReturn().getResponse();
            JsonNode body = json.readTree(r.getContentAsString());
            if (!"RUNNING".equals(body.get("status").asText())) {
                return body;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("run " + runId + " did not finish");
    }

    @Test
    void overdrawnWalletIsReportedAsOverdraftAndBalanceMismatch() {
        seeder.seed(5, 10, 200);
        long wallet = scalar("SELECT id FROM accounts WHERE account_type = 'USER_WALLET' ORDER BY id LIMIT 1");
        long cashOut = scalar("SELECT id FROM accounts WHERE system_code = 'CASH_OUT'");
        // A balanced withdrawal bigger than the wallet holds, written without updating the stored balance.
        tx.executeWithoutResult(status -> {
            long t = jdbc.sql("""
                    INSERT INTO transfers (transfer_type, status, from_account_id, to_account_id, amount)
                    VALUES ('WITHDRAWAL', 'COMPLETED', :w, :c, 99999999) RETURNING id""")
                    .param("w", wallet).param("c", cashOut).query(Long.class).single();
            jdbc.sql("INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (:t, :a, 'DEBIT', 99999999)")
                    .param("t", t).param("a", wallet).update();
            jdbc.sql("INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (:t, :a, 'CREDIT', 99999999)")
                    .param("t", t).param("a", cashOut).update();
        });

        long runId = service.runNow("MANUAL");

        assertThat(pairs(runId)).containsExactlyInAnyOrder(
                pair("OVERDRAFT", wallet), pair("ACCOUNT_BALANCE", wallet), pair("ACCOUNT_BALANCE", cashOut));
    }

    @Test
    void ledgerEntryForAMissingTransferIsAnOrphan() {
        seeder.seed(5, 10, 200);
        long wallet = scalar("SELECT id FROM accounts WHERE account_type = 'USER_WALLET' ORDER BY id LIMIT 1");
        long missing = 987_654_321L;
        tx.executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql("INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (:t, :a, 'CREDIT', 10)")
                    .param("t", missing).param("a", wallet).update();
        });

        long runId = service.runNow("MANUAL");

        assertThat(pairs(runId)).containsExactlyInAnyOrder(
                pair("ORPHAN", missing), pair("TRANSFER_BALANCE", missing), pair("GLOBAL_BALANCE", null),
                pair("ACCOUNT_BALANCE", wallet));
    }

    @Test
    void heldTransfersWithoutEntriesAreNotOrphans() {
        seeder.seed(5, 10, 200);
        long from = scalar("SELECT id FROM accounts WHERE account_type = 'USER_WALLET' ORDER BY id LIMIT 1");
        long to = scalar("SELECT id FROM accounts WHERE account_type = 'USER_WALLET' ORDER BY id LIMIT 1 OFFSET 1");
        jdbc.sql("""
                INSERT INTO transfers (transfer_type, status, from_account_id, to_account_id, amount)
                VALUES ('PEER_TRANSFER', 'HELD', :f, :t, 5)""").param("f", from).param("t", to).update();

        assertThat(repo.issues(service.runNow("MANUAL"))).isEmpty();
    }

    @Test
    void runIsSkippedWhileAnotherRunHoldsTheLock() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            Long skippedRun = tx.execute(status -> {
                assertThat(repo.tryLock()).isTrue();
                Future<Long> other = executor.submit(() -> service.runNow("MANUAL"));
                try {
                    return other.get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            var run = repo.findRun(skippedRun).orElseThrow();
            assertThat(run.status()).isEqualTo("SKIPPED");
            assertThat(run.finishedAt()).isNotNull();
            assertThat(repo.issues(skippedRun)).isEmpty();
            assertThat(repo.findRun(service.runNow("MANUAL")).orElseThrow().status()).isEqualTo("COMPLETED");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void reportsButNeverFixesProblems() {
        seeder.seed(5, 10, 200);
        long wallet = scalar("SELECT id FROM accounts WHERE account_type = 'USER_WALLET' ORDER BY id LIMIT 1");
        jdbc.sql("UPDATE accounts SET balance = balance + 1 WHERE id = :a").param("a", wallet).update();
        long balanceBefore = scalar("SELECT balance::bigint FROM accounts WHERE id = " + wallet);

        long first = service.runNow("MANUAL");
        long second = service.runNow("MANUAL");

        assertThat(pairs(first)).containsExactly(pair("ACCOUNT_BALANCE", wallet));
        assertThat(pairs(second)).containsExactly(pair("ACCOUNT_BALANCE", wallet));
        assertThat(scalar("SELECT balance::bigint FROM accounts WHERE id = " + wallet)).isEqualTo(balanceBefore);
    }

    @Test
    void purgesIdempotencyKeysOlderThan24Hours() {
        jdbc.sql("""
                INSERT INTO idempotency_keys (key, request_hash, status, created_at)
                VALUES (:k, 'h', 'COMPLETED', now() - interval '25 hours')""").param("k", UUID.randomUUID()).update();
        UUID recent = UUID.randomUUID();
        jdbc.sql("INSERT INTO idempotency_keys (key, request_hash, status, created_at) VALUES (:k, 'h', 'COMPLETED', now() - interval '23 hours')")
                .param("k", recent).update();

        service.runNow("MANUAL");

        assertThat(scalar("SELECT count(*) FROM idempotency_keys")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM idempotency_keys WHERE key = '" + recent + "'")).isEqualTo(1);
    }

    @Test
    void adminEndpointsRequireTheKeyAndUnknownRunIs404() throws Exception {
        assertThat(mvc.perform(post("/admin/reconciliation/run").header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/admin/reconciliation/runs/1")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/admin/reconciliation/runs/999999999").header("X-Admin-Key", "dev-admin-key"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(post("/admin/reconciliation/run").header("X-Admin-Key", "dev-admin-key"))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void manualRunIsIdempotent() throws Exception {
        String key = UUID.randomUUID().toString();
        var first = mvc.perform(post("/admin/reconciliation/run").header("X-Admin-Key", "dev-admin-key")
                .header("Idempotency-Key", key)).andReturn().getResponse();
        var second = mvc.perform(post("/admin/reconciliation/run").header("X-Admin-Key", "dev-admin-key")
                .header("Idempotency-Key", key)).andReturn().getResponse();

        assertThat(second.getStatus()).isEqualTo(202);
        assertThat(second.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(second.getHeader("Idempotent-Replayed")).isEqualTo("true");
        awaitRun(json.readTree(first.getContentAsString()).get("run_id").asLong());
        assertThat(scalar("SELECT count(*) FROM reconciliation_runs")).isEqualTo(1);
    }
}
