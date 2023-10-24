BEGIN;
SET search_path TO libeufin_nexus;

CREATE OR REPLACE FUNCTION create_outgoing_tx(
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

COMMENT ON FUNCTION create_outgoing_tx(taler_amount, TEXT, BIGINT, TEXT, TEXT, BIGINT)
  IS 'Creates a new outgoing payment and optionally reconciles the related initiated payment with it.  If the initiated payment to reconcile is not found, it inserts NOTHING.';

CREATE OR REPLACE FUNCTION bounce_payment(
  IN in_incoming_transaction_id BIGINT
  ,IN in_initiation_time BIGINT
  ,OUT out_nx_incoming_payment BOOLEAN
)
LANGUAGE plpgsql AS $$
BEGIN

INSERT INTO initiated_outgoing_transactions (
  amount
  ,wire_transfer_subject
  ,credit_payto_uri
  ,initiation_time
  )
  SELECT
    amount
    ,'refund: ' || wire_transfer_subject
    ,debit_payto_uri
    ,in_initiation_time
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

COMMENT ON FUNCTION bounce_payment(BIGINT, BIGINT) IS 'Marks an incoming payment as bounced and initiates its refunding payment';
