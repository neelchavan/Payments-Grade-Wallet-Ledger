package com.paymentsgrade.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsgrade.TransferTestSupport;
import com.paymentsgrade.idempotency.IdempotencyService.Result;
import com.paymentsgrade.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class IdempotencyTest extends TransferTestSupport {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    IdempotencyService idempotency;

    private MockHttpServletResponse send(String url, Long userId, String key, String body) throws Exception {
        var req = post(url).contentType(MediaType.APPLICATION_JSON).content(body);
        if (userId != null) {
            req.header("X-User-Id", userId);
        }
        if (key != null) {
            req.header("Idempotency-Key", key);
        }
        return mvc.perform(req).andReturn().getResponse();
    }

    private MockHttpServletResponse topup(Party p, String key, String amount) throws Exception {
        return send("/transfers/topup", p.userId(), key, "{\"wallet_id\":%d,\"amount\":%s}".formatted(p.walletId(), amount));
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private long transferCount(Party p) {
        return jdbc.sql("SELECT count(*) FROM transfers WHERE to_account_id = :a OR from_account_id = :a")
                .param("a", p.walletId()).query(Long.class).single();
    }

    @Test
    void missingOrMalformedKeyIs400() throws Exception {
        Party p = newParty();
        assertThat(topup(p, null, "10").getStatus()).isEqualTo(400);
        MockHttpServletResponse bad = topup(p, "not-a-uuid", "10");
        assertThat(bad.getStatus()).isEqualTo(400);
        assertThat(json.readTree(bad.getContentAsString()).get("code").asText()).isEqualTo("INVALID_IDEMPOTENCY_KEY");
        assertThat(topup(p, "   ", "10").getStatus()).isEqualTo(400);
        assertThat(storedBalance(p.walletId())).isEqualByComparingTo("0");
    }

    @Test
    void sameRequestFiveTimesChargesOnce() throws Exception {
        Party a = newParty();
        Party b = newParty();
        topup(a, newKey(), "100");
        String key = newKey();
        String body = "{\"from_wallet_id\":%d,\"to_wallet_id\":%d,\"amount\":30,\"note\":\"once\"}"
                .formatted(a.walletId(), b.walletId());

        List<MockHttpServletResponse> responses = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            responses.add(send("/transfers/peer", a.userId(), key, body));
        }

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(201));
        assertThat(responses).extracting(r -> r.getContentAsString()).containsOnly(responses.get(0).getContentAsString());
        assertThat(responses.get(0).getHeader("Idempotent-Replayed")).isNull();
        assertThat(responses.subList(1, 5)).allSatisfy(r -> assertThat(r.getHeader("Idempotent-Replayed")).isEqualTo("true"));
        assertThat(storedBalance(a.walletId())).isEqualByComparingTo("70");
        assertThat(storedBalance(b.walletId())).isEqualByComparingTo("30");
        assertThat(transferCount(b)).isEqualTo(1);
    }

    @Test
    void sameKeyTenTimesInParallelRunsOnce() throws Exception {
        Party a = newParty();
        Party b = newParty();
        topup(a, newKey(), "100");
        String key = newKey();
        String body = "{\"from_wallet_id\":%d,\"to_wallet_id\":%d,\"amount\":10}".formatted(a.walletId(), b.walletId());

        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return send("/transfers/peer", a.userId(), key, body);
                }));
            }
            ready.await();
            go.countDown();
            List<MockHttpServletResponse> responses = new ArrayList<>();
            for (Future<MockHttpServletResponse> f : futures) {
                responses.add(f.get());
            }

            assertThat(responses).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(201));
            assertThat(responses).extracting(r -> r.getContentAsString()).containsOnly(responses.get(0).getContentAsString());
            assertThat(responses.stream().filter(r -> r.getHeader("Idempotent-Replayed") == null).count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(storedBalance(a.walletId())).isEqualByComparingTo("90");
        assertThat(storedBalance(b.walletId())).isEqualByComparingTo("10");
        assertThat(transferCount(b)).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentAmountIs422() throws Exception {
        Party p = newParty();
        String key = newKey();
        assertThat(topup(p, key, "50").getStatus()).isEqualTo(201);

        MockHttpServletResponse reused = topup(p, key, "60");

        assertThat(reused.getStatus()).isEqualTo(422);
        assertThat(json.readTree(reused.getContentAsString()).get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(storedBalance(p.walletId())).isEqualByComparingTo("50");
    }

    @Test
    void sameKeyOnADifferentEndpointWithTheSameBodyIs422() throws Exception {
        Party p = newParty();
        topup(p, newKey(), "100");
        String key = newKey();
        String body = "{\"wallet_id\":%d,\"amount\":10}".formatted(p.walletId());
        assertThat(send("/transfers/topup", p.userId(), key, body).getStatus()).isEqualTo(201);

        MockHttpServletResponse other = send("/transfers/withdrawal", p.userId(), key, body);

        assertThat(other.getStatus()).isEqualTo(422);
        assertThat(storedBalance(p.walletId())).isEqualByComparingTo("110");
    }

    @Test
    void keyOrderWhitespaceAndTrailingZerosDoNotChangeTheHash() throws Exception {
        Party p = newParty();
        String key = newKey();
        assertThat(send("/transfers/topup", p.userId(), key,
                "{\"wallet_id\":%d,\"amount\":10,\"note\":null}".formatted(p.walletId())).getStatus()).isEqualTo(201);

        MockHttpServletResponse replay = send("/transfers/topup", p.userId(), key,
                "{ \"amount\": 10.00,\n  \"wallet_id\": %d }".formatted(p.walletId()));

        assertThat(replay.getStatus()).isEqualTo(201);
        assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(storedBalance(p.walletId())).isEqualByComparingTo("10");
    }

    @Test
    void businessErrorIsSavedAndReplayedEvenAfterConditionsChange() throws Exception {
        Party p = newParty();
        String key = newKey();
        MockHttpServletResponse first = send("/transfers/withdrawal", p.userId(), key,
                "{\"wallet_id\":%d,\"amount\":25}".formatted(p.walletId()));
        assertThat(first.getStatus()).isEqualTo(422);
        assertThat(transferCount(p)).isZero();

        topup(p, newKey(), "100");
        MockHttpServletResponse second = send("/transfers/withdrawal", p.userId(), key,
                "{\"wallet_id\":%d,\"amount\":25}".formatted(p.walletId()));

        assertThat(second.getStatus()).isEqualTo(422);
        assertThat(second.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(second.getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(storedBalance(p.walletId())).isEqualByComparingTo("100");
    }

    @Test
    void unexpectedErrorRollsBackTheKeySoTheClientCanRetry() {
        UUID key = UUID.randomUUID();

        assertThatThrownBy(() -> idempotency.execute(null, key.toString(), "POST /probe", null, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.sql("SELECT count(*) FROM idempotency_keys WHERE key = :k").param("k", key)
                .query(Long.class).single()).isZero();
        var retry = idempotency.execute(null, key.toString(), "POST /probe", null, () -> Result.created("ok"));
        assertThat(retry.getStatusCode().value()).isEqualTo(201);
        assertThat(retry.getBody()).isEqualTo("\"ok\"");
    }

    @Test
    void failedHandlerWritesInsideTheSavepointAreUndone() throws Exception {
        Party p = newParty();
        long before = count("transfers");

        MockHttpServletResponse r = send("/transfers/peer", p.userId(), newKey(),
                "{\"from_wallet_id\":%d,\"to_wallet_id\":%d,\"amount\":5}".formatted(p.walletId(), p.walletId()));

        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(count("transfers")).isEqualTo(before);
    }

    @Test
    void sameKeyCanBeUsedByDifferentUsers() throws Exception {
        Party a = newParty();
        Party b = newParty();
        String key = newKey();

        assertThat(topup(a, key, "10").getStatus()).isEqualTo(201);
        MockHttpServletResponse other = topup(b, key, "10");

        assertThat(other.getStatus()).isEqualTo(201);
        assertThat(other.getHeader("Idempotent-Replayed")).isNull();
        assertThat(storedBalance(a.walletId())).isEqualByComparingTo("10");
        assertThat(storedBalance(b.walletId())).isEqualByComparingTo("10");
    }

    @Test
    void invalidBodyDoesNotConsumeTheKey() throws Exception {
        Party p = newParty();
        String key = newKey();
        assertThat(topup(p, key, "-5").getStatus()).isEqualTo(400);

        MockHttpServletResponse ok = topup(p, key, "5");

        assertThat(ok.getStatus()).isEqualTo(201);
        assertThat(ok.getHeader("Idempotent-Replayed")).isNull();
    }

    @Test
    void createUserIsIdempotentWithoutAUser() throws Exception {
        String key = newKey();
        long n = System.nanoTime();
        String body = "{\"name\":\"Idem\",\"email\":\"idem%d@example.com\",\"phone\":\"+9181%08d\"}".formatted(n, n % 100_000_000);

        MockHttpServletResponse first = send("/users", null, key, body);
        MockHttpServletResponse second = send("/users", null, key, body);

        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(second.getStatus()).isEqualTo(201);
        assertThat(second.getContentAsString()).isEqualTo(first.getContentAsString());
        JsonNode user = json.readTree(first.getContentAsString());
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE id = :i").param("i", user.get("id").asLong())
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void keyStillInProgressIs409() throws Exception {
        UUID key = UUID.randomUUID();
        jdbc.sql("INSERT INTO idempotency_keys (user_id, key, request_hash, status) VALUES (NULL, :k, 'x', 'IN_PROGRESS')")
                .param("k", key).update();

        assertThatThrownBy(() -> idempotency.execute(null, key.toString(), "POST /probe", null, () -> Result.created("x")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_IN_PROGRESS"));
    }

    @Test
    void unknownUserIsRejectedBeforeAnyWork() throws Exception {
        MockHttpServletResponse r = send("/transfers/topup", 999_999_999L, newKey(), "{\"wallet_id\":1,\"amount\":5}");
        assertThat(r.getStatus()).isEqualTo(404);
    }
}
