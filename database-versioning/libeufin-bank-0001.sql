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
  AS ENUM ('hour', 'day', 'month', 'year');

CREATE TYPE rounding_mode
  AS ENUM ('nearest', 'up', 'down'); -- up is toward infinity and down toward zero


-- FIXME: comments on types (see exchange for example)!

-- start of: bank accounts

CREATE TABLE IF NOT EXISTS customers
  (customer_id BIGINT GENERATED BY DEFAULT AS IDENTITY UNIQUE
  ,login TEXT NOT NULL UNIQUE
  ,password_hash TEXT NOT NULL
  ,name TEXT
  ,email TEXT
  ,phone TEXT
  ,cashout_payto TEXT
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
  (cashout_uuid uuid NOT NULL PRIMARY KEY
  ,amount_debit taler_amount NOT NULL
  ,amount_credit taler_amount NOT NULL
  ,subject TEXT NOT NULL
  ,creation_time BIGINT NOT NULL
  ,bank_account BIGINT NOT NULL
    REFERENCES bank_accounts(bank_account_id)
    ON DELETE CASCADE
    ON UPDATE RESTRICT
  ,tan_channel tan_enum NOT NULL
  ,tan_code TEXT NOT NULL
  ,tan_confirmation_time BIGINT DEFAULT NULL
  ,aborted BOOLEAN NOT NULL DEFAULT FALSE
  ,local_transaction BIGINT UNIQUE DEFAULT NULL-- FIXME: Comment that the transaction only gets created after the TAN confirmation
    REFERENCES bank_account_transactions(bank_transaction_id)
    ON DELETE RESTRICT
    ON UPDATE RESTRICT
  );

-- FIXME: table comment missing

COMMENT ON COLUMN cashout_operations.tan_confirmation_time
  IS 'Timestamp when the customer confirmed the cash-out operation via TAN';
COMMENT ON COLUMN cashout_operations.tan_code
  IS 'text that the customer must send to confirm the cash-out operation';

-- end of: cashout management

-- start of: Taler integration
CREATE TABLE IF NOT EXISTS taler_exchange_outgoing
  (exchange_outgoing_id BIGINT GENERATED BY DEFAULT AS IDENTITY
  ,request_uid BYTEA UNIQUE CHECK (LENGTH(request_uid)=64)
  ,wtid BYTEA NOT NULL UNIQUE CHECK (LENGTH(wtid)=32)
  ,exchange_base_url TEXT NOT NULL
  ,bank_transaction BIGINT UNIQUE NOT NULL
    REFERENCES bank_account_transactions(bank_transaction_id)
      ON DELETE RESTRICT
      ON UPDATE RESTRICT
  );

CREATE TABLE IF NOT EXISTS taler_exchange_incoming
  (exchange_incoming_id BIGINT GENERATED BY DEFAULT AS IDENTITY
  ,reserve_pub BYTEA NOT NULL UNIQUE CHECK (LENGTH(reserve_pub)=32)
  ,bank_transaction BIGINT UNIQUE NOT NULL
    REFERENCES bank_account_transactions(bank_transaction_id)
      ON DELETE RESTRICT
      ON UPDATE RESTRICT
  );

CREATE TABLE IF NOT EXISTS taler_withdrawal_operations
  (withdrawal_uuid uuid NOT NULL PRIMARY KEY
  ,amount taler_amount NOT NULL
  ,selection_done BOOLEAN DEFAULT FALSE NOT NULL
  ,aborted BOOLEAN DEFAULT FALSE NOT NULL
  ,confirmation_done BOOLEAN DEFAULT FALSE NOT NULL
  ,reserve_pub BYTEA UNIQUE CHECK (LENGTH(reserve_pub)=32)
  ,subject TEXT
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

-- start of: Statistics
CREATE TABLE IF NOT EXISTS regional_stats (
  timeframe stat_timeframe_enum NOT NULL
  ,start_time timestamp NOT NULL
  ,cashin_count BIGINT NOT NULL
  ,cashin_volume_in_fiat taler_amount NOT NULL
  ,cashout_count BIGINT NOT NULL
  ,cashout_volume_in_fiat taler_amount NOT NULL
  ,internal_taler_payments_count BIGINT NOT NULL
  ,internal_taler_payments_volume taler_amount NOT NULL
  ,PRIMARY KEY (start_time, timeframe) 
);
-- TODO garbage collection
COMMENT ON TABLE regional_stats IS 'Stores statistics about the regional currency usage.';
COMMENT ON COLUMN regional_stats.timeframe IS 'particular timeframe that this row accounts for';
COMMENT ON COLUMN regional_stats.start_time IS 'timestamp of the start of the timeframe that this row accounts for, truncated according to the precision of the timeframe';
COMMENT ON COLUMN regional_stats.cashin_count IS 'how many cashin operations took place in the timeframe';
COMMENT ON COLUMN regional_stats.cashin_volume_in_fiat IS 'how much fiat currency was cashed in in the timeframe';
COMMENT ON COLUMN regional_stats.cashout_count IS 'how many cashout operations took place in the timeframe';
COMMENT ON COLUMN regional_stats.cashout_volume_in_fiat IS 'how much fiat currency was payed by the bank to customers in the timeframe';
COMMENT ON COLUMN regional_stats.internal_taler_payments_count IS 'how many internal payments were made by a Taler exchange';
COMMENT ON COLUMN regional_stats.internal_taler_payments_volume IS 'how much internal currency was paid by a Taler exchange';

-- end of: Statistics

-- start of: Conversion

CREATE TABLE IF NOT EXISTS config (
  key TEXT NOT NULL PRIMARY KEY,
  value JSONB NOT NULL
);

-- end of: Conversion

COMMIT;
