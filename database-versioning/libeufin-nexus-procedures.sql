BEGIN;
SET search_path TO libeufin_nexus;

CREATE FUNCTION register_outgoing(
  IN in_amount taler_amount
  ,IN in_wire_transfer_subject TEXT
  ,IN in_execution_time BIGINT
  ,IN in_credit_payto_uri TEXT
  ,IN in_bank_transfer_id TEXT
  ,OUT out_found BOOLEAN
  ,OUT out_initiated BOOLEAN
)
LANGUAGE plpgsql AS $$
DECLARE
init_id BIGINT;
tx_id BIGINT;
BEGIN
-- Check if already registered
SELECT outgoing_transaction_id INTO tx_id
  FROM outgoing_transactions
  WHERE bank_transfer_id = in_bank_transfer_id;
IF FOUND THEN
  out_found = true;
  -- TODO Should we update the subject and credit payto if it's finally found
  -- TODO Should we check that amount and other info match ?
ELSE
  -- Store the transaction in the database
  INSERT INTO outgoing_transactions (
    amount
    ,wire_transfer_subject
    ,execution_time
    ,credit_payto_uri
    ,bank_transfer_id
  ) VALUES (
    in_amount
    ,in_wire_transfer_subject
    ,in_execution_time
    ,in_credit_payto_uri
    ,in_bank_transfer_id
  )
    RETURNING outgoing_transaction_id
      INTO tx_id;

  -- Reconciles the related initiated payment
  UPDATE initiated_outgoing_transactions
    SET outgoing_transaction_id = tx_id
    WHERE request_uid = in_bank_transfer_id
    RETURNING true INTO out_initiated;
END IF;
END $$;
COMMENT ON FUNCTION register_outgoing
  IS 'Register an outgoing payment and optionally reconciles the related initiated payment with it';

CREATE FUNCTION register_incoming(
  IN in_amount taler_amount
  ,IN in_wire_transfer_subject TEXT
  ,IN in_execution_time BIGINT
  ,IN in_debit_payto_uri TEXT
  ,IN in_bank_transfer_id TEXT
  ,OUT out_found BOOLEAN
  ,OUT out_tx_id BIGINT
)
LANGUAGE plpgsql AS $$
BEGIN
-- Check if already registered
SELECT incoming_transaction_id INTO out_tx_id
  FROM incoming_transactions
  WHERE bank_transfer_id = in_bank_transfer_id;
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
    ,bank_transfer_id
  ) VALUES (
    in_amount
    ,in_wire_transfer_subject
    ,in_execution_time
    ,in_debit_payto_uri
    ,in_bank_transfer_id
  ) RETURNING incoming_transaction_id INTO out_tx_id;
END IF;
END $$;
COMMENT ON FUNCTION register_incoming
  IS 'Register an incoming payment';

CREATE FUNCTION register_incoming_and_bounce(
  IN in_amount taler_amount
  ,IN in_wire_transfer_subject TEXT
  ,IN in_execution_time BIGINT
  ,IN in_debit_payto_uri TEXT
  ,IN in_bank_transfer_id TEXT
  ,IN in_timestamp BIGINT
  ,IN in_request_uid TEXT
  ,IN in_bounce_amount taler_amount
  ,IN in_bounce_subject TEXT
  ,OUT out_found BOOLEAN -- TODO return tx_id
)
LANGUAGE plpgsql AS $$
DECLARE
tx_id BIGINT;
init_id BIGINT;
BEGIN
-- Register the incoming transaction
SELECT reg.out_found, out_tx_id
  FROM register_incoming(in_amount, in_wire_transfer_subject, in_execution_time, in_debit_payto_uri, in_bank_transfer_id) as reg
  INTO out_found, tx_id;

-- Initiate the bounce transaction
INSERT INTO initiated_outgoing_transactions (
  amount
  ,wire_transfer_subject
  ,credit_payto_uri
  ,initiation_time
  ,request_uid
  ) VALUES (
    in_bounce_amount
    ,in_bounce_subject
    ,in_debit_payto_uri
    ,in_timestamp
    ,in_request_uid
  )
  ON CONFLICT (request_uid) DO NOTHING
  RETURNING initiated_outgoing_transaction_id INTO init_id;
IF FOUND THEN
  -- Register the bounce
  INSERT INTO bounced_transactions (
    incoming_transaction_id ,initiated_outgoing_transaction_id
  ) VALUES (tx_id ,init_id)
    ON CONFLICT
      DO NOTHING;
END IF;
END $$;
COMMENT ON FUNCTION register_incoming_and_bounce
  IS 'Register an incoming payment and bounce it';

CREATE FUNCTION register_incoming_and_talerable(
  IN in_amount taler_amount
  ,IN in_wire_transfer_subject TEXT
  ,IN in_execution_time BIGINT
  ,IN in_debit_payto_uri TEXT
  ,IN in_bank_transfer_id TEXT
  ,IN in_reserve_public_key BYTEA
  ,OUT out_found BOOLEAN -- TODO return tx_id
)
LANGUAGE plpgsql AS $$
DECLARE
tx_id INT8;
BEGIN
-- Register the incoming transaction
SELECT reg.out_found, out_tx_id
  FROM register_incoming(in_amount, in_wire_transfer_subject, in_execution_time, in_debit_payto_uri, in_bank_transfer_id) as reg
  INTO out_found, tx_id;

-- Register as talerable bounce
INSERT INTO talerable_incoming_transactions (
  incoming_transaction_id
  ,reserve_public_key
) VALUES (
  tx_id
  ,in_reserve_public_key
) ON CONFLICT (incoming_transaction_id) DO NOTHING;
END $$;
COMMENT ON FUNCTION register_incoming_and_talerable IS '
Creates one row in the incoming transactions table and one row
in the talerable transactions table.  The talerable row links the
incoming one.';