BEGIN;

SELECT _v.unregister_patch('nexus-0001');

DROP SCHEMA nexus CASCADE;

COMMIT;
