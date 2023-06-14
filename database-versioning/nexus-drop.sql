BEGIN;

-- This script DROPs all of the tables we create, including the
-- versioning schema!
--
-- Unlike the other SQL files, it SHOULD be updated to reflect the
-- latest requirements for dropping tables.

SELECT _v.unregister_patch('nexus-0001');

DROP TABLE IF EXISTS nexususers CASCADE; 
DROP TABLE IF EXISTS nexusbankconnections CASCADE;
DROP TABLE IF EXISTS xlibeufinbankusers CASCADE;
DROP TABLE IF EXISTS nexusscheduledtasks CASCADE;
DROP TABLE IF EXISTS nexusbankaccounts CASCADE;
DROP TABLE IF EXISTS nexusbanktransactions CASCADE;
DROP TABLE IF EXISTS paymentinitiations CASCADE;
DROP TABLE IF EXISTS nexusebicssubscribers CASCADE;
DROP TABLE IF EXISTS nexusbankbalances CASCADE;
DROP TABLE IF EXISTS anastasisincomingpayments CASCADE;
DROP TABLE IF EXISTS talerincomingpayments CASCADE;
DROP TABLE IF EXISTS facades CASCADE;
DROP TABLE IF EXISTS talerrequestedpayments CASCADE;
DROP TABLE IF EXISTS facadestate CASCADE;
DROP TABLE IF EXISTS talerinvalidincomingpayments CASCADE;
DROP TABLE IF EXISTS nexusbankmessages CASCADE;
DROP TABLE IF EXISTS offeredbankaccounts CASCADE;
DROP TABLE IF EXISTS nexuspermissions CASCADE;

COMMIT;
