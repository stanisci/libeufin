BEGIN;

-- NOTE: The following unregistration would affect the
-- legacy database schema too.  That's acceptable as the
-- legacy schema is being removed.
SELECT _v.unregister_patch('libeufin-bank-0001');
SELECT _v.unregister_patch('libeufin-bank-0002');
DROP SCHEMA libeufin_bank CASCADE;

COMMIT;
