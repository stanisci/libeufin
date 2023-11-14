BEGIN;
SET search_path TO libeufin_nexus;

CREATE OR REPLACE FUNCTION create_incoming_and_bounce(
  IN in_amount taler_amount
  ,IN in_wire_transfer_subject TEXT
  ,IN in_execution_time BIGINT
  ,IN in_debit_payto_uri TEXT
  ,IN in_bank_transfer_id TEXT
  ,IN in_timestamp BIGINT
  ,IN in_request_uid TEXT
  ,OUT out_ok BOOLEAN
) RETURNS BOOLEAN
LANGUAGE plpgsql AS $$
DECLARE
new_tx_id INT8;
new_init_id INT8;
BEGIN
-- creating the bounced incoming transaction.
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
  ) RETURNING incoming_transaction_id INTO new_tx_id;

-- creating its reimbursement.
INSERT INTO initiated_outgoing_transactions (
  amount
  ,wire_transfer_subject
  ,credit_payto_uri
  ,initiation_time
  ,request_uid
  ) VALUES (
    in_amount
    ,'refund: ' || in_wire_transfer_subject
    ,in_debit_payto_uri
    ,in_timestamp
    ,in_request_uid
  ) RETURNING initiated_outgoing_transaction_id INTO new_init_id;

INSERT INTO bounced_transactions (
  incoming_transaction_id
  ,initiated_outgoing_transaction_id
) VALUES (
  new_tx_id
  ,new_init_id
);
out_ok = TRUE;
END $$;

COMMENT ON FUNCTION create_incoming_and_bounce(taler_amount, TEXT, BIGINT, TEXT, TEXT, BIGINT, TEXT)
  IS 'creates one incoming transaction with a bounced state and initiates its related refund.';

CREATE OR REPLACE FUNCTION create_outgoing_payment(
  IN in_amount taler_amount
  ,IN in_wire_transfer_subject TEXT
  ,IN in_execution_time BIGINT
  ,IN in_credit_payto_uri TEXT
  ,IN in_bank_transfer_id TEXT
  ,IN in_initiated_id BIGINT
  ,OUT out_nx_initiated BOOLEAN
)
LANGUAGE plpgsql AS $$
DECLARE
new_outgoing_transaction_id BIGINT;
BEGIN

IF in_initiated_id IS NULL THEN
  out_nx_initiated = FALSE;
ELSE
  PERFORM 1
    FROM initiated_outgoing_transactions
    WHERE initiated_outgoing_transaction_id = in_initiated_id;
    IF NOT FOUND THEN
      out_nx_initiated = TRUE;
      RETURN;
      END IF;
END IF;

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
    INTO new_outgoing_transaction_id;

IF in_initiated_id IS NOT NULL
THEN
  UPDATE initiated_outgoing_transactions
    SET outgoing_transaction_id = new_outgoing_transaction_id
    WHERE initiated_outgoing_transaction_id = in_initiated_id;
END IF;
END $$;

COMMENT ON FUNCTION create_outgoing_payment(taler_amount, TEXT, BIGINT, TEXT, TEXT, BIGINT)
  IS 'Creates a new outgoing payment and optionally reconciles the related initiated payment with it.  If the initiated payment to reconcile is not found, it inserts NOTHING.';

CREATE OR REPLACE FUNCTION bounce_payment(
  IN in_incoming_transaction_id BIGINT
  ,IN in_initiation_time BIGINT
  ,IN in_request_uid TEXT
  ,OUT out_nx_incoming_payment BOOLEAN
)
LANGUAGE plpgsql AS $$
BEGIN

INSERT INTO initiated_outgoing_transactions (
  amount
  ,wire_transfer_subject
  ,credit_payto_uri
  ,initiation_time
  ,request_uid
  )
  SELECT
    amount
    ,'refund: ' || wire_transfer_subject
    ,debit_payto_uri
    ,in_initiation_time
    ,in_request_uid
    FROM incoming_transactions
    WHERE incoming_transaction_id = in_incoming_transaction_id;

IF NOT FOUND THEN
  out_nx_incoming_payment=TRUE;
  RETURN;
END IF;
out_nx_incoming_payment=FALSE;

-- finally setting the payment as bounced.  Not checking
-- the update outcome since the row existence was checked
-- just above.

UPDATE incoming_transactions
  SET bounced = true
  WHERE incoming_transaction_id = in_incoming_transaction_id;
END $$;

COMMENT ON FUNCTION bounce_payment(BIGINT, BIGINT, TEXT) IS 'Marks an incoming payment as bounced and initiates its refunding payment';

CREATE OR REPLACE FUNCTION create_incoming_talerable(
  IN in_amount taler_amount
  ,IN in_wire_transfer_subject TEXT
  ,IN in_execution_time BIGINT
  ,IN in_debit_payto_uri TEXT
  ,IN in_bank_transfer_id TEXT
  ,IN in_reserve_public_key BYTEA
  ,OUT out_ok BOOLEAN
) RETURNS BOOLEAN
LANGUAGE plpgsql AS $$
DECLARE
new_tx_id INT8;
BEGIN
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
  ) RETURNING incoming_transaction_id INTO new_tx_id;
INSERT INTO talerable_incoming_transactions (
  incoming_transaction_id
  ,reserve_public_key
) VALUES (
  new_tx_id
  ,in_reserve_public_key
);
out_ok = TRUE;
END $$;

COMMENT ON FUNCTION create_incoming_talerable(taler_amount, TEXT, BIGINT, TEXT, TEXT, BYTEA) IS '
Creates one row in the incoming transactions table and one row
in the talerable transactions table.  The talerable row links the
incoming one.';