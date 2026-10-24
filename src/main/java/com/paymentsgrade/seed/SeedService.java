package com.paymentsgrade.seed;

import com.paymentsgrade.account.AccountRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bulk-loads consistent demo data. Balances are tracked in memory, ids are reserved from the sequences,
 * and rows go in with batch inserts, so the ledger stays balanced and no wallet goes negative.
 */
@Service
public class SeedService {

    public record Summary(int users, int wallets, int transfers, long ledgerEntries) {
    }

    private static final Logger log = LoggerFactory.getLogger(SeedService.class);
    private static final int CHUNK = 5_000;
    private static final long MAX_AMOUNT_PAISE = 50_000;
    private static final String[] NOTES = {"rent", "lunch", "movie tickets", "groceries", "thanks!", "split dinner"};

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AccountRepository accounts;

    public SeedService(JdbcTemplate jdbc, TransactionTemplate tx, AccountRepository accounts) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.accounts = accounts;
    }

    public Summary seed(int users, int wallets, int transfers) {
        if (users < 1 || wallets < users || transfers < wallets) {
            throw new IllegalArgumentException("need users >= 1, wallets >= users and transfers >= wallets");
        }
        long started = System.currentTimeMillis();
        List<Long> userIds = reserveIds("users", users);
        List<Long> walletIds = reserveIds("accounts", wallets);
        insertUsers(userIds);
        insertWallets(walletIds, userIds);

        long cashIn = accounts.systemAccountId("CASH_IN");
        long cashOut = accounts.systemAccountId("CASH_OUT");
        List<Long> transferIds = reserveIds("transfers", transfers);
        long[] balance = new long[wallets];
        long[] systemDelta = new long[2];
        Random rnd = new Random(42);
        Instant start = Instant.now().minus(Duration.ofDays(30));
        long stepMillis = Duration.ofDays(30).toMillis() / transfers;

        for (int from = 0; from < transfers; from += CHUNK) {
            int to = Math.min(transfers, from + CHUNK);
            List<Object[]> transferRows = new ArrayList<>();
            List<Object[]> entryRows = new ArrayList<>();
            for (int i = from; i < to; i++) {
                Timestamp at = Timestamp.from(start.plusMillis(stepMillis * i));
                long id = transferIds.get(i);
                int a = i < wallets ? i : rnd.nextInt(wallets);
                long maxSpend = Math.min(balance[a], MAX_AMOUNT_PAISE);
                int roll = rnd.nextInt(100);
                String note = rnd.nextInt(5) == 0 ? NOTES[rnd.nextInt(NOTES.length)] : null;

                if (i >= wallets && roll < 70 && maxSpend >= 100 && wallets > 1) {
                    int b = (a + 1 + rnd.nextInt(wallets - 1)) % wallets;
                    long amount = 100 + rnd.nextInt((int) (maxSpend - 99));
                    balance[a] -= amount;
                    balance[b] += amount;
                    add(transferRows, entryRows, id, "PEER_TRANSFER", walletIds.get(a), walletIds.get(b), amount, note, at);
                } else if (i >= wallets && roll >= 85 && maxSpend >= 100) {
                    long amount = 100 + rnd.nextInt((int) (maxSpend - 99));
                    balance[a] -= amount;
                    systemDelta[1] += amount;
                    add(transferRows, entryRows, id, "WITHDRAWAL", walletIds.get(a), cashOut, amount, note, at);
                } else {
                    long amount = i < wallets ? 10_000 + rnd.nextInt(490_000) : 5_000 + rnd.nextInt(195_000);
                    balance[a] += amount;
                    systemDelta[0] -= amount;
                    add(transferRows, entryRows, id, "TOPUP", cashIn, walletIds.get(a), amount, note, at);
                }
            }
            tx.executeWithoutResult(s -> insertChunk(transferRows, entryRows));
        }

        tx.executeWithoutResult(s -> {
            jdbc.batchUpdate("UPDATE accounts SET balance = ? WHERE id = ?", walletUpdates(walletIds, balance));
            jdbc.update("UPDATE accounts SET balance = balance + ? WHERE id = ?", paise(systemDelta[0]), cashIn);
            jdbc.update("UPDATE accounts SET balance = balance + ? WHERE id = ?", paise(systemDelta[1]), cashOut);
        });

        Summary summary = new Summary(users, wallets, transfers, 2L * transfers);
        log.info("Seeded {} in {} ms", summary, System.currentTimeMillis() - started);
        return summary;
    }

    private void insertChunk(List<Object[]> transferRows, List<Object[]> entryRows) {
        jdbc.queryForObject("SELECT set_config('app.actor', 'seed', true)", String.class);
        jdbc.batchUpdate("""
                INSERT INTO transfers (id, transfer_type, status, from_account_id, to_account_id, amount, note, created_at)
                OVERRIDING SYSTEM VALUE VALUES (?, ?, 'COMPLETED', ?, ?, ?, ?, ?)""", transferRows);
        jdbc.batchUpdate("""
                INSERT INTO ledger_entries (transfer_id, account_id, direction, amount, created_at)
                VALUES (?, ?, ?, ?, ?)""", entryRows);
    }

    private static void add(List<Object[]> transfers, List<Object[]> entries, long id, String type, long from,
            long to, long amountPaise, String note, Timestamp at) {
        BigDecimal amount = paise(amountPaise);
        transfers.add(new Object[] {id, type, from, to, amount, note, at});
        entries.add(new Object[] {id, from, "DEBIT", amount, at});
        entries.add(new Object[] {id, to, "CREDIT", amount, at});
    }

    private static List<Object[]> walletUpdates(List<Long> walletIds, long[] balance) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < balance.length; i++) {
            rows.add(new Object[] {paise(balance[i]), walletIds.get(i)});
        }
        return rows;
    }

    private void insertUsers(List<Long> userIds) {
        List<Object[]> rows = new ArrayList<>();
        for (long id : userIds) {
            rows.add(new Object[] {id, "Seed User " + id, "seed" + id + "@example.com",
                    String.format("+91%010d", 7_000_000_000L + id)});
        }
        tx.executeWithoutResult(s -> jdbc.batchUpdate(
                "INSERT INTO users (id, name, email, phone) OVERRIDING SYSTEM VALUE VALUES (?, ?, ?, ?)", rows));
    }

    private void insertWallets(List<Long> walletIds, List<Long> userIds) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < walletIds.size(); i++) {
            rows.add(new Object[] {walletIds.get(i), userIds.get(i % userIds.size())});
        }
        tx.executeWithoutResult(s -> jdbc.batchUpdate("""
                INSERT INTO accounts (id, account_type, user_id) OVERRIDING SYSTEM VALUE
                VALUES (?, 'USER_WALLET', ?)""", rows));
    }

    private List<Long> reserveIds(String table, int count) {
        return jdbc.queryForList(
                "SELECT nextval(pg_get_serial_sequence(?, 'id')) FROM generate_series(1, ?)", Long.class, table, count);
    }

    private static BigDecimal paise(long paise) {
        return BigDecimal.valueOf(paise, 2);
    }
}
