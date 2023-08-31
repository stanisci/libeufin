BEGIN;
SET search_path TO libeufin_bank;

CREATE OR REPLACE FUNCTION amount_normalize(
    IN amount taler_amount
  ,OUT normalized taler_amount
)
LANGUAGE plpgsql
AS $$
BEGIN
  normalized.val = amount.val + amount.frac / 100000000;
  normalized.frac = amount.frac % 100000000;
END $$;
COMMENT ON FUNCTION amount_normalize
  IS 'Returns the normalized amount by adding to the .val the value of (.frac / 100000000) and removing the modulus 100000000 from .frac.';

CREATE OR REPLACE FUNCTION amount_add(
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
END $$;
COMMENT ON FUNCTION amount_add
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

CREATE OR REPLACE PROCEDURE bank_wire_transfer(
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
debtor_account RECORD;
creditor_account RECORD;
BEGIN
-- check debtor exists.
SELECT
  INTO debtor_account
  FROM bank_accounts
  WHERE bank_account_id=in_debtor_account_id
IF NOT FOUND
  out_nx_debtor=FALSE
  out_nx_creditor=NULL
  out_balance_insufficient=NULL
  RETURN;
END IF;
-- check creditor exists.  Future versions may skip this
-- due to creditors being hosted at other banks.
SELECT
  INTO creditor_account
  FROM bank_accounts
  WHERE bank_account_id=in_creditor_account_id
IF NOT FOUND
  out_nx_debtor=TRUE
  out_nx_creditor=FALSE
  out_balance_insufficient=NULL
  RETURN;
END IF;
-- DEBTOR SIDE
-- check debtor has enough funds.
IF (debtor_account.has_debt)
THEN -- debt case: simply checking against the max debt allowed.
SELECT
  INTO potential_balance
  FROM amount_add(debtor_account.balance
                  in_amount);
SELECT *
INTO potential_balance_check
FROM amount_left_minus_right(debtor_account.max_debt,
                             potential_balance);
IF (NOT potential_balance_check.ok)
THEN
out_nx_creditor=TRUE;
out_nx_debtor=TRUE;
out_balance_insufficient=TRUE;
RETURN;
new_debtor_balance=potential_balance_check.diff;
will_debtor_have_debt=TRUE;
END IF;
ELSE -- not a debt account
SELECT -- checking first funds availability.
  INTO spending_capacity
  FROM amount_add(debtor_account.balance,
                  debtor_account.max_debt);
IF (NOT spending_capacity.ok)
THEN
out_nx_creditor=TRUE;
out_nx_debtor=TRUE;
out_balance_insufficient=TRUE;
RETURN;
END IF;
-- debtor has enough funds, now determine the new
-- balance and whether they go to debit.
SELECT
  INTO potential_balance
  FROM amount_left_minus_right(debtor_account.balance,
                               in_amount);
IF (potential_balance.ok) -- debtor has enough funds in the (positive) balance.
THEN
new_debtor_balance=potential_balance.diff;
will_debtor_have_debt=FALSE;
ELSE -- debtor will switch to debt: determine their new negative balance.
SELECT diff
  INTO new_debtor_balance
  FROM amount_left_minus_right(in_amount,
                               debtor_account.balance);
will_debtor_have_debt=TRUE;
END IF; -- closes has_debt.
-- CREDITOR SIDE.
-- Here we figure out whether the creditor would switch
-- from debit to a credit situation, and adjust the balance
-- accordingly.
IF (NOT creditor_account.has_debt) -- easy case.
THEN
SELECT
  INTO new_creditor_balance
  FROM amount_add(creditor_account.balance,
                  in_amount);
will_creditor_have_debit=FALSE;
ELSE -- creditor had debit but MIGHT switch to credit.
SELECT
  INTO new_creditor_balance
  FROM amount_left_minus_right(creditor_account.balance,
                               in_amount);
IF (new_debtor_balance.ok)
-- the debt is bigger than the amount, keep
-- this last calculated balance but stay debt.
will_creditor_have_debit=TRUE;
END IF;
-- the amount would bring the account back to credit,
-- determine by how much.
SELECT
  INTO new_creditor_balance
  FROM amount_left_minus_right(in_amount,
                               creditor_account.balance);
will_creditor_have_debit=FALSE;

-- checks and balances set up, now update bank accounts.
UPDATE bank_accounts
SET
  balance=new_debtor_balance
  has_debt=will_debtor_have_debt
WHERE bank_account_id=in_debtor_account_id;

UPDATE bank_accounts
SET
  balance=new_creditor_balance
  has_debt=will_creditor_have_debt
WHERE bank_account_id=in_creditor_account_id;

-- now actually create the bank transaction.
-- debtor side:
INSERT INTO bank_account_transactions (
  ,creditor_iban 
  ,creditor_bic
  ,creditor_name
  ,debtor_iban 
  ,debtor_bic
  ,debtor_name
  ,subject
  ,amount taler_amount
  ,transaction_date
  ,account_servicer_reference
  ,payment_information_id
  ,end_to_end_id
  ,direction direction_enum
  ,bank_account_id
  )
VALUES (
  creditor_account.iban,
  creditor_account.bic,
  creditor_account.name,
  debtor_account.iban,
  debtor_account.bic,
  debtor_account.name,
  in_subject,
  in_amount,
  in_transaction_date,
  in_account_servicer_reference,
  in_payment_information_id,
  in_end_to_end_id,
  "debit",
  in_debtor_account_id
);

-- debtor side:
INSERT INTO bank_account_transactions (
  ,creditor_iban
  ,creditor_bic
  ,creditor_name
  ,debtor_iban
  ,debtor_bic
  ,debtor_name
  ,subject
  ,amount taler_amount
  ,transaction_date
  ,account_servicer_reference
  ,payment_information_id
  ,end_to_end_id
  ,direction direction_enum
  ,bank_account_id
  )
VALUES (
  creditor_account.iban,
  creditor_account.bic,
  creditor_account.name,
  debtor_account.iban,
  debtor_account.bic,
  debtor_account.name,
  in_subject,
  in_amount,
  in_transaction_date,
  in_account_servicer_reference,
  in_payment_information_id,
  in_end_to_end_id, -- does this interest the receiving party?
  "credit",
  in_creditor_account_id
);
out_nx_debtor=TRUE;
out_nx_creditor=TRUE;
out_balance_insufficient=FALSE;
END $$;
COMMIT;
