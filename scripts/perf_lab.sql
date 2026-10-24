-- Performance lab. Run against a seeded database:
--   docker compose --profile seed run --rm seed
--   docker compose exec -T postgres psql -U payments -d payments < scripts/perf_lab.sql
-- It adds one very busy wallet (150,000 ledger entries) and then measures with EXPLAIN ANALYZE.
\set ON_ERROR_STOP on
\pset pager off

\echo '=== setup: one hot wallet with 150,000 entries'
SELECT id AS whale FROM accounts WHERE account_type = 'USER_WALLET' ORDER BY id LIMIT 1 \gset
SELECT id AS cashin FROM accounts WHERE system_code = 'CASH_IN' \gset
BEGIN;
SET LOCAL session_replication_role = replica;
WITH t AS (
    INSERT INTO transfers (transfer_type, status, from_account_id, to_account_id, amount, created_at)
    SELECT 'TOPUP', 'COMPLETED', :cashin, :whale, 1, now() - (g || ' seconds')::interval
    FROM generate_series(1, 150000) g
    RETURNING id, from_account_id, to_account_id, amount, created_at)
INSERT INTO ledger_entries (transfer_id, account_id, direction, amount, created_at)
SELECT id, from_account_id, 'DEBIT', amount, created_at FROM t
UNION ALL
SELECT id, to_account_id, 'CREDIT', amount, created_at FROM t;
UPDATE accounts SET balance = balance + 150000 WHERE id = :whale;
UPDATE accounts SET balance = balance - 150000 WHERE id = :cashin;
COMMIT;
ANALYZE;
SELECT count(*) AS whale_entries FROM ledger_entries WHERE account_id = :whale;

\echo '=== A. statement page 1 (OFFSET 0), the query used by GET /wallets/{id}/statement'
EXPLAIN (ANALYZE, BUFFERS)
SELECT e.id, e.transfer_id, e.direction, e.amount, e.created_at, t.transfer_type, t.status, t.note
FROM ledger_entries e JOIN transfers t ON t.id = e.transfer_id
WHERE e.account_id = :whale
ORDER BY e.created_at DESC, e.id DESC
LIMIT 20 OFFSET 0;

\echo '=== B. statement page 5001 (OFFSET 100000)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT e.id, e.transfer_id, e.direction, e.amount, e.created_at, t.transfer_type, t.status, t.note
FROM ledger_entries e JOIN transfers t ON t.id = e.transfer_id
WHERE e.account_id = :whale
ORDER BY e.created_at DESC, e.id DESC
LIMIT 20 OFFSET 100000;

\echo '=== C. the same page with keyset (cursor) pagination'
SELECT created_at AS cur_at, id AS cur_id FROM ledger_entries WHERE account_id = :whale
ORDER BY created_at DESC, id DESC LIMIT 1 OFFSET 99999 \gset
EXPLAIN (ANALYZE, BUFFERS)
SELECT e.id, e.transfer_id, e.direction, e.amount, e.created_at, t.transfer_type, t.status, t.note
FROM ledger_entries e JOIN transfers t ON t.id = e.transfer_id
WHERE e.account_id = :whale AND (e.created_at, e.id) < (:'cur_at', :cur_id)
ORDER BY e.created_at DESC, e.id DESC
LIMIT 20;

\echo '=== D. the OFFSET 100000 page if the join ran only for the 20 returned rows'
EXPLAIN (ANALYZE, BUFFERS)
SELECT p.*, t.transfer_type, t.status, t.note
FROM (SELECT e.id, e.transfer_id, e.direction, e.amount, e.created_at
      FROM ledger_entries e WHERE e.account_id = :whale
      ORDER BY e.created_at DESC, e.id DESC LIMIT 20 OFFSET 100000) p
JOIN transfers t ON t.id = p.transfer_id
ORDER BY p.created_at DESC, p.id DESC;

\echo '=== E. index sizes'
SELECT relname AS table_name, indexrelname AS index_name, pg_size_pretty(pg_relation_size(indexrelid)) AS size
FROM pg_stat_user_indexes WHERE relname IN ('ledger_entries', 'transfers')
ORDER BY pg_relation_size(indexrelid) DESC;
SELECT pg_size_pretty(pg_relation_size('ledger_entries')) AS ledger_table_size,
       pg_size_pretty(pg_relation_size('transfers')) AS transfers_table_size;

\echo '=== F. index write cost: insert 500,000 rows into scratch tables with 0, 1 and 3 indexes'
DROP SCHEMA IF EXISTS lab CASCADE;
CREATE SCHEMA lab;
CREATE TABLE lab.e0 (id bigint, transfer_id bigint, account_id bigint, direction text, amount numeric(19,2), created_at timestamptz);
CREATE TABLE lab.e1 (LIKE lab.e0);
CREATE TABLE lab.e3 (LIKE lab.e0);
ALTER TABLE lab.e1 ADD PRIMARY KEY (id);
ALTER TABLE lab.e3 ADD PRIMARY KEY (id);
CREATE INDEX ON lab.e3 (account_id, created_at DESC, id DESC);
CREATE INDEX ON lab.e3 (transfer_id);
\timing on
\echo 'no indexes:'
INSERT INTO lab.e0 SELECT g, g / 2, g % 2000, 'CREDIT', 1, now() - (g || ' seconds')::interval FROM generate_series(1, 500000) g;
\echo 'primary key only:'
INSERT INTO lab.e1 SELECT g, g / 2, g % 2000, 'CREDIT', 1, now() - (g || ' seconds')::interval FROM generate_series(1, 500000) g;
\echo 'primary key + the two ledger indexes:'
INSERT INTO lab.e3 SELECT g, g / 2, g % 2000, 'CREDIT', 1, now() - (g || ' seconds')::interval FROM generate_series(1, 500000) g;
\timing off
SELECT 'e3' AS tbl, pg_size_pretty(pg_relation_size('lab.e3')) AS heap, pg_size_pretty(pg_indexes_size('lab.e3')) AS indexes;
DROP SCHEMA lab CASCADE;

\echo '=== G. risk rule queries for a typical busy sender'
SELECT from_account_id AS sender FROM transfers WHERE transfer_type = 'PEER_TRANSFER'
GROUP BY from_account_id ORDER BY count(*) DESC LIMIT 1 \gset
SELECT to_account_id AS payee FROM transfers WHERE from_account_id = :sender AND transfer_type = 'PEER_TRANSFER' LIMIT 1 \gset
SELECT count(*) AS sender_transfers FROM transfers WHERE from_account_id = :sender;

\echo '--- G1. VELOCITY'
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*) FROM transfers
WHERE from_account_id = :sender AND transfer_type IN ('PEER_TRANSFER', 'WITHDRAWAL')
  AND status IN ('COMPLETED', 'HELD', 'REVERSED') AND created_at >= now() - make_interval(mins => 10);

\echo '--- G2. AMOUNT_SPIKE (30-day average)'
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*) AS n, COALESCE(avg(amount), 0) AS average FROM transfers
WHERE from_account_id = :sender AND transfer_type IN ('PEER_TRANSFER', 'WITHDRAWAL')
  AND status = 'COMPLETED' AND created_at >= now() - make_interval(days => 30);

\echo '--- G3. NEW_PAYEE_LARGE'
EXPLAIN (ANALYZE, BUFFERS)
SELECT EXISTS (SELECT 1 FROM transfers WHERE from_account_id = :sender AND to_account_id = :payee AND status = 'COMPLETED');

\echo '--- G4. loading the rule table'
EXPLAIN (ANALYZE) SELECT rule_code, enabled, action, params::text FROM risk_rules;
