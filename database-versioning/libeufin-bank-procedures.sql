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

CREATE OR REPLACE FUNCTION account_balance_is_sufficient(
  IN in_account_id BIGINT,
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

CREATE OR REPLACE FUNCTION account_reconfig(
  IN in_login TEXT,
  IN in_name TEXT,
  IN in_phone TEXT,
  IN in_email TEXT,
  IN in_cashout_payto TEXT,
  IN in_is_taler_exchange BOOLEAN,
  IN in_max_debt taler_amount,
  IN in_is_admin BOOLEAN,
  OUT out_not_found BOOLEAN,
  OUT out_legal_name_change BOOLEAN,
  OUT out_debt_limit_change BOOLEAN
)
LANGUAGE plpgsql AS $$
DECLARE
my_customer_id INT8;
BEGIN
IF (in_max_debt.val IS NULL) THEN
  in_max_debt = NULL;
END IF;
-- Get user ID and check reconfig rights
SELECT
  customer_id,
  in_name IS NOT NULL AND name != in_name AND NOT in_is_admin,
  in_max_debt IS NOT NULL AND max_debt != in_max_debt AND NOT in_is_admin
  INTO my_customer_id, out_legal_name_change, out_debt_limit_change
  FROM customers
    JOIN bank_accounts 
    ON customer_id=owning_customer_id
  WHERE login=in_login;
IF NOT FOUND THEN
  out_not_found=TRUE;
  RETURN;
ELSIF out_legal_name_change OR out_debt_limit_change THEN
  RETURN;
END IF;

-- Update bank info
UPDATE bank_accounts SET 
  is_taler_exchange = COALESCE(in_is_taler_exchange, is_taler_exchange),
  max_debt = COALESCE(in_max_debt, max_debt)
WHERE owning_customer_id = my_customer_id;
-- Update customer info
UPDATE customers SET
  cashout_payto=in_cashout_payto,
  phone=in_phone,
  email=in_email,
  name = COALESCE(in_name, name)
WHERE customer_id = my_customer_id;
END $$;
COMMENT ON FUNCTION account_reconfig
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
  IN in_debtor_account_id BIGINT,
  IN in_creditor_account_id BIGINT,
  IN in_debit_row_id BIGINT,
  IN in_credit_row_id BIGINT
)
LANGUAGE plpgsql AS $$
DECLARE 
  local_amount taler_amount;
  local_bank_account_id BIGINT;
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
CALL stats_register_payment('taler_out', now()::TIMESTAMP, local_amount, null);
-- notify new transaction
PERFORM pg_notify('outgoing_tx', in_debtor_account_id || ' ' || in_creditor_account_id || ' ' || in_debit_row_id || ' ' || in_credit_row_id);
END $$;
COMMENT ON PROCEDURE register_outgoing
  IS 'Register a bank transaction as a taler outgoing transaction and announce it';

CREATE OR REPLACE PROCEDURE register_incoming(
  IN in_reserve_pub BYTEA,
  IN in_tx_row_id BIGINT
)
LANGUAGE plpgsql AS $$
DECLARE
local_amount taler_amount;
local_bank_account_id BIGINT;
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
CALL stats_register_payment('taler_in', now()::TIMESTAMP, local_amount, null);
-- notify new transaction
PERFORM pg_notify('incoming_tx', local_bank_account_id || ' ' || in_tx_row_id);
END $$;
COMMENT ON PROCEDURE register_incoming
  IS 'Register a bank transaction as a taler incoming transaction and announce it';


CREATE OR REPLACE FUNCTION taler_transfer(
  IN in_request_uid BYTEA,
  IN in_wtid BYTEA,
  IN in_subject TEXT,
  IN in_amount taler_amount,
  IN in_exchange_base_url TEXT,
  IN in_credit_account_payto TEXT,
  IN in_username TEXT,
  IN in_timestamp BIGINT,
  -- Error status
  OUT out_debtor_not_found BOOLEAN,
  OUT out_debtor_not_exchange BOOLEAN,
  OUT out_creditor_not_found BOOLEAN,
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
credit_row_id BIGINT;
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

CREATE OR REPLACE FUNCTION taler_add_incoming(
  IN in_reserve_pub BYTEA,
  IN in_subject TEXT,
  IN in_amount taler_amount,
  IN in_debit_account_payto TEXT,
  IN in_username TEXT,
  IN in_timestamp BIGINT,
  -- Error status
  OUT out_creditor_not_found BOOLEAN,
  OUT out_creditor_not_exchange BOOLEAN,
  OUT out_debtor_not_found BOOLEAN,
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

CREATE OR REPLACE FUNCTION bank_transaction(
  IN in_credit_account_payto TEXT,
  IN in_debit_account_username TEXT,
  IN in_subject TEXT,
  IN in_amount taler_amount,
  IN in_timestamp BIGINT,
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
    NULL,
    NULL,
    NULL
  ) as transfer;
IF out_balance_insufficient THEN
  RETURN;
END IF;
END $$;
COMMENT ON FUNCTION bank_transaction IS 'Create a bank transaction';

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
BEGIN
-- check account exists
SELECT bank_account_id, is_taler_exchange
  INTO account_id, out_account_is_exchange
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
SELECT account_balance_is_sufficient(account_id, in_amount) INTO out_balance_insufficient;
IF out_balance_insufficient THEN
  RETURN;
END IF;

-- Create withdrawal operation
INSERT INTO taler_withdrawal_operations
    (withdrawal_uuid, wallet_bank_account, amount)
  VALUES (in_withdrawal_uuid, account_id, in_amount);
END $$;
COMMENT ON FUNCTION create_taler_withdrawal IS 'Create a new withdrawal operation';

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
IF NOT FOUND THEN
  out_no_op=TRUE;
  RETURN;
ELSIF out_already_selected THEN
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

  -- Notify status change
  PERFORM pg_notify('withdrawal_status', in_withdrawal_uuid::text || ' selected');
END IF;
END $$;
COMMENT ON FUNCTION select_taler_withdrawal IS 'Set details of a withdrawal operation';

CREATE OR REPLACE FUNCTION abort_taler_withdrawal(
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
IF NOT FOUND THEN
  out_no_op=TRUE;
  RETURN;
ELSIF out_already_confirmed THEN
  RETURN;
END IF;

-- Notify status change
PERFORM pg_notify('withdrawal_status', in_withdrawal_uuid::text || ' aborted');
END $$;
COMMENT ON FUNCTION abort_taler_withdrawal IS 'Abort a withdrawal operation.';

CREATE OR REPLACE FUNCTION confirm_taler_withdrawal(
  IN in_withdrawal_uuid uuid,
  IN in_confirmation_date BIGINT,
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

CREATE OR REPLACE FUNCTION cashin(
  IN in_now_date BIGINT,
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
  admin_account_id BIGINT;
  exchange_account_id BIGINT;
  tx_row_id BIGINT;
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
CALL stats_register_payment('cashin', now()::TIMESTAMP, converted_amount, in_amount);

END $$;
COMMENT ON FUNCTION cashin IS 'Perform a cashin operation';


CREATE OR REPLACE FUNCTION cashout_create(
  IN in_account_username TEXT,
  IN in_request_uid BYTEA,
  IN in_amount_debit taler_amount,
  IN in_amount_credit taler_amount,
  IN in_subject TEXT,
  IN in_now_date INT8,
  IN in_tan_channel tan_enum,
  IN in_tan_code TEXT,
  IN in_retry_counter INT4,
  IN in_validity_period INT8,
  -- Error status
  OUT out_bad_conversion BOOLEAN,
  OUT out_account_not_found BOOLEAN,
  OUT out_account_is_exchange BOOLEAN,
  OUT out_missing_tan_info BOOLEAN,
  OUT out_balance_insufficient BOOLEAN,
  OUT out_request_uid_reuse BOOLEAN,
  -- Success return
  OUT out_cashout_id BIGINT,
  OUT out_tan_info TEXT,
  OUT out_tan_code TEXT
)
LANGUAGE plpgsql AS $$ 
DECLARE
account_id BIGINT;
challenge_id BIGINT;
BEGIN
-- check conversion
SELECT too_small OR no_config OR in_amount_credit!=converted INTO out_bad_conversion FROM conversion_to(in_amount_debit, 'cashout'::text);
IF out_bad_conversion THEN
  RETURN;
END IF;

-- check account exists and has appropriate tan info
SELECT 
    bank_account_id, is_taler_exchange,
    CASE 
      WHEN in_tan_channel = 'sms'   THEN phone
      WHEN in_tan_channel = 'email' THEN email
    END
  INTO account_id, out_account_is_exchange, out_tan_info
  FROM bank_accounts
  JOIN customers ON bank_accounts.owning_customer_id = customers.customer_id
  WHERE login=in_account_username;
IF NOT FOUND THEN
  out_account_not_found=TRUE;
  RETURN;
ELSIF out_account_is_exchange THEN
  RETURN;
ELSIF out_tan_info IS NULL THEN
  out_missing_tan_info=TRUE;
  RETURN;
END IF;

-- check enough funds
SELECT account_balance_is_sufficient(account_id, in_amount_debit) INTO out_balance_insufficient;
IF out_balance_insufficient THEN
  RETURN;
END IF;

-- Check for idempotence and conflict
SELECT (amount_debit != in_amount_debit
          OR subject != in_subject 
          OR bank_account != account_id)
        , challenge, cashout_id
  INTO out_request_uid_reuse, challenge_id, out_cashout_id
  FROM cashout_operations
  WHERE request_uid = in_request_uid;

IF NOT found THEN
  -- New cashout
  out_tan_code = in_tan_code;

  -- Create challenge
  SELECT challenge_create(in_tan_code, in_now_date, in_validity_period, in_retry_counter) INTO challenge_id;

  -- Create cashout operation
  INSERT INTO cashout_operations (
    request_uid
    ,amount_debit
    ,amount_credit
    ,subject
    ,creation_time
    ,bank_account
    ,challenge
  ) VALUES (
    in_request_uid
    ,in_amount_debit
    ,in_amount_credit
    ,in_subject
    ,in_now_date
    ,account_id
    ,challenge_id
  ) RETURNING cashout_id INTO out_cashout_id;
ELSE -- Already exist, check challenge retransmission
  SELECT challenge_resend(challenge_id, in_tan_code, in_now_date, in_validity_period, in_retry_counter) INTO out_tan_code;
END IF;
END $$;

CREATE OR REPLACE FUNCTION cashout_confirm(
  IN in_cashout_id BIGINT,
  IN in_login TEXT,
  IN in_tan_code TEXT,
  IN in_now_date BIGINT,
  OUT out_no_op BOOLEAN,
  OUT out_bad_conversion BOOLEAN,
  OUT out_bad_code BOOLEAN,
  OUT out_balance_insufficient BOOLEAN,
  OUT out_aborted BOOLEAN,
  OUT out_no_retry BOOLEAN,
  OUT out_no_cashout_payto BOOLEAN
)
LANGUAGE plpgsql as $$
DECLARE
  wallet_account_id BIGINT;
  admin_account_id BIGINT;
  already_confirmed BOOLEAN;
  subject_local TEXT;
  amount_debit_local taler_amount;
  amount_credit_local taler_amount;
  challenge_id BIGINT;
  tx_id BIGINT;
BEGIN
-- Retrieve cashout operation info
SELECT
  local_transaction IS NOT NULL,
  aborted, subject,
  bank_account, challenge,
  (amount_debit).val, (amount_debit).frac,
  (amount_credit).val, (amount_credit).frac,
  cashout_payto IS NULL
  INTO
    already_confirmed,
    out_aborted, subject_local,
    wallet_account_id, challenge_id,
    amount_debit_local.val, amount_debit_local.frac,
    amount_credit_local.val, amount_credit_local.frac,
    out_no_cashout_payto
  FROM cashout_operations
    JOIN bank_accounts ON bank_account_id=bank_account
    JOIN customers ON customer_id=owning_customer_id
  WHERE cashout_id=in_cashout_id AND login=in_login;
IF NOT FOUND THEN
  out_no_op=TRUE;
  RETURN;
ELSIF already_confirmed OR out_aborted OR out_no_cashout_payto THEN
  RETURN;
END IF;

-- check conversion
SELECT too_small OR no_config OR amount_credit_local!=converted INTO out_bad_conversion FROM conversion_to(amount_debit_local, 'cashout'::text);
IF out_bad_conversion THEN
  RETURN;
END IF;

-- check challenge
SELECT NOT ok, no_retry
  INTO out_bad_code, out_no_retry
  FROM challenge_try(challenge_id, in_tan_code, in_now_date);
IF out_bad_code OR out_no_retry THEN
  RETURN;
END IF;

-- Retrieve admin account id
SELECT bank_account_id
  INTO admin_account_id
  FROM bank_accounts
    JOIN customers 
      ON customer_id=owning_customer_id
  WHERE login = 'admin';

-- Perform bank wire transfer
SELECT transfer.out_balance_insufficient, out_debit_row_id
INTO out_balance_insufficient, tx_id
FROM bank_wire_transfer(
  admin_account_id,
  wallet_account_id,
  subject_local,
  amount_debit_local,
  in_now_date,
  NULL,
  NULL,
  NULL
) as transfer;
IF out_balance_insufficient THEN
  RETURN;
END IF;

-- Confirm operation
UPDATE cashout_operations
  SET local_transaction = tx_id
  WHERE cashout_id=in_cashout_id;

-- update stats
CALL stats_register_payment('cashout', now()::TIMESTAMP, amount_debit_local, amount_credit_local);
END $$;

CREATE OR REPLACE FUNCTION challenge_create (
  IN in_code TEXT,
  IN in_now_date INT8,
  IN in_validity_period INT8,
  IN in_retry_counter INT4,
  OUT out_challenge_id BIGINT
)
LANGUAGE sql AS $$
  INSERT INTO challenges (
    code,
    creation_date,
    expiration_date,
    retry_counter
  ) VALUES (
    in_code,
    in_now_date,
    in_now_date + in_validity_period,
    in_retry_counter
  ) RETURNING challenge_id
$$;
COMMENT ON FUNCTION challenge_create IS 'Create a new challenge, return the generated id';

CREATE OR REPLACE FUNCTION challenge_mark_sent (
  IN in_challenge_id BIGINT,
  IN in_now_date INT8,
  IN in_retransmission_period INT8
) RETURNS void
LANGUAGE sql AS $$
  UPDATE challenges SET 
    retransmission_date = in_now_date + in_retransmission_period
  WHERE challenge_id = in_challenge_id;
$$;
COMMENT ON FUNCTION challenge_create IS 'Register a challenge as successfully sent';

CREATE OR REPLACE FUNCTION challenge_resend (
  IN in_challenge_id BIGINT, 
  IN in_code TEXT,            -- New code to use if the old code expired
  IN in_now_date INT8,        
  IN in_validity_period INT8,
  IN in_retry_counter INT4,
  OUT out_tan_code TEXT       -- Code to send, NULL if nothing should be sent
)
LANGUAGE plpgsql as $$
DECLARE
expired BOOLEAN;
retransmit BOOLEAN;
BEGIN
-- Recover expiration date
SELECT 
  (in_now_date >= expiration_date OR retry_counter <= 0) AND confirmation_date IS NULL
  ,in_now_date >= retransmission_date AND confirmation_date IS NULL
  ,code
INTO expired, retransmit, out_tan_code
FROM challenges WHERE challenge_id = in_challenge_id;

IF expired THEN
  UPDATE challenges SET
     code = in_code
    ,expiration_date = in_now_date + in_validity_period
    ,retry_counter = in_retry_counter
  WHERE challenge_id = in_challenge_id;
  out_tan_code = in_code;
ELSIF NOT retransmit THEN
  out_tan_code = NULL;
END IF;
END $$;
COMMENT ON FUNCTION challenge_resend IS 'Get the challenge code to send, return NULL if nothing should be sent';

CREATE OR REPLACE FUNCTION challenge_try (
  IN in_challenge_id BIGINT, 
  IN in_code TEXT,    
  IN in_now_date INT8,        
  OUT ok BOOLEAN,
  OUT no_retry BOOLEAN
)
LANGUAGE sql as $$
  UPDATE challenges SET 
    confirmation_date = CASE 
      WHEN (retry_counter > 0 AND in_now_date < expiration_date AND code = in_code) THEN in_now_date
      ELSE confirmation_date
    END,
    retry_counter = retry_counter - 1
  WHERE challenge_id = in_challenge_id
  RETURNING confirmation_date IS NOT NULL, retry_counter < 0 AND confirmation_date IS NULL;
$$;
COMMENT ON FUNCTION challenge_try IS 'Try to confirm a challenge, return true if the challenge have been confirmed';

CREATE OR REPLACE FUNCTION stats_get_frame(
  IN now TIMESTAMP,
  IN in_timeframe stat_timeframe_enum,
  IN which INTEGER,
  OUT cashin_count BIGINT,
  OUT cashin_regional_volume taler_amount,
  OUT cashin_fiat_volume taler_amount,
  OUT cashout_count BIGINT,
  OUT cashout_regional_volume taler_amount,
  OUT cashout_fiat_volume taler_amount,
  OUT taler_in_count BIGINT,
  OUT taler_in_volume taler_amount,
  OUT taler_out_count BIGINT,
  OUT taler_out_volume taler_amount
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
    AND s.start_time = local_start_time;
END $$;

CREATE OR REPLACE PROCEDURE stats_register_payment(
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

CREATE OR REPLACE PROCEDURE config_set_amount(
  IN name TEXT,
  IN amount taler_amount
)
LANGUAGE sql AS $$
  INSERT INTO config (key, value) VALUES (name, jsonb_build_object('val', amount.val, 'frac', amount.frac))
    ON CONFLICT (key) DO UPDATE SET value = excluded.value
$$;

CREATE OR REPLACE PROCEDURE config_set_rounding_mode(
  IN name TEXT,
  IN mode rounding_mode
)
LANGUAGE sql AS $$
  INSERT INTO config (key, value) VALUES (name, jsonb_build_object('mode', mode::text))
    ON CONFLICT (key) DO UPDATE SET value = excluded.value
$$;

CREATE OR REPLACE FUNCTION config_get_amount(
  IN name TEXT,
  OUT amount taler_amount
)
LANGUAGE sql AS $$
  SELECT (value['val']::int8, value['frac']::int4)::taler_amount FROM config WHERE key=name
$$;

CREATE OR REPLACE FUNCTION config_get_rounding_mode(
  IN name TEXT,
  OUT mode rounding_mode
)
LANGUAGE sql AS $$ SELECT (value->>'mode')::rounding_mode FROM config WHERE key=name $$;

CREATE OR REPLACE FUNCTION conversion_apply_ratio(
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

  IF (result.val > 1::bigint<<52) THEN
    RAISE EXCEPTION 'amount value overflowed';
  END IF;
END $$;
COMMENT ON FUNCTION conversion_apply_ratio
  IS 'Apply a ratio to an amount rouding the result to a tiny amount following a rounding mode. It raises an exception when the resulting .val is larger than 2^52';

CREATE OR REPLACE FUNCTION conversion_revert_ratio(
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

  IF (result.val > 1::bigint<<52) THEN
    RAISE EXCEPTION 'amount value overflowed';
  END IF;
END $$;
COMMENT ON FUNCTION conversion_revert_ratio
  IS 'Revert the application of a ratio. This function does not always return the smallest possible amount. It raises an exception when the resulting .val is larger than 2^52';


CREATE OR REPLACE FUNCTION conversion_to(
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

CREATE OR REPLACE FUNCTION conversion_from(
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