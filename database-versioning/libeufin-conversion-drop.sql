BEGIN;
SET search_path TO libeufin_bank;

DROP TRIGGER IF EXISTS cashin_link ON libeufin_nexus.talerable_incoming_transactions;
DROP FUNCTION IF EXISTS cashin_link;
DROP TRIGGER IF EXISTS cashout_link ON libeufin_bank.cashout_operations;
DROP FUNCTION IF EXISTS cashout_link;

COMMIT;