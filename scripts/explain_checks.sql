-- EXPLAIN ANALYZE for each reconciliation check. Keep in sync with ReconciliationChecks.java (:cap = 1000).
-- Usage: docker compose exec -T postgres psql -U payments -d payments < scripts/explain_checks.sql
ANALYZE;
\timing off

\echo '=== 1. GLOBAL_BALANCE'
EXPLAIN (ANALYZE, BUFFERS)
SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0) AS debits,
       COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0) AS credits
FROM ledger_entries;

\echo '=== 2. TRANSFER_BALANCE'
EXPLAIN (ANALYZE, BUFFERS)
SELECT transfer_id,
       COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0) AS debits,
       COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0) AS credits
FROM ledger_entries
GROUP BY transfer_id
HAVING COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0)
    <> COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
ORDER BY transfer_id
LIMIT 1000;

\echo '=== 3. ACCOUNT_BALANCE'
EXPLAIN (ANALYZE, BUFFERS)
SELECT a.id, a.balance AS stored, COALESCE(l.net, 0) AS computed
FROM accounts a
LEFT JOIN (SELECT account_id,
                  SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END) AS net
           FROM ledger_entries GROUP BY account_id) l ON l.account_id = a.id
WHERE a.balance <> COALESCE(l.net, 0)
ORDER BY a.id
LIMIT 1000;

\echo '=== 4. OVERDRAFT'
EXPLAIN (ANALYZE, BUFFERS)
SELECT a.id, a.balance AS stored, COALESCE(l.net, 0) AS computed
FROM accounts a
LEFT JOIN (SELECT account_id,
                  SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END) AS net
           FROM ledger_entries GROUP BY account_id) l ON l.account_id = a.id
WHERE a.account_type = 'USER_WALLET' AND (a.balance < 0 OR COALESCE(l.net, 0) < 0)
ORDER BY a.id
LIMIT 1000;

\echo '=== 5a. ORPHAN: entries without a transfer'
EXPLAIN (ANALYZE, BUFFERS)
SELECT DISTINCT e.transfer_id AS id
FROM ledger_entries e
WHERE NOT EXISTS (SELECT 1 FROM transfers t WHERE t.id = e.transfer_id)
ORDER BY 1
LIMIT 1000;

\echo '=== 5b. ORPHAN: entries without an account'
EXPLAIN (ANALYZE, BUFFERS)
SELECT DISTINCT e.account_id AS id
FROM ledger_entries e
WHERE NOT EXISTS (SELECT 1 FROM accounts a WHERE a.id = e.account_id)
ORDER BY 1
LIMIT 1000;

\echo '=== 5c. ORPHAN: completed transfers without entries'
EXPLAIN (ANALYZE, BUFFERS)
SELECT t.id
FROM transfers t
WHERE t.status IN ('COMPLETED', 'REVERSED')
  AND NOT EXISTS (SELECT 1 FROM ledger_entries e WHERE e.transfer_id = t.id)
ORDER BY t.id
LIMIT 1000;
