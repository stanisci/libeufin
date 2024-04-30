BEGIN;
SET search_path TO public;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

SET search_path TO libeufin_nexus;

-- Remove all existing functions
DO
$do$
DECLARE
  _sql text;
BEGIN
  SELECT INTO _sql
        string_agg(format('DROP %s %s CASCADE;'
                        , CASE prokind
                            WHEN 'f' THEN 'FUNCTION'
                            WHEN 'p' THEN 'PROCEDURE'
                          END
                        , oid::regprocedure)
                  , E'\n')
  FROM   pg_proc
  WHERE  pronamespace = 'libeufin_nexus'::regnamespace;

  IF _sql IS NOT NULL THEN
    EXECUTE _sql;
  END IF;
END
$do$;

CREATE FUNCTION register_outgoing(
  IN in_amount taler_amount
  ,IN in_wire_transfer_subject TEXT
  ,IN in_execution_time INT8
  ,IN in_credit_payto_uri TEXT
  ,IN in_message_id TEXT
  ,IN in_wtid BYTEA
  ,IN in_exchange_url TEXT
  ,OUT out_tx_id INT8
  ,OUT out_found BOOLEAN
  ,OUT out_initiated BOOLEAN
)
LANGUAGE plpgsql AS $$
DECLARE
init_id INT8;
BEGIN
-- Check if already registered
SELECT outgoing_transaction_id INTO out_tx_id
  FROM outgoing_transactions
  WHERE message_id = in_message_id;
IF FOUND THEN
  out_found = true;
  -- TODO Should we update the subject and credit payto if it's finally found
  -- TODO Should we check that amount and other info match ?
  SELECT true INTO out_initiated
    FROM initiated_outgoing_transactions
    WHERE outgoing_transaction_id = out_tx_id;
ELSE
  -- Store the transaction in the database
  INSERT INTO outgoing_transactions (
    amount
    ,wire_transfer_subject
    ,execution_time
    ,credit_payto_uri
    ,message_id
  ) VALUES (
    in_amount
    ,in_wire_transfer_subject
    ,in_execution_time
    ,in_credit_payto_uri
    ,in_message_id
  )
    RETURNING outgoing_transaction_id
      INTO out_tx_id;

  -- Reconciles the related initiated transaction
  UPDATE initiated_outgoing_transactions
    SET 
      outgoing_transaction_id = out_tx_id
      ,submitted = 'success'
      ,failure_message = null
    WHERE request_uid = in_message_id
    RETURNING true INTO out_initiated;
END IF;

-- Register as talerable if contains wtid and exchange URL
IF in_wtid IS NOT NULL OR in_exchange_url IS NOT NULL THEN
  INSERT INTO talerable_outgoing_transactions (
    outgoing_transaction_id,
    wtid,
    exchange_base_url
  ) VALUES (out_tx_id, in_wtid, in_exchange_url)
    ON CONFLICT (wtid) DO NOTHING;
  IF FOUND THEN
    PERFORM pg_notify('outgoing_tx', out_tx_id::text);
  END IF;
END IF;
END $$;
COMMENT ON FUNCTION register_outgoing
  IS 'Register an outgoing transaction and optionally reconciles the related initiated transaction with it';

CREATE FUNCTION register_incoming(
  IN in_amount taler_amount
  ,IN in_wire_transfer_subject TEXT
  ,IN in_execution_time INT8
  ,IN in_debit_payto_uri TEXT
  ,IN in_bank_id TEXT
  ,OUT out_found BOOLEAN
  ,OUT out_tx_id INT8
)
LANGUAGE plpgsql AS $$
BEGIN
-- Check if already registered
SELECT incoming_transaction_id INTO out_tx_id
  FROM incoming_transactions
  WHERE bank_id = in_bank_id;
IF FOUND THEN
  out_found = true;
  -- TODO Should we check that amount and other info match ?
ELSE
  -- Store the transaction in the database
  INSERT INTO incoming_transactions (
    amount
    ,wire_transfer_subject
    ,execution_time
    ,debit_payto_uri
    ,bank_id
  ) VALUES (
    in_amount
    ,in_wire_transfer_subject
    ,in_execution_time
    ,in_debit_payto_uri
    ,in_bank_id
  ) RETURNING incoming_transaction_id INTO out_tx_id;
  PERFORM pg_notify('revenue_tx', out_tx_id::text);
END IF;
END $$;
COMMENT ON FUNCTION register_incoming
  IS 'Register an incoming transaction';

CREATE FUNCTION bounce_incoming(
  IN tx_id INT8
  ,IN in_bounce_amount taler_amount
  ,IN in_now_date INT8
  ,OUT out_bounce_id TEXT
)
LANGUAGE plpgsql AS $$
DECLARE
local_bank_id TEXT;
payto_uri TEXT;
init_id INT8;
BEGIN
-- Get incoming transaction bank ID and creditor
SELECT bank_id, debit_payto_uri 
  INTO local_bank_id, payto_uri
  FROM incoming_transactions
  WHERE incoming_transaction_id = tx_id;
-- Generate a bounce ID deterministically from the bank ID
-- We hash the bank ID with SHA-256 then we encode the hash using base64
-- As bank id can be at most 35 characters long we truncate the encoded hash
-- We are not sure whether this field is case-insensitive in all banks as the standard 
-- does not clearly specify this, so we have chosen to capitalise it
SELECT upper(substr(encode(public.digest(local_bank_id, 'sha256'), 'base64'), 0, 35)) INTO out_bounce_id;

-- Initiate the bounce transaction
INSERT INTO initiated_outgoing_transactions (
  amount
  ,wire_transfer_subject
  ,credit_payto_uri
  ,initiation_time
  ,request_uid
  ) VALUES (
    in_bounce_amount
    ,'bounce: ' || local_bank_id
    ,payto_uri
    ,in_now_date
    ,out_bounce_id
  )
  ON CONFLICT (request_uid) DO NOTHING -- idempotent
  RETURNING initiated_outgoing_transaction_id INTO init_id;
IF FOUND THEN
  -- Register the bounce
  INSERT INTO bounced_transactions (
    incoming_transaction_id ,initiated_outgoing_transaction_id
  ) VALUES (tx_id, init_id);
END IF;
END$$;
COMMENT ON FUNCTION bounce_incoming
  IS 'Bounce an incoming transaction, initiate a bounce outgoing transaction with a deterministic ID';

CREATE FUNCTION register_incoming_and_bounce(
  IN in_amount taler_amount
  ,IN in_wire_transfer_subject TEXT
  ,IN in_execution_time INT8
  ,IN in_debit_payto_uri TEXT
  ,IN in_bank_id TEXT
  ,IN in_bounce_amount taler_amount
  ,IN in_now_date INT8
  ,OUT out_found BOOLEAN
  ,OUT out_tx_id INT8
  ,OUT out_bounce_id TEXT
)
LANGUAGE plpgsql AS $$
DECLARE
init_id INT8;
BEGIN
-- Register the incoming transaction
SELECT reg.out_found, reg.out_tx_id
  FROM register_incoming(in_amount, in_wire_transfer_subject, in_execution_time, in_debit_payto_uri, in_bank_id) as reg
  INTO out_found, out_tx_id;

-- Bounce the incoming transaction
SELECT b.out_bounce_id INTO out_bounce_id FROM bounce_incoming(out_tx_id, in_bounce_amount, in_now_date) as b;
END $$;
COMMENT ON FUNCTION register_incoming_and_bounce
  IS 'Register an incoming transaction and bounce it';

CREATE FUNCTION register_incoming_and_talerable(
  IN in_amount taler_amount
  ,IN in_wire_transfer_subject TEXT
  ,IN in_execution_time INT8
  ,IN in_debit_payto_uri TEXT
  ,IN in_bank_id TEXT
  ,IN in_reserve_public_key BYTEA
  -- Error status
  ,OUT out_reserve_pub_reuse BOOLEAN
  -- Success return
  ,OUT out_found BOOLEAN
  ,OUT out_tx_id INT8
)
LANGUAGE plpgsql AS $$
BEGIN
-- Check conflict
IF EXISTS (
  SELECT FROM talerable_incoming_transactions 
  JOIN incoming_transactions USING(incoming_transaction_id)
  WHERE reserve_public_key = in_reserve_public_key
  AND bank_id != in_bank_id
) THEN
  out_reserve_pub_reuse = TRUE;
  RETURN;
END IF;

-- Register the incoming transaction
SELECT reg.out_found, reg.out_tx_id
  FROM register_incoming(in_amount, in_wire_transfer_subject, in_execution_time, in_debit_payto_uri, in_bank_id) as reg
  INTO out_found, out_tx_id;

-- Register as talerable
IF NOT EXISTS(SELECT 1 FROM talerable_incoming_transactions WHERE incoming_transaction_id = out_tx_id) THEN
  -- We cannot use ON CONFLICT here because conversion use a trigger before insertion that isn't idempotent
  INSERT INTO talerable_incoming_transactions (
    incoming_transaction_id
    ,reserve_public_key
  ) VALUES (
    out_tx_id
    ,in_reserve_public_key
  );
  PERFORM pg_notify('incoming_tx', out_tx_id::text);
END IF;
END $$;
COMMENT ON FUNCTION register_incoming_and_talerable IS '
Creates one row in the incoming transactions table and one row
in the talerable transactions table.  The talerable row links the
incoming one.';

CREATE FUNCTION taler_transfer(
  IN in_request_uid BYTEA,
  IN in_wtid BYTEA,
  IN in_subject TEXT,
  IN in_amount taler_amount,
  IN in_exchange_base_url TEXT,
  IN in_credit_account_payto TEXT,
  IN in_bank_id TEXT,
  IN in_timestamp INT8,
  -- Error status
  OUT out_request_uid_reuse BOOLEAN,
  -- Success return
  OUT out_tx_row_id INT8,
  OUT out_timestamp INT8
)
LANGUAGE plpgsql AS $$
BEGIN
-- Check for idempotence and conflict
SELECT (amount != in_amount 
          OR credit_payto_uri != in_credit_account_payto
          OR exchange_base_url != in_exchange_base_url
          OR wtid != in_wtid)
        ,transfer_operations.initiated_outgoing_transaction_id, initiation_time
  INTO out_request_uid_reuse, out_tx_row_id, out_timestamp
  FROM transfer_operations
      JOIN initiated_outgoing_transactions
        ON transfer_operations.initiated_outgoing_transaction_id=initiated_outgoing_transactions.initiated_outgoing_transaction_id 
  WHERE transfer_operations.request_uid = in_request_uid;
IF FOUND THEN
  RETURN;
END IF;
-- Initiate bank transfer
INSERT INTO initiated_outgoing_transactions (
  amount
  ,wire_transfer_subject
  ,credit_payto_uri
  ,initiation_time
  ,request_uid
) VALUES (
  in_amount
  ,in_subject
  ,in_credit_account_payto
  ,in_timestamp
  ,in_bank_id
) RETURNING initiated_outgoing_transaction_id INTO out_tx_row_id;
-- Register outgoing transaction
INSERT INTO transfer_operations(
  initiated_outgoing_transaction_id
  ,request_uid
  ,wtid
  ,exchange_base_url
) VALUES (
  out_tx_row_id
  ,in_request_uid
  ,in_wtid
  ,in_exchange_base_url
);
out_timestamp = in_timestamp;
PERFORM pg_notify('outgoing_tx', out_tx_row_id::text);
END $$;
