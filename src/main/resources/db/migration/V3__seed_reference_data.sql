INSERT INTO accounts (account_type, system_code) VALUES
    ('CASH_IN', 'CASH_IN'),
    ('CASH_OUT', 'CASH_OUT'),
    ('FEE_REVENUE', 'FEE_REVENUE');

INSERT INTO job_locks (name) VALUES ('reconciliation');

INSERT INTO risk_rules (rule_code, enabled, action, params) VALUES
    ('VELOCITY', TRUE, 'HOLD', '{"max_count": 5, "window_minutes": 10}'),
    ('AMOUNT_SPIKE', TRUE, 'HOLD', '{"multiplier": 5, "lookback_days": 30, "min_history": 3}'),
    ('NEW_PAYEE_LARGE', TRUE, 'FLAG', '{"min_amount": 10000}');
