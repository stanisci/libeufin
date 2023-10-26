BEGIN;
SET search_path TO libeufin_bank;

CREATE OR REPLACE FUNCTION amount_normalize(
    IN amount taler_amount
  ,OUT normalized taler_amount
)
LANGUAGE plpgsql AS $$
BEGIN
  normalized.val = amount.val + amount.frac / 100000000;
  IF (normalized.val > 1::bigint<<52) THEN
    RAISE EXCEPTION 'amount value overflowed';
  END IF;
  normalized.frac = amount.frac % 100000000;

END $$;
COMMENT ON FUNCTION amount_normalize
  IS 'Returns the normalized amount by adding to the .val the value of (.frac / 100000000) and removing the modulus 100000000 from .frac.'
      'It raises an exception when the resulting .val is larger than 2^52';

CREATE OR REPLACE FUNCTION amount_add(
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

CREATE OR REPLACE FUNCTION amount_mul(
   IN a taler_amount
  ,IN b taler_amount
  ,OUT product taler_amount
)
LANGUAGE plpgsql AS $$
DECLARE
  tmp NUMERIC(24, 8); -- 16 digit for val and 8 for frac
BEGIN
  -- TODO write custom multiplication logic to get more control over rounding
  tmp = (a.val::numeric(24, 8) + a.frac::numeric(24, 8) / 100000000) * (b.val::numeric(24, 8) + b.frac::numeric(24, 8) / 100000000);
  product = (trunc(tmp)::int8, (tmp * 100000000 % 100000000)::int4);
  IF (product.val > 1::bigint<<52) THEN
    RAISE EXCEPTION 'amount value overflowed';
  END IF;
END $$;
COMMENT ON FUNCTION amount_mul -- TODO document rounding
  IS 'Returns the product of two amounts. It raises an exception when the resulting .val is larger than 2^52';

CREATE OR REPLACE FUNCTION amount_left_minus_right(
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

CREATE OR REPLACE PROCEDURE bank_set_config(
  IN in_key TEXT,
  IN in_value TEXT
)
LANGUAGE plpgsql AS $$
BEGIN
UPDATE configuration SET config_value=in_value WHERE config_key=in_key;
IF NOT FOUND THEN
  INSERT INTO configuration (config_key, config_value) VALUES (in_key, in_value);
END IF;
END $$;
COMMENT ON PROCEDURE bank_set_config(TEXT, TEXT)
  IS 'Update or insert configuration values';

CREATE OR REPLACE FUNCTION account_reconfig(
  IN in_login TEXT,
  IN in_name TEXT,
  IN in_phone TEXT,
  IN in_email TEXT,
  IN in_cashout_payto TEXT,
  IN in_is_taler_exchange BOOLEAN,
  IN in_is_admin BOOLEAN,
  OUT out_not_found BOOLEAN,
  OUT out_legal_name_change BOOLEAN
)
LANGUAGE plpgsql AS $$
DECLARE
my_customer_id INT8;
BEGIN
SELECT
  customer_id,
  in_name IS NOT NULL AND name != in_name AND NOT in_is_admin
  INTO my_customer_id, out_legal_name_change
  FROM customers
  WHERE login=in_login;
IF NOT FOUND THEN
  out_not_found=TRUE;
  RETURN;
ELSIF out_legal_name_change THEN
  RETURN;
END IF;

-- optionally updating the Taler exchange flag
IF in_is_taler_exchange IS NOT NULL THEN
  UPDATE bank_accounts
    SET is_taler_exchange = in_is_taler_exchange
    WHERE owning_customer_id = my_customer_id;
  IF NOT FOUND THEN
    out_not_found=TRUE;
    RETURN;
  END IF;
END IF;


-- bank account patching worked, custom must as well
-- since this runs in a DB transaction and the customer
-- was found earlier in this function.
UPDATE customers
SET
  cashout_payto=in_cashout_payto,
  phone=in_phone,
  email=in_email
WHERE customer_id = my_customer_id;
-- optionally updating the name
IF in_name IS NOT NULL THEN
  UPDATE customers SET name=in_name WHERE customer_id = my_customer_id;
END IF;
END $$;
COMMENT ON FUNCTION account_reconfig(TEXT, TEXT, TEXT, TEXT, TEXT, BOOLEAN, BOOLEAN)
  IS 'Updates values on customer and bank account rows based on the input data.';

CREATE OR REPLACE FUNCTION customer_delete(
  IN in_login TEXT,
  OUT out_nx_customer BOOLEAN,
  OUT out_balance_not_zero BOOLEAN
)
LANGUAGE plpgsql AS $$
DECLARE
my_customer_id BIGINT;
my_balance_val INT8;
my_balance_frac INT4;
BEGIN
-- check if login exists
SELECT customer_id
  INTO my_customer_id
  FROM customers
  WHERE login = in_login;
IF NOT FOUND THEN
  out_nx_customer=TRUE;
  RETURN;
END IF;
out_nx_customer=FALSE;

-- get the balance
SELECT
  (balance).val as balance_val,
  (balance).frac as balance_frac
  INTO
    my_balance_val,
    my_balance_frac
  FROM bank_accounts
  WHERE owning_customer_id = my_customer_id;
IF NOT FOUND THEN
  RAISE EXCEPTION 'Invariant failed: customer lacks bank account';
END IF;
-- check that balance is zero.
IF my_balance_val != 0 OR my_balance_frac != 0 THEN
  out_balance_not_zero=TRUE;
  RETURN;
END IF;
out_balance_not_zero=FALSE;

-- actual deletion
DELETE FROM customers WHERE login = in_login;
END $$;
COMMENT ON FUNCTION customer_delete(TEXT)
  IS 'Deletes a customer (and its bank account via cascade) if the balance is zero';

CREATE OR REPLACE PROCEDURE register_outgoing(
  IN in_request_uid BYTEA,
  IN in_wtid BYTEA,
  IN in_exchange_base_url TEXT,
  IN in_tx_row_id BIGINT
)
LANGUAGE plpgsql AS $$
DECLARE 
  local_amount taler_amount;
  local_bank_account_id BIGINT;
BEGIN
-- Register outgoing transaction
INSERT
  INTO taler_exchange_outgoing (
    request_uid,
    wtid,
    exchange_base_url,
    bank_transaction
) VALUES (
  in_request_uid,
  in_wtid,
  in_exchange_base_url,
  in_tx_row_id
);
-- TODO check if not drain
SELECT (amount).val, (amount).frac, bank_account_id
INTO local_amount.val, local_amount.frac, local_bank_account_id
FROM bank_account_transactions WHERE bank_transaction_id=in_tx_row_id;
CALL stats_register_internal_taler_payment(now()::TIMESTAMP, local_amount);
-- notify new transaction
PERFORM pg_notify('outgoing_tx', local_bank_account_id || ' ' || in_tx_row_id);
END $$;
COMMENT ON PROCEDURE register_outgoing
  IS 'Register a bank transaction as a taler outgoing transaction';

CREATE OR REPLACE PROCEDURE register_incoming(
  IN in_reserve_pub BYTEA,
  IN in_tx_row_id BIGINT,
  IN in_exchange_bank_account_id BIGINT
)
LANGUAGE plpgsql AS $$
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
-- notify new transaction
PERFORM pg_notify('incoming_tx', in_exchange_bank_account_id || ' ' || in_tx_row_id);
END $$;
COMMENT ON PROCEDURE register_incoming
  IS 'Register a bank transaction as a taler incoming transaction';


CREATE OR REPLACE FUNCTION taler_transfer(
  IN in_request_uid BYTEA,
  IN in_wtid BYTEA,
  IN in_subject TEXT,
  IN in_amount taler_amount,
  IN in_exchange_base_url TEXT,
  IN in_credit_account_payto TEXT,
  IN in_username TEXT,
  IN in_timestamp BIGINT,
  IN in_account_servicer_reference TEXT,
  IN in_payment_information_id TEXT,
  IN in_end_to_end_id TEXT,
  -- Error status
  OUT out_debtor_not_found BOOLEAN,
  OUT out_debtor_not_exchange BOOLEAN,
  OUT out_creditor_not_found BOOLEAN,
  OUT out_same_account BOOLEAN,
  OUT out_both_exchanges BOOLEAN,
  OUT out_request_uid_reuse BOOLEAN,
  OUT out_exchange_balance_insufficient BOOLEAN,
  -- Success return
  OUT out_tx_row_id BIGINT,
  OUT out_timestamp BIGINT
)
LANGUAGE plpgsql AS $$
DECLARE
exchange_bank_account_id BIGINT;
receiver_bank_account_id BIGINT;
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
  WHERE login = in_username;
IF NOT FOUND THEN
  out_debtor_not_found=TRUE;
  RETURN;
ELSIF out_debtor_not_exchange THEN
  RETURN;
END IF;
-- Find receiver bank account id
SELECT
  bank_account_id, is_taler_exchange
  INTO receiver_bank_account_id, out_both_exchanges
  FROM bank_accounts
  WHERE internal_payto_uri = in_credit_account_payto;
IF NOT FOUND THEN
  out_creditor_not_found=TRUE;
  RETURN;
ELSIF out_both_exchanges THEN
  RETURN;
END IF;
-- Perform bank transfer
SELECT
  out_balance_insufficient,
  out_debit_row_id,
  transfer.out_same_account
  INTO
    out_exchange_balance_insufficient,
    out_tx_row_id,
    out_same_account
  FROM bank_wire_transfer(
    receiver_bank_account_id,
    exchange_bank_account_id,
    in_subject,
    in_amount,
    in_timestamp,
    in_account_servicer_reference,
    in_payment_information_id,
    in_end_to_end_id
  ) as transfer;
IF out_exchange_balance_insufficient THEN
  RETURN;
END IF;
out_timestamp=in_timestamp;
-- Register outgoing transaction
CALL register_outgoing(in_request_uid, in_wtid, in_exchange_base_url, out_tx_row_id);
END $$;
COMMENT ON FUNCTION taler_transfer(
  bytea,
  bytea,
  text,
  taler_amount,
  text,
  text,
  text,
  bigint,
  text,
  text,
  text
  )-- TODO new comment
  IS 'function that (1) inserts the TWG requests'
     'details into the database and (2) performs '
     'the actual bank transaction to pay the merchant';


CREATE OR REPLACE FUNCTION taler_add_incoming(
  IN in_reserve_pub BYTEA,
  IN in_subject TEXT,
  IN in_amount taler_amount,
  IN in_debit_account_payto TEXT,
  IN in_username TEXT,
  IN in_timestamp BIGINT,
  IN in_account_servicer_reference TEXT,
  IN in_payment_information_id TEXT,
  IN in_end_to_end_id TEXT,
  -- Error status
  OUT out_creditor_not_found BOOLEAN,
  OUT out_creditor_not_exchange BOOLEAN,
  OUT out_debtor_not_found BOOLEAN,
  OUT out_same_account BOOLEAN,
  OUT out_both_exchanges BOOLEAN,
  OUT out_reserve_pub_reuse BOOLEAN,
  OUT out_debitor_balance_insufficient BOOLEAN,
  -- Success return
  OUT out_tx_row_id BIGINT
)
LANGUAGE plpgsql AS $$
DECLARE
exchange_bank_account_id BIGINT;
sender_bank_account_id BIGINT;
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
  WHERE login = in_username;
IF NOT FOUND THEN
  out_creditor_not_found=TRUE;
  RETURN;
ELSIF out_creditor_not_exchange THEN
  RETURN;
END IF;
-- Find sender bank account id
SELECT
  bank_account_id, is_taler_exchange
  INTO sender_bank_account_id, out_both_exchanges
  FROM bank_accounts
  WHERE internal_payto_uri = in_debit_account_payto;
IF NOT FOUND THEN
  out_debtor_not_found=TRUE;
  RETURN;
ELSIF out_both_exchanges THEN
  RETURN;
END IF;
-- Perform bank transfer
SELECT
  out_balance_insufficient,
  out_credit_row_id,
  transfer.out_same_account
  INTO
    out_debitor_balance_insufficient,
    out_tx_row_id,
    out_same_account
  FROM bank_wire_transfer(
    exchange_bank_account_id,
    sender_bank_account_id,
    in_subject,
    in_amount,
    in_timestamp,
    in_account_servicer_reference,
    in_payment_information_id,
    in_end_to_end_id
  ) as transfer;
IF out_debitor_balance_insufficient THEN
  RETURN;
END IF;
-- Register incoming transaction
CALL register_incoming(in_reserve_pub, out_tx_row_id, exchange_bank_account_id);
END $$;
COMMENT ON FUNCTION taler_add_incoming(
  bytea,
  text,
  taler_amount,
  text,
  text,
  bigint,
  text,
  text,
  text
  ) -- TODO new comment
  IS 'function that (1) inserts the TWG requests'
     'details into the database and (2) performs '
     'the actual bank transaction to pay the merchant';

CREATE OR REPLACE FUNCTION bank_transaction(
  IN in_credit_account_payto TEXT,
  IN in_debit_account_username TEXT,
  IN in_subject TEXT,
  IN in_amount taler_amount,
  IN in_timestamp BIGINT,
  IN in_account_servicer_reference TEXT,
  IN in_payment_information_id TEXT,
  IN in_end_to_end_id TEXT,
  -- Error status
  OUT out_creditor_not_found BOOLEAN,
  OUT out_debtor_not_found BOOLEAN,
  OUT out_same_account BOOLEAN,
  OUT out_balance_insufficient BOOLEAN,
  -- Success return
  OUT out_credit_bank_account_id BIGINT,
  OUT out_debit_bank_account_id BIGINT,
  OUT out_credit_row_id BIGINT,
  OUT out_debit_row_id BIGINT,
  OUT out_creditor_is_exchange BOOLEAN,
  OUT out_debtor_is_exchange BOOLEAN
)
LANGUAGE plpgsql AS $$
BEGIN
-- Find credit bank account id
SELECT bank_account_id
  INTO out_credit_bank_account_id
  FROM bank_accounts
  WHERE internal_payto_uri = in_credit_account_payto;
IF NOT FOUND THEN
  out_creditor_not_found=TRUE;
  RETURN;
END IF;
-- Find debit bank account id
SELECT bank_account_id
  INTO out_debit_bank_account_id
  FROM bank_accounts 
      JOIN customers 
        ON customer_id=owning_customer_id
  WHERE login = in_debit_account_username;
IF NOT FOUND THEN
  out_debtor_not_found=TRUE;
  RETURN;
END IF;
-- Perform bank transfer
SELECT
  transfer.out_balance_insufficient,
  transfer.out_credit_row_id,
  transfer.out_debit_row_id,
  transfer.out_same_account,
  transfer.out_creditor_is_exchange,
  transfer.out_debtor_is_exchange
  INTO
    out_balance_insufficient,
    out_credit_row_id,
    out_debit_row_id,
    out_same_account,
    out_creditor_is_exchange,
    out_debtor_is_exchange
  FROM bank_wire_transfer(
    out_credit_bank_account_id,
    out_debit_bank_account_id,
    in_subject,
    in_amount,
    in_timestamp,
    in_account_servicer_reference,
    in_payment_information_id,
    in_end_to_end_id
  ) as transfer;
IF out_balance_insufficient THEN
  RETURN;
END IF;
END $$;

CREATE OR REPLACE FUNCTION create_taler_withdrawal(
  IN in_account_username TEXT,
  IN in_withdrawal_uuid UUID,
  IN in_amount taler_amount,
   -- Error status
  OUT out_account_not_found BOOLEAN,
  OUT out_account_is_exchange BOOLEAN,
  OUT out_balance_insufficient BOOLEAN
)
LANGUAGE plpgsql AS $$ 
DECLARE
account_id BIGINT;
account_has_debt BOOLEAN;
account_balance taler_amount;
account_max_debt taler_amount;
BEGIN
-- check account exists
SELECT
  has_debt, bank_account_id,
  (balance).val, (balance).frac,
  (max_debt).val, (max_debt).frac,
  is_taler_exchange
  INTO
    account_has_debt, account_id,
    account_balance.val, account_balance.frac,
    account_max_debt.val, account_max_debt.frac,
    out_account_is_exchange
  FROM bank_accounts
  JOIN customers ON bank_accounts.owning_customer_id = customers.customer_id
  WHERE login=in_account_username;
IF NOT FOUND THEN
  out_account_not_found=TRUE;
  RETURN;
ELSIF out_account_is_exchange THEN
  RETURN;
END IF;

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

-- Create withdrawal operation
INSERT INTO taler_withdrawal_operations
    (withdrawal_uuid, wallet_bank_account, amount)
  VALUES (in_withdrawal_uuid, account_id, in_amount);
END $$;

CREATE OR REPLACE FUNCTION select_taler_withdrawal(
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
  OUT out_confirmation_done BOOLEAN
)
LANGUAGE plpgsql AS $$ 
DECLARE
not_selected BOOLEAN;
BEGIN
-- Check for conflict and idempotence
SELECT
  NOT selection_done, confirmation_done,
  selection_done 
    AND (selected_exchange_payto != in_selected_exchange_payto OR reserve_pub != in_reserve_pub)
  INTO not_selected, out_confirmation_done, out_already_selected
  FROM taler_withdrawal_operations
  WHERE withdrawal_uuid=in_withdrawal_uuid;
IF NOT FOUND THEN
  out_no_op=TRUE;
  RETURN;
ELSIF out_already_selected THEN
  RETURN;
END IF;

IF NOT out_confirmation_done AND not_selected THEN
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
  IF NOT FOUND THEN
    out_account_not_found=TRUE;
    RETURN;
  ELSIF out_account_is_not_exchange THEN
    RETURN;
  END IF;

  -- Update withdrawal operation
  UPDATE taler_withdrawal_operations
    SET selected_exchange_payto=in_selected_exchange_payto, reserve_pub=in_reserve_pub, subject=in_subject, selection_done=true
    WHERE withdrawal_uuid=in_withdrawal_uuid;
END IF;
END $$;


CREATE OR REPLACE FUNCTION confirm_taler_withdrawal(
  IN in_withdrawal_uuid uuid,
  IN in_confirmation_date BIGINT,
  IN in_acct_svcr_ref TEXT,
  IN in_pmt_inf_id TEXT,
  IN in_end_to_end_id TEXT,
  OUT out_no_op BOOLEAN,
  OUT out_balance_insufficient BOOLEAN,
  OUT out_creditor_not_found BOOLEAN,
  OUT out_exchange_not_found BOOLEAN,
  OUT out_not_selected BOOLEAN,
  OUT out_aborted BOOLEAN
)
LANGUAGE plpgsql AS $$
DECLARE
  already_confirmed BOOLEAN;
  subject_local TEXT;
  reserve_pub_local BYTEA;
  selected_exchange_payto_local TEXT;
  wallet_bank_account_local BIGINT;
  amount_local taler_amount;
  exchange_bank_account_id BIGINT;
  tx_row_id BIGINT;
BEGIN
SELECT -- Really no-star policy and instead DECLARE almost one var per column?
  confirmation_done,
  aborted, NOT selection_done,
  reserve_pub, subject,
  selected_exchange_payto,
  wallet_bank_account,
  (amount).val, (amount).frac
  INTO
    already_confirmed,
    out_aborted, out_not_selected,
    reserve_pub_local, subject_local,
    selected_exchange_payto_local,
    wallet_bank_account_local,
    amount_local.val, amount_local.frac
  FROM taler_withdrawal_operations
  WHERE withdrawal_uuid=in_withdrawal_uuid;
IF NOT FOUND THEN
  out_no_op=TRUE;
  RETURN;
ELSIF already_confirmed OR out_aborted OR out_not_selected THEN
  RETURN;
END IF;

-- sending the funds to the exchange, but needs first its bank account row ID
SELECT
  bank_account_id
  INTO exchange_bank_account_id
  FROM bank_accounts
  WHERE internal_payto_uri = selected_exchange_payto_local;
IF NOT FOUND THEN
  out_exchange_not_found=TRUE;
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
  in_acct_svcr_ref,
  in_pmt_inf_id,
  in_end_to_end_id
) as transfer;
IF out_balance_insufficient THEN
  RETURN;
END IF;

-- Confirm operation
UPDATE taler_withdrawal_operations
  SET confirmation_done = true
  WHERE withdrawal_uuid=in_withdrawal_uuid;

-- Register incoming transaction
CALL register_incoming(reserve_pub_local, tx_row_id, exchange_bank_account_id);
END $$;
COMMENT ON FUNCTION confirm_taler_withdrawal(uuid, bigint, text, text, text)
  IS 'Set a withdrawal operation as confirmed and wire the funds to the exchange.';

CREATE OR REPLACE FUNCTION bank_wire_transfer(
  IN in_creditor_account_id BIGINT,
  IN in_debtor_account_id BIGINT,
  IN in_subject TEXT,
  IN in_amount taler_amount,
  IN in_transaction_date BIGINT, -- GNUnet microseconds.
  IN in_account_servicer_reference TEXT,
  IN in_payment_information_id TEXT,
  IN in_end_to_end_id TEXT,
  -- Error status
  OUT out_same_account BOOLEAN,
  OUT out_debtor_not_found BOOLEAN,
  OUT out_creditor_not_found BOOLEAN,
  OUT out_balance_insufficient BOOLEAN,
  -- Success return
  OUT out_credit_row_id BIGINT,
  OUT out_debit_row_id BIGINT,
  OUT out_creditor_is_exchange BOOLEAN,
  OUT out_debtor_is_exchange BOOLEAN
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
new_debit_row_id BIGINT;
new_credit_row_id BIGINT;
BEGIN

IF in_creditor_account_id=in_debtor_account_id THEN
  out_same_account=TRUE;
  RETURN;
END IF;
out_same_account=FALSE;

-- check debtor exists.
SELECT
  has_debt,
  (balance).val, (balance).frac,
  (max_debt).val, (max_debt).frac,
  internal_payto_uri, customers.name,
  is_taler_exchange
  INTO
    debtor_has_debt,
    debtor_balance.val, debtor_balance.frac,
    debtor_max_debt.val, debtor_max_debt.frac,
    debtor_payto_uri, debtor_name,
    out_debtor_is_exchange
  FROM bank_accounts
  JOIN customers ON (bank_accounts.owning_customer_id = customers.customer_id)
  WHERE bank_account_id=in_debtor_account_id;
IF NOT FOUND THEN
  out_debtor_not_found=TRUE;
  RETURN;
END IF;
out_debtor_not_found=FALSE;
-- check creditor exists.  Future versions may skip this
-- due to creditors being hosted at other banks.
SELECT
  has_debt,
  (balance).val, (balance).frac,
  internal_payto_uri, customers.name,
  is_taler_exchange
  INTO
    creditor_has_debt,
    creditor_balance.val, creditor_balance.frac,
    creditor_payto_uri, creditor_name,
    out_creditor_is_exchange
  FROM bank_accounts
  JOIN customers ON (bank_accounts.owning_customer_id = customers.customer_id)
  WHERE bank_account_id=in_creditor_account_id;
IF NOT FOUND THEN
  out_creditor_not_found=TRUE;
  RETURN;
END IF;
out_creditor_not_found=FALSE;

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
) RETURNING bank_transaction_id INTO new_debit_row_id;
out_debit_row_id=new_debit_row_id;

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
) RETURNING bank_transaction_id INTO new_credit_row_id;
out_credit_row_id=new_credit_row_id;

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

CREATE OR REPLACE FUNCTION cashout_delete(
  IN in_cashout_uuid UUID,
  OUT out_already_confirmed BOOLEAN
)
LANGUAGE plpgsql AS $$
BEGIN
  PERFORM
    FROM cashout_operations
    WHERE cashout_uuid=in_cashout_uuid AND tan_confirmation_time IS NOT NULL;
  IF FOUND THEN
    out_already_confirmed=TRUE;
    RETURN;
  END IF;
  out_already_confirmed=FALSE;
  DELETE FROM cashout_operations WHERE cashout_uuid=in_cashout_uuid;
END $$;

CREATE OR REPLACE FUNCTION stats_get_frame(
  IN now TIMESTAMP,
  IN in_timeframe stat_timeframe_enum,
  IN which INTEGER,
  OUT cashin_count BIGINT,
  OUT cashin_volume_in_fiat taler_amount,
  OUT cashout_count BIGINT,
  OUT cashout_volume_in_fiat taler_amount,
  OUT internal_taler_payments_count BIGINT,
  OUT internal_taler_payments_volume taler_amount
)
LANGUAGE plpgsql AS $$
DECLARE
  local_start_time TIMESTAMP;
BEGIN
  local_start_time = CASE 
    WHEN which IS NULL          THEN date_trunc(in_timeframe::text, now)
    WHEN in_timeframe = 'hour'  THEN date_trunc('day'  , now) + make_interval(hours  => which)
    WHEN in_timeframe = 'day'   THEN date_trunc('month', now) + make_interval(days   => which-1)
    WHEN in_timeframe = 'month' THEN date_trunc('year' , now) + make_interval(months => which-1)
    WHEN in_timeframe = 'year'  THEN make_date(which, 1, 1)::TIMESTAMP
  END;
  SELECT 
    s.cashin_count
    ,(s.cashin_volume_in_fiat).val
    ,(s.cashin_volume_in_fiat).frac
    ,s.cashout_count
    ,(s.cashout_volume_in_fiat).val
    ,(s.cashout_volume_in_fiat).frac
    ,s.internal_taler_payments_count
    ,(s.internal_taler_payments_volume).val
    ,(s.internal_taler_payments_volume).frac
  INTO
    cashin_count
    ,cashin_volume_in_fiat.val
    ,cashin_volume_in_fiat.frac
    ,cashout_count
    ,cashout_volume_in_fiat.val
    ,cashout_volume_in_fiat.frac
    ,internal_taler_payments_count
    ,internal_taler_payments_volume.val
    ,internal_taler_payments_volume.frac
  FROM regional_stats AS s
  WHERE s.timeframe = in_timeframe 
    AND s.start_time = local_start_time;
END $$;

CREATE OR REPLACE PROCEDURE stats_register_internal_taler_payment(
  IN now TIMESTAMP,
  IN amount taler_amount
)
LANGUAGE plpgsql AS $$
DECLARE
  frame stat_timeframe_enum;
BEGIN
  FOREACH frame IN ARRAY enum_range(null::stat_timeframe_enum) LOOP
    INSERT INTO regional_stats AS s (
      timeframe
      ,start_time
      ,cashin_count
      ,cashin_volume_in_fiat
      ,cashout_count
      ,cashout_volume_in_fiat
      ,internal_taler_payments_count
      ,internal_taler_payments_volume
      ) 
    VALUES (
        frame
        ,date_trunc(frame::text, now)
        ,0
        ,(0, 0)::taler_amount
        ,0
        ,(0, 0)::taler_amount
        ,1
        ,amount
      )
    ON CONFLICT (timeframe, start_time) DO UPDATE
    SET internal_taler_payments_count = s.internal_taler_payments_count+1
        ,internal_taler_payments_volume = (SELECT amount_add(s.internal_taler_payments_volume, amount));
  END LOOP;
END $$;

CREATE OR REPLACE PROCEDURE conversion_config_update(
  IN buy_at_ratio taler_amount,
  IN sell_at_ratio taler_amount,
  IN buy_in_fee taler_amount,
  IN sell_out_fee taler_amount
)
LANGUAGE sql AS $$
  INSERT INTO config (key, value) VALUES ('buy_at_ratio', jsonb_build_object('val', buy_at_ratio.val, 'frac', buy_at_ratio.frac));
  INSERT INTO config (key, value) VALUES ('sell_at_ratio', jsonb_build_object('val', sell_at_ratio.val, 'frac', sell_at_ratio.frac));
  INSERT INTO config (key, value) VALUES ('buy_in_fee', jsonb_build_object('val', buy_in_fee.val, 'frac', buy_in_fee.frac));
  INSERT INTO config (key, value) VALUES ('sell_out_fee', jsonb_build_object('val', sell_out_fee.val, 'frac', sell_out_fee.frac));
$$;

CREATE OR REPLACE FUNCTION conversion_internal_to_fiat(
  IN internal_amount taler_amount,
  OUT fiat_amount taler_amount
)
LANGUAGE plpgsql AS $$
DECLARE
  sell_at_ratio taler_amount;
  sell_out_fee taler_amount;
  calculation_ok BOOLEAN;
BEGIN
  SELECT value['val']::int8, value['frac']::int4 INTO sell_at_ratio.val, sell_at_ratio.frac FROM config WHERE key='sell_at_ratio';
  SELECT value['val']::int8, value['frac']::int4 INTO sell_out_fee.val, sell_out_fee.frac FROM config WHERE key='sell_out_fee';

  SELECT product.val, product.frac INTO fiat_amount.val, fiat_amount.frac FROM amount_mul(internal_amount, sell_at_ratio) as product;
  SELECT (diff).val, (diff).frac, ok INTO fiat_amount.val, fiat_amount.frac, calculation_ok FROM amount_left_minus_right(fiat_amount, sell_out_fee);

  IF NOT calculation_ok THEN
    fiat_amount = (0, 0); -- TODO how to handle zero and less than zero ?
  END IF;
END $$;

COMMIT;