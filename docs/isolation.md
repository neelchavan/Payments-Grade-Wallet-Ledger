# Isolation and locking

`scripts/isolation_lab.sh` reproduces every case below with two concurrent `psql` sessions in a scratch schema. Start Postgres with `docker compose up -d postgres`, then run `./scripts/isolation_lab.sh`. All outputs below are from a real run on PostgreSQL 16.

Setup for every case: `lab.acct(id, balance CHECK (balance >= 0))` with two rows of balance 100. Session A starts first and session B starts 0.5 s later.

## What the lab shows

| # | Scenario | Result |
|---|---|---|
| 1 | `READ COMMITTED`, read the balance, then `UPDATE SET balance = :read - x`, no lock. A withdraws 30, B withdraws 50. | Final balance **50**, not 20. B overwrote A's write using a stale read: a **lost update**. |
| 2 | Same, but the read is `SELECT ... FOR UPDATE`. | Final balance **20**. B waits for A's row lock, then reads the committed value. |
| 3 | One atomic `UPDATE SET balance = balance - 80` from each session, with the `CHECK`. | A succeeds (balance 20). B waits, re-evaluates against 20 and fails with `violates check constraint`. |
| 4 | `REPEATABLE READ`, read then write, no lock. | Final balance **70**. B fails with `could not serialize access due to concurrent update`, so the client must retry. |
| 5 | A locks row 1 then row 2, B locks row 2 then row 1. | Postgres detects `deadlock detected` after `deadlock_timeout` (1 s) and aborts one session. |
| 6 | Both sessions lock `WHERE id IN (1,2) ORDER BY id FOR UPDATE`. | No deadlock. B waits for A, then proceeds. |

## What the application does

`TransferService` follows the pattern from cases 2 and 6.

1. `READ COMMITTED` transaction.
2. Lock both accounts with `SELECT ... WHERE id IN (...) ORDER BY id FOR UPDATE`, which is the same ascending-id order for every transfer. In the plan's A-to-B versus B-to-A race both transactions therefore queue on the lower id first. No cycle can form.
3. Read the balance from the locked row, so it cannot be stale. If a wallet would go below zero, return `INSUFFICIENT_FUNDS` (422) and roll back with no trace.
4. Insert the transfer, both ledger entries, and the two balance updates.

`CHECK (balance >= 0)` on wallets is the last line of defence: even a bug in the application cannot commit a negative wallet balance.

Plain `REPEATABLE READ` or `SERIALIZABLE` would also be correct, but every conflict would surface as a serialization failure that the caller must retry. Row locks make the conflicting transaction wait instead, which suits a hot wallet.

## Measured effect of skipping the locks

`StampedeTest.naiveTransferWithoutLocksOverdrawsTheLedger` runs 100 concurrent ₹10 transfers out of a ₹500 wallet, using a test-only transfer that reads the balance without a lock (as in case 1).

| | Safe `TransferService` | Naive (no lock) |
|---|---|---|
| Transfers accepted | 50 of 100 | 100 of 100 |
| Sender balance in `accounts.balance` | 0 | 380 |
| Sender balance computed from the ledger | 0 | **-500** |
| Receiver balance | 500 | 1000 |

The naive version pays out ₹1000 from a ₹500 wallet. The stored balance column hides it because lost updates overwrite each other, but the ledger shows the real overdraft of ₹500, and the stored and computed balances disagree by ₹880. This is exactly what the nightly reconciliation's stored-versus-computed check detects.

## Trade-off: the hot row

Every top-up debits the single `CASH_IN` account, so top-ups serialize on that row. This is acceptable at this scale. At higher volume you would split `CASH_IN` into several shards and pick one per transfer, or aggregate it from the ledger instead of storing it.
