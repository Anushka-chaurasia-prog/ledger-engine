-- V2: Database hardening

-- 1. Remove redundant idempotency index since idempotency_key has a UNIQUE constraint
DROP INDEX IF EXISTS idx_transactions_idempotency_key;

-- 2. Add deferred constraint trigger for double-entry invariant
CREATE OR REPLACE FUNCTION check_double_entry_balance()
RETURNS TRIGGER AS $$
DECLARE
    sum_amount BIGINT;
    tx_id UUID;
BEGIN
    IF TG_OP = 'DELETE' THEN
        tx_id := OLD.transaction_id;
    ELSE
        tx_id := NEW.transaction_id;
    END IF;

    SELECT COALESCE(SUM(amount), 0) INTO sum_amount
    FROM entries
    WHERE transaction_id = tx_id;

    IF sum_amount != 0 THEN
        RAISE EXCEPTION 'Double-entry invariant violated for transaction %: sum is %', tx_id, sum_amount;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trigger_check_double_entry_balance
AFTER INSERT OR UPDATE OR DELETE ON entries
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION check_double_entry_balance();
