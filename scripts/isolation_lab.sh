#!/usr/bin/env bash
# Demonstrates lost updates, row locks, REPEATABLE READ failures and deadlocks with two concurrent psql sessions.
# Needs a running Postgres: `docker compose up -d postgres`, or set PSQL to any psql command line.
# Everything happens in a scratch schema `lab`, which is dropped at the end.
set -u

[ -d /Applications/Docker.app/Contents/Resources/bin ] && PATH="$PATH:/Applications/Docker.app/Contents/Resources/bin"
PSQL=${PSQL:-"docker compose exec -T postgres psql -X -q -t -A -U payments -d payments"}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

sql() { echo "$1" | $PSQL 2>&1; }

reset() {
  sql "DROP SCHEMA IF EXISTS lab CASCADE; CREATE SCHEMA lab;
       CREATE TABLE lab.acct (id int PRIMARY KEY, balance int NOT NULL CHECK (balance >= 0));
       INSERT INTO lab.acct VALUES (1, 100), (2, 100);" >/dev/null
}

# scenario TITLE EXPECTATION SESSION_A SESSION_B [GAP_SECONDS]
# Session A starts first; session B starts GAP seconds later (default 0.5).
scenario() {
  local title=$1 expect=$2 a=$3 b=$4 gap=${5:-0.5}
  reset
  echo
  echo "=== $title"
  echo "    expected: $expect"
  echo "$a" | $PSQL >"$OUT/a" 2>&1 &
  local pa=$!
  sleep "$gap"
  echo "$b" | $PSQL >"$OUT/b" 2>&1 &
  local pb=$!
  wait $pa $pb
  sed -e '/^$/d' -e 's/^/    A: /' "$OUT/a"
  sed -e '/^$/d' -e 's/^/    B: /' "$OUT/b"
  echo "    final balances (id|balance): $(sql 'SELECT string_agg(id || '"'"'|'"'"' || balance, '"'"', '"'"' ORDER BY id) FROM lab.acct')"
}

withdraw_read_write() { # amount, extra "begin" options
  cat <<SQL
BEGIN$2;
SELECT balance AS bal FROM lab.acct WHERE id = 1 \gset
SELECT pg_sleep(1.5);
UPDATE lab.acct SET balance = :bal - $1 WHERE id = 1;
COMMIT;
SQL
}

scenario "1. Lost update: READ COMMITTED, read then write, no lock" \
  "two withdrawals of 30 and 50 from 100 should leave 20; the stale read leaves 70 or 50" \
  "$(withdraw_read_write 30 '')" "$(withdraw_read_write 50 '')"

locked_withdraw() {
  cat <<SQL
BEGIN;
SELECT balance AS bal FROM lab.acct WHERE id = 1 FOR UPDATE \gset
SELECT pg_sleep(1.5);
UPDATE lab.acct SET balance = :bal - $1 WHERE id = 1;
COMMIT;
SQL
}

scenario "2. SELECT ... FOR UPDATE: second session waits and re-reads the committed balance" \
  "20" \
  "$(locked_withdraw 30)" "$(locked_withdraw 50)"

atomic_withdraw() {
  cat <<SQL
BEGIN;
UPDATE lab.acct SET balance = balance - $1 WHERE id = 1;
SELECT pg_sleep(1.5);
COMMIT;
SQL
}

scenario "3. Atomic UPDATE with CHECK (balance >= 0): 80 + 80 from 100" \
  "second withdrawal waits, re-evaluates against 20 and fails the CHECK; final 20" \
  "$(atomic_withdraw 80)" "$(atomic_withdraw 80)"

scenario "4. REPEATABLE READ, read then write" \
  "second session fails with 'could not serialize access due to concurrent update'; final 70" \
  "$(withdraw_read_write 30 ' ISOLATION LEVEL REPEATABLE READ')" "$(withdraw_read_write 50 ' ISOLATION LEVEL REPEATABLE READ')"

cross_lock() { # first id, second id
  cat <<SQL
BEGIN;
SELECT id FROM lab.acct WHERE id = $1 FOR UPDATE;
SELECT pg_sleep(1);
SELECT id FROM lab.acct WHERE id = $2 FOR UPDATE;
COMMIT;
SQL
}

scenario "5. Deadlock: A locks 1 then 2, B locks 2 then 1" \
  "Postgres aborts one session with 'deadlock detected'" \
  "$(cross_lock 1 2)" "$(cross_lock 2 1)" 0.2

ordered_lock() {
  cat <<SQL
BEGIN;
SELECT id FROM lab.acct WHERE id IN (1, 2) ORDER BY id FOR UPDATE;
SELECT pg_sleep(1);
COMMIT;
SQL
}

scenario "6. Same two accounts, always locked in ascending id order" \
  "no deadlock; B simply waits for A" \
  "$(ordered_lock)" "$(ordered_lock)" 0.2

sql "DROP SCHEMA lab CASCADE" >/dev/null
echo
