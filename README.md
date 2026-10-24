# Payments-Grade

Backend for a digital wallet. Users hold INR balances, add money, send money to each other, withdraw, and ask questions about their statement. Money is never created, lost or double-charged, even with concurrent and repeated requests.

Java 21, Spring Boot 3.5, Gradle, PostgreSQL 16, plain SQL through `JdbcClient` (no JPA), Flyway migrations, Testcontainers.

## Quick start

You need Docker Desktop. For the tests you also need a JDK (the build targets Java 21; it also runs on newer JDKs).

```bash
docker compose up -d --build        # postgres, api (:8080) and worker (nightly job)
./scripts/smoke.sh                  # 22 end-to-end checks against the running stack
docker compose --profile seed run --rm seed    # optional: 1,000 users, 2,000 wallets, 100,000 transfers
```

Run the tests (Testcontainers starts its own Postgres):

```bash
export PATH="$PATH:/Applications/Docker.app/Contents/Resources/bin"    # macOS, if docker is not on PATH
export DOCKER_HOST=unix://$HOME/.docker/run/docker.sock                # Docker Desktop on macOS
./gradlew cleanTest test
```

Configuration is by environment variable: `ADMIN_KEY` (default `dev-admin-key`), `LLM_PROVIDER` (`mock` by default, or `openrouter`), `OPENROUTER_API_KEY` and `OPENROUTER_MODEL`. Put secrets in a `.env` file, which is git-ignored and read by Docker Compose.

## API

Every `POST` that changes state needs an `Idempotency-Key` header (a UUID). User calls send `X-User-Id`; admin calls send `X-Admin-Key`. Errors look like `{"code", "message", "request_id"}`, and every response carries `X-Request-Id`.

| Endpoint | Purpose |
|---|---|
| `POST /users` | Create a user |
| `POST /users/{id}/wallets`, `GET /users/{id}/wallets` | Create and list your wallets |
| `GET /wallets/{id}/balance` | Balance |
| `GET /wallets/{id}/statement?limit=&offset=` | Ledger lines, newest first (limit up to 100) |
| `POST /transfers/topup`, `/transfers/withdrawal`, `/transfers/peer` | Move money. Returns 201, or 202 if the transfer is held for review |
| `GET /transfers/{id}` | A transfer you took part in |
| `POST /users/{id}/statement/ask` | Ask a question in plain English about your transfers (read-only, no key needed) |
| `POST /admin/transfers/{id}/reverse` | Reverse a completed transfer in full |
| `POST /admin/reconciliation/run`, `GET /admin/reconciliation/runs/{id}` | Start an audit (202) and read its result |
| `GET /admin/risk/queue`, `POST /admin/risk/{id}/decision` | Review held transfers: `APPROVE` or `REJECT` |

Other people's wallets and transfers return 404, never 403, so ids cannot be probed.

## How it works

**Double-entry ledger.** Every transfer writes one `DEBIT` and one `CREDIT` of the same amount to `ledger_entries`. A balance is credits minus debits. Entries are never updated or deleted (a trigger refuses it, including `TRUNCATE`). A deferred trigger checks at commit that each transfer's debits equal its credits. `accounts.balance` is a stored copy, checked by the nightly audit. Wallets cannot go negative (`CHECK`). System accounts (`CASH_IN`, `CASH_OUT`, `FEE_REVENUE`) live in the same table. See [docs/schema.md](docs/schema.md).

**Concurrency.** A transfer runs in one `READ COMMITTED` transaction. It locks both accounts with `SELECT ... FOR UPDATE` in ascending id order, reads the balance from the locked row, then writes. Two transfers in opposite directions both queue on the lower id first, so they cannot deadlock. Without the locks, 100 simultaneous ₹10 transfers out of a ₹500 wallet all succeed and the ledger shows the wallet at -₹500; with them exactly 50 succeed, every time. Details, with a runnable lab, are in [docs/isolation.md](docs/isolation.md) (`./scripts/isolation_lab.sh`).

**Idempotency.** The key is claimed, the work is done and the response is saved in one transaction. Repeating a request returns the saved response with `Idempotent-Replayed: true`. The same key with a different body (or on a different endpoint) is 422. Ten identical requests at once run the work once: the others wait on the database and then get the same answer. A business error such as insufficient funds is saved too, so retrying the same key replays the same error; a server crash rolls the key back so the client can retry. Keys are scoped per user, with one shared scope for `POST /users` and admin calls, and the nightly job deletes keys older than 24 hours.

**Reconciliation.** A nightly job on the `worker` container (02:00, `app.reconciliation.cron`) and `POST /admin/reconciliation/run` run five read-only checks in one `REPEATABLE READ` snapshot: total debits equal credits, every transfer balances, stored balances match the ledger, no wallet is overdrawn, and nothing is orphaned (entries without a transfer or account, completed transfers without entries). It reports and never repairs. A `job_locks` row (`FOR UPDATE NOWAIT`) stops two runs overlapping; the loser is marked `SKIPPED`. A run over 1,000,000 entries takes about half a second ([docs/performance.md](docs/performance.md)).

**Risk rules** (peer transfers and withdrawals; thresholds live in the `risk_rules` table):

| Rule | Default | Action |
|---|---|---|
| Velocity | more than 5 transfers in 10 minutes | Hold (202) |
| Amount spike | more than 5 times the sender's 30-day average, with at least 3 past transfers | Hold |
| New payee | first transfer to a payee, over ₹10,000 | Flag and allow |

A held transfer has no ledger entries and reserves nothing. An admin approves or rejects it; approval locks the accounts again and re-checks the balance.

**Questions about your statement.** `POST /users/{id}/statement/ask` supports four questions: total spent, total sent to one person, number of transfers, and the largest transfer, each over a date range. The model sees only the question and today's date. It must answer with a small JSON object that is validated strictly (exact keys, known type, real dates, a plain-name counterparty). The values are used only as bind parameters in queries limited to the caller's own wallets, and the answer text is written by the code, so a hostile question, a hostile transfer note or a hostile model reply cannot reach other users' data. After each audit, a model labels each issue from a fixed list; the stored explanation is canned text for that label, and transfer notes reach the model only as capped, JSON-quoted data. `MockLlmClient` is the default, so no key or network is needed. Set `LLM_PROVIDER=openrouter` for a real model.

## Assumptions

- Currency is INR only, with 2 decimal places.
- KYC status is stored but not enforced. The fee transfer type and fee account exist, but no fee is charged.
- Auth is a plain `X-User-Id` header and an `X-Admin-Key` header; there is no login or JWT.
- Reversals are full only, admin-only, and the original becomes `REVERSED`. A reversal fails with 422 if the recipient has already spent the money.
- Held transfers have no entries, so the audit expects entries only for `COMPLETED` and `REVERSED` transfers.
- One corruption can trip several audit checks (deleting one ledger entry trips three), so the acceptance test asserts the exact set of `(check, id)` pairs.
- The plan did not define the four question types; the ones above are my choice. Dates in questions are UTC. "Spending" means completed peer transfers and withdrawals.
- The Q&A endpoint is read-only, so it does not need an `Idempotency-Key`.

## Trade-offs

- **The cash-in account is a hot row.** Every top-up debits `CASH_IN`, so top-ups queue on that row. This is fine at this scale. Splitting it into several accounts, or deriving its balance from the ledger, would remove the bottleneck.
- **Waiting instead of 409.** Because the key and the work commit together, a concurrent duplicate waits and replays rather than getting a "still running" error. A stray `IN_PROGRESS` row still returns 409.
- **Row locks, not `SERIALIZABLE`.** Waiting on a lock suits a busy wallet better than retrying serialization failures.
- **`LIMIT`/`OFFSET` statements.** A page 100,000 rows deep takes 74 ms against 0.08 ms with a cursor (see the performance notes).
- **Free-tier model.** The free OpenRouter model is rate-limited; a 429 becomes a 503 on the Q&A endpoint and a missing explanation in the audit, never a failed run.
- **Test isolation.** Reconciliation tests empty the shared test database first and need a superuser, which Testcontainers provides.

## Tests

122 tests, all passing: schema rules, transfers, the stampede (100 transfers against ₹500, repeated 20 times) and opposite-direction deadlock tests, idempotency (5 repeats, 10 in parallel, mismatched body), reconciliation (exact issue set for three corruptions, clean seed), risk rules and the admin queue, 11 sample statement questions with expected answers, prompt-injection and hostile-model-output tests, and the OpenRouter client.

## Documentation

- [docs/plan.md](docs/plan.md): the implementation plan
- [docs/schema.md](docs/schema.md): tables, cardinalities and an ER diagram
- [docs/isolation.md](docs/isolation.md): locking, isolation levels and the measured effect of skipping the locks
- [docs/performance.md](docs/performance.md): audit, pagination, index and risk-rule measurements
- [docs/demo.md](docs/demo.md): step-by-step script for a 5-minute demo video
