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
