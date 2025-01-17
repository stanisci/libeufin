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
  AS (val INT8, frac INT4);
COMMENT ON TYPE taler_amount
  IS 'Stores an amount, fraction is in units of 1/100000000 of the base value';

CREATE TYPE submission_state AS ENUM
  ('unsubmitted'
  ,'transient_failure'
  ,'permanent_failure'
  ,'success'
  ,'never_heard_back'
  );
COMMENT ON TYPE submission_state
  IS 'expresses the state of an initiated outgoing transaction, where
  unsubmitted is the default.  transient_failure suggests that the submission
  should be retried, in contrast to the permanent_failure state.  success
  means that the submission itself was successful, but in no way means that
  the bank will fulfill the request.  That must be asked via camt.5x or pain.002.
  never_heard_back is a fallback state, in case one successful submission did
  never get confirmed via camt.5x or pain.002.';

CREATE TABLE incoming_transactions
  (incoming_transaction_id INT8 GENERATED BY DEFAULT AS IDENTITY UNIQUE
  ,amount taler_amount NOT NULL
  ,wire_transfer_subject TEXT NOT NULL
  ,execution_time INT8 NOT NULL
  ,debit_payto_uri TEXT NOT NULL
  ,bank_id TEXT NOT NULL UNIQUE
  );
COMMENT ON COLUMN incoming_transactions.bank_id
  IS 'ISO20022 AccountServicerReference';

-- only active in exchange mode. Note: duplicate keys are another reason to bounce.
CREATE TABLE talerable_incoming_transactions
  (incoming_transaction_id INT8 NOT NULL UNIQUE REFERENCES incoming_transactions(incoming_transaction_id) ON DELETE CASCADE
   ,reserve_public_key BYTEA NOT NULL UNIQUE CHECK (LENGTH(reserve_public_key)=32)
  );

CREATE TABLE outgoing_transactions
  (outgoing_transaction_id INT8 GENERATED BY DEFAULT AS IDENTITY UNIQUE
  ,amount taler_amount NOT NULL
  ,wire_transfer_subject TEXT
  ,execution_time INT8 NOT NULL
  ,credit_payto_uri TEXT
  ,message_id TEXT NOT NULL UNIQUE
  );
COMMENT ON COLUMN outgoing_transactions.message_id
  IS 'ISO20022 MessageIdentification';

CREATE TABLE initiated_outgoing_transactions
  (initiated_outgoing_transaction_id INT8 GENERATED BY DEFAULT AS IDENTITY UNIQUE
  ,amount taler_amount NOT NULL
  ,wire_transfer_subject TEXT NOT NULL
  ,initiation_time INT8 NOT NULL
  ,last_submission_time INT8
  ,submission_counter INT NOT NULL DEFAULT 0
  ,credit_payto_uri TEXT NOT NULL
  ,outgoing_transaction_id INT8 UNIQUE REFERENCES outgoing_transactions (outgoing_transaction_id)
  ,submitted submission_state DEFAULT 'unsubmitted'
  ,hidden BOOL DEFAULT FALSE -- FIXME: explain this.
  ,request_uid TEXT NOT NULL UNIQUE CHECK (char_length(request_uid) <= 35)
  ,failure_message TEXT -- NOTE: that may mix soon failures (those found at initiation time), or late failures (those found out along a fetch operation)
  );
COMMENT ON COLUMN initiated_outgoing_transactions.outgoing_transaction_id
  IS 'Points to the bank transaction that was found via nexus-fetch.  If "submitted" is false or nexus-fetch could not download this initiation, this column is expected to be NULL.';
COMMENT ON COLUMN initiated_outgoing_transactions.request_uid
  IS 'Unique identifier of this outgoing transaction initiation.
This value could come both from a nexus-httpd client or directly
generated when nexus-fetch bounces one payment.  In both cases, this
value will be used as a unique identifier for its related pain.001 document.
For this reason, it must have at most 35 characters';

-- only active in exchange mode.
CREATE TABLE bounced_transactions
  (incoming_transaction_id INT8 NOT NULL UNIQUE REFERENCES incoming_transactions(incoming_transaction_id) ON DELETE CASCADE
   ,initiated_outgoing_transaction_id INT8 NOT NULL UNIQUE REFERENCES initiated_outgoing_transactions(initiated_outgoing_transaction_id) ON DELETE CASCADE
  );

CREATE INDEX incoming_transaction_timestamp
  ON incoming_transactions (execution_time);

CREATE INDEX outgoing_transaction_timestamp
  ON outgoing_transactions (execution_time);

COMMIT;
