BEGIN;

DO
$do$
DECLARE
    patch text;
BEGIN
    for patch in SELECT patch_name FROM _v.patches WHERE patch_name LIKE 'libeufin_bank_%' loop 
        PERFORM _v.unregister_patch(patch);
    end loop;
END
$do$;

DROP SCHEMA libeufin_bank CASCADE;

COMMIT;
