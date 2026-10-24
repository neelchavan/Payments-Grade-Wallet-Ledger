#!/usr/bin/env bash
# Times POST /transfers/peer with the risk rules on and off. Needs a seeded database and a running api (see docs/performance.md).
set -eu
[ -d /Applications/Docker.app/Contents/Resources/bin ] && PATH="$PATH:/Applications/Docker.app/Contents/Resources/bin"
BASE=${BASE:-http://localhost:8080}
N=${N:-300}

psql_q() { docker compose exec -T postgres psql -U payments -d payments -At -F ' ' -c "$1"; }

RECV=$(psql_q "SELECT id FROM accounts WHERE account_type='USER_WALLET' ORDER BY id DESC LIMIT 1")
# Each request uses a different sender so the velocity rule never holds one.
psql_q "SELECT user_id, id FROM accounts WHERE account_type='USER_WALLET' AND balance >= 10 AND id <> $RECV ORDER BY id LIMIT $((N * 2 + 50))" > /tmp/senders.txt

send() {
  while read -r U W; do
    curl -s -o /dev/null -w '%{time_total} %{http_code}\n' -X POST "$BASE/transfers/peer" -H 'Content-Type: application/json' \
      -H "X-User-Id: $U" -H "Idempotency-Key: $(uuidgen)" -d "{\"from_wallet_id\":$W,\"to_wallet_id\":$RECV,\"amount\":1}"
  done
}

stats() {
  python3 -c "
import sys, statistics
rows = [l.split() for l in sys.stdin if l.strip()]
t = sorted(float(r[0]) * 1000 for r in rows)
bad = sum(1 for r in rows if r[1] != '201')
print('n=%d mean=%.2f ms p50=%.2f ms p95=%.2f ms non-201=%d' % (len(t), statistics.mean(t), t[len(t)//2], t[int(len(t)*0.95)], bad))"
}

psql_q "UPDATE risk_rules SET enabled = true" > /dev/null
sed -n "1,50p" /tmp/senders.txt | send > /dev/null
printf 'rules ON : '; sed -n "51,$((50 + N))p" /tmp/senders.txt | send | stats
psql_q "UPDATE risk_rules SET enabled = false" > /dev/null
printf 'rules OFF: '; sed -n "$((51 + N)),$((50 + 2 * N))p" /tmp/senders.txt | send | stats
psql_q "UPDATE risk_rules SET enabled = true" > /dev/null
