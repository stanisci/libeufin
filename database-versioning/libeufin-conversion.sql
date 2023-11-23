BEGIN;
SET search_path TO libeufin_conversion;

CREATE OR REPLACE FUNCTION cashout() 
RETURNS trigger 
LANGUAGE plpgsql AS $$
  DECLARE
    now_date BIGINT;
    payto_uri TEXT;
  BEGIN
    IF NEW.local_transaction IS NOT NULL THEN
      SELECT transaction_date INTO now_date
        FROM libeufin_bank.bank_account_transactions
        WHERE bank_transaction_id = NEW.local_transaction;
      SELECT cashout_payto INTO payto_uri
        FROM libeufin_bank.bank_accounts
          JOIN libeufin_bank.customers ON customer_id=owning_customer_id
        WHERE bank_account_id=NEW.bank_account;
      INSERT INTO libeufin_nexus.initiated_outgoing_transactions (
          amount
          ,wire_transfer_subject
          ,credit_payto_uri
          ,initiation_time
          ,request_uid
      ) VALUES (
          ((NEW.amount_credit).val, (NEW.amount_credit).frac)::libeufin_nexus.taler_amount
          ,NEW.subject
          ,payto_uri
          ,now_date
          ,'TODO' -- How to generate this
      );
    END IF;
    RETURN NEW;
  END;
$$;

CREATE OR REPLACE TRIGGER cashout BEFORE INSERT OR UPDATE ON libeufin_bank.cashout_operations
    FOR EACH ROW EXECUTE FUNCTION cashout();

CREATE OR REPLACE FUNCTION cashin() 
RETURNS trigger 
LANGUAGE plpgsql AS $$
  DECLARE
    now_date BIGINT;
    payto_uri TEXT;
    local_amount libeufin_bank.taler_amount;
    subject TEXT;
    too_small BOOLEAN;
    balance_insufficient BOOLEAN;
    no_account BOOLEAN;
  BEGIN
    SELECT (amount).val, (amount).frac, wire_transfer_subject, execution_time, debit_payto_uri
      INTO local_amount.val, local_amount.frac, subject, now_date, payto_uri
      FROM libeufin_nexus.incoming_transactions
      WHERE incoming_transaction_id = NEW.incoming_transaction_id;
    SET search_path TO libeufin_bank;
    SELECT out_too_small, out_balance_insufficient, out_no_account
      INTO too_small, balance_insufficient, no_account
      FROM libeufin_bank.cashin(now_date, payto_uri, local_amount, subject);
    SET search_path TO libeufin_conversion;

    IF no_account THEN
      RAISE EXCEPTION 'TODO soft error bounce: unknown account';
    END IF;
    IF too_small THEN
      RAISE EXCEPTION 'TODO soft error bounce: too small amount';
    END IF;
    IF balance_insufficient THEN
      RAISE EXCEPTION 'TODO hard error bounce';
    END IF;
    RETURN NEW;
  END;
$$;

CREATE OR REPLACE TRIGGER cashin BEFORE INSERT ON libeufin_nexus.talerable_incoming_transactions
    FOR EACH ROW EXECUTE FUNCTION cashin();

COMMIT;