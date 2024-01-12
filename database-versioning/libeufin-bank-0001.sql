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
  AS (val INT8 ,frac INT4);
COMMENT ON TYPE taler_amount
  IS 'Stores an amount, fraction is in units of 1/100000000 of the base value';

-- Indicates whether a transaction is incoming or outgoing.
CREATE TYPE direction_enum
  AS ENUM ('credit', 'debit');

CREATE TYPE token_scope_enum
  AS ENUM ('readonly', 'readwrite');

CREATE TYPE tan_enum
  AS ENUM ('sms', 'email');

CREATE TYPE cashout_status_enum
  AS ENUM ('pending', 'confirmed');

CREATE TYPE subscriber_key_state_enum
  AS ENUM ('new', 'invalid', 'confirmed');

CREATE TYPE subscriber_state_enum
  AS ENUM ('new', 'confirmed');

CREATE TYPE stat_timeframe_enum
  AS ENUM ('hour', 'day', 'month', 'year');

CREATE TYPE rounding_mode
  AS ENUM ('zero', 'up', 'nearest');


-- FIXME: comments on types (see exchange for example)!

-- start of: bank accounts

CREATE TABLE IF NOT EXISTS customers
  (customer_id INT8 GENERATED BY DEFAULT AS IDENTITY UNIQUE
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

CREATE TABLE IF NOT EXISTS bank_accounts 
  (bank_account_id INT8 GENERATED BY DEFAULT AS IDENTITY UNIQUE
  ,internal_payto_uri TEXT NOT NULL UNIQUE
  ,owning_customer_id INT8 NOT NULL UNIQUE -- UNIQUE enforces 1-1 map with customers
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

CREATE TABLE IF NOT EXISTS bearer_tokens
  (bearer_token_id INT8 GENERATED BY DEFAULT AS IDENTITY UNIQUE
  ,content BYTEA NOT NULL UNIQUE CHECK (LENGTH(content)=32)
  ,creation_time INT8
  ,expiration_time INT8
  ,scope token_scope_enum
  ,is_refreshable BOOLEAN
  ,bank_customer INT8 NOT NULL 
    REFERENCES customers(customer_id)
    ON DELETE CASCADE
);
COMMENT ON TABLE bearer_tokens
  IS 'Login tokens associated with one bank customer.';
COMMENT ON COLUMN bearer_tokens.bank_customer
  IS 'The customer that directly created this token, or the customer that'
     ' created the very first token that originated all the refreshes until'
     ' this token was created.';

CREATE TABLE IF NOT EXISTS iban_history 
  (iban TEXT PRIMARY KEY
  ,creation_time INT8 NOT NULL
  );
COMMENT ON TABLE iban_history IS 'Track all generated iban, some might be unused.';

-- end of: bank accounts

-- start of: money transactions

CREATE TABLE IF NOT EXISTS bank_account_transactions 
  (bank_transaction_id INT8 GENERATED BY DEFAULT AS IDENTITY UNIQUE
  ,creditor_payto_uri TEXT NOT NULL
  ,creditor_name TEXT NOT NULL
  ,debtor_payto_uri TEXT NOT NULL
  ,debtor_name TEXT NOT NULL
  ,subject TEXT NOT NULL
  ,amount taler_amount NOT NULL
  ,transaction_date INT8 NOT NULL
  ,account_servicer_reference TEXT
  ,payment_information_id TEXT
  ,end_to_end_id TEXT
  ,direction direction_enum NOT NULL
  ,bank_account_id INT8 NOT NULL REFERENCES bank_accounts(bank_account_id)
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

-- start of: TAN challenge
CREATE TABLE IF NOT EXISTS challenges
  (challenge_id INT8 GENERATED BY DEFAULT AS IDENTITY UNIQUE,
   code TEXT NOT NULL,
   creation_date INT8 NOT NULL,
   expiration_date INT8 NOT NULL,
   retransmission_date INT8 NOT NULL DEFAULT 0,
   retry_counter INT4 NOT NULL,
   confirmation_date INT8 DEFAULT NULL);
COMMENT ON TABLE challenges
  IS 'Stores a code which is checked for the authentication by SMS, E-Mail..';
COMMENT ON COLUMN challenges.code
  IS 'The pin code which is sent to the user and verified';
COMMENT ON COLUMN challenges.creation_date
  IS 'Creation date of the code';
COMMENT ON COLUMN challenges.retransmission_date
  IS 'When did we last transmit the challenge to the user';
COMMENT ON COLUMN challenges.expiration_date
  IS 'When will the code expire';
COMMENT ON COLUMN challenges.retry_counter
  IS 'How many tries are left for this code must be > 0';
COMMENT ON COLUMN challenges.confirmation_date
  IS 'When was this challenge successfully verified, NULL if pending';

-- end of: TAN challenge

-- start of: cashout management

CREATE TABLE IF NOT EXISTS cashout_operations 
  (cashout_id INT8 GENERATED BY DEFAULT AS IDENTITY UNIQUE
  ,request_uid BYTEA NOT NULL PRIMARY KEY CHECK (LENGTH(request_uid)=32)
  ,amount_debit taler_amount NOT NULL
  ,amount_credit taler_amount NOT NULL
  ,subject TEXT NOT NULL
  ,creation_time INT8 NOT NULL
  ,bank_account INT8 NOT NULL
    REFERENCES bank_accounts(bank_account_id)
  ,challenge INT8 NOT NULL UNIQUE
    REFERENCES challenges(challenge_id)
      ON DELETE SET NULL
  ,tan_channel TEXT NULL DEFAULT NULL
  ,tan_info TEXT NULL DEFAULT NULL
  ,aborted BOOLEAN NOT NULL DEFAULT FALSE
  ,local_transaction INT8 UNIQUE DEFAULT NULL
    REFERENCES bank_account_transactions(bank_transaction_id)
      ON DELETE CASCADE
  );
COMMENT ON COLUMN cashout_operations.bank_account IS 'Bank amount to debit during confirmation';
COMMENT ON COLUMN cashout_operations.challenge IS 'TAN challenge used to confirm the operation';
COMMENT ON COLUMN cashout_operations.local_transaction IS 'Transaction generated during confirmation';
COMMENT ON COLUMN cashout_operations.tan_channel IS 'Channel of the last successful transmission of the TAN challenge';
COMMENT ON COLUMN cashout_operations.tan_info IS 'Info of the last successful transmission of the TAN challenge';

-- end of: cashout management

-- start of: Taler integration
CREATE TABLE IF NOT EXISTS taler_exchange_outgoing
  (exchange_outgoing_id INT8 GENERATED BY DEFAULT AS IDENTITY
  ,request_uid BYTEA UNIQUE CHECK (LENGTH(request_uid)=64)
  ,wtid BYTEA NOT NULL UNIQUE CHECK (LENGTH(wtid)=32)
  ,exchange_base_url TEXT NOT NULL
  ,bank_transaction INT8 UNIQUE NOT NULL
    REFERENCES bank_account_transactions(bank_transaction_id)
      ON DELETE CASCADE
  ,creditor_account_id INT8 NOT NULL
    REFERENCES bank_accounts(bank_account_id)
  );

CREATE TABLE IF NOT EXISTS taler_exchange_incoming
  (exchange_incoming_id INT8 GENERATED BY DEFAULT AS IDENTITY
  ,reserve_pub BYTEA NOT NULL UNIQUE CHECK (LENGTH(reserve_pub)=32)
  ,bank_transaction INT8 UNIQUE NOT NULL
    REFERENCES bank_account_transactions(bank_transaction_id)
      ON DELETE CASCADE
  );

CREATE TABLE IF NOT EXISTS taler_withdrawal_operations
  (withdrawal_id INT8 GENERATED BY DEFAULT AS IDENTITY
  ,withdrawal_uuid uuid NOT NULL UNIQUE
  ,amount taler_amount NOT NULL
  ,selection_done BOOLEAN DEFAULT FALSE NOT NULL
  ,aborted BOOLEAN DEFAULT FALSE NOT NULL
  ,confirmation_done BOOLEAN DEFAULT FALSE NOT NULL
  ,reserve_pub BYTEA UNIQUE CHECK (LENGTH(reserve_pub)=32)
  ,subject TEXT
  ,selected_exchange_payto TEXT
  ,wallet_bank_account INT8 NOT NULL
    REFERENCES bank_accounts(bank_account_id)
      ON DELETE CASCADE
  );
COMMENT ON COLUMN taler_withdrawal_operations.selection_done
  IS 'Signals whether the wallet specified the exchange and gave the reserve public key';
COMMENT ON COLUMN taler_withdrawal_operations.confirmation_done
  IS 'Signals whether the payment to the exchange took place';

-- end of: Taler integration

-- start of: Statistics
CREATE TABLE IF NOT EXISTS bank_stats (
  timeframe stat_timeframe_enum NOT NULL
  ,start_time timestamp NOT NULL
  ,taler_in_count INT8 NOT NULL DEFAULT 0
  ,taler_in_volume taler_amount NOT NULL DEFAULT (0, 0)
  ,taler_out_count INT8 NOT NULL DEFAULT 0
  ,taler_out_volume taler_amount NOT NULL DEFAULT (0, 0)
  ,cashin_count INT8 NOT NULL DEFAULT 0
  ,cashin_regional_volume taler_amount NOT NULL DEFAULT (0, 0)
  ,cashin_fiat_volume taler_amount NOT NULL DEFAULT (0, 0)
  ,cashout_count INT8 NOT NULL DEFAULT 0
  ,cashout_regional_volume taler_amount NOT NULL DEFAULT (0, 0)
  ,cashout_fiat_volume taler_amount NOT NULL DEFAULT (0, 0)
  ,PRIMARY KEY (start_time, timeframe) 
);
COMMENT ON TABLE bank_stats 
  IS 'Stores statistics about the bank usage.';
COMMENT ON COLUMN bank_stats.timeframe 
  IS 'particular timeframe that this row accounts for';
COMMENT ON COLUMN bank_stats.start_time 
  IS 'timestamp of the start of the timeframe that this row accounts for, truncated according to the precision of the timeframe';
COMMENT ON COLUMN bank_stats.taler_out_count
  IS 'how many internal payments were made by a Taler exchange';
COMMENT ON COLUMN bank_stats.taler_out_volume
  IS 'how much internal currency was paid by a Taler exchange';
COMMENT ON COLUMN bank_stats.taler_in_count
  IS 'how many internal payments were made to a Taler exchange';
COMMENT ON COLUMN bank_stats.taler_in_volume
  IS 'how much internal currency was paid to a Taler exchange';
COMMENT ON COLUMN bank_stats.cashin_count
  IS 'how many cashin operations took place in the timeframe';
COMMENT ON COLUMN bank_stats.cashin_regional_volume
  IS 'how much regional currency was cashed in in the timeframe';
COMMENT ON COLUMN bank_stats.cashin_fiat_volume
  IS 'how much fiat currency was cashed in in the timeframe';
COMMENT ON COLUMN bank_stats.cashout_count
  IS 'how many cashout operations took place in the timeframe';
COMMENT ON COLUMN bank_stats.cashout_regional_volume
  IS 'how much regional currency was payed by the bank to customers in the timeframe';
COMMENT ON COLUMN bank_stats.cashout_fiat_volume 
  IS 'how much fiat currency was payed by the bank to customers in the timeframe';

-- end of: Statistics

-- start of: Conversion

CREATE TABLE IF NOT EXISTS config (
  key TEXT NOT NULL PRIMARY KEY,
  value JSONB NOT NULL
);

-- end of: Conversion

COMMIT;