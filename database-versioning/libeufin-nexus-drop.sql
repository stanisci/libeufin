BEGIN;

SELECT _v.unregister_patch('libeufin-nexus-0001');
SELECT _v.unregister_patch('libeufin-nexus-0002');
DROP SCHEMA libeufin_nexus CASCADE;

COMMIT;
