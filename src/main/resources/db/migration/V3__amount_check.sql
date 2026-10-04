-- V3: Prevent zero-valued ledger entries

ALTER TABLE entries ADD CONSTRAINT check_amount_non_zero CHECK (amount <> 0);
