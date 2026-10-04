-- Post-Run Invariant Verification

-- 1. Double-Entry Invariant Check: Sum of entries per transaction must be 0
SELECT transaction_id, SUM(amount) AS net_sum
FROM entries
GROUP BY transaction_id
HAVING SUM(amount) != 0;

-- 2. Orphaned Transaction Check: Every transaction must have at least 2 entries
SELECT t.id, COUNT(e.id) as entry_count
FROM transactions t
LEFT JOIN entries e ON e.transaction_id = t.id
GROUP BY t.id
HAVING COUNT(e.id) < 2;

-- 3. Check for any negative account balances (excluding system account)
SELECT account_id, SUM(amount) AS balance
FROM entries
GROUP BY account_id
HAVING SUM(amount) < 0;

-- 4. Outbox Integrity: Count of transactions vs Count of outbox events
SELECT 
    (SELECT COUNT(*) FROM transactions) AS total_transactions,
    (SELECT COUNT(*) FROM outbox_events) AS total_outbox_events;
