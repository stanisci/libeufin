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

SELECT _v.register_patch('libeufin-nexus-0001', NULL, NULL);

CREATE SCHEMA libeufin_nexus;
SET search_path TO libeufin_nexus;

CREATE TYPE taler_amount
  AS
  (val INT8
  ,frac INT4
  );
COMMENT ON TYPE taler_amount
  IS 'Stores an amount, fraction is in units of 1/100000000 of the base value';

CREATE TABLE IF NOT EXISTS incoming_transactions
  (incoming_transaction_id INT8 GENERATED BY DEFAULT AS IDENTITY
  ,amount taler_amount NOT NULL
  ,wire_transfer_subject TEXT
  ,execution_time INT8 NOT NULL
  ,debit_payto_uri TEXT NOT NULL
  ,bank_transfer_id TEXT NOT NULL -- EBICS or Depolymerizer (generic)
  ,bounced BOOL DEFAULT FALSE -- to track if we bounced it
  );

CREATE TABLE IF NOT EXISTS outgoing_transactions
  (outgoing_transaction_id INT8 GENERATED BY DEFAULT AS IDENTITY UNIQUE
  ,amount taler_amount NOT NULL
  ,wire_transfer_subject TEXT
  ,execution_time INT8 NOT NULL
  ,credit_payto_uri TEXT NOT NULL
  ,bank_transfer_id TEXT NOT NULL
  );

CREATE TABLE IF NOT EXISTS initiated_outgoing_transactions
  (initiated_outgoing_transaction_id INT8 GENERATED BY DEFAULT AS IDENTITY UNIQUE -- used as our ID in PAIN
  ,amount taler_amount NOT NULL
  ,wire_transfer_subject TEXT
  ,execution_time INT8 NOT NULL
  ,credit_payto_uri TEXT NOT NULL
  ,outgoing_transaction_id INT8 REFERENCES outgoing_transactions (outgoing_transaction_id)
  ,submitted BOOL DEFAULT FALSE 
  ,hidden BOOL DEFAULT FALSE -- FIXME: explain this.
  ,client_request_uuid TEXT UNIQUE
  ,failure_message TEXT -- NOTE: that may mix soon failures (those found at initiation time), or late failures (those found out along a fetch operation)
    );

COMMENT ON COLUMN initiated_outgoing_transactions.outgoing_transaction_id
    IS 'Points to the bank transaction that was found via nexus-fetch.  If "submitted" is false or nexus-fetch could not download this initiation, this column is expected to be NULL.';

COMMIT;
