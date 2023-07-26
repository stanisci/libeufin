-- To Do: comments, although '--' vs 'COMMENT ON' is under discussion.

BEGIN;

SELECT _v.register_patch('nexus-0001', NULL, NULL);

-- start of: user management

CREATE TABLE IF NOT EXISTS nexus_users
  (id BIGSERIAL PRIMARY KEY
  ,username TEXT NOT NULL
  ,password TEXT NOT NULL
  ,superuser BOOLEAN NOT NULL
  );

-- end of: user management

-- start of: connection management

CREATE TABLE IF NOT EXISTS nexus_bank_connections 
  (id BIGSERIAL PRIMARY KEY
  ,connection_id TEXT NOT NULL
  ,type TEXT NOT NULL
  ,dialect TEXT NULL
  ,user BIGINT NOT NULL
  ,CONSTRAINT fk_nexusbankconnections_user_id FOREIGN KEY (user) REFERENCES nexus_users(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );

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

CREATE TABLE IF NOT EXISTS xlibeufin_bank_users
  (id BIGSERIAL PRIMARY KEY
  ,username TEXT NOT NULL
  ,password TEXT NOT NULL
  ,base_url TEXT NOT NULL
  ,nexus_bank_connection BIGINT NOT NULL
  ,CONSTRAINT fk_xlibeufinbankusers_nexusbankconnection_id FOREIGN KEY (nexus_bank_connection) REFERENCES nexus_bank_connections(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );

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

CREATE TABLE IF NOT EXISTS facade_state 
  (id BIGSERIAL PRIMARY KEY
  ,bank_account TEXT NOT NULL
  ,bank_connection TEXT NOT NULL
  ,currency TEXT NOT NULL
  ,reserve_transfer_level TEXT NOT NULL
  ,facade BIGINT NOT NULL
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

CREATE TABLE IF NOT EXISTS taler_incoming_payments 
  (id BIGSERIAL PRIMARY KEY
  ,payment NOT NULL REFERENCES nexus_bank_transactions(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,reserve_public_key TEXT NOT NULL
  ,timestamp_ms BIGINT NOT NULL
  ,incoming_payto_uri TEXT NOT NULL
  );

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

CREATE TABLE IF NOT EXISTS nexus_bank_balances -- table never used
  (id BIGSERIAL PRIMARY KEY
  ,balance TEXT NOT NULL
  ,credit_debit_indicator TEXT NOT NULL
  ,bank_account BIGINT NOT NULL
  ,date TEXT NOT NULL
  ,CONSTRAINT fk_nexusbankbalances_bankaccount_id FOREIGN KEY (bank_account) REFERENCES nexus_bank_accounts(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );

CREATE TABLE IF NOT EXISTS nexus_bank_messages
  (id BIGSERIAL PRIMARY KEY
  ,bank_connection BIGINT NOT NULL
  ,message bytea NOT NULL
  ,message_id TEXT NULL
  ,fetch_level VARCHAR(16) NOT NULL
  ,errors BOOLEAN DEFAULT false NOT NULL
  ,CONSTRAINT fk_nexusbankmessages_bankconnection_id FOREIGN KEY (bank_connection) REFERENCES nexus_bank_connections(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );

ALTER TABLE
  offered_bank_accounts ADD CONSTRAINT offeredbankaccounts_offeredaccountid_bankconnection_unique UNIQUE (offered_account_id, bank_connection);

ALTER TABLE
  nexus_permissions ADD CONSTRAINT nexuspermissions_resourcetype_resourceid_subjecttype_subjectnam UNIQUE (resource_type, resource_id, subject_type, subject_name, permission_name);

-- end of: core banking

COMMIT
