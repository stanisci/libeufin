BEGIN;

DO
$do$
DECLARE
    patch text;
BEGIN
    IF EXISTS(SELECT 1 FROM information_schema.schemata WHERE schema_name='_v') THEN
        FOR patch IN SELECT patch_name FROM _v.patches WHERE patch_name LIKE 'libeufin_nexus_%' LOOP 
            PERFORM _v.unregister_patch(patch);
        END LOOP;
    END IF;
END
$do$;

DROP SCHEMA IF EXISTS libeufin_nexus CASCADE;

COMMIT;
