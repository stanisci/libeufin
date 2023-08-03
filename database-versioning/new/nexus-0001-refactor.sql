-- To Do: comments, although '--' vs 'COMMENT ON' is under discussion.

BEGIN;

SELECT _v.register_patch('nexus-0001', NULL, NULL);

CREATE SCHEMA nexus;
SET search_path TO nexus;

-- start of: user management

-- This table accounts the users registered at Nexus
-- without any mention of banking connections.
CREATE TABLE IF NOT EXISTS nexus_users
  (id BIGSERIAL PRIMARY KEY
  ,username TEXT NOT NULL
  ,password TEXT NOT NULL
  ,superuser BOOLEAN NOT NULL
  );

-- end of: user management

-- start of: connection management


-- This table accounts the bank connections that were
-- created in Nexus and points to their owners.  NO connection
-- configuration details are supposed to exist here.
CREATE TABLE IF NOT EXISTS nexus_bank_connections 
  (id BIGSERIAL PRIMARY KEY
  ,connection_id TEXT NOT NULL
  ,type TEXT NOT NULL
  ,dialect TEXT NULL
  ,user BIGINT NOT NULL
  ,CONSTRAINT fk_nexusbankconnections_user_id FOREIGN KEY (user) REFERENCES nexus_users(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );


-- Details of one EBICS connection.  Each row should point to
-- nexus_bank_connections, where the meta information (like name and type)
-- about the connection is stored.
CREATE TABLE IF NOT EXISTS nexus_ebics_subscribers
  (id BIGSERIAL PRIMARY KEY
  ,ebics_url TEXT NOT NULL
  ,host_id TEXT NOT NULL
  ,partner_id TEXT NOT NULL
  ,user_id TEXT NOT NULL
  ,system_id TEXT NULL
  ,signature_private_key bytea NOT NULL
  ,encryption_private_key bytea NOT NULL
  ,authentication_private_key bytea NOT NULL
  ,bank_encryption_public_key bytea NULL
  ,bank_authentication_public_key bytea NULL
  ,nexus_bank_connection BIGINT NOT NULL
  ,ebics_ini_state VARCHAR(16) NOT NULL
  ,ebics_hia_state VARCHAR(16) NOT NULL
  ,CONSTRAINT fk_nexusebicssubscribers_nexusbankconnection_id FOREIGN KEY (nexus_bank_connection) REFERENCES nexus_bank_connections(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );


-- Details of one X-LIBEUFIN-BANK connection.  In other
-- words, each line is one Libeufin-Sandbox user.
CREATE TABLE IF NOT EXISTS xlibeufin_bank_users
  (id BIGSERIAL PRIMARY KEY
  ,username TEXT NOT NULL
  ,password TEXT NOT NULL
  ,base_url TEXT NOT NULL
  ,nexus_bank_connection BIGINT NOT NULL
  ,CONSTRAINT fk_xlibeufinbankusers_nexusbankconnection_id FOREIGN KEY (nexus_bank_connection) REFERENCES nexus_bank_connections(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );


-- This table holds the names of the bank accounts as they
-- exist at the bank where the Nexus user has one account.
-- This table participates in the process of 'importing' one
-- bank account.  The importing action has the main goal of
-- providing friendlier names to the Nexus side of one bank
-- account.
CREATE TABLE IF NOT EXISTS offered_bank_accounts 
  (id BIGSERIAL PRIMARY KEY
  ,offered_account_id TEXT NOT NULL
  ,bank_connection BIGINT NOT NULL
  ,iban TEXT NOT NULL
  ,bank_code TEXT NOT NULL
  ,holder_name TEXT NOT NULL
  ,imported BIGINT NULL
  ,CONSTRAINT fk_offeredbankaccounts_bankconnection_id FOREIGN KEY (bank_connection) REFERENCES nexusbankconnections(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,CONSTRAINT fk_offeredbankaccounts_imported_id FOREIGN KEY (imported) REFERENCES nexus_bank_accounts(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );

-- end of: connection management

-- start of: background tasks


-- Accounts for the background tasks that were created by the user.
CREATE TABLE IF NOT EXISTS nexus_scheduled_tasks 
  (id BIGSERIAL PRIMARY KEY
  ,resource_type TEXT NOT NULL
  ,resource_id TEXT NOT NULL
  ,task_name TEXT NOT NULL
  ,task_type TEXT NOT NULL
  ,task_cronspec TEXT NOT NULL
  ,task_params TEXT NOT NULL
  ,next_scheduled_execution_sec BIGINT NULL
  ,last_scheduled_execution_sec BIGINT NULL
  );

-- end of: background tasks

-- start of: facades management

-- Basic information about the facade state.
CREATE TABLE IF NOT EXISTS facade_state 
  (id BIGSERIAL PRIMARY KEY
  ,bank_account TEXT NOT NULL
  ,bank_connection TEXT NOT NULL
  ,currency TEXT NOT NULL
  -- The following column informs whether this facade
  -- wants payment data to come from statements (usually
  -- once a day when the payment is very likely settled),
  -- reports (multiple times a day but the payment might
  -- not be settled).
  ,reserve_transfer_level TEXT NOT NULL
  ,facade BIGINT NOT NULL
  -- The following column points to the last transaction
  -- that was processed already by the facade.  It's used
  -- along the facade-specific ingestion.
  ,highest_seen_message_serial_id BIGINT DEFAULT 0 NOT NULL
  ,CONSTRAINT fk_facadestate_facade_id FOREIGN KEY (facade) REFERENCES facades(id) ON DELETE CASCADE ON UPDATE RESTRICT
  );

CREATE TABLE IF NOT EXISTS facades 
  (id BIGSERIAL PRIMARY KEY
  ,facade_name TEXT NOT NULL UNIQUE
  ,type TEXT NOT NULL
  ,creator BIGINT NOT NULL
  ,CONSTRAINT fk_facades_creator_id FOREIGN KEY (creator) REFERENCES nexus_users(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );

CREATE TABLE IF NOT EXISTS nexus_permissions 
  (id BIGSERIAL PRIMARY KEY
  ,resource_type TEXT NOT NULL
  ,resource_id TEXT NOT NULL
  ,subject_type TEXT NOT NULL
  ,subject_name TEXT NOT NULL
  ,permission_name TEXT NOT NULL
  );

-- end of: general facades management

-- start of: Taler facade management

-- Holds valid Taler payments, typically those that are returned
-- to the Wirewatch by the Taler facade.
CREATE TABLE IF NOT EXISTS taler_incoming_payments 
  (id BIGSERIAL PRIMARY KEY
  ,payment NOT NULL REFERENCES nexus_bank_transactions(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,reserve_public_key TEXT NOT NULL
  ,timestamp_ms BIGINT NOT NULL
  ,incoming_payto_uri TEXT NOT NULL
  );

-- This table holds the outgoing payments that were requested
-- by the exchange to pay merchants.  The columns reflect the
-- data model of the /transfer call from the TWG.
CREATE TABLE IF NOT EXISTS taler_requested_payments 
  (id BIGSERIAL PRIMARY KEY
  ,facade NOT NULL REFERENCES facades(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,payment NOT NULL REFERENCES payment_initiations(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,request_uid TEXT NOT NULL
  ,amount TEXT NOT NULL
  ,exchange_base_url TEXT NOT NULL
  ,wtid TEXT NOT NULL
  ,credit_account TEXT NOT NULL
  );


-- Typically contains payments with an invalid reserve public
-- key as the subject.  The 'payment' columns points at the ingested
-- transaction that is invalid in the Taler sense.
CREATE TABLE IF NOT EXISTS taler_invalid_incoming_payments 
  (id BIGSERIAL PRIMARY KEY
  ,payment NOT NULL REFERENCES nexus_bank_transactions(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,timestamp_ms BIGINT NOT NULL
  ,refunded BOOLEAN DEFAULT false NOT NULL
  );

-- end of: Taler facade management

-- start of: Anastasis facade management

CREATE TABLE IF NOT EXISTS anastasis_incoming_payments 
  (id BIGSERIAL PRIMARY KEY
  ,payment NOT NULL REFERENCES nexus_bank_transactions(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,subject TEXT NOT NULL
  ,timestamp_ms BIGINT NOT NULL
  ,incoming_payto_uri TEXT NOT NULL
  );

-- end of: Anastasis facade management

-- start of: core banking

-- A bank account managed by Nexus.  Each row corresponds to an
-- actual bank account at the bank and that is owned by the 'account_holder'
-- column.  FIXME: is account_holder a name or a user-name?
CREATE TABLE IF NOT EXISTS nexus_bank_accounts
  (id BIGSERIAL PRIMARY KEY
  ,bank_account_id TEXT NOT NULL UNIQUE
  ,account_holder TEXT NOT NULL
  ,iban TEXT NOT NULL
  ,bank_code TEXT NOT NULL
  ,default_bank_connection BIGINT NULL
  ,last_statement_creation_timestamp BIGINT NULL
  ,last_report_creation_timestamp BIGINT NULL
  ,last_notification_creation_timestamp BIGINT NULL
  ,highest_seen_bank_message_serial_id BIGINT NOT NULL
  ,pain001counter BIGINT DEFAULT 1 NOT NULL
  ,CONSTRAINT fk_nexusbankaccounts_defaultbankconnection_id FOREIGN KEY (default_bank_connection) REFERENCES nexus_bank_connections(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );


-- All the payments that were ingested by Nexus.  Each row
-- points at the Nexus bank account that is related to the transaction.
-- FIXME: explain 'updated_by'.
CREATE TABLE IF NOT EXISTS nexus_bank_transactions 
  (id BIGSERIAL PRIMARY KEY
  ,account_transaction_id TEXT NOT NULL
  ,bank_account NOT NULL REFERENCES nexus_bank_accounts(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,credit_debit_indicator TEXT NOT NULL
  ,currency TEXT NOT NULL
  ,amount TEXT NOT NULL
  ,status VARCHAR(16) NOT NULL
  ,updated_by BIGINT NULL REFERENCES nexus_bank_transactions(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,transaction_json TEXT NOT NULL
  );

-- Table holding the data that represent one outgoing payment
-- made by the (user owning the) 'bank_account'.  The 'raw_confirmation'
-- column points at the global table of all the ingested payments
-- where the pointed ingested payment is the confirmation that the
-- pointing payment initiation was finalized at the bank.  All
-- the IDs involved in this table mimic the semantics of ISO20022 pain.001.
CREATE TABLE IF NOT EXISTS payment_initiations
  (id BIGSERIAL PRIMARY KEY
  ,bank_account NOT NULL REFERENCES nexus_bank_accounts(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,preparation_date BIGINT NOT NULL
  ,submission_date BIGINT NULL
  ,sum TEXT NOT NULL
  ,currency TEXT NOT NULL
  ,end_to_end_id TEXT NOT NULL
  ,payment_information_id TEXT NOT NULL
  ,instruction_id TEXT NOT NULL
  ,subject TEXT NOT NULL
  ,creditor_iban TEXT NOT NULL
  ,creditor_bic TEXT NULL
  ,creditor_name TEXT NOT NULL
  ,submitted BOOLEAN DEFAULT false NOT NULL
  ,invalid BOOLEAN NULL
  ,message_id TEXT NOT NULL
  ,raw_confirmation BIGINT NULL REFERENCES nexus_bank_transactions(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );

-- This table stores user balances for a certain bank account.
-- It was however never used, plus it needs the collaboration
-- of the bank, since giving balances along the ISO20022 is not
-- mandatory.
CREATE TABLE IF NOT EXISTS nexus_bank_balances
  (id BIGSERIAL PRIMARY KEY
  ,balance TEXT NOT NULL
  ,credit_debit_indicator TEXT NOT NULL
  ,bank_account BIGINT NOT NULL
  ,date TEXT NOT NULL
  ,CONSTRAINT fk_nexusbankbalances_bankaccount_id FOREIGN KEY (bank_account) REFERENCES nexus_bank_accounts(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );


-- This table holds the business content that came from the
-- bank.  Storing messages here happens with problematic messages,
-- or when the storing is enabled.  By default, successful messages
-- are never stored.
CREATE TABLE IF NOT EXISTS nexus_bank_messages
  (id BIGSERIAL PRIMARY KEY
  ,bank_connection BIGINT NOT NULL
  ,message bytea NOT NULL
  ,message_id TEXT NULL
  ,fetch_level VARCHAR(16) NOT NULL -- report, statement or notification?
  ,errors BOOLEAN DEFAULT false NOT NULL
  ,CONSTRAINT fk_nexusbankmessages_bankconnection_id FOREIGN KEY (bank_connection) REFERENCES nexus_bank_connections(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );

-- Tuple made by the account name as it is offered by the bank
-- and the associated connection name.
ALTER TABLE
  offered_bank_accounts ADD CONSTRAINT offeredbankaccounts_offeredaccountid_bankconnection_unique UNIQUE (offered_account_id, bank_connection);

ALTER TABLE
  nexus_permissions ADD CONSTRAINT nexuspermissions_resourcetype_resourceid_subjecttype_subjectnam UNIQUE (resource_type, resource_id, subject_type, subject_name, permission_name);

-- end of: core banking

COMMIT
