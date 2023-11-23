BEGIN;
SET search_path TO libeufin_bank;

DROP TRIGGER IF EXISTS cashin_link;
DROP FUNCTION IF EXISTS cashin_link;
DROP TRIGGER IF EXISTS cashout_link;
DROP FUNCTION IF EXISTS cashout_link;

COMMIT;