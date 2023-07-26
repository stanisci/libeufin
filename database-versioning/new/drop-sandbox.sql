BEGIN;

SELECT _v.unregister_patch('sandbox-0001');

DROP SCHEMA sandbox CASCADE;

COMMIT;
