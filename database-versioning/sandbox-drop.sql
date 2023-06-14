BEGIN;

-- This script DROPs all of the tables we create, including the
-- versioning schema!
--
-- Unlike the other SQL files, it SHOULD be updated to reflect the
-- latest requirements for dropping tables.

SELECT _v.unregister_patch('sandbox-0001');

DROP TABLE IF EXISTS demobankconfigs CASCADE;
DROP TABLE IF EXISTS bankaccounts CASCADE;
DROP TABLE IF EXISTS bankaccounttransactions CASCADE;
DROP TABLE IF EXISTS cashoutsubmissions CASCADE;
DROP TABLE IF EXISTS demobankconfigpairs CASCADE;
DROP TABLE IF EXISTS ebicssubscribers CASCADE;
DROP TABLE IF EXISTS ebicssubscriberpublickeysCASCADE;
DROP TABLE IF EXISTS ebicshosts CASCADE;
DROP TABLE IF EXISTS ebicsdownloadtransactions CASCADE;
DROP TABLE IF EXISTS ebicsuploadtransactions CASCADE;
DROP TABLE IF EXISTS ebicsuploadtransactionchunks CASCADE;
DROP TABLE IF EXISTS ebicsordersignatures CASCADE;
DROP TABLE IF EXISTS bankaccountfreshtransactions CASCADE;
DROP TABLE IF EXISTS bankaccountreports CASCADE;
DROP TABLE IF EXISTS bankaccountstatements CASCADE;
DROP TABLE IF EXISTS talerwithdrawals CASCADE;
DROP TABLE IF EXISTS demobankcustomers CASCADE;
DROP TABLE IF EXISTS cashoutoperations CASCADE;

COMMIT;
