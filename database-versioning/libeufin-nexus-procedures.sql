BEGIN;
SET search_path TO libeufin_nexus;

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
