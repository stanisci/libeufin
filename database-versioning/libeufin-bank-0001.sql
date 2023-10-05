--
-- This file is part of TALER
-- Copyright (C) 2023 Taler Systems SA
--
-- TALER is free software; you can redistribute it and/or modify it under the
-- terms of the GNU General Public License as published by the Free Software
-- Foundation; either version 3, or (at your option) any later version.
--
-- TALER is distributed in the hope that it will be useful, but WITHOUT ANY
-- WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
-- A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
--
-- You should have received a copy of the GNU General Public License along with
-- TALER; see the file COPYING.  If not, see <http://www.gnu.org/licenses/>

BEGIN;

SELECT _v.register_patch('libeufin-bank-0001', NULL, NULL);

CREATE SCHEMA libeufin_bank;
SET search_path TO libeufin_bank;

CREATE TYPE taler_amount
  AS
  (val INT8
  ,frac INT4
  );
COMMENT ON TYPE taler_amount
  IS 'Stores an amount, fraction is in units of 1/100000000 of the base value';

-- Indicates whether a transaction is incoming or outgoing.
CREATE TYPE direction_enum
  AS ENUM ('credit', 'debit');

CREATE TYPE token_scope_enum
  AS ENUM ('readonly', 'readwrite');

CREATE TYPE tan_enum
  AS ENUM ('sms', 'email', 'file'); -- file is for testing purposes.

CREATE TYPE cashout_status_enum
  AS ENUM ('pending', 'confirmed');

CREATE TYPE subscriber_key_state_enum
  AS ENUM ('new', 'invalid', 'confirmed');

CREATE TYPE subscriber_state_enum
  AS ENUM ('new', 'confirmed');

CREATE TYPE stat_timeframe_enum
  AS ENUM ('hour', 'day', 'month', 'year', 'decade');

-- FIXME: comments on types (see exchange for example)!

-- start of: bank accounts

CREATE TABLE IF NOT EXISTS customers
  (customer_id BIGINT GENERATED BY DEFAULT AS IDENTITY UNIQUE
  ,login TEXT NOT NULL UNIQUE
  ,password_hash TEXT NOT NULL
  ,name TEXT
  ,email TEXT
  ,phone TEXT
  ,cashout_payto TEXT -- here because has no business meaning inside libeufin-bank
  ,cashout_currency TEXT
  );

COMMENT ON COLUMN customers.cashout_payto
  IS 'RFC 8905 payto URI to collect fiat payments that come from the conversion of regional currency cash-out operations.';
COMMENT ON COLUMN customers.name
  IS 'Full name of the customer.';

CREATE TABLE IF NOT EXISTS bearer_tokens
  (bearer_token_id BIGINT GENERATED BY DEFAULT AS IDENTITY UNIQUE
  ,content BYTEA NOT NULL UNIQUE CHECK (LENGTH(content)=32)
  ,creation_time INT8
  ,expiration_time INT8
  ,scope token_scope_enum
  ,is_refreshable BOOLEAN
  ,bank_customer BIGINT NOT NULL REFERENCES customers(customer_id) ON DELETE CASCADE
);

COMMENT ON TABLE bearer_tokens
  IS 'Login tokens associated with one bank customer.  There is currently'
     ' no garbage collector that deletes the expired tokens from the table';

COMMENT ON COLUMN bearer_tokens.bank_customer
  IS 'The customer that directly created this token, or the customer that'
     ' created the very first token that originated all the refreshes until'
     ' this token was created.';

CREATE TABLE IF NOT EXISTS bank_accounts 
  (bank_account_id BIGINT GENERATED BY DEFAULT AS IDENTITY UNIQUE
  ,internal_payto_uri TEXT NOT NULL UNIQUE
  ,owning_customer_id BIGINT NOT NULL UNIQUE -- UNIQUE enforces 1-1 map with customers
    REFERENCES customers(customer_id)
    ON DELETE CASCADE
  ,is_public BOOLEAN DEFAULT FALSE NOT NULL -- privacy by default
  ,is_taler_exchange BOOLEAN DEFAULT FALSE NOT NULL
  ,last_nexus_fetch_row_id BIGINT
  ,balance taler_amount DEFAULT (0, 0)
  ,max_debt taler_amount DEFAULT (0, 0)
  ,has_debt BOOLEAN NOT NULL DEFAULT FALSE
  );

COMMENT ON TABLE bank_accounts
  IS 'In Sandbox, usernames (AKA logins) are different entities
respect to bank accounts (in contrast to what the Python bank
did).  The idea was to provide multiple bank accounts to one
user.  Nonetheless, for simplicity the current version enforces
one bank account for one user, and additionally the bank
account label matches always the login.';
COMMENT ON COLUMN bank_accounts.has_debt
  IS 'When true, the balance is negative';
COMMENT ON COLUMN bank_accounts.last_nexus_fetch_row_id
  IS 'Keeps the ID of the last incoming payment that was learnt
from Nexus.  For that reason, this ID is stored verbatim as
it was returned by Nexus.  It helps to build queries to Nexus
that needs this value as a parameter.';

COMMENT ON COLUMN bank_accounts.is_public
  IS 'Indicates whether the bank account history
can be publicly shared';

COMMENT ON COLUMN bank_accounts.owning_customer_id
  IS 'Login that owns the bank account';

-- end of: bank accounts

-- start of: money transactions

CREATE TABLE IF NOT EXISTS bank_account_transactions 
  (bank_transaction_id BIGINT GENERATED BY DEFAULT AS IDENTITY UNIQUE
  ,creditor_payto_uri TEXT NOT NULL
  ,creditor_name TEXT NOT NULL
  ,debtor_payto_uri TEXT NOT NULL
  ,debtor_name TEXT NOT NULL
  ,subject TEXT NOT NULL
  ,amount taler_amount NOT NULL
  ,transaction_date BIGINT NOT NULL -- is this ISO20022 terminology? document format (microseconds since epoch)
  ,account_servicer_reference TEXT NOT NULL
  ,payment_information_id TEXT
  ,end_to_end_id TEXT
  ,direction direction_enum NOT NULL
  ,bank_account_id BIGINT NOT NULL
    REFERENCES bank_accounts(bank_account_id)
    ON DELETE CASCADE ON UPDATE RESTRICT
  );

COMMENT ON COLUMN bank_account_transactions.direction
  IS 'Indicates whether the transaction is incoming or outgoing for the bank account associated with this transaction.';
COMMENT ON COLUMN bank_account_transactions.payment_information_id
  IS 'ISO20022 specific';
COMMENT ON COLUMN bank_account_transactions.end_to_end_id
  IS 'ISO20022 specific';
COMMENT ON COLUMN bank_account_transactions.bank_account_id
  IS 'The bank account affected by this transaction.';

-- end of: money transactions

-- start of: cashout management

CREATE TABLE IF NOT EXISTS cashout_operations 
  (cashout_operation_id BIGINT GENERATED BY DEFAULT AS IDENTITY UNIQUE
  ,cashout_uuid uuid PRIMARY KEY
  ,local_transaction BIGINT UNIQUE -- FIXME: Comment that the transaction only gets created after the TAN confirmation
    REFERENCES bank_account_transactions(bank_transaction_id)
    ON DELETE RESTRICT
    ON UPDATE RESTRICT
  ,amount_debit taler_amount NOT NULL -- FIXME: comment on column how to derive the currency
  ,amount_credit taler_amount NOT NULL -- FIXME: comment on column how to derive the currency
  ,buy_at_ratio INT4 NOT NULL -- FIXME: document format (fractional base)
  ,buy_in_fee taler_amount NOT NULL -- FIXME: comment on column how to derive the currency
  ,sell_at_ratio INT4 NOT NULL -- FIXME: document format (fractional base)
  ,sell_out_fee taler_amount NOT NULL -- FIXME: comment on column how to derive the currency
  ,subject TEXT NOT NULL
  ,creation_time BIGINT NOT NULL
  ,tan_confirmation_time BIGINT
  ,tan_channel tan_enum NOT NULL
  ,tan_code TEXT NOT NULL
  ,bank_account BIGINT DEFAULT(NULL)
    REFERENCES bank_accounts(bank_account_id)
    ON DELETE CASCADE
    ON UPDATE RESTRICT
  ,credit_payto_uri TEXT NOT NULL
  ,cashout_currency TEXT NOT NULL -- need, or include in credit_payto_uri?
  );

-- FIXME: table comment missing

COMMENT ON COLUMN cashout_operations.tan_confirmation_time
  IS 'Timestamp when the customer confirmed the cash-out operation via TAN';
COMMENT ON COLUMN cashout_operations.tan_code
  IS 'text that the customer must send to confirm the cash-out operation';

-- FIXME: check in the code if this really only has pending or failed submissions!
CREATE TABLE IF NOT EXISTS pending_cashout_submissions 
  (cashout_submission_id BIGINT GENERATED BY DEFAULT AS IDENTITY
  ,cashout_operation_id BIGINT NOT NULL
    REFERENCES cashout_operations(cashout_operation_id)
    ON DELETE CASCADE
    ON UPDATE RESTRICT
  ,nexus_response TEXT
  ,submission_time BIGINT
  );

COMMENT ON TABLE pending_cashout_submissions
  IS 'Tracks payment requests made from Sandbox to Nexus to trigger fiat transactions that finalize cash-outs.';
COMMENT ON COLUMN pending_cashout_submissions.nexus_response
  IS 'Keeps the Nexus response to the payment submission on failure';


-- end of: cashout management

-- start of: EBICS management

CREATE TABLE IF NOT EXISTS ebics_hosts 
  (ebics_host_id BIGINT GENERATED BY DEFAULT AS IDENTITY UNIQUE
  ,ebics_host_name TEXT NOT NULL
  ,ebics_version TEXT NOT NULL -- FIXME: This should be an enum
  -- FIXME: Do we want to specify the dialect here?
  ,encryption_private_key BYTEA NOT NULL
  ,signature_private_key BYTEA NOT NULL
  );

CREATE TABLE IF NOT EXISTS ebics_subscribers 
  (ebics_subscriber_id BIGINT GENERATED BY DEFAULT AS IDENTITY UNIQUE
  ,ebics_user_id TEXT NOT NULL
  ,ebics_partner_id TEXT NOT NULL
  ,ebics_system_id TEXT
  ,ebics_host_id BIGINT NOT NULL REFERENCES ebics_hosts(ebics_host_id)
  ,signature_key_rsa_pub BYTEA NOT NULL
  ,signature_key_state subscriber_key_state_enum NOT NULL
  ,encryption_key_rsa_pub BYTEA NOT NULL
  ,encryption_key_state subscriber_key_state_enum NOT NULL
  ,subscriber_state subscriber_state_enum DEFAULT 'new' NOT NULL
  -- FIXME: Do we need some information about the next order ID? There is a bug open.
  );
COMMENT ON COLUMN ebics_subscribers.subscriber_state
  IS 'Tracks the state changes of one subscriber.'; -- Really needed?

-- FIXME: comment this table
-- FIXME: indices on both columns individually
CREATE TABLE IF NOT EXISTS ebics_subscribers_of_bank_accounts
  (ebics_subscriber_id BIGINT NOT NULL
    REFERENCES ebics_subscribers(ebics_subscriber_id)
  ,bank_account_id BIGINT NOT NULL
    REFERENCES bank_accounts(bank_account_id)
  );


CREATE TABLE IF NOT EXISTS ebics_download_transactions 
  (transaction_id TEXT PRIMARY KEY
  ,order_type VARCHAR(3) NOT NULL
  -- EBICS3: ,btf_type TEXT -- fixme: document: in EBICS 2.x this can be NULL
  -- FIXME: see what else we need for EBICS 3
  ,ebics_host_id BIGINT NOT NULL
    REFERENCES ebics_hosts(ebics_host_id)
    ON DELETE CASCADE
    ON UPDATE RESTRICT
  ,ebics_subscriber BIGINT NOT NULL
    REFERENCES ebics_subscribers(ebics_subscriber_id)
    ON DELETE CASCADE
    ON UPDATE RESTRICT
  ,encoded_response TEXT NOT NULL
  ,transaction_key_enc BYTEA NOT NULL
  ,num_segments INT NOT NULL
  ,segment_size INT NOT NULL
  ,receipt_received BOOLEAN NOT NULL DEFAULT (FALSE) -- FIXME: Do we need this field if we anyway delete the entry after the receipt?
  -- FIXME: Download start time for garbage collection / timeouts
  );

COMMENT ON TABLE ebics_download_transactions
  IS 'Tracks the evolution of one EBICS download transaction';
COMMENT ON COLUMN ebics_download_transactions.ebics_host_id
  IS 'EBICS host that governs this transaction'; -- exists for a multi-host scenario.

CREATE TABLE IF NOT EXISTS ebics_upload_transactions 
  (ebics_transaction_id BIGINT GENERATED BY DEFAULT AS IDENTITY UNIQUE
  ,order_type VARCHAR(3) NOT NULL
  -- EBICS3: ,btf_type TEXT -- fixme: document: in EBICS 2.x this can be NULL
  -- FIXME: see what else we need for EBICS 3
  ,order_id TEXT NOT NULL
  ,ebics_host BIGINT NOT NULL
    REFERENCES ebics_hosts(ebics_host_id)
      ON DELETE RESTRICT
      ON UPDATE RESTRICT
  ,ebics_subscriber BIGINT NOT NULL
    REFERENCES ebics_subscribers(ebics_subscriber_id)
      ON DELETE RESTRICT
      ON UPDATE RESTRICT
  ,num_segments INT NOT NULL
  ,last_seen_segment INT NOT NULL
  ,transaction_key_enc BYTEA NOT NULL
  -- FIXME: Download start time for garbage collection / timeouts
  );

CREATE TABLE IF NOT EXISTS ebics_upload_transaction_chunks 
  (ebics_transaction_id BIGINT
    REFERENCES ebics_upload_transactions(ebics_transaction_id)
    ON DELETE CASCADE
    ON UPDATE RESTRICT
  ,upload_chunk_index INT NOT NULL
  ,upload_chunk_content BYTEA NOT NULL
  );

-- FIXME: look at the code how it's used
-- I *think* this is only used for upload orders.
-- I am not sure if the signature (especially with VEU)
-- can be uploaded before the order itself has been uploaded
CREATE TABLE IF NOT EXISTS ebics_order_signatures 
  (order_signature_id SERIAL PRIMARY KEY
   ,ebics_transaction_id BIGINT
    REFERENCES ebics_upload_transactions(ebics_transaction_id)
    ON DELETE CASCADE
    ON UPDATE RESTRICT
  ,ebics_subscriber_id BIGINT
    REFERENCES ebics_subscribers(ebics_subscriber_id)
    ON DELETE CASCADE
    ON UPDATE RESTRICT
  -- FIXME: do we also need to reference the ebics host? or does the subscriber uniquely determine it?
  ,order_id TEXT NOT NULL
  ,order_type TEXT NOT NULL
  ,signature_algorithm TEXT NOT NULL
  ,signature_value BYTEA NOT NULL
  );

COMMENT ON TABLE ebics_order_signatures
  IS 'Keeps signature data collected from the subscribers.';

-- end of: EBICS management

-- start of: accounts activity report 

-- Really keep this table?  It tracks the EBICS reports.
CREATE TABLE IF NOT EXISTS bank_account_reports 
  (report_id BIGINT GENERATED BY DEFAULT AS IDENTITY UNIQUE
   ,creation_time BIGINT NOT NULL
   ,xml_message TEXT NOT NULL
   ,bank_account BIGINT NOT NULL
      REFERENCES bank_accounts(bank_account_id)
      ON DELETE CASCADE
      ON UPDATE RESTRICT
  );

-- Really keep this table?  It tracks the EBICS statements
-- mostly because they are supposed never to change.  Not used
CREATE TABLE IF NOT EXISTS bank_account_statements 
  (statement_id BIGINT GENERATED BY DEFAULT AS IDENTITY UNIQUE
  ,creation_time BIGINT NOT NULL
  ,xml_message TEXT NOT NULL
  ,bank_account BIGINT NOT NULL
    REFERENCES bank_accounts(bank_account_id)
    ON DELETE CASCADE
    ON UPDATE RESTRICT
  ,balance_clbd TEXT NOT NULL -- FIXME: name. balance_closing?
  );
-- end of: accounts activity report 

-- start of: Taler integration
CREATE TABLE IF NOT EXISTS taler_exchange_transfers
  (exchange_transfer_id BIGINT GENERATED BY DEFAULT AS IDENTITY
  ,request_uid TEXT NOT NULL UNIQUE
  ,wtid TEXT NOT NULL UNIQUE
  ,exchange_base_url TEXT NOT NULL
  ,credit_account_payto TEXT NOT NULL
  ,amount taler_amount NOT NULL
  ,bank_transaction BIGINT UNIQUE NOT NULL
    REFERENCES bank_account_transactions(bank_transaction_id)
      ON DELETE RESTRICT
      ON UPDATE RESTRICT
  );
COMMENT ON TABLE taler_exchange_transfers
  IS 'Tracks all the requests made by Taler exchanges to pay merchants';
COMMENT ON COLUMN taler_exchange_transfers.bank_transaction
  IS 'Reference to the (outgoing) bank transaction that finalizes the exchange transfer request.';

CREATE TABLE IF NOT EXISTS taler_withdrawal_operations
  (taler_withdrawal_id BIGINT GENERATED BY DEFAULT AS IDENTITY
  ,withdrawal_uuid uuid NOT NULL
  ,amount taler_amount NOT NULL
  ,selection_done BOOLEAN DEFAULT FALSE NOT NULL
  ,aborted BOOLEAN DEFAULT FALSE NOT NULL
  ,confirmation_done BOOLEAN DEFAULT FALSE NOT NULL
  ,reserve_pub TEXT NULL -- Kotlin must check it's valid.
  ,selected_exchange_payto TEXT
  ,wallet_bank_account BIGINT NOT NULL
    REFERENCES bank_accounts(bank_account_id)
      ON DELETE RESTRICT
      ON UPDATE RESTRICT
  );
COMMENT ON COLUMN taler_withdrawal_operations.selection_done
  IS 'Signals whether the wallet specified the exchange and gave the reserve public key';
COMMENT ON COLUMN taler_withdrawal_operations.confirmation_done
  IS 'Signals whether the payment to the exchange took place';

-- end of: Taler integration

CREATE TABLE IF NOT EXISTS regional_stats (
  regional_stats_id BIGINT GENERATED BY DEFAULT AS IDENTITY
  ,cashin_count BIGINT NOT NULL
  ,cashin_volume_in_fiat taler_amount NOT NULL
  ,cashout_count BIGINT NOT NULL
  ,cashout_volume_in_fiat taler_amount NOT NULL
  ,internal_taler_payments_count BIGINT NOT NULL
  ,internal_taler_payments_volume taler_amount NOT NULL
  ,timeframe stat_timeframe_enum NOT NULL
);

COMMENT ON TABLE regional_stats IS
  'Stores statistics about the regional currency usage.  At any given time, this table stores at most: 24 hour rows, N day rows (with N being the highest day number of the current month), 12 month rows, 9 year rows, and any number of decade rows';
COMMENT ON COLUMN regional_stats.cashin_count IS 'how many cashin operations took place in the timeframe';
COMMENT ON COLUMN regional_stats.cashin_volume_in_fiat IS 'how much fiat currency was cashed in in the timeframe';
COMMENT ON COLUMN regional_stats.cashout_count IS 'how many cashout operations took place in the timeframe';
COMMENT ON COLUMN regional_stats.cashout_volume_in_fiat IS 'how much fiat currency was payed by the bank to customers in the timeframe';
COMMENT ON COLUMN regional_stats.internal_taler_payments_count IS 'how many internal payments were made by a Taler exchange';
COMMENT ON COLUMN regional_stats.internal_taler_payments_volume IS 'how much internal currency was paid by a Taler exchange';
COMMENT ON COLUMN regional_stats.timeframe IS 'particular timeframe that this row accounts for';

COMMIT;
