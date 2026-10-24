-- Reused for every append-only table.
CREATE FUNCTION forbid_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP;
    RETURN NULL;
END;
$$;

CREATE TRIGGER ledger_entries_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER ledger_entries_no_truncate
    BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER transfer_status_history_immutable
    BEFORE UPDATE OR DELETE ON transfer_status_history
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER transfer_status_history_no_truncate
    BEFORE TRUNCATE ON transfer_status_history
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

-- Runs at COMMIT, so all entries of a transfer must be inserted in one transaction.
CREATE FUNCTION check_transfer_balanced() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    debits  NUMERIC;
    credits NUMERIC;
BEGIN
    SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
      INTO debits, credits
      FROM ledger_entries
     WHERE transfer_id = NEW.transfer_id;

    IF debits <> credits THEN
        RAISE EXCEPTION 'transfer % is unbalanced: debits % credits %', NEW.transfer_id, debits, credits
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_transfer_balanced();

-- The actor comes from set_config('app.actor', ..., true) in the same transaction.
CREATE FUNCTION log_transfer_status() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    actor TEXT := COALESCE(NULLIF(current_setting('app.actor', true), ''), 'system');
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO transfer_status_history (transfer_id, old_status, new_status, actor)
        VALUES (NEW.id, NULL, NEW.status, actor);
    ELSIF NEW.status IS DISTINCT FROM OLD.status THEN
        INSERT INTO transfer_status_history (transfer_id, old_status, new_status, actor)
        VALUES (NEW.id, OLD.status, NEW.status, actor);
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER transfers_status_audit
    AFTER INSERT OR UPDATE OF status ON transfers
    FOR EACH ROW EXECUTE FUNCTION log_transfer_status();
