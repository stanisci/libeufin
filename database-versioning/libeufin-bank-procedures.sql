BEGIN;
SET search_path TO libeufin_bank;

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
  WHERE  pronamespace = 'libeufin_bank'::regnamespace;

  IF _sql IS NOT NULL THEN
    EXECUTE _sql;
  END IF;
END
$do$;

CREATE FUNCTION amount_normalize(
    IN amount taler_amount
  ,OUT normalized taler_amount
)
LANGUAGE plpgsql AS $$
BEGIN
  normalized.val = amount.val + amount.frac / 100000000;
  IF (normalized.val > 1::INT8<<52) THEN
    RAISE EXCEPTION 'amount value overflowed';
  END IF;
  normalized.frac = amount.frac % 100000000;

END $$;
COMMENT ON FUNCTION amount_normalize
  IS 'Returns the normalized amount by adding to the .val the value of (.frac / 100000000) and removing the modulus 100000000 from .frac.'
      'It raises an exception when the resulting .val is larger than 2^52';

CREATE FUNCTION amount_add(
   IN a taler_amount
  ,IN b taler_amount
  ,OUT sum taler_amount
)
LANGUAGE plpgsql AS $$
BEGIN
  sum = (a.val + b.val, a.frac + b.frac);
  SELECT normalized.val, normalized.frac INTO sum.val, sum.frac FROM amount_normalize(sum) as normalized;
END $$;
COMMENT ON FUNCTION amount_add
  IS 'Returns the normalized sum of two amounts. It raises an exception when the resulting .val is larger than 2^52';

CREATE FUNCTION amount_left_minus_right(
  IN l taler_amount
 ,IN r taler_amount
 ,OUT diff taler_amount
 ,OUT ok BOOLEAN
)
LANGUAGE plpgsql AS $$
BEGIN
IF l.val > r.val THEN
  ok = TRUE;
  IF l.frac >= r.frac THEN
    diff.val = l.val - r.val;
    diff.frac = l.frac - r.frac;
  ELSE
    diff.val = l.val - r.val - 1;
    diff.frac = l.frac + 100000000 - r.frac;
  END IF;
ELSE IF l.val = r.val AND l.frac >= r.frac THEN
    diff.val = 0;
    diff.frac = l.frac - r.frac;
    ok = TRUE;
  ELSE
    diff = (-1, -1);
    ok = FALSE;
  END IF;
END IF;
END $$;
COMMENT ON FUNCTION amount_left_minus_right
  IS 'Subtracts the right amount from the left and returns the difference and TRUE, if the left amount is larger than the right, or an invalid amount and FALSE otherwise.';

CREATE FUNCTION account_balance_is_sufficient(
  IN in_account_id INT8,
  IN in_amount taler_amount,
  OUT out_balance_insufficient BOOLEAN
)
LANGUAGE plpgsql AS $$ 
DECLARE
account_has_debt BOOLEAN;
account_balance taler_amount;
account_max_debt taler_amount;
BEGIN
-- get account info, we expect the account to exist
SELECT
  has_debt,
  (balance).val, (balance).frac,
  (max_debt).val, (max_debt).frac
  INTO
    account_has_debt,
    account_balance.val, account_balance.frac,
    account_max_debt.val, account_max_debt.frac
  FROM bank_accounts WHERE bank_account_id=in_account_id;

-- check enough funds
IF account_has_debt THEN 
  -- debt case: simply checking against the max debt allowed.
  SELECT sum.val, sum.frac 
    INTO account_balance.val, account_balance.frac 
    FROM amount_add(account_balance, in_amount) as sum;
  SELECT NOT ok
    INTO out_balance_insufficient
    FROM amount_left_minus_right(account_max_debt, account_balance);
  IF out_balance_insufficient THEN
    RETURN;
  END IF;
ELSE -- not a debt account
  SELECT NOT ok
    INTO out_balance_insufficient
    FROM amount_left_minus_right(account_balance, in_amount);
  IF out_balance_insufficient THEN
     -- debtor will switch to debt: determine their new negative balance.
    SELECT
      (diff).val, (diff).frac
      INTO
        account_balance.val, account_balance.frac
      FROM amount_left_minus_right(in_amount, account_balance);
    SELECT NOT ok
      INTO out_balance_insufficient
      FROM amount_left_minus_right(account_max_debt, account_balance);
    IF out_balance_insufficient THEN
      RETURN;
    END IF;
  END IF;
END IF;
END $$;
COMMENT ON FUNCTION account_balance_is_sufficient IS 'Check if an account have enough fund to transfer an amount.';

CREATE FUNCTION account_delete(
  IN in_login TEXT,
  IN in_now INT8,
  IN in_is_tan BOOLEAN,
  OUT out_not_found BOOLEAN,
  OUT out_balance_not_zero BOOLEAN,
  OUT out_tan_required BOOLEAN
)
LANGUAGE plpgsql AS $$
DECLARE
my_customer_id INT8;
BEGIN
-- check if account exists, has zero balance and if 2FA is required
SELECT 
   customer_id
  ,(NOT in_is_tan AND tan_channel IS NOT NULL)
  ,((balance).val != 0 OR (balance).frac != 0)
  INTO 
     my_customer_id
    ,out_tan_required
    ,out_balance_not_zero
  FROM customers 
    JOIN bank_accounts ON owning_customer_id = customer_id
  WHERE login = in_login AND deleted_at IS NULL;
IF NOT FOUND OR out_balance_not_zero OR out_tan_required THEN
  out_not_found=NOT FOUND;
  RETURN;
END IF;

-- actual deletion
UPDATE customers SET deleted_at = in_now WHERE customer_id = my_customer_id;
END $$;
COMMENT ON FUNCTION account_delete IS 'Deletes an account if the balance is zero';

CREATE PROCEDURE register_outgoing(
  IN in_request_uid BYTEA,
  IN in_wtid BYTEA,
  IN in_exchange_base_url TEXT,
  IN in_debtor_account_id INT8,
  IN in_creditor_account_id INT8,
  IN in_debit_row_id INT8,
  IN in_credit_row_id INT8
)
LANGUAGE plpgsql AS $$
DECLARE 
  local_amount taler_amount;
  local_bank_account_id INT8;
BEGIN
-- register outgoing transaction
INSERT
  INTO taler_exchange_outgoing (
    request_uid,
    wtid,
    exchange_base_url,
    bank_transaction,
    creditor_account_id
) VALUES (
  in_request_uid,
  in_wtid,
  in_exchange_base_url,
  in_debit_row_id,
  in_creditor_account_id
);
-- TODO check if not drain
-- update stats
SELECT (amount).val, (amount).frac, bank_account_id
INTO local_amount.val, local_amount.frac, local_bank_account_id
FROM bank_account_transactions WHERE bank_transaction_id=in_debit_row_id;
CALL stats_register_payment('taler_out', NULL, local_amount, null);
-- notify new transaction
PERFORM pg_notify('outgoing_tx', in_debtor_account_id || ' ' || in_creditor_account_id || ' ' || in_debit_row_id || ' ' || in_credit_row_id);
END $$;
COMMENT ON PROCEDURE register_outgoing
  IS 'Register a bank transaction as a taler outgoing transaction and announce it';

CREATE PROCEDURE register_incoming(
  IN in_reserve_pub BYTEA,
  IN in_tx_row_id INT8
)
LANGUAGE plpgsql AS $$
DECLARE
local_amount taler_amount;
local_bank_account_id INT8;
BEGIN
-- Register incoming transaction
INSERT
  INTO taler_exchange_incoming (
    reserve_pub,
    bank_transaction
) VALUES (
  in_reserve_pub,
  in_tx_row_id
);
-- update stats
SELECT (amount).val, (amount).frac, bank_account_id
INTO local_amount.val, local_amount.frac, local_bank_account_id
FROM bank_account_transactions WHERE bank_transaction_id=in_tx_row_id;
CALL stats_register_payment('taler_in', NULL, local_amount, null);
-- notify new transaction
PERFORM pg_notify('incoming_tx', local_bank_account_id || ' ' || in_tx_row_id);
END $$;
COMMENT ON PROCEDURE register_incoming
  IS 'Register a bank transaction as a taler incoming transaction and announce it';


CREATE FUNCTION taler_transfer(
  IN in_request_uid BYTEA,
  IN in_wtid BYTEA,
  IN in_subject TEXT,
  IN in_amount taler_amount,
  IN in_exchange_base_url TEXT,
  IN in_credit_account_payto TEXT,
  IN in_username TEXT,
  IN in_timestamp INT8,
  -- Error status
  OUT out_debtor_not_found BOOLEAN,
  OUT out_debtor_not_exchange BOOLEAN,
  OUT out_creditor_not_found BOOLEAN,
  OUT out_both_exchanges BOOLEAN,
  OUT out_request_uid_reuse BOOLEAN,
  OUT out_exchange_balance_insufficient BOOLEAN,
  -- Success return
  OUT out_tx_row_id INT8,
  OUT out_timestamp INT8
)
LANGUAGE plpgsql AS $$
DECLARE
exchange_bank_account_id INT8;
receiver_bank_account_id INT8;
credit_row_id INT8;
BEGIN
-- Check for idempotence and conflict
SELECT (amount != in_amount 
          OR creditor_payto_uri != in_credit_account_payto
          OR exchange_base_url != in_exchange_base_url
          OR wtid != in_wtid)
        ,bank_transaction_id, transaction_date
  INTO out_request_uid_reuse, out_tx_row_id, out_timestamp
  FROM taler_exchange_outgoing
      JOIN bank_account_transactions AS txs
        ON bank_transaction=txs.bank_transaction_id 
  WHERE request_uid = in_request_uid;
IF found THEN
  RETURN;
END IF;
-- Find exchange bank account id
SELECT
  bank_account_id, NOT is_taler_exchange
  INTO exchange_bank_account_id, out_debtor_not_exchange
  FROM bank_accounts 
      JOIN customers 
        ON customer_id=owning_customer_id
  WHERE login = in_username AND deleted_at IS NULL;
IF NOT FOUND OR out_debtor_not_exchange THEN
  out_debtor_not_found=NOT FOUND;
  RETURN;
END IF;
-- Find receiver bank account id
SELECT
  bank_account_id, is_taler_exchange
  INTO receiver_bank_account_id, out_both_exchanges
  FROM bank_accounts
  WHERE internal_payto_uri = in_credit_account_payto;
IF NOT FOUND OR out_both_exchanges THEN
  out_creditor_not_found=NOT FOUND;
  RETURN;
END IF;
-- Perform bank transfer
SELECT
  out_balance_insufficient,
  out_debit_row_id, out_credit_row_id
  INTO
    out_exchange_balance_insufficient,
    out_tx_row_id, credit_row_id
  FROM bank_wire_transfer(
    receiver_bank_account_id,
    exchange_bank_account_id,
    in_subject,
    in_amount,
    in_timestamp,
    NULL,
    NULL,
    NULL
  ) as transfer;
IF out_exchange_balance_insufficient THEN
  RETURN;
END IF;
out_timestamp=in_timestamp;
-- Register outgoing transaction
CALL register_outgoing(in_request_uid, in_wtid, in_exchange_base_url, exchange_bank_account_id, receiver_bank_account_id, out_tx_row_id, credit_row_id);
END $$;
COMMENT ON FUNCTION taler_transfer IS 'Create an outgoing taler transaction and register it';

CREATE FUNCTION taler_add_incoming(
  IN in_reserve_pub BYTEA,
  IN in_subject TEXT,
  IN in_amount taler_amount,
  IN in_debit_account_payto TEXT,
  IN in_username TEXT,
  IN in_timestamp INT8,
  -- Error status
  OUT out_creditor_not_found BOOLEAN,
  OUT out_creditor_not_exchange BOOLEAN,
  OUT out_debtor_not_found BOOLEAN,
  OUT out_both_exchanges BOOLEAN,
  OUT out_reserve_pub_reuse BOOLEAN,
  OUT out_debitor_balance_insufficient BOOLEAN,
  -- Success return
  OUT out_tx_row_id INT8
)
LANGUAGE plpgsql AS $$
DECLARE
exchange_bank_account_id INT8;
sender_bank_account_id INT8;
BEGIN
-- Check conflict
SELECT true FROM taler_exchange_incoming WHERE reserve_pub = in_reserve_pub
UNION ALL
SELECT true FROM taler_withdrawal_operations WHERE reserve_pub = in_reserve_pub
  INTO out_reserve_pub_reuse;
IF out_reserve_pub_reuse THEN
  RETURN;
END IF;
-- Find exchange bank account id
SELECT
  bank_account_id, NOT is_taler_exchange
  INTO exchange_bank_account_id, out_creditor_not_exchange
  FROM bank_accounts 
      JOIN customers 
        ON customer_id=owning_customer_id
  WHERE login = in_username AND deleted_at IS NULL;
IF NOT FOUND OR out_creditor_not_exchange THEN
  out_creditor_not_found=NOT FOUND;
  RETURN;
END IF;
-- Find sender bank account id
SELECT
  bank_account_id, is_taler_exchange
  INTO sender_bank_account_id, out_both_exchanges
  FROM bank_accounts
  WHERE internal_payto_uri = in_debit_account_payto;
IF NOT FOUND OR out_both_exchanges THEN
  out_debtor_not_found=NOT FOUND;
  RETURN;
END IF;
-- Perform bank transfer
SELECT
  out_balance_insufficient,
  out_credit_row_id
  INTO
    out_debitor_balance_insufficient,
    out_tx_row_id
  FROM bank_wire_transfer(
    exchange_bank_account_id,
    sender_bank_account_id,
    in_subject,
    in_amount,
    in_timestamp,
    NULL,
    NULL,
    NULL
  ) as transfer;
IF out_debitor_balance_insufficient THEN
  RETURN;
END IF;
-- Register incoming transaction
CALL register_incoming(in_reserve_pub, out_tx_row_id);
END $$;
COMMENT ON FUNCTION taler_add_incoming IS 'Create an incoming taler transaction and register it';

CREATE FUNCTION bank_transaction(
  IN in_credit_account_payto TEXT,
  IN in_debit_account_username TEXT,
  IN in_subject TEXT,
  IN in_amount taler_amount,
  IN in_timestamp INT8,
  IN in_is_tan BOOLEAN,
  IN in_request_uid BYTEA,
  -- Error status
  OUT out_creditor_not_found BOOLEAN,
  OUT out_debtor_not_found BOOLEAN,
  OUT out_same_account BOOLEAN,
  OUT out_balance_insufficient BOOLEAN,
  OUT out_creditor_admin BOOLEAN,
  OUT out_tan_required BOOLEAN,
  OUT out_request_uid_reuse BOOLEAN,
  -- Success return
  OUT out_credit_bank_account_id INT8,
  OUT out_debit_bank_account_id INT8,
  OUT out_credit_row_id INT8,
  OUT out_debit_row_id INT8,
  OUT out_creditor_is_exchange BOOLEAN,
  OUT out_debtor_is_exchange BOOLEAN,
  OUT out_idempotent BOOLEAN
)
LANGUAGE plpgsql AS $$
BEGIN
-- Find credit bank account id and check it's not admin
SELECT bank_account_id, is_taler_exchange, login='admin'
  INTO out_credit_bank_account_id, out_creditor_is_exchange, out_creditor_admin
  FROM bank_accounts
    JOIN customers ON customer_id=owning_customer_id
  WHERE internal_payto_uri = in_credit_account_payto AND deleted_at IS NULL;
IF NOT FOUND OR out_creditor_admin THEN
  out_creditor_not_found=NOT FOUND;
  RETURN;
END IF;
-- Find debit bank account ID and check it's a different account and if 2FA is required
SELECT bank_account_id, is_taler_exchange, out_credit_bank_account_id=bank_account_id, NOT in_is_tan AND tan_channel IS NOT NULL
  INTO out_debit_bank_account_id, out_debtor_is_exchange, out_same_account, out_tan_required
  FROM bank_accounts 
    JOIN customers ON customer_id=owning_customer_id
  WHERE login = in_debit_account_username AND deleted_at IS NULL;
IF NOT FOUND OR out_same_account THEN
  out_debtor_not_found=NOT FOUND;
  RETURN;
END IF;
-- Check for idempotence and conflict
IF in_request_uid IS NOT NULL THEN
  SELECT (amount != in_amount
      OR subject != in_subject 
      OR bank_account_id != out_debit_bank_account_id), bank_transaction
    INTO out_request_uid_reuse, out_debit_row_id
    FROM bank_transaction_operations
      JOIN bank_account_transactions ON bank_transaction = bank_transaction_id
    WHERE request_uid = in_request_uid;
  IF found OR out_tan_required THEN
    out_idempotent = found AND NOT out_request_uid_reuse;
    RETURN;
  END IF;
ELSIF out_tan_required THEN
  RETURN;
END IF;

-- Perform bank transfer
SELECT
  transfer.out_balance_insufficient,
  transfer.out_credit_row_id,
  transfer.out_debit_row_id
  INTO
    out_balance_insufficient,
    out_credit_row_id,
    out_debit_row_id
  FROM bank_wire_transfer(
    out_credit_bank_account_id,
    out_debit_bank_account_id,
    in_subject,
    in_amount,
    in_timestamp,
    NULL,
    NULL,
    NULL
  ) as transfer;
-- Store operation
IF in_request_uid IS NOT NULL THEN
  INSERT INTO bank_transaction_operations (request_uid, bank_transaction)  
    VALUES (in_request_uid, out_debit_row_id);
END IF;
END $$;
COMMENT ON FUNCTION bank_transaction IS 'Create a bank transaction';

CREATE FUNCTION create_taler_withdrawal(
  IN in_account_username TEXT,
  IN in_withdrawal_uuid UUID,
  IN in_amount taler_amount,
  IN in_now_date INT8,
   -- Error status
  OUT out_account_not_found BOOLEAN,
  OUT out_account_is_exchange BOOLEAN,
  OUT out_balance_insufficient BOOLEAN
)
LANGUAGE plpgsql AS $$ 
DECLARE
account_id INT8;
BEGIN
-- Check account exists
SELECT bank_account_id, is_taler_exchange
  INTO account_id, out_account_is_exchange
  FROM bank_accounts
  JOIN customers ON bank_accounts.owning_customer_id = customers.customer_id
  WHERE login=in_account_username AND deleted_at IS NULL;
IF NOT FOUND OR out_account_is_exchange THEN
  out_account_not_found=NOT FOUND;
  RETURN;
END IF;

-- Check enough funds
SELECT account_balance_is_sufficient(account_id, in_amount) INTO out_balance_insufficient;
IF out_balance_insufficient THEN
  RETURN;
END IF;

-- Create withdrawal operation
INSERT INTO taler_withdrawal_operations
    (withdrawal_uuid, wallet_bank_account, amount, creation_date)
  VALUES (in_withdrawal_uuid, account_id, in_amount, in_now_date);
END $$;
COMMENT ON FUNCTION create_taler_withdrawal IS 'Create a new withdrawal operation';

CREATE FUNCTION select_taler_withdrawal(
  IN in_withdrawal_uuid uuid,
  IN in_reserve_pub BYTEA,
  IN in_subject TEXT,
  IN in_selected_exchange_payto TEXT,
  -- Error status
  OUT out_no_op BOOLEAN,
  OUT out_already_selected BOOLEAN,
  OUT out_reserve_pub_reuse BOOLEAN,
  OUT out_account_not_found BOOLEAN,
  OUT out_account_is_not_exchange BOOLEAN,
  -- Success return
  OUT out_status TEXT
)
LANGUAGE plpgsql AS $$ 
DECLARE
not_selected BOOLEAN;
BEGIN
-- Check for conflict and idempotence
SELECT
  NOT selection_done, 
  CASE 
    WHEN confirmation_done THEN 'confirmed'
    WHEN aborted THEN 'aborted'
    ELSE 'selected'
  END,
  selection_done 
    AND (selected_exchange_payto != in_selected_exchange_payto OR reserve_pub != in_reserve_pub)
  INTO not_selected, out_status, out_already_selected
  FROM taler_withdrawal_operations
  WHERE withdrawal_uuid=in_withdrawal_uuid;
IF NOT FOUND OR out_already_selected THEN
  out_no_op=NOT FOUND;
  RETURN;
END IF;

IF not_selected THEN
  -- Check reserve_pub reuse
  SELECT true FROM taler_exchange_incoming WHERE reserve_pub = in_reserve_pub
  UNION ALL
  SELECT true FROM taler_withdrawal_operations WHERE reserve_pub = in_reserve_pub
    INTO out_reserve_pub_reuse;
  IF out_reserve_pub_reuse THEN
    RETURN;
  END IF;
  -- Check exchange account
  SELECT NOT is_taler_exchange
    INTO out_account_is_not_exchange
    FROM bank_accounts
    WHERE internal_payto_uri=in_selected_exchange_payto;
  IF NOT FOUND OR out_account_is_not_exchange THEN
    out_account_not_found=NOT FOUND;
    RETURN;
  END IF;

  -- Update withdrawal operation
  UPDATE taler_withdrawal_operations
    SET selected_exchange_payto=in_selected_exchange_payto, reserve_pub=in_reserve_pub, subject=in_subject, selection_done=true
    WHERE withdrawal_uuid=in_withdrawal_uuid;

  -- Notify status change
  PERFORM pg_notify('withdrawal_status', in_withdrawal_uuid::text || ' selected');
END IF;
END $$;
COMMENT ON FUNCTION select_taler_withdrawal IS 'Set details of a withdrawal operation';

CREATE FUNCTION abort_taler_withdrawal(
  IN in_withdrawal_uuid uuid,
  OUT out_no_op BOOLEAN,
  OUT out_already_confirmed BOOLEAN
)
LANGUAGE plpgsql AS $$
BEGIN
UPDATE taler_withdrawal_operations
  SET aborted = NOT confirmation_done
  WHERE withdrawal_uuid=in_withdrawal_uuid
  RETURNING confirmation_done
  INTO out_already_confirmed;
IF NOT FOUND OR out_already_confirmed THEN
  out_no_op=NOT FOUND;
  RETURN;
END IF;

-- Notify status change
PERFORM pg_notify('withdrawal_status', in_withdrawal_uuid::text || ' aborted');
END $$;
COMMENT ON FUNCTION abort_taler_withdrawal IS 'Abort a withdrawal operation.';

CREATE FUNCTION confirm_taler_withdrawal(
  IN in_login TEXT,
  IN in_withdrawal_uuid uuid,
  IN in_confirmation_date INT8,
  IN in_is_tan BOOLEAN,
  OUT out_no_op BOOLEAN,
  OUT out_balance_insufficient BOOLEAN,
  OUT out_creditor_not_found BOOLEAN,
  OUT out_exchange_not_found BOOLEAN,
  OUT out_not_selected BOOLEAN,
  OUT out_aborted BOOLEAN,
  OUT out_tan_required BOOLEAN
)
LANGUAGE plpgsql AS $$
DECLARE
  already_confirmed BOOLEAN;
  subject_local TEXT;
  reserve_pub_local BYTEA;
  selected_exchange_payto_local TEXT;
  wallet_bank_account_local INT8;
  amount_local taler_amount;
  exchange_bank_account_id INT8;
  tx_row_id INT8;
BEGIN
-- Check op exists
SELECT
  confirmation_done,
  aborted, NOT selection_done,
  reserve_pub, subject,
  selected_exchange_payto,
  wallet_bank_account,
  (amount).val, (amount).frac,
  (NOT in_is_tan AND tan_channel IS NOT NULL)
  INTO
    already_confirmed,
    out_aborted, out_not_selected,
    reserve_pub_local, subject_local,
    selected_exchange_payto_local,
    wallet_bank_account_local,
    amount_local.val, amount_local.frac,
    out_tan_required
  FROM taler_withdrawal_operations
    JOIN bank_accounts ON wallet_bank_account=bank_account_id
    JOIN customers ON owning_customer_id=customer_id
  WHERE withdrawal_uuid=in_withdrawal_uuid AND login=in_login AND deleted_at IS NULL;
IF NOT FOUND OR already_confirmed OR out_aborted OR out_not_selected THEN
  out_no_op=NOT FOUND;
  RETURN;
END IF;

-- Check exchange account then 2faa
SELECT
  bank_account_id
  INTO exchange_bank_account_id
  FROM bank_accounts
  WHERE internal_payto_uri = selected_exchange_payto_local;
IF NOT FOUND OR out_tan_required THEN
  out_exchange_not_found=NOT FOUND;
  RETURN;
END IF;

SELECT -- not checking for accounts existence, as it was done above.
  transfer.out_balance_insufficient,
  out_credit_row_id
  INTO out_balance_insufficient, tx_row_id
FROM bank_wire_transfer(
  exchange_bank_account_id,
  wallet_bank_account_local,
  subject_local,
  amount_local,
  in_confirmation_date,
  NULL,
  NULL,
  NULL
) as transfer;
IF out_balance_insufficient THEN
  RETURN;
END IF;

-- Confirm operation
UPDATE taler_withdrawal_operations
  SET confirmation_done = true
  WHERE withdrawal_uuid=in_withdrawal_uuid;

-- Register incoming transaction
CALL register_incoming(reserve_pub_local, tx_row_id);

-- Notify status change
PERFORM pg_notify('withdrawal_status', in_withdrawal_uuid::text || ' confirmed');
END $$;
COMMENT ON FUNCTION confirm_taler_withdrawal
  IS 'Set a withdrawal operation as confirmed and wire the funds to the exchange.';

CREATE FUNCTION bank_wire_transfer(
  IN in_creditor_account_id INT8,
  IN in_debtor_account_id INT8,
  IN in_subject TEXT,
  IN in_amount taler_amount,
  IN in_transaction_date INT8,
  IN in_account_servicer_reference TEXT,
  IN in_payment_information_id TEXT,
  IN in_end_to_end_id TEXT,
  -- Error status
  OUT out_balance_insufficient BOOLEAN,
  -- Success return
  OUT out_credit_row_id INT8,
  OUT out_debit_row_id INT8
)
LANGUAGE plpgsql AS $$
DECLARE
debtor_has_debt BOOLEAN;
debtor_balance taler_amount;
debtor_max_debt taler_amount;
debtor_payto_uri TEXT;
debtor_name TEXT;
creditor_has_debt BOOLEAN;
creditor_balance taler_amount;
creditor_payto_uri TEXT;
creditor_name TEXT;
potential_balance taler_amount;
new_debtor_balance taler_amount;
new_debtor_balance_ok BOOLEAN;
new_creditor_balance taler_amount;
will_debtor_have_debt BOOLEAN;
will_creditor_have_debt BOOLEAN;
BEGIN
-- Retrieve debtor info
SELECT
  has_debt,
  (balance).val, (balance).frac,
  (max_debt).val, (max_debt).frac,
  internal_payto_uri, customers.name
  INTO
    debtor_has_debt,
    debtor_balance.val, debtor_balance.frac,
    debtor_max_debt.val, debtor_max_debt.frac,
    debtor_payto_uri, debtor_name
  FROM bank_accounts
    JOIN customers ON customer_id=owning_customer_id
  WHERE bank_account_id=in_debtor_account_id;
IF NOT FOUND THEN
  RAISE EXCEPTION 'fuck debtor';
END IF;
-- Retrieve creditor info
SELECT
  has_debt,
  (balance).val, (balance).frac,
  internal_payto_uri, customers.name
  INTO
    creditor_has_debt,
    creditor_balance.val, creditor_balance.frac,
    creditor_payto_uri, creditor_name
  FROM bank_accounts
    JOIN customers ON customer_id=owning_customer_id
  WHERE bank_account_id=in_creditor_account_id;
IF NOT FOUND THEN
  RAISE EXCEPTION 'fuck creditor %', in_creditor_account_id;
END IF;

-- DEBTOR SIDE
-- check debtor has enough funds.
IF debtor_has_debt THEN 
  -- debt case: simply checking against the max debt allowed.
  SELECT sum.val, sum.frac 
    INTO potential_balance.val, potential_balance.frac 
    FROM amount_add(debtor_balance, in_amount) as sum;
  SELECT NOT ok
    INTO out_balance_insufficient
    FROM amount_left_minus_right(debtor_max_debt,
                                 potential_balance);
  IF out_balance_insufficient THEN
    RETURN;
  END IF;
  new_debtor_balance=potential_balance;
  will_debtor_have_debt=TRUE;
ELSE -- not a debt account
  SELECT
    NOT ok,
    (diff).val, (diff).frac
    INTO
      out_balance_insufficient,
      potential_balance.val,
      potential_balance.frac
    FROM amount_left_minus_right(debtor_balance,
                                 in_amount);
  IF NOT out_balance_insufficient THEN -- debtor has enough funds in the (positive) balance.
    new_debtor_balance=potential_balance;
    will_debtor_have_debt=FALSE;
  ELSE -- debtor will switch to debt: determine their new negative balance.
    SELECT
      (diff).val, (diff).frac
      INTO
        new_debtor_balance.val, new_debtor_balance.frac
      FROM amount_left_minus_right(in_amount,
                                   debtor_balance);
    will_debtor_have_debt=TRUE;
    SELECT NOT ok
      INTO out_balance_insufficient
      FROM amount_left_minus_right(debtor_max_debt,
                                   new_debtor_balance);
    IF out_balance_insufficient THEN
      RETURN;
    END IF;
  END IF;
END IF;
out_balance_insufficient=FALSE;

-- CREDITOR SIDE.
-- Here we figure out whether the creditor would switch
-- from debit to a credit situation, and adjust the balance
-- accordingly.
IF NOT creditor_has_debt THEN -- easy case.
  SELECT sum.val, sum.frac 
    INTO new_creditor_balance.val, new_creditor_balance.frac 
    FROM amount_add(creditor_balance, in_amount) as sum;
  will_creditor_have_debt=FALSE;
ELSE -- creditor had debit but MIGHT switch to credit.
  SELECT
    (diff).val, (diff).frac,
    NOT ok
    INTO
      new_creditor_balance.val, new_creditor_balance.frac,
      will_creditor_have_debt
    FROM amount_left_minus_right(in_amount,
                                 creditor_balance);
  IF will_creditor_have_debt THEN
    -- the amount is not enough to bring the receiver
    -- to a credit state, switch operators to calculate the new balance.
    SELECT
      (diff).val, (diff).frac
      INTO new_creditor_balance.val, new_creditor_balance.frac
      FROM amount_left_minus_right(creditor_balance,
	                           in_amount);
  END IF;
END IF;

-- now actually create the bank transaction.
-- debtor side:
INSERT INTO bank_account_transactions (
  creditor_payto_uri
  ,creditor_name
  ,debtor_payto_uri
  ,debtor_name
  ,subject
  ,amount
  ,transaction_date
  ,account_servicer_reference
  ,payment_information_id
  ,end_to_end_id
  ,direction
  ,bank_account_id
  )
VALUES (
  creditor_payto_uri,
  creditor_name,
  debtor_payto_uri,
  debtor_name,
  in_subject,
  in_amount,
  in_transaction_date,
  in_account_servicer_reference,
  in_payment_information_id,
  in_end_to_end_id,
  'debit',
  in_debtor_account_id
) RETURNING bank_transaction_id INTO out_debit_row_id;

-- debtor side:
INSERT INTO bank_account_transactions (
  creditor_payto_uri
  ,creditor_name
  ,debtor_payto_uri
  ,debtor_name
  ,subject
  ,amount
  ,transaction_date
  ,account_servicer_reference
  ,payment_information_id
  ,end_to_end_id
  ,direction
  ,bank_account_id
  )
VALUES (
  creditor_payto_uri,
  creditor_name,
  debtor_payto_uri,
  debtor_name,
  in_subject,
  in_amount,
  in_transaction_date,
  in_account_servicer_reference,
  in_payment_information_id,
  in_end_to_end_id, -- does this interest the receiving party?
  'credit',
  in_creditor_account_id
) RETURNING bank_transaction_id INTO out_credit_row_id;

-- checks and balances set up, now update bank accounts.
UPDATE bank_accounts
SET
  balance=new_debtor_balance,
  has_debt=will_debtor_have_debt
WHERE bank_account_id=in_debtor_account_id;

UPDATE bank_accounts
SET
  balance=new_creditor_balance,
  has_debt=will_creditor_have_debt
WHERE bank_account_id=in_creditor_account_id;

-- notify new transaction
PERFORM pg_notify('bank_tx', in_debtor_account_id || ' ' || in_creditor_account_id || ' ' || out_debit_row_id || ' ' || out_credit_row_id);
END $$;

CREATE FUNCTION cashin(
  IN in_now_date INT8,
  IN in_reserve_pub BYTEA,
  IN in_amount taler_amount,
  IN in_subject TEXT,
  -- Error status
  OUT out_no_account BOOLEAN,
  OUT out_too_small BOOLEAN,
  OUT out_no_config BOOLEAN,
  OUT out_balance_insufficient BOOLEAN
)
LANGUAGE plpgsql AS $$ 
DECLARE
  converted_amount taler_amount;
  admin_account_id INT8;
  exchange_account_id INT8;
  tx_row_id INT8;
BEGIN
-- TODO check reserve_pub reuse ?

-- Recover exchange account info
SELECT bank_account_id
  INTO exchange_account_id
  FROM bank_accounts
    JOIN customers 
      ON customer_id=owning_customer_id
  WHERE login = 'exchange';
IF NOT FOUND THEN
  out_no_account = true;
  RETURN;
END IF;

-- Retrieve admin account id
SELECT bank_account_id
  INTO admin_account_id
  FROM bank_accounts
    JOIN customers 
      ON customer_id=owning_customer_id
  WHERE login = 'admin';

-- Perform conversion
SELECT (converted).val, (converted).frac, too_small, no_config
  INTO converted_amount.val, converted_amount.frac, out_too_small, out_no_config
  FROM conversion_to(in_amount, 'cashin'::text);
IF out_too_small OR out_no_config THEN
  RETURN;
END IF;

-- Perform bank wire transfer
SELECT 
  transfer.out_balance_insufficient,
  transfer.out_credit_row_id
  INTO 
    out_balance_insufficient,
    tx_row_id
  FROM bank_wire_transfer(
    exchange_account_id,
    admin_account_id,
    in_subject,
    converted_amount,
    in_now_date,
    NULL,
    NULL,
    NULL
  ) as transfer;
IF out_balance_insufficient THEN
  RETURN;
END IF;

-- Register incoming transaction
CALL register_incoming(in_reserve_pub, tx_row_id);

-- update stats
CALL stats_register_payment('cashin', NULL, converted_amount, in_amount);

END $$;
COMMENT ON FUNCTION cashin IS 'Perform a cashin operation';


CREATE FUNCTION cashout_create(
  IN in_login TEXT,
  IN in_request_uid BYTEA,
  IN in_amount_debit taler_amount,
  IN in_amount_credit taler_amount,
  IN in_subject TEXT,
  IN in_now_date INT8,
  IN in_is_tan BOOLEAN,
  -- Error status
  OUT out_bad_conversion BOOLEAN,
  OUT out_account_not_found BOOLEAN,
  OUT out_account_is_exchange BOOLEAN,
  OUT out_balance_insufficient BOOLEAN,
  OUT out_request_uid_reuse BOOLEAN,
  OUT out_no_cashout_payto BOOLEAN,
  OUT out_tan_required BOOLEAN,
  -- Success return
  OUT out_cashout_id INT8
)
LANGUAGE plpgsql AS $$ 
DECLARE
account_id INT8;
admin_account_id INT8;
tx_id INT8;
BEGIN
-- check conversion
SELECT too_small OR no_config OR in_amount_credit!=converted INTO out_bad_conversion FROM conversion_to(in_amount_debit, 'cashout'::text);
IF out_bad_conversion THEN
  RETURN;
END IF;

-- Check account exists, has all info and if 2FA is required
SELECT 
    bank_account_id, is_taler_exchange, cashout_payto IS NULL, (NOT in_is_tan AND tan_channel IS NOT NULL) 
  INTO account_id, out_account_is_exchange, out_no_cashout_payto, out_tan_required
  FROM bank_accounts
  JOIN customers ON bank_accounts.owning_customer_id = customers.customer_id
  WHERE login=in_login;
IF NOT FOUND THEN
  out_account_not_found=TRUE;
  RETURN;
ELSIF out_account_is_exchange OR out_no_cashout_payto THEN
  RETURN;
END IF;

-- Retrieve admin account id
SELECT bank_account_id
  INTO admin_account_id
  FROM bank_accounts
    JOIN customers 
      ON customer_id=owning_customer_id
  WHERE login = 'admin';

-- Check for idempotence and conflict
SELECT (amount_debit != in_amount_debit
          OR subject != in_subject 
          OR bank_account != account_id)
        , cashout_id
  INTO out_request_uid_reuse, out_cashout_id
  FROM cashout_operations
  WHERE request_uid = in_request_uid;
IF found OR out_request_uid_reuse OR out_tan_required THEN
  RETURN;
END IF;

-- Perform bank wire transfer
SELECT transfer.out_balance_insufficient, out_debit_row_id
INTO out_balance_insufficient, tx_id
FROM bank_wire_transfer(
  admin_account_id,
  account_id,
  in_subject,
  in_amount_debit,
  in_now_date,
  NULL,
  NULL,
  NULL
) as transfer;
IF out_balance_insufficient THEN
  RETURN;
END IF;

-- Create cashout operation
INSERT INTO cashout_operations (
  request_uid
  ,amount_debit
  ,amount_credit
  ,creation_time
  ,bank_account
  ,subject
  ,local_transaction
) VALUES (
  in_request_uid
  ,in_amount_debit
  ,in_amount_credit
  ,in_now_date
  ,account_id
  ,in_subject
  ,tx_id
) RETURNING cashout_id INTO out_cashout_id;

-- update stats
CALL stats_register_payment('cashout', NULL, in_amount_debit, in_amount_credit);
END $$;

CREATE FUNCTION tan_challenge_create (
  IN in_body TEXT,
  IN in_op op_enum,
  IN in_code TEXT,
  IN in_now_date INT8,
  IN in_validity_period INT8,
  IN in_retry_counter INT4,
  IN in_login TEXT,
  IN in_tan_channel tan_enum,
  IN in_tan_info TEXT,
  OUT out_challenge_id INT8
)
LANGUAGE plpgsql as $$
DECLARE
account_id INT8;
BEGIN
-- Retrieve account id
SELECT customer_id INTO account_id FROM customers WHERE login = in_login AND deleted_at IS NULL;
-- Create challenge
INSERT INTO tan_challenges (
  body,
  op,
  code,
  creation_date,
  expiration_date,
  retry_counter,
  customer,
  tan_channel,
  tan_info
) VALUES (
  in_body,
  in_op,
  in_code,
  in_now_date,
  in_now_date + in_validity_period,
  in_retry_counter,
  account_id,
  in_tan_channel,
  in_tan_info
) RETURNING challenge_id INTO out_challenge_id;
END $$;
COMMENT ON FUNCTION tan_challenge_create IS 'Create a new challenge, return the generated id';

CREATE FUNCTION tan_challenge_send (
  IN in_challenge_id INT8,
  IN in_login TEXT,
  IN in_code TEXT,              -- New code to use if the old code expired
  IN in_now_date INT8,        
  IN in_validity_period INT8,
  IN in_retry_counter INT4,
  -- Error status
  OUT out_no_op BOOLEAN,
  -- Success return
  OUT out_tan_code TEXT,        -- TAN code to send, NULL if nothing should be sent
  OUT out_tan_channel tan_enum, -- TAN channel to use, NULL if nothing should be sent
  OUT out_tan_info TEXT         -- TAN info to use, NULL if nothing should be sent
)
LANGUAGE plpgsql as $$
DECLARE
account_id INT8;
expired BOOLEAN;
retransmit BOOLEAN;
BEGIN
-- Retrieve account id
SELECT customer_id, tan_channel, CASE tan_channel
    WHEN 'sms'   THEN phone
    WHEN 'email' THEN email
  END
INTO account_id, out_tan_channel, out_tan_info
FROM customers WHERE login = in_login AND deleted_at IS NULL;

-- Recover expiration date
SELECT 
  (in_now_date >= expiration_date OR retry_counter <= 0) AND confirmation_date IS NULL
  ,in_now_date >= retransmission_date AND confirmation_date IS NULL
  ,code, COALESCE(tan_channel, out_tan_channel), COALESCE(tan_info, out_tan_info)
INTO expired, retransmit, out_tan_code, out_tan_channel, out_tan_info
FROM tan_challenges WHERE challenge_id = in_challenge_id AND customer = account_id;
IF NOT FOUND THEN
  out_no_op = true;
  RETURN;
END IF;

IF expired THEN
  UPDATE tan_challenges SET
     code = in_code
    ,expiration_date = in_now_date + in_validity_period
    ,retry_counter = in_retry_counter
  WHERE challenge_id = in_challenge_id;
  out_tan_code = in_code;
ELSIF NOT retransmit THEN
  out_tan_code = NULL;
END IF;
END $$;
COMMENT ON FUNCTION tan_challenge_send IS 'Get the challenge to send, return NULL if nothing should be sent';

CREATE FUNCTION tan_challenge_mark_sent (
  IN in_challenge_id INT8,
  IN in_now_date INT8,
  IN in_retransmission_period INT8
) RETURNS void
LANGUAGE sql AS $$
  UPDATE tan_challenges SET 
    retransmission_date = in_now_date + in_retransmission_period
  WHERE challenge_id = in_challenge_id;
$$;
COMMENT ON FUNCTION tan_challenge_mark_sent IS 'Register a challenge as successfully sent';

CREATE FUNCTION tan_challenge_try (
  IN in_challenge_id INT8, 
  IN in_login TEXT,
  IN in_code TEXT,    
  IN in_now_date INT8,
  -- Error status       
  OUT out_ok BOOLEAN,
  OUT out_no_op BOOLEAN,
  OUT out_no_retry BOOLEAN,
  OUT out_expired BOOLEAN,
  -- Success return
  OUT out_op op_enum,
  OUT out_body TEXT,
  OUT out_channel tan_enum,
  OUT out_info TEXT
)
LANGUAGE plpgsql as $$
DECLARE
account_id INT8;
BEGIN
-- Retrieve account id
SELECT customer_id INTO account_id FROM customers WHERE login = in_login AND deleted_at IS NULL;
-- Check challenge
UPDATE tan_challenges SET 
  confirmation_date = CASE 
    WHEN (retry_counter > 0 AND in_now_date < expiration_date AND code = in_code) THEN in_now_date
    ELSE confirmation_date
  END,
  retry_counter = retry_counter - 1
WHERE challenge_id = in_challenge_id AND customer = account_id
RETURNING 
  confirmation_date IS NOT NULL, 
  retry_counter <= 0 AND confirmation_date IS NULL,
  in_now_date >= expiration_date AND confirmation_date IS NULL
INTO out_ok, out_no_retry, out_expired;
IF NOT FOUND OR NOT out_ok OR out_no_retry OR out_expired THEN
  out_no_op = NOT FOUND;
  RETURN;
END IF;

-- Recover body and op from challenge
SELECT body, op, tan_channel, tan_info
  INTO out_body, out_op, out_channel, out_info
  FROM tan_challenges WHERE challenge_id = in_challenge_id;
END $$;
COMMENT ON FUNCTION tan_challenge_try IS 'Try to confirm a challenge, return true if the challenge have been confirmed';

CREATE FUNCTION stats_get_frame(
  IN date TIMESTAMP,
  IN in_timeframe stat_timeframe_enum,
  OUT cashin_count INT8,
  OUT cashin_regional_volume taler_amount,
  OUT cashin_fiat_volume taler_amount,
  OUT cashout_count INT8,
  OUT cashout_regional_volume taler_amount,
  OUT cashout_fiat_volume taler_amount,
  OUT taler_in_count INT8,
  OUT taler_in_volume taler_amount,
  OUT taler_out_count INT8,
  OUT taler_out_volume taler_amount
)
LANGUAGE plpgsql AS $$
BEGIN
  date = date_trunc(in_timeframe::text, date);
  SELECT 
    s.cashin_count
    ,(s.cashin_regional_volume).val
    ,(s.cashin_regional_volume).frac
    ,(s.cashin_fiat_volume).val
    ,(s.cashin_fiat_volume).frac
    ,s.cashout_count
    ,(s.cashout_regional_volume).val
    ,(s.cashout_regional_volume).frac
    ,(s.cashout_fiat_volume).val
    ,(s.cashout_fiat_volume).frac
    ,s.taler_in_count
    ,(s.taler_in_volume).val
    ,(s.taler_in_volume).frac
    ,s.taler_out_count
    ,(s.taler_out_volume).val
    ,(s.taler_out_volume).frac
  INTO
    cashin_count
    ,cashin_regional_volume.val
    ,cashin_regional_volume.frac
    ,cashin_fiat_volume.val
    ,cashin_fiat_volume.frac
    ,cashout_count
    ,cashout_regional_volume.val
    ,cashout_regional_volume.frac
    ,cashout_fiat_volume.val
    ,cashout_fiat_volume.frac
    ,taler_in_count
    ,taler_in_volume.val
    ,taler_in_volume.frac
    ,taler_out_count
    ,taler_out_volume.val
    ,taler_out_volume.frac
  FROM bank_stats AS s
  WHERE s.timeframe = in_timeframe 
    AND s.start_time = date;
END $$;

CREATE PROCEDURE stats_register_payment(
  IN name TEXT,
  IN now TIMESTAMP,
  IN regional_amount taler_amount,
  IN fiat_amount taler_amount
)
LANGUAGE plpgsql AS $$
DECLARE
  frame stat_timeframe_enum;
  query TEXT;
BEGIN
  IF now IS NULL THEN
    now = timezone('utc', now())::TIMESTAMP;
  END IF;
  IF fiat_amount IS NULL THEN
    query = format('INSERT INTO bank_stats AS s '
      '(timeframe, start_time, %1$I_count, %1$I_volume) '
      'VALUES ($1, $2, 1, $3) '
      'ON CONFLICT (timeframe, start_time) DO UPDATE '
      'SET %1$I_count=s.%1$I_count+1 '
      ', %1$I_volume=(SELECT amount_add(s.%1$I_volume, $3))', 
      name);
    FOREACH frame IN ARRAY enum_range(null::stat_timeframe_enum) LOOP
      EXECUTE query USING frame, date_trunc(frame::text, now), regional_amount;
    END LOOP;
  ELSE
    query = format('INSERT INTO bank_stats AS s '
      '(timeframe, start_time, %1$I_count, %1$I_regional_volume, %1$I_fiat_volume) '
      'VALUES ($1, $2, 1, $3, $4)'
      'ON CONFLICT (timeframe, start_time) DO UPDATE '
      'SET %1$I_count=s.%1$I_count+1 '
      ', %1$I_regional_volume=(SELECT amount_add(s.%1$I_regional_volume, $3))' 
      ', %1$I_fiat_volume=(SELECT amount_add(s.%1$I_fiat_volume, $4))',
      name);
    FOREACH frame IN ARRAY enum_range(null::stat_timeframe_enum) LOOP
      EXECUTE query USING frame, date_trunc(frame::text, now), regional_amount, fiat_amount;
    END LOOP;
  END IF;
END $$;

CREATE PROCEDURE config_set_amount(
  IN name TEXT,
  IN amount taler_amount
)
LANGUAGE sql AS $$
  INSERT INTO config (key, value) VALUES (name, jsonb_build_object('val', amount.val, 'frac', amount.frac))
    ON CONFLICT (key) DO UPDATE SET value = excluded.value
$$;

CREATE PROCEDURE config_set_rounding_mode(
  IN name TEXT,
  IN mode rounding_mode
)
LANGUAGE sql AS $$
  INSERT INTO config (key, value) VALUES (name, jsonb_build_object('mode', mode::text))
    ON CONFLICT (key) DO UPDATE SET value = excluded.value
$$;

CREATE FUNCTION config_get_amount(
  IN name TEXT,
  OUT amount taler_amount
)
LANGUAGE sql AS $$
  SELECT (value['val']::int8, value['frac']::int4)::taler_amount FROM config WHERE key=name
$$;

CREATE FUNCTION config_get_rounding_mode(
  IN name TEXT,
  OUT mode rounding_mode
)
LANGUAGE sql AS $$ SELECT (value->>'mode')::rounding_mode FROM config WHERE key=name $$;

CREATE FUNCTION conversion_apply_ratio(
   IN amount taler_amount
  ,IN ratio taler_amount
  ,IN tiny taler_amount       -- Result is rounded to this amount
  ,IN rounding rounding_mode  -- With this rounding mode
  ,OUT result taler_amount
)
LANGUAGE plpgsql AS $$
DECLARE
  product_numeric NUMERIC(33, 8); -- 16 digit for val, 8 for frac and 1 for rounding error
  tiny_numeric NUMERIC(24);
  rounding_error real;
BEGIN
  -- Perform multiplication using big numbers
  product_numeric = (amount.val::numeric(24) * 100000000 + amount.frac::numeric(24)) * (ratio.val::numeric(24, 8) + ratio.frac::numeric(24, 8) / 100000000);

  -- Round to tiny amounts
  tiny_numeric = (tiny.val::numeric(24) * 100000000 + tiny.frac::numeric(24));
  product_numeric = product_numeric / tiny_numeric;
  rounding_error = (product_numeric % 1)::real;
  product_numeric = trunc(product_numeric) * tiny_numeric;
  
  -- Apply rounding mode
  IF (rounding = 'nearest'::rounding_mode AND rounding_error >= 0.5)
    OR (rounding = 'up'::rounding_mode AND rounding_error > 0.0) THEN
    product_numeric = product_numeric + tiny_numeric;
  END IF;

  -- Extract product parts
  result = (trunc(product_numeric / 100000000)::int8, (product_numeric % 100000000)::int4);

  IF (result.val > 1::INT8<<52) THEN
    RAISE EXCEPTION 'amount value overflowed';
  END IF;
END $$;
COMMENT ON FUNCTION conversion_apply_ratio
  IS 'Apply a ratio to an amount rounding the result to a tiny amount following a rounding mode. It raises an exception when the resulting .val is larger than 2^52';

CREATE FUNCTION conversion_revert_ratio(
   IN amount taler_amount
  ,IN ratio taler_amount
  ,IN tiny taler_amount       -- Result is rounded to this amount
  ,IN rounding rounding_mode  -- With this rounding mode
  ,OUT result taler_amount
)
LANGUAGE plpgsql AS $$
DECLARE
  fraction_numeric NUMERIC(33, 8); -- 16 digit for val, 8 for frac and 1 for rounding error
  tiny_numeric NUMERIC(24);
  rounding_error real;
BEGIN
  -- Perform division using big numbers
  fraction_numeric = (amount.val::numeric(24) * 100000000 + amount.frac::numeric(24)) / (ratio.val::numeric(24, 8) + ratio.frac::numeric(24, 8) / 100000000);

  -- Round to tiny amounts
  tiny_numeric = (tiny.val::numeric(24) * 100000000 + tiny.frac::numeric(24));
  fraction_numeric = fraction_numeric / tiny_numeric;
  rounding_error = (fraction_numeric % 1)::real;
  fraction_numeric = trunc(fraction_numeric) * tiny_numeric;

  -- Recover potentially lost tiny amount
  IF (rounding = 'zero'::rounding_mode AND rounding_error > 0) THEN
    fraction_numeric = fraction_numeric + tiny_numeric;
  END IF;

  -- Extract division parts
  result = (trunc(fraction_numeric / 100000000)::int8, (fraction_numeric % 100000000)::int4);

  IF (result.val > 1::INT8<<52) THEN
    RAISE EXCEPTION 'amount value overflowed';
  END IF;
END $$;
COMMENT ON FUNCTION conversion_revert_ratio
  IS 'Revert the application of a ratio. This function does not always return the smallest possible amount. It raises an exception when the resulting .val is larger than 2^52';


CREATE FUNCTION conversion_to(
  IN amount taler_amount,
  IN direction TEXT,
  OUT converted taler_amount,
  OUT too_small BOOLEAN,
  OUT no_config BOOLEAN
)
LANGUAGE plpgsql AS $$
DECLARE
  at_ratio taler_amount;
  out_fee taler_amount;
  tiny_amount taler_amount;
  min_amount taler_amount;
  mode rounding_mode;
BEGIN
  -- Check min amount
  SELECT value['val']::int8, value['frac']::int4 INTO min_amount.val, min_amount.frac FROM config WHERE key=direction||'_min_amount';
  IF NOT FOUND THEN
    no_config = true;
    RETURN;
  END IF;
  SELECT NOT ok INTO too_small FROM amount_left_minus_right(amount, min_amount);
  IF too_small THEN
    converted = (0, 0);
    RETURN;
  END IF;

  -- Perform conversion
  SELECT value['val']::int8, value['frac']::int4 INTO at_ratio.val, at_ratio.frac FROM config WHERE key=direction||'_ratio';
  SELECT value['val']::int8, value['frac']::int4 INTO out_fee.val, out_fee.frac FROM config WHERE key=direction||'_fee';
  SELECT value['val']::int8, value['frac']::int4 INTO tiny_amount.val, tiny_amount.frac FROM config WHERE key=direction||'_tiny_amount';
  SELECT (value->>'mode')::rounding_mode INTO mode FROM config WHERE key=direction||'_rounding_mode';

  SELECT (diff).val, (diff).frac, NOT ok INTO converted.val, converted.frac, too_small 
    FROM amount_left_minus_right(conversion_apply_ratio(amount, at_ratio, tiny_amount, mode), out_fee);

  IF too_small THEN
    converted = (0, 0);
  END IF;
END $$;

CREATE FUNCTION conversion_from(
  IN amount taler_amount,
  IN direction TEXT,
  OUT converted taler_amount,
  OUT too_small BOOLEAN,
  OUT no_config BOOLEAN
)
LANGUAGE plpgsql AS $$
DECLARE
  at_ratio taler_amount;
  out_fee taler_amount;
  tiny_amount taler_amount;
  min_amount taler_amount;
  mode rounding_mode;
BEGIN
  -- Perform conversion
  SELECT value['val']::int8, value['frac']::int4 INTO at_ratio.val, at_ratio.frac FROM config WHERE key=direction||'_ratio';
  SELECT value['val']::int8, value['frac']::int4 INTO out_fee.val, out_fee.frac FROM config WHERE key=direction||'_fee';
  SELECT value['val']::int8, value['frac']::int4 INTO tiny_amount.val, tiny_amount.frac FROM config WHERE key=direction||'_tiny_amount';
  SELECT (value->>'mode')::rounding_mode INTO mode FROM config WHERE key=direction||'_rounding_mode';
  IF NOT FOUND THEN
    no_config = true;
    RETURN;
  END IF;
  SELECT result.val, result.frac INTO converted.val, converted.frac 
    FROM conversion_revert_ratio(amount_add(amount, out_fee), at_ratio, tiny_amount, mode) as result;
  
  -- Check min amount
  SELECT value['val']::int8, value['frac']::int4 INTO min_amount.val, min_amount.frac FROM config WHERE key=direction||'_min_amount';
  SELECT NOT ok INTO too_small FROM amount_left_minus_right(converted, min_amount);
  IF too_small THEN
    converted = (0, 0);
  END IF;
END $$;

COMMIT;