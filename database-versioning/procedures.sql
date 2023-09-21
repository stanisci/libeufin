BEGIN;
SET search_path TO libeufin_bank;

CREATE OR REPLACE PROCEDURE amount_normalize(
    IN amount taler_amount
  ,OUT normalized taler_amount
)
LANGUAGE plpgsql
AS $$
BEGIN
  normalized.val = amount.val + amount.frac / 100000000;
  normalized.frac = amount.frac % 100000000;
END $$;
COMMENT ON PROCEDURE amount_normalize
  IS 'Returns the normalized amount by adding to the .val the value of (.frac / 100000000) and removing the modulus 100000000 from .frac.';

CREATE OR REPLACE PROCEDURE amount_add(
   IN a taler_amount
  ,IN b taler_amount
  ,OUT sum taler_amount
)
LANGUAGE plpgsql
AS $$
BEGIN
  sum = (a.val + b.val, a.frac + b.frac);
  CALL amount_normalize(sum ,sum);
  IF (sum.val > (1<<52))
  THEN
    RAISE EXCEPTION 'addition overflow';
  END IF;
  RETURN;
END $$;
COMMENT ON PROCEDURE amount_add
  IS 'Returns the normalized sum of two amounts. It raises an exception when the resulting .val is larger than 2^52';

CREATE OR REPLACE FUNCTION amount_left_minus_right(
  IN l taler_amount
 ,IN r taler_amount
 ,OUT diff taler_amount
 ,OUT ok BOOLEAN
)
LANGUAGE plpgsql
AS $$
BEGIN
IF (l.val > r.val)
THEN
  ok = TRUE;
  IF (l.frac >= r.frac)
  THEN
    diff.val = l.val - r.val;
    diff.frac = l.frac - r.frac;
  ELSE
    diff.val = l.val - r.val - 1;
    diff.frac = l.frac + 100000000 - r.frac;
  END IF;
ELSE
  IF (l.val = r.val) AND (l.frac >= r.frac)
  THEN
    diff.val = 0;
    diff.frac = l.frac - r.frac;
    ok = TRUE;
  ELSE
    diff = (-1, -1);
    ok = FALSE;
  END IF;
END IF;
RETURN;
END $$;
COMMENT ON FUNCTION amount_left_minus_right
  IS 'Subtracts the right amount from the left and returns the difference and TRUE, if the left amount is larger than the right, or an invalid amount and FALSE otherwise.';

CREATE OR REPLACE PROCEDURE bank_set_config(
  IN in_key TEXT,
  IN in_value TEXT
)
LANGUAGE plpgsql
AS $$
BEGIN
UPDATE configuration SET config_value=in_value WHERE config_key=in_key;
IF NOT FOUND
THEN
  INSERT INTO configuration (config_key, config_value) VALUES (in_key, in_value);
END IF;

END $$;
COMMENT ON PROCEDURE bank_set_config(TEXT, TEXT)
  IS 'Update or insert configuration values';

CREATE OR REPLACE FUNCTION taler_transfer(
  IN in_request_uid TEXT,
  IN in_wtid TEXT,
  IN in_amount taler_amount,
  IN in_exchange_base_url TEXT,
  IN in_credit_account_payto TEXT,
  IN in_exchange_bank_account_id BIGINT,
  IN in_timestamp BIGINT,
  IN in_account_servicer_reference TEXT,
  IN in_payment_information_id TEXT,
  IN in_end_to_end_id TEXT,
  OUT out_exchange_balance_insufficient BOOLEAN,
  OUT out_nx_creditor BOOLEAN
)
LANGUAGE plpgsql
AS $$
DECLARE
maybe_balance_insufficient BOOLEAN;
receiver_bank_account_id BIGINT;
payment_subject TEXT;
BEGIN

INSERT
  INTO taler_exchange_transfers (
    request_uid,
    wtid,
    exchange_base_url,
    credit_account_payto,
    amount
    -- FIXME: this needs the bank transaction row ID here.
) VALUES (
  in_request_uid,
  in_wtid,
  in_exchange_base_url,
  in_credit_account_payto,
  in_amount
);
SELECT
  bank_account_id
  INTO receiver_bank_account_id
  FROM bank_accounts
  WHERE internal_payto_uri = in_credit_account_payto;
IF NOT FOUND
THEN
  out_nx_creditor=TRUE;
  RETURN;
END IF;
out_nx_creditor=FALSE;
SELECT CONCAT(in_wtid, ' ', in_exchange_base_url)
  INTO payment_subject;
SELECT
  out_balance_insufficient
  INTO maybe_balance_insufficient
  FROM bank_wire_transfer(
    receiver_bank_account_id,
    in_exchange_bank_account_id,
    payment_subject,
    in_amount,
    in_timestamp,
    in_account_servicer_reference,
    in_payment_information_id,
    in_end_to_end_id
  );
IF (maybe_balance_insufficient)
THEN
  out_exchange_balance_insufficient=TRUE;
END IF;
out_exchange_balance_insufficient=FALSE;
END $$;
COMMENT ON FUNCTION taler_transfer(
  text,
  text,
  taler_amount,
  text,
  text,
  bigint,
  bigint,
  text,
  text,
  text
  )
  IS 'function that (1) inserts the TWG requests'
     'details into the database and (2) performs '
     'the actual bank transaction to pay the merchant';

CREATE OR REPLACE FUNCTION confirm_taler_withdrawal(
  IN in_withdrawal_uuid uuid,
  IN in_confirmation_date BIGINT,
  IN in_acct_svcr_ref TEXT,
  IN in_pmt_inf_id TEXT,
  IN in_end_to_end_id TEXT,
  OUT out_nx_op BOOLEAN,
  -- can't use out_balance_insufficient, because
  -- it conflicts with the return column of the called
  -- function that moves the funds.  FIXME?
  OUT out_insufficient_funds BOOLEAN,
  OUT out_nx_exchange BOOLEAN
)
LANGUAGE plpgsql
AS $$
DECLARE
  confirmation_done_local BOOLEAN;
  reserve_pub_local TEXT;
  selected_exchange_payto_local TEXT;
  wallet_bank_account_local BIGINT;
  amount_local taler_amount;
  exchange_bank_account_id BIGINT;
  maybe_balance_insufficient BOOLEAN;
BEGIN
SELECT -- Really no-star policy and instead DECLARE almost one var per column?
  confirmation_done,
  reserve_pub,
  selected_exchange_payto,
  wallet_bank_account,
  (amount).val, (amount).frac
  INTO
    confirmation_done_local,
    reserve_pub_local,
    selected_exchange_payto_local,
    wallet_bank_account_local,
    amount_local.val, amount_local.frac
  FROM taler_withdrawal_operations
  WHERE withdrawal_uuid=in_withdrawal_uuid;
IF NOT FOUND
THEN
  out_nx_op=TRUE;
  RETURN;
END IF;
out_nx_op=FALSE;
IF (confirmation_done_local)
THEN
  RETURN; -- nothing to do, idempotentially returning.
END IF;
-- exists and wasn't confirmed, do it.
UPDATE taler_withdrawal_operations
  SET confirmation_done = true
  WHERE withdrawal_uuid=in_withdrawal_uuid;
-- sending the funds to the exchange, but needs first its bank account row ID
SELECT
  bank_account_id
  INTO exchange_bank_account_id
  FROM bank_accounts
  WHERE internal_payto_uri = selected_exchange_payto_local;
IF NOT FOUND
THEN
  out_nx_exchange=TRUE;
  RETURN;
END IF;
out_nx_exchange=FALSE;
SELECT -- not checking for accounts existence, as it was done above.
  out_balance_insufficient
  INTO
    maybe_balance_insufficient
FROM bank_wire_transfer(
  exchange_bank_account_id,
  wallet_bank_account_local,
  reserve_pub_local,
  amount_local,
  in_confirmation_date,
  in_acct_svcr_ref,
  in_pmt_inf_id,
  in_end_to_end_id
);
IF (maybe_balance_insufficient)
THEN
  out_insufficient_funds=TRUE;
END IF;
out_insufficient_funds=FALSE;
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
  OUT out_nx_creditor BOOLEAN,
  OUT out_nx_debtor BOOLEAN,
  OUT out_balance_insufficient BOOLEAN
)
LANGUAGE plpgsql
AS $$
DECLARE
debtor_has_debt BOOLEAN;
debtor_balance taler_amount;
debtor_payto_uri TEXT;
debtor_name TEXT;
creditor_payto_uri TEXT;
creditor_name TEXT;
debtor_max_debt taler_amount;
creditor_has_debt BOOLEAN;
creditor_balance taler_amount;
potential_balance taler_amount;
potential_balance_check BOOLEAN;
new_debtor_balance taler_amount;
new_debtor_balance_ok BOOLEAN;
new_creditor_balance taler_amount;
will_debtor_have_debt BOOLEAN;
will_creditor_have_debt BOOLEAN;
amount_at_least_debit BOOLEAN;
potential_balance_ok BOOLEAN;
BEGIN
-- check debtor exists.
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
  JOIN customers ON (bank_accounts.owning_customer_id = customers.customer_id)
  WHERE bank_account_id=in_debtor_account_id;
IF NOT FOUND
THEN
  out_nx_debtor=TRUE;
  RETURN;
END IF;
out_nx_debtor=FALSE;
-- check creditor exists.  Future versions may skip this
-- due to creditors being hosted at other banks.
SELECT
  has_debt,
  (balance).val, (balance).frac,
  internal_payto_uri, customers.name
  INTO
    creditor_has_debt,
    creditor_balance.val, creditor_balance.frac,
    creditor_payto_uri, creditor_name
  FROM bank_accounts
  JOIN customers ON (bank_accounts.owning_customer_id = customers.customer_id)
  WHERE bank_account_id=in_creditor_account_id;
IF NOT FOUND
THEN
  out_nx_creditor=TRUE;
  RETURN;
END IF;
out_nx_creditor=FALSE;
-- DEBTOR SIDE
-- check debtor has enough funds.
IF (debtor_has_debt)
THEN -- debt case: simply checking against the max debt allowed.
  CALL amount_add(debtor_balance,
	          in_amount,
		  potential_balance);
  SELECT ok
    INTO potential_balance_check
    FROM amount_left_minus_right(debtor_max_debt,
                                 potential_balance);
  IF (NOT potential_balance_check)
  THEN
    out_balance_insufficient=TRUE;
    RETURN;
  END IF;
  new_debtor_balance=potential_balance;
  will_debtor_have_debt=TRUE;
ELSE -- not a debt account
  SELECT
    ok,
    (diff).val, (diff).frac
    INTO
      potential_balance_ok,
      potential_balance.val,
      potential_balance.frac
    FROM amount_left_minus_right(debtor_balance,
                                 in_amount);
  IF (potential_balance_ok) -- debtor has enough funds in the (positive) balance.
  THEN
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
    SELECT ok
      INTO potential_balance_check
      FROM amount_left_minus_right(debtor_max_debt,
                                   new_debtor_balance);
    IF (NOT potential_balance_check)
    THEN
      out_balance_insufficient=TRUE;
      RETURN;
    END IF;
  END IF;
END IF;

-- CREDITOR SIDE.
-- Here we figure out whether the creditor would switch
-- from debit to a credit situation, and adjust the balance
-- accordingly.
IF (NOT creditor_has_debt) -- easy case.
THEN
  CALL amount_add(creditor_balance, in_amount, new_creditor_balance);
  will_creditor_have_debt=FALSE;
ELSE -- creditor had debit but MIGHT switch to credit.
  SELECT
    (diff).val, (diff).frac,
    ok
    INTO
      new_creditor_balance.val, new_creditor_balance.frac,
      amount_at_least_debit
    FROM amount_left_minus_right(in_amount,
                                 creditor_balance);
  IF (amount_at_least_debit)
  -- the amount is at least as big as the debit, can switch to credit then.
  THEN
    will_creditor_have_debt=FALSE;
    -- compute new balance.
  ELSE
  -- the amount is not enough to bring the receiver
  -- to a credit state, switch operators to calculate the new balance.
    SELECT
      (diff).val, (diff).frac
      INTO new_creditor_balance.val, new_creditor_balance.frac
      FROM amount_left_minus_right(creditor_balance,
	                           in_amount);
    will_creditor_have_debt=TRUE;
  END IF;
END IF;
out_balance_insufficient=FALSE;
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
);

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
);
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
RETURN;
END $$;

CREATE OR REPLACE FUNCTION cashout_delete(
  IN in_cashout_uuid UUID,
  OUT out_already_confirmed BOOLEAN
)
LANGUAGE plpgsql
AS $$
BEGIN
  PERFORM
    FROM cashout_operations
    WHERE cashout_uuid=in_cashout_uuid AND tan_confirmation_time IS NOT NULL;
  IF FOUND
  THEN
    out_already_confirmed=TRUE;
    RETURN;
  END IF;
  out_already_confirmed=FALSE;
  DELETE FROM cashout_operations WHERE cashout_uuid=in_cashout_uuid;
  RETURN;
END $$;
COMMIT;
