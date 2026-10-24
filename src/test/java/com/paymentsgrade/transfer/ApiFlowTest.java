package com.paymentsgrade.transfer;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paymentsgrade.TransferTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ApiFlowTest extends TransferTestSupport {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    private static String key() {
        return java.util.UUID.randomUUID().toString();
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String url, Long userId, String content) throws Exception {
        var req = post(url).contentType(MediaType.APPLICATION_JSON).content(content).header("Idempotency-Key", key());
        if (userId != null) {
            req.header("X-User-Id", userId);
        }
        return mvc.perform(req);
    }

    @Test
    void endToEndHappyPath() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        long aliceId = body(postJson("/users", null, """
                {"name":"Alice","email":"alice%s@example.com","phone":"+9170%08d"}""".formatted(suffix, System.nanoTime() % 100_000_000))
                .andExpect(status().isCreated())).get("id").asLong();
        long bobId = body(postJson("/users", null, """
                {"name":"Bob","email":"bob%s@example.com","phone":"+9171%08d"}""".formatted(suffix, System.nanoTime() % 100_000_000))
                .andExpect(status().isCreated())).get("id").asLong();

        long aliceWallet = body(mvc.perform(post("/users/" + aliceId + "/wallets").header("X-User-Id", aliceId).header("Idempotency-Key", key()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.balance").value(0))).get("id").asLong();
        long bobWallet = body(mvc.perform(post("/users/" + bobId + "/wallets").header("X-User-Id", bobId).header("Idempotency-Key", key()))
                .andExpect(status().isCreated())).get("id").asLong();

        postJson("/transfers/topup", aliceId, "{\"wallet_id\":%d,\"amount\":500.00,\"note\":\"initial\"}".formatted(aliceWallet))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transfer_type").value("TOPUP"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        long transferId = body(postJson("/transfers/peer", aliceId,
                "{\"from_wallet_id\":%d,\"to_wallet_id\":%d,\"amount\":120.75,\"note\":\"rent\"}".formatted(aliceWallet, bobWallet))
                .andExpect(status().isCreated())).get("id").asLong();

        mvc.perform(get("/wallets/" + aliceWallet + "/balance").header("X-User-Id", aliceId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(379.25))
                .andExpect(jsonPath("$.currency").value("INR"));
        mvc.perform(get("/wallets/" + bobWallet + "/balance").header("X-User-Id", bobId))
                .andExpect(jsonPath("$.balance").value(120.75));

        mvc.perform(get("/wallets/" + aliceWallet + "/statement").header("X-User-Id", aliceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].transfer_type").value("PEER_TRANSFER"))
                .andExpect(jsonPath("$.items[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.items[0].note").value("rent"))
                .andExpect(jsonPath("$.items[1].transfer_type").value("TOPUP"));
        mvc.perform(get("/wallets/" + aliceWallet + "/statement?limit=1&offset=1").header("X-User-Id", aliceId))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].transfer_type").value("TOPUP"));

        mvc.perform(get("/transfers/" + transferId).header("X-User-Id", bobId)).andExpect(status().isOk());
        mvc.perform(get("/users/" + aliceId + "/wallets").header("X-User-Id", aliceId))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void insufficientFundsIs422() throws Exception {
        Party a = newParty();
        Party b = newParty();
        postJson("/transfers/peer", a.userId(),
                "{\"from_wallet_id\":%d,\"to_wallet_id\":%d,\"amount\":1}".formatted(a.walletId(), b.walletId()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.request_id").exists());
    }

    @Test
    void otherUsersCannotSeeOrUseAWallet() throws Exception {
        Party a = newParty();
        Party mallory = newParty();
        mvc.perform(get("/wallets/" + a.walletId() + "/balance").header("X-User-Id", mallory.userId()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/wallets/" + a.walletId() + "/statement").header("X-User-Id", mallory.userId()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/users/" + a.userId() + "/wallets").header("X-User-Id", mallory.userId()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/users/" + a.userId() + "/wallets").header("X-User-Id", mallory.userId()).header("Idempotency-Key", key()))
                .andExpect(status().isNotFound());
        postJson("/transfers/withdrawal", mallory.userId(), "{\"wallet_id\":%d,\"amount\":1}".formatted(a.walletId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingUserHeaderIs400() throws Exception {
        mvc.perform(get("/wallets/1/balance")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(get("/wallets/1/balance").header("X-User-Id", "abc")).andExpect(status().isBadRequest());
    }

    @Test
    void invalidTransferAmountsAreRejected() throws Exception {
        Party a = newParty();
        for (String amount : new String[] {"0", "-5", "1.001", "\"abc\"", "null"}) {
            postJson("/transfers/topup", a.userId(), "{\"wallet_id\":%d,\"amount\":%s}".formatted(a.walletId(), amount))
                    .andExpect(status().isBadRequest());
        }
        postJson("/transfers/topup", a.userId(),
                "{\"wallet_id\":%d,\"amount\":1,\"note\":\"%s\"}".formatted(a.walletId(), "x".repeat(201)))
                .andExpect(status().isBadRequest());
        postJson("/transfers/topup", a.userId(), "{\"amount\":1}").andExpect(status().isBadRequest());
    }

    @Test
    void statementPagingLimitsAreValidated() throws Exception {
        Party a = newParty();
        for (String q : new String[] {"limit=0", "limit=101", "offset=-1"}) {
            mvc.perform(get("/wallets/" + a.walletId() + "/statement?" + q).header("X-User-Id", a.userId()))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void duplicateUserIs409AndBadInputIs400() throws Exception {
        String email = "dup" + System.nanoTime() + "@example.com";
        postJson("/users", null, "{\"name\":\"D\",\"email\":\"%s\",\"phone\":\"+9172%08d\"}".formatted(email, System.nanoTime() % 100_000_000))
                .andExpect(status().isCreated());
        postJson("/users", null, "{\"name\":\"D2\",\"email\":\"%s\",\"phone\":\"+9173%08d\"}".formatted(email.toUpperCase(), System.nanoTime() % 100_000_000))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USER_EXISTS"));
        postJson("/users", null, "{\"name\":\"\",\"email\":\"nope\",\"phone\":\"12\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminReversalRequiresAdminKey() throws Exception {
        Party a = newParty();
        Party b = newParty();
        postJson("/transfers/topup", a.userId(), "{\"wallet_id\":%d,\"amount\":50}".formatted(a.walletId()))
                .andExpect(status().isCreated());
        long id = body(postJson("/transfers/peer", a.userId(),
                "{\"from_wallet_id\":%d,\"to_wallet_id\":%d,\"amount\":20}".formatted(a.walletId(), b.walletId()))
                .andExpect(status().isCreated())).get("id").asLong();

        mvc.perform(post("/admin/transfers/" + id + "/reverse")).andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/transfers/" + id + "/reverse").header("X-Admin-Key", "wrong"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/transfers/" + id + "/reverse").header("X-User-Id", a.userId()))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/admin/transfers/" + id + "/reverse").header("X-Admin-Key", "dev-admin-key").header("Idempotency-Key", key()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transfer_type").value("REVERSAL"))
                .andExpect(jsonPath("$.reverses_transfer_id").value(id));
        mvc.perform(post("/admin/transfers/" + id + "/reverse").header("X-Admin-Key", "dev-admin-key").header("Idempotency-Key", key()))
                .andExpect(status().isConflict());
        mvc.perform(get("/wallets/" + a.walletId() + "/balance").header("X-User-Id", a.userId()))
                .andExpect(jsonPath("$.balance").value(50));
    }
}
