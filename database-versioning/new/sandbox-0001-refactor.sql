-- Under discussion:

-- amount format
-- timestamp format
-- comment format: '--' vs 'COMMENT ON'

BEGIN;

SELECT _v.register_patch('sandbox-0001', NULL, NULL);

CREATE SCHEMA sandbox;
SET search_path TO sandbox;

-- start of: demobank config tables

CREATE TABLE IF NOT EXISTS demobank_configs 
  (id BIGSERIAL PRIMARY KEY
  ,name TEXT NOT NULL
  );

CREATE TABLE IF NOT EXISTS demobank_config_pairs 
  (id BIGSERIAL PRIMARY KEY
  ,demobank_name TEXT NOT NULL
  ,config_key TEXT NOT NULL
  ,config_value TEXT NULL
  );

-- end of: demobank config tables

-- start of: bank accounts

CREATE TABLE IF NOT EXISTS demobank_customers 
  (id BIGSERIAL PRIMARY KEY
  ,username TEXT NOT NULL
  ,password_hash TEXT NOT NULL
  ,name TEXT NULL
  ,email TEXT NULL
  ,phone TEXT NULL
  ,cashout_address TEXT NULL
  );

CREATE TABLE IF NOT EXISTS bank_accounts 
  (id SERIAL PRIMARY KEY
  ,iban TEXT NOT NULL
  ,bic TEXT NOT NULL -- NOTE: This had a default of 'SANDBOXX', now Kotlin must keep it.
  ,label TEXT NOT NULL UNIQUE
  ,owner TEXT NOT NULL
  ,is_public BOOLEAN DEFAULT false NOT NULL
  ,demo_bank FIXME_TYPE REFERENCES demobank_configs(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,last_transaction FIXME_TYPE NULL REFERENCES bank_account_transactions(id) ON DELETE RESTRICT ON UPDATE RESTRICT -- FIXME: under discussion on MM, might be removed.
  ,last_fiat_submission FIXME_TYPE NULL REFERENCES bank_account_transactions(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,last_fiat_fetch TEXT DEFAULT '0' NOT NULL
  ,balance TEXT DEFAULT '0'
  );

-- end of: bank accounts

-- start of: money transactions

CREATE TABLE IF NOT EXISTS bank_account_transactions 
  (id BIGSERIAL PRIMARY KEY
  ,creditor_iban TEXT NOT NULL
  ,creditor_bic TEXT NULL
  ,creditor_name TEXT NOT NULL
  ,debtor_iban TEXT NOT NULL
  ,debtor_bic TEXT NULL
  ,debtor_name TEXT NOT NULL
  ,subject TEXT NOT NULL
  ,amount TEXT NOT NULL
  ,currency TEXT NOT NULL
  ,date BIGINT NOT NULL
  ,account_servicer_reference TEXT NOT NULL
  ,pmt_inf_id TEXT NULL
  ,end_to_end_id TEXT NULL
  ,direction TEXT NOT NULL
  ,account INT NOT NULL REFERENCES bank_accounts(id) ON DELETE CASCADE ON UPDATE RESTRICT
  ,demobank FIXME_TYPE NOT NULL REFERENCES demobank_configs(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );

-- end of: money transactions

-- start of: cashout management

CREATE TABLE IF NOT EXISTS cashout_submissions 
  (id BIGSERIAL PRIMARY KEY
  ,local_transaction FIXME_TYPE NOT NULL UNIQUE REFERENCES bank_account_transactions(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,maybe_nexus_response TEXT NULL
  ,submission_time BIGINT NULL
  );

CREATE TABLE IF NOT EXISTS cashout_operations 
  (id BIGSERIAL PRIMARY KEY
  ,uuid uuid NOT NULL
  ,amount_debit TEXT NOT NULL
  ,amount_credit TEXT NOT NULL
  ,buy_at_ratio TEXT NOT NULL
  ,buy_in_fee TEXT NOT NULL
  ,sell_at_ratio TEXT NOT NULL
  ,sell_out_fee TEXT NOT NULL
  ,subject TEXT NOT NULL
  ,creation_time BIGINT NOT NULL
  ,confirmation_time BIGINT NULL
  ,tan_channel INT NOT NULL
  ,account TEXT NOT NULL
  ,cashout_address TEXT NOT NULL
  ,tan TEXT NOT NULL
  ,status INT DEFAULT 1 NOT NULL
  );

-- end of: cashout management

-- start of: EBICS management

CREATE TABLE IF NOT EXISTS ebics_hosts 
  (id SERIAL PRIMARY KEY
  ,host_id TEXT NOT NULL
  ,ebics_version TEXT NOT NULL
  ,signature_private_key bytea NOT NULL
  ,encryption_private_key bytea NOT NULL
  ,authentication_private_key bytea NOT NULL
  );

CREATE TABLE IF NOT EXISTS ebics_subscribers 
  (id SERIAL PRIMARY KEY
  ,user_id TEXT NOT NULL
  ,partner_id TEXT NOT NULL
  ,system_id TEXT NULL
  ,host_id TEXT NOT NULL
  ,signature_key INT NULL REFERENCES ebics_subscriber_public_keys(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,encryption_key INT NULL REFERENCES ebics_subscriber_public_keys(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,authorization_key INT NULL REFERENCES ebics_subscriber_public_keys(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,next_order_id INT NOT NULL
  ,state INT NOT NULL
  ,bank_account INT NULL REFERENCES bank_accounts(id) ON DELETE CASCADE ON UPDATE RESTRICT
  );

CREATE TABLE IF NOT EXISTS ebics_subscriber_public_keys
  (id SERIAL PRIMARY KEY
   ,rsa_public_key bytea NOT NULL
   ,state INT NOT NULL
  );

CREATE TABLE IF NOT EXISTS ebics_download_transactions 
  (transaction_id TEXT NOT NULL
  ,order_type TEXT NOT NULL
  ,host INT NOT NULL REFERENCES ebics_hosts(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,subscriber INT NOT NULL REFERENCES ebics_subscribers(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,encoded_response TEXT NOT NULL
  ,transaction_key_enc bytea NOT NULL
  ,num_segments INT NOT NULL
  ,segment_size INT NOT NULL
  ,receipt_received BOOLEAN NOT NULL
  );

CREATE TABLE IF NOT EXISTS ebics_upload_transactions 
  (transaction_id TEXT NOT NULL
  ,order_type TEXT NOT NULL
  ,order_id TEXT NOT NULL
  ,host INT NOT NULL REFERENCES ebics_hosts(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,subscriber INT NOT NULL REFERENCES ebics_subscribers(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,num_segments INT NOT NULL
  ,last_seen_segment INT NOT NULL
  ,transaction_key_enc bytea NOT NULL
  );

CREATE TABLE IF NOT EXISTS ebics_upload_transaction_chunks 
  (transaction_id TEXT NOT NULL
  ,chunk_index INT NOT NULL
  ,chunk_content bytea NOT NULL
  );

CREATE TABLE IF NOT EXISTS ebics_order_signatures 
  (id SERIAL PRIMARY KEY
  ,order_id TEXT NOT NULL
  ,order_type TEXT NOT NULL
  ,partner_id TEXT NOT NULL
  ,user_id TEXT NOT NULL
  ,signature_algorithm TEXT NOT NULL
  ,signature_value bytea NOT NULL
  );

-- end of: EBICS management

-- start of: accounts activity report 

CREATE TABLE IF NOT EXISTS bank_account_fresh_transactions 
  (id BIGSERIAL PRIMARY KEY
  ,transaction FIXME_TYPE NOT NULL REFERENCES bank_account_transactions(id) ON DELETE CASCADE ON UPDATE RESTRICT
  );

CREATE TABLE IF NOT EXISTS bank_account_reports 
  (id SERIAL PRIMARY KEY
  ,report_id TEXT NOT NULL
  ,creation_time BIGINT NOT NULL
  ,xml_message TEXT NOT NULL
  ,bank_account INT NOT NULL REFERENCES bank_accounts(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );

CREATE TABLE IF NOT EXISTS bank_account_statements 
  (id SERIAL PRIMARY KEY
  ,statement_id TEXT NOT NULL
  ,creation_time BIGINT NOT NULL
  ,xml_message TEXT NOT NULL
  ,bank_account INT NOT NULL REFERENCES bank_accounts(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  ,balance_clbd TEXT NOT NULL
  );

-- end of: accounts activity report 

-- start of: Taler integration

CREATE TABLE IF NOT EXISTS taler_withdrawals 
  (id BIGSERIAL PRIMARY KEY
  ,wopid uuid NOT NULL
  ,amount TEXT NOT NULL
  ,selection_done BOOLEAN DEFAULT false NOT NULL
  ,aborted BOOLEAN DEFAULT false NOT NULL
  ,confirmation_done BOOLEAN DEFAULT false NOT NULL
  ,reserve_pub TEXT NULL
  ,selected_exchange_payto TEXT NULL
  ,wallet_bank_account INT NOT NULL REFERENCES bank_accounts(id) ON DELETE RESTRICT ON UPDATE RESTRICT
  );

-- end of: Taler integration

COMMIT;
