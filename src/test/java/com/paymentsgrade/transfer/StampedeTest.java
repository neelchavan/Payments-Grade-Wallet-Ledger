package com.paymentsgrade.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.paymentsgrade.TestDatabase;
import com.paymentsgrade.TransferTestSupport;
import com.paymentsgrade.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class StampedeTest extends TransferTestSupport {

    private static final int ROUNDS = 20;
    private static final int THREADS = 100;

    @Autowired
    TransferService service;

    @Autowired
    TransactionTemplate tx;

    // 100 transfers in a second would trip the velocity rule; this test is about locking, not risk.
    @BeforeEach
    void switchRiskRulesOff() {
        TestDatabase.setRulesEnabled(jdbc, false);
    }

    @AfterEach
    void switchRiskRulesOn() {
        TestDatabase.setRulesEnabled(jdbc, true);
    }

    // Releases all tasks at once so they contend for the same rows.
    private List<Throwable> runConcurrently(List<Callable<Void>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Throwable>> futures = new ArrayList<>();
            for (Callable<Void> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        task.call();
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            ready.await();
            go.countDown();
            List<Throwable> failures = new ArrayList<>();
            for (Future<Throwable> f : futures) {
                Throwable t = f.get();
                if (t != null) {
                    failures.add(t);
                }
            }
            return failures;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void stampedeOf100TransfersAgainst500NeverOverdraws() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Party sender = newParty();
            Party receiver = newParty();
            service.topup(sender.userId(), sender.walletId(), money("500"), null);

            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                tasks.add(() -> {
                    service.peerTransfer(sender.userId(), sender.walletId(), receiver.walletId(), money("10"), null);
                    return null;
                });
            }
            List<Throwable> failures = runConcurrently(tasks);

            assertThat(failures).as("round %d: only insufficient-funds rejections are allowed", round)
                    .allSatisfy(t -> assertThat(t).isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.code()).isEqualTo("INSUFFICIENT_FUNDS")));
            assertThat(THREADS - failures.size()).as("round %d successes", round).isEqualTo(50);
            assertThat(storedBalance(sender.walletId())).as("round %d sender", round).isEqualByComparingTo("0");
            assertThat(storedBalance(receiver.walletId())).as("round %d receiver", round).isEqualByComparingTo("500");
            assertThat(ledgerBalance(sender.walletId())).isEqualByComparingTo("0");
            assertThat(ledgerBalance(receiver.walletId())).isEqualByComparingTo("500");
        }
    }

    @Test
    void oppositeDirectionTransfersNeverDeadlock() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Party a = newParty();
            Party b = newParty();
            service.topup(a.userId(), a.walletId(), money("1000"), null);
            service.topup(b.userId(), b.walletId(), money("1000"), null);

            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                tasks.add(() -> {
                    service.peerTransfer(a.userId(), a.walletId(), b.walletId(), money("1"), null);
                    return null;
                });
                tasks.add(() -> {
                    service.peerTransfer(b.userId(), b.walletId(), a.walletId(), money("1"), null);
                    return null;
                });
            }
            List<Throwable> failures = runConcurrently(tasks);

            assertThat(failures).as("round %d: no deadlock or other error", round).isEmpty();
            assertThat(storedBalance(a.walletId())).isEqualByComparingTo("1000");
            assertThat(storedBalance(b.walletId())).isEqualByComparingTo("1000");
        }
    }

    // Test-only: the same stampede without locks, to show what the safe version prevents.
    @Test
    void naiveTransferWithoutLocksOverdrawsTheLedger() throws Exception {
        Party sender = newParty();
        Party receiver = newParty();
        service.topup(sender.userId(), sender.walletId(), money("500"), null);

        AtomicInteger accepted = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> {
                if (naiveTransfer(sender.walletId(), receiver.walletId(), money("10"))) {
                    accepted.incrementAndGet();
                }
                return null;
            });
        }
        List<Throwable> failures = runConcurrently(tasks);

        BigDecimal ledger = ledgerBalance(sender.walletId());
        System.out.printf("NAIVE RESULT: accepted=%d of %d, failures=%d, stored sender balance=%s, ledger sender balance=%s, receiver=%s%n",
                accepted.get(), THREADS, failures.size(), storedBalance(sender.walletId()), ledger,
                storedBalance(receiver.walletId()));

        assertThat(failures).isEmpty();
        assertThat(accepted.get()).isGreaterThan(50);
        assertThat(ledger).isNegative();
        assertThat(storedBalance(sender.walletId())).isNotEqualByComparingTo(ledger);
    }

    // Read-check-write on a stale balance, with a pause to make the race window reliable.
    private boolean naiveTransfer(long fromId, long toId, BigDecimal amount) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            BigDecimal balance = jdbc.sql("SELECT balance FROM accounts WHERE id = :id").param("id", fromId)
                    .query(BigDecimal.class).single();
            if (balance.compareTo(amount) < 0) {
                return false;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            long transferId = jdbc.sql("""
                    INSERT INTO transfers (transfer_type, status, from_account_id, to_account_id, amount)
                    VALUES ('PEER_TRANSFER', 'COMPLETED', :f, :t, :a) RETURNING id""")
                    .param("f", fromId).param("t", toId).param("a", amount).query(Long.class).single();
            jdbc.sql("INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (:t, :a, 'DEBIT', :m)")
                    .param("t", transferId).param("a", fromId).param("m", amount).update();
            jdbc.sql("INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (:t, :a, 'CREDIT', :m)")
                    .param("t", transferId).param("a", toId).param("m", amount).update();
            jdbc.sql("UPDATE accounts SET balance = :b WHERE id = :id")
                    .param("b", balance.subtract(amount)).param("id", fromId).update();
            jdbc.sql("UPDATE accounts SET balance = balance + :a WHERE id = :id")
                    .param("a", amount).param("id", toId).update();
            return true;
        }));
    }
}
