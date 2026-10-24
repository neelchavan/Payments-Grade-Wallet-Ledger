package com.paymentsgrade.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsgrade.TestDatabase;
import com.paymentsgrade.TransferTestSupport;
import com.paymentsgrade.recon.ReconciliationService;
import com.paymentsgrade.transfer.Transfer;
import com.paymentsgrade.transfer.TransferService;
import com.paymentsgrade.transfer.TransferStatus;
import com.paymentsgrade.web.ApiException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
class RiskTest extends TransferTestSupport {

    private static final String ADMIN = "dev-admin-key";

    @Autowired
    TransferService service;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    ReconciliationService reconciliation;

    @AfterEach
    void restoreRules() {
        TestDatabase.restoreRiskRules(jdbc);
    }

    private Party funded(String amount) {
        Party p = newParty();
        service.topup(p.userId(), p.walletId(), money(amount), null);
        return p;
    }

    private Transfer send(Party from, Party to, String amount) {
        return service.peerTransfer(from.userId(), from.walletId(), to.walletId(), money(amount), null);
    }

    private List<String> flags(long transferId) {
        return jdbc.sql("SELECT rule_code || ':' || action || ':' || coalesce(decision, '-') FROM risk_flags WHERE transfer_id = :t ORDER BY id")
                .param("t", transferId).query(String.class).list();
    }

    private long entries(long transferId) {
        return jdbc.sql("SELECT count(*) FROM ledger_entries WHERE transfer_id = :t").param("t", transferId)
                .query(Long.class).single();
    }

    private Transfer sixthTransferIsHeld(Party a, Party b) {
        for (int i = 0; i < 5; i++) {
            assertThat(send(a, b, "10").status()).isEqualTo(TransferStatus.COMPLETED);
        }
        return send(a, b, "10");
    }

    @Test
    void moreThanFiveTransfersInTenMinutesAreHeld() {
        Party a = funded("1000");
        Party b = newParty();

        Transfer held = sixthTransferIsHeld(a, b);

        assertThat(held.status()).isEqualTo(TransferStatus.HELD);
        assertThat(entries(held.id())).isZero();
        assertThat(storedBalance(a.walletId())).isEqualByComparingTo("950");
        assertThat(storedBalance(b.walletId())).isEqualByComparingTo("50");
        assertThat(flags(held.id())).containsExactly("VELOCITY:HOLD:-");
    }

    @Test
    void approvingAHeldTransferMovesTheMoney() {
        Party a = funded("1000");
        Party b = newParty();
        Transfer held = sixthTransferIsHeld(a, b);

        Transfer done = service.decide(held.id(), true);

        assertThat(done.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(entries(held.id())).isEqualTo(2);
        assertThat(storedBalance(a.walletId())).isEqualByComparingTo("940");
        assertThat(storedBalance(b.walletId())).isEqualByComparingTo("60");
        assertThat(ledgerBalance(a.walletId())).isEqualByComparingTo("940");
        assertThat(flags(held.id())).containsExactly("VELOCITY:HOLD:APPROVED");
        List<String> history = jdbc.sql("SELECT coalesce(old_status,'-') || '>' || new_status || '@' || actor FROM transfer_status_history WHERE transfer_id = :t ORDER BY id")
                .param("t", held.id()).query(String.class).list();
        assertThat(history).containsExactly("->HELD@user:" + a.userId(), "HELD>COMPLETED@admin");
    }

    @Test
    void rejectingAHeldTransferLeavesBalancesUntouched() {
        Party a = funded("1000");
        Party b = newParty();
        Transfer held = sixthTransferIsHeld(a, b);

        Transfer rejected = service.decide(held.id(), false);

        assertThat(rejected.status()).isEqualTo(TransferStatus.REJECTED);
        assertThat(entries(held.id())).isZero();
        assertThat(storedBalance(a.walletId())).isEqualByComparingTo("950");
        assertThat(flags(held.id())).containsExactly("VELOCITY:HOLD:REJECTED");
    }

    @Test
    void approvalChecksTheBalanceAgain() {
        Party a = funded("100");
        Party b = newParty();
        Transfer held = sixthTransferIsHeld(a, b);
        jdbc.sql("UPDATE risk_rules SET enabled = FALSE WHERE rule_code = 'VELOCITY'").update();
        service.withdraw(a.userId(), a.walletId(), money("50"), null);

        assertThatThrownBy(() -> service.decide(held.id(), true))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("INSUFFICIENT_FUNDS"));

        assertThat(service.get(a.userId(), held.id()).status()).isEqualTo(TransferStatus.HELD);
        assertThat(storedBalance(a.walletId())).isEqualByComparingTo("0");
    }

    @Test
    void insufficientFundsIsReportedInsteadOfHolding() {
        Party a = funded("1000");
        Party b = newParty();
        for (int i = 0; i < 5; i++) {
            send(a, b, "10");
        }

        assertThatThrownBy(() -> send(a, b, "5000"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("INSUFFICIENT_FUNDS"));
    }

    @Test
    void withdrawalsCountTowardsVelocityToo() {
        Party a = funded("1000");
        for (int i = 0; i < 5; i++) {
            assertThat(service.withdraw(a.userId(), a.walletId(), money("10"), null).status())
                    .isEqualTo(TransferStatus.COMPLETED);
        }

        assertThat(service.withdraw(a.userId(), a.walletId(), money("10"), null).status())
                .isEqualTo(TransferStatus.HELD);
    }

    @Test
    void topupsAreNeverHeld() {
        Party a = newParty();
        for (int i = 0; i < 8; i++) {
            assertThat(service.topup(a.userId(), a.walletId(), money("10"), null).status())
                    .isEqualTo(TransferStatus.COMPLETED);
        }
    }

    @Test
    void amountMoreThanFiveTimesTheAverageIsHeld() {
        Party a = funded("1000");
        Party b = newParty();
        for (int i = 0; i < 3; i++) {
            send(a, b, "10");
        }
        assertThat(send(a, b, "50").status()).isEqualTo(TransferStatus.COMPLETED);

        Transfer spike = send(a, b, "101");

        assertThat(spike.status()).isEqualTo(TransferStatus.HELD);
        assertThat(flags(spike.id())).containsExactly("AMOUNT_SPIKE:HOLD:-");
    }

    @Test
    void amountSpikeNeedsEnoughHistory() {
        Party a = funded("1000");
        Party b = newParty();
        send(a, b, "10");
        send(a, b, "10");

        assertThat(send(a, b, "500").status()).isEqualTo(TransferStatus.COMPLETED);
    }

    @Test
    void largeTransferToANewPayeeIsFlaggedButAllowed() {
        Party a = funded("40000");
        Party b = newParty();

        Transfer first = send(a, b, "15000");
        Transfer second = send(a, b, "15000");

        assertThat(first.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(flags(first.id())).containsExactly("NEW_PAYEE_LARGE:FLAG:-");
        assertThat(second.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(flags(second.id())).isEmpty();
        assertThat(storedBalance(b.walletId())).isEqualByComparingTo("30000");
    }

    @Test
    void exactlyTheThresholdIsNotFlagged() {
        Party a = funded("20000");
        Party b = newParty();

        Transfer t = send(a, b, "10000");

        assertThat(flags(t.id())).isEmpty();
    }

    @Test
    void thresholdsAndSwitchesComeFromTheTable() {
        Party a = funded("1000");
        Party b = newParty();
        jdbc.sql("UPDATE risk_rules SET params = '{\"max_count\": 1, \"window_minutes\": 10}' WHERE rule_code = 'VELOCITY'").update();
        assertThat(send(a, b, "10").status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(send(a, b, "10").status()).isEqualTo(TransferStatus.HELD);

        TestDatabase.setRulesEnabled(jdbc, false);
        Party c = funded("1000");
        for (int i = 0; i < 8; i++) {
            assertThat(send(c, b, "10").status()).isEqualTo(TransferStatus.COMPLETED);
        }
    }

    private MockHttpServletResponse postJson(String url, String body, String adminKey) throws Exception {
        var req = post(url).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Idempotency-Key", UUID.randomUUID().toString());
        if (adminKey != null) {
            req.header("X-Admin-Key", adminKey);
        }
        return mvc.perform(req).andReturn().getResponse();
    }

    @Test
    void heldTransferIsAcceptedWith202AndAppearsInTheAdminQueue() throws Exception {
        Party a = funded("1000");
        Party b = newParty();
        for (int i = 0; i < 5; i++) {
            send(a, b, "10");
        }

        MockHttpServletResponse response = mvc.perform(post("/transfers/peer").contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id", a.userId()).header("Idempotency-Key", UUID.randomUUID().toString())
                .content("{\"from_wallet_id\":%d,\"to_wallet_id\":%d,\"amount\":10,\"note\":\"sixth\"}"
                        .formatted(a.walletId(), b.walletId()))).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(202);
        long id = json.readTree(response.getContentAsString()).get("id").asLong();
        assertThat(json.readTree(response.getContentAsString()).get("status").asText()).isEqualTo("HELD");

        mvc.perform(get("/admin/risk/queue?limit=100").header("X-Admin-Key", ADMIN)).andExpect(status().isOk());
        JsonNode queue = json.readTree(mvc.perform(get("/admin/risk/queue?limit=100").header("X-Admin-Key", ADMIN))
                .andReturn().getResponse().getContentAsString());
        JsonNode item = null;
        for (JsonNode candidate : queue.get("items")) {
            if (candidate.get("transfer").get("id").asLong() == id) {
                item = candidate;
            }
        }
        assertThat(item).isNotNull();
        assertThat(item.get("flags").get(0).get("rule_code").asText()).isEqualTo("VELOCITY");
        JsonNode statement = json.readTree(mvc.perform(get("/wallets/" + a.walletId() + "/statement?limit=100")
                .header("X-User-Id", a.userId())).andReturn().getResponse().getContentAsString());
        for (JsonNode line : statement.get("items")) {
            assertThat(line.get("transfer_id").asLong()).isNotEqualTo(id);
        }
        mvc.perform(get("/transfers/" + id).header("X-User-Id", a.userId()))
                .andExpect(jsonPath("$.status").value("HELD"));
    }

    @Test
    void adminDecisionEndpointApprovesRejectsAndValidates() throws Exception {
        Party a = funded("1000");
        Party b = newParty();
        Transfer toApprove = sixthTransferIsHeld(a, b);
        Transfer toReject = send(a, b, "10");
        assertThat(toReject.status()).isEqualTo(TransferStatus.HELD);

        assertThat(postJson("/admin/risk/" + toApprove.id() + "/decision", "{\"decision\":\"APPROVE\"}", null).getStatus())
                .isEqualTo(401);
        assertThat(postJson("/admin/risk/" + toApprove.id() + "/decision", "{\"decision\":\"MAYBE\"}", ADMIN).getStatus())
                .isEqualTo(400);
        assertThat(postJson("/admin/risk/" + toApprove.id() + "/decision", "{}", ADMIN).getStatus()).isEqualTo(400);
        assertThat(postJson("/admin/risk/999999999/decision", "{\"decision\":\"APPROVE\"}", ADMIN).getStatus())
                .isEqualTo(404);

        MockHttpServletResponse approved = postJson("/admin/risk/" + toApprove.id() + "/decision", "{\"decision\":\"APPROVE\"}", ADMIN);
        assertThat(approved.getStatus()).isEqualTo(200);
        assertThat(json.readTree(approved.getContentAsString()).get("status").asText()).isEqualTo("COMPLETED");
        assertThat(postJson("/admin/risk/" + toApprove.id() + "/decision", "{\"decision\":\"REJECT\"}", ADMIN).getStatus())
                .isEqualTo(409);

        MockHttpServletResponse rejected = postJson("/admin/risk/" + toReject.id() + "/decision", "{\"decision\":\"REJECT\"}", ADMIN);
        assertThat(json.readTree(rejected.getContentAsString()).get("status").asText()).isEqualTo("REJECTED");
        assertThat(storedBalance(b.walletId())).isEqualByComparingTo("60");
    }

    @Test
    void queueRejectsBadPagingAndNeedsTheAdminKey() throws Exception {
        mvc.perform(get("/admin/risk/queue")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/risk/queue?limit=0").header("X-Admin-Key", ADMIN)).andExpect(status().isBadRequest());
        mvc.perform(get("/admin/risk/queue?offset=-1").header("X-Admin-Key", ADMIN)).andExpect(status().isBadRequest());
    }

    @Test
    void heldTransfersAreNotReconciliationProblems() {
        TestDatabase.reset(jdbc, tx);
        Party a = funded("1000");
        Party b = newParty();
        Transfer held = sixthTransferIsHeld(a, b);
        assertThat(held.status()).isEqualTo(TransferStatus.HELD);

        long runId = reconciliation.runNow("MANUAL");

        assertThat(reconciliation.get(runId).issues()).isEmpty();
        assertThat(reconciliation.get(runId).status()).isEqualTo("COMPLETED");
    }
}
