# Performance

Measured on PostgreSQL 16 in Docker on a laptop, with data from the `seed` profile:
1,000 users, 2,000 wallets, 500,000 transfers, 1,000,000 ledger entries.

```bash
SPRING_PROFILES_ACTIVE=seed ./gradlew bootRun --args='--seed.transfers=500000'   # about 24 s
docker compose exec -T postgres psql -U payments -d payments < scripts/explain_checks.sql
```

## Reconciliation checks

All five checks run inside one `REPEATABLE READ` read-only transaction, so they see one snapshot while transfers keep flowing. A full run over HTTP (`POST /admin/reconciliation/run`, then `GET .../runs/{id}`) took **0.57 s** at 1M entries and reported no issues.

| Check | Plan | `EXPLAIN ANALYZE` time |
|---|---|---|
| 1. Global balance (sum of debits = sum of credits) | Parallel seq scan of `ledger_entries`, partial aggregates | 39 ms |
| 2. Per-transfer balance | Index scan on `ledger_entries_transfer_idx` feeding a `GroupAggregate` | 264 ms |
| 3. Stored versus computed balance | Parallel seq scan, hash aggregate by account, merge join with `accounts` | 64 ms |
| 4. Overdrafts | Same plan as check 3 | 66 ms |
| 5a. Entries without a transfer | Parallel hash anti join with `transfers` | 85 ms |
| 5b. Entries without an account | Hash anti join with `accounts` (2,003 rows) | 34 ms |
| 5c. Completed transfers without entries | Parallel hash right anti join | 94 ms |

Why they are fast enough:

- Every check is one pass over the ledger, so the cost grows linearly with it. At 1M entries each pass takes tens of milliseconds, because Postgres runs a parallel sequential scan with 2 workers.
- Check 2 is the slowest because it groups by `transfer_id` (500,000 groups). It reads the ledger in index order to avoid a large sort, which costs more than a hash aggregate for this shape. It would only become a problem at roughly 100 times this size.
- Checks 3 and 4 return at most one row per account (about 2,000), so the aggregation is small.
- Checks 5a and 5c use anti joins, which are much cheaper than `NOT IN` or per-row subqueries.

Each check returns at most 1,000 rows (`MAX_PER_CHECK`), so a bug that corrupts millions of rows cannot exhaust memory in the job.

`scripts/explain_checks.sql` repeats the check queries from `ReconciliationChecks.java`. Keep the two in sync.

## Seeding

| Dataset | Rows | Time |
|---|---|---|
| Default (`docker compose --profile seed run --rm seed`) | 1,000 users, 2,000 wallets, 100,000 transfers, 200,000 entries | 3.8 s |
| Scaled (`SEED_TRANSFERS=500000`) | 500,000 transfers, 1,000,000 entries | about 24 s |

Ids are reserved from the sequences, balances are tracked in memory, and rows go in as batches of 5,000 transfers per transaction. Because the seeder only ever spends money a wallet has, a reconciliation run on a fresh seed reports no issues (checked at 100,000 transfers: 0.15 s; at 1M entries: 0.57 s).

## Statement pagination: `OFFSET 100000`

The statement endpoint (`GET /wallets/{id}/statement`) uses `LIMIT`/`OFFSET`. To see what a deep page costs, `scripts/perf_lab.sql` adds one hot wallet with 150,000 ledger entries and measures the exact query the endpoint runs.

| Query | What Postgres does | Time |
|---|---|---|
| Page 1 (`OFFSET 0`) | Index scan on `ledger_entries_account_idx`, then 20 primary-key lookups in `transfers` | 0.09 ms |
| **Page 5001 (`OFFSET 100000`), the current query** | Parallel hash join of **all 150,000** entries with `transfers`, an on-disk merge sort (about 5 MB per worker), then 100,000 rows thrown away | **74 ms** (7,700 buffers, temp files) |
| Same page, join done after `LIMIT`/`OFFSET` | Walks 100,020 index entries in order, throws away 100,000, then 20 lookups | 9.0 ms |
| Same page with a keyset cursor | Starts the index range scan at the cursor and reads 20 rows | 0.08 ms |

Why it is slow:

- `OFFSET n` cannot jump. The database must produce the first `n` rows in order and discard them, so the cost grows with `n`. Page 5001 costs about 800 times page 1.
- Here it is worse than necessary. The join to `transfers` sits below the `LIMIT`, so the planner joined and sorted every entry of the wallet instead of reading the index in order.
- Even the best plan (third row) reads 100,000 index entries, about 100 times more than the cursor version.

The fix is keyset (cursor) pagination: `WHERE (e.created_at, e.id) < (:cursor_time, :cursor_id) ORDER BY e.created_at DESC, e.id DESC LIMIT 20`. The existing index `(account_id, created_at DESC, id DESC)` already matches that order, so it needs no new index. It is not wired into the API yet (see "Next steps" in the README); the endpoint caps `limit` at 100 but not `offset`.

## Index write cost

Inserting 500,000 `ledger_entries`-shaped rows into scratch tables with different indexes (`scripts/perf_lab.sql`, section F):

| Indexes | Time | Relative |
|---|---|---|
| none | 292 ms | 1.0x |
| primary key | 493 ms | 1.7x |
| primary key + `(account_id, created_at DESC, id DESC)` + `(transfer_id)` | 1,386 ms | 4.8x |

- The three indexes cost about 2.2 microseconds per inserted row on top of the table write, and they use more space than the table itself: 51 MB of indexes for a 37 MB table.
- On the real ledger (350,000 entries) the sizes are: `ledger_entries_account_idx` 32 MB, primary key 11 MB, `ledger_entries_transfer_idx` 10 MB, table 37 MB. On `transfers` the largest index is `transfers_from_created_idx` at 12 MB.
- Every transfer writes two ledger rows and one transfer row, so each transfer updates about 6 ledger index entries and about 3 `transfers` index entries.
- Each index pays for a specific read: the account index serves statements and balance recomputation, the `transfer_id` index serves the per-transfer balance check and the commit-time trigger, and the partial indexes on `transfers` serve the risk rules and the review queue. The partial ones are small (`transfers_held_idx` is 8 KB because few rows are held).
- This is a bulk insert measurement. A real transfer is dominated by the commit and the row locks, and real inserts land at random places in the account index, so the true per-row cost is somewhat higher. It is still small next to the 4 to 5 ms a transfer takes end to end.

## Risk rules: index and time added to a transfer

Measured for a sender with 61 completed transfers (`scripts/perf_lab.sql`, section G):

| Rule | Index used | Query time |
|---|---|---|
| Velocity (more than 5 in 10 minutes) | `transfers_from_created_idx` on `(from_account_id, created_at)`, 3 buffers | 0.010 ms |
| Amount spike (5x the 30-day average) | Bitmap scan on `transfers_pair_completed_idx`, 61 heap pages, then a filter on date and type | 0.099 ms |
| New payee over 10,000 | Index-only scan on `transfers_pair_completed_idx`, 0 heap fetches, 4 buffers | 0.014 ms |
| Loading `risk_rules` (3 rows, read on every transfer) | Sequential scan of a 3-row table | 0.020 ms |

End to end, `scripts/risk_overhead.sh` sends 300 real `POST /transfers/peer` requests with the rules on and 300 with them off, each from a different sender so none is held:

| Run | Rules on (mean / p95) | Rules off (mean / p95) | Added |
|---|---|---|---|
| 1 | 5.38 ms / 6.36 ms | 4.30 ms / 5.02 ms | about 1.1 ms |
| 2 | 4.41 ms / 4.90 ms | 4.08 ms / 5.02 ms | about 0.3 ms |

So the rules add roughly **0.3 to 1.1 ms** to a transfer that takes 4 to 5 ms. The four queries each run in under 0.1 ms, so most of the added time is the four extra round trips to Postgres inside the transaction.

Two things to watch at larger scale:

- The amount-spike query cost grows with the sender's number of completed outgoing transfers, because the 30-day cut-off is not part of the index. A sender with thousands of transfers would benefit from a partial index on `(from_account_id, created_at) WHERE status = 'COMPLETED'`.
- Reading `risk_rules` on every transfer keeps thresholds editable at runtime. If the extra round trip ever matters, cache the three rows for a few seconds.

## Reproducing

```bash
docker compose up -d postgres
docker compose --profile seed run --rm seed
docker compose exec -T postgres psql -U payments -d payments < scripts/perf_lab.sql
docker compose up -d --build api && ./scripts/risk_overhead.sh
```

All timings are from one laptop with Postgres in Docker, so compare ratios rather than absolute numbers.
