BEGIN;
SET search_path TO libeufin_bank;

CREATE OR REPLACE FUNCTION cashout_link() 
RETURNS trigger 
LANGUAGE plpgsql AS $$
  DECLARE
    now_date BIGINT;
    payto_uri TEXT;
  BEGIN
    -- TODO should send to an exchange
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
          ,LEFT(gen_random_uuid()::text, 35)
      );
    END IF;
    RETURN NEW;
  END;
$$;

CREATE OR REPLACE TRIGGER cashout_link BEFORE INSERT OR UPDATE ON libeufin_bank.cashout_operations
    FOR EACH ROW EXECUTE FUNCTION cashout_link();

CREATE OR REPLACE FUNCTION cashin_link() 
RETURNS trigger 
LANGUAGE plpgsql AS $$
  DECLARE
    now_date BIGINT;
    local_amount libeufin_bank.taler_amount;
    subject TEXT;
    too_small BOOLEAN;
    balance_insufficient BOOLEAN;
    no_account BOOLEAN;
    no_config BOOLEAN;
  BEGIN
    SELECT (amount).val, (amount).frac, wire_transfer_subject, execution_time
      INTO local_amount.val, local_amount.frac, subject, now_date
      FROM libeufin_nexus.incoming_transactions
      WHERE incoming_transaction_id = NEW.incoming_transaction_id;
    SET search_path TO libeufin_bank;
    SELECT out_too_small, out_balance_insufficient, out_no_account, out_no_config
      INTO too_small, balance_insufficient, no_account, no_config
      FROM libeufin_bank.cashin(now_date, NEW.reserve_public_key, local_amount, subject);
    SET search_path TO libeufin_nexus;

    -- Bounce on soft failures
    IF too_small THEN
      -- TODO bounce fees ?
      PERFORM bounce_incoming(NEW.incoming_transaction_id, ((local_amount).val, (local_amount).frac)::taler_amount, now_date);
      RETURN NULL;
    END IF;

    -- Error on hard failures
    IF no_config THEN
      RAISE EXCEPTION 'cashin currency conversion failed: missing conversion rates';
    END IF;
    IF no_account THEN
      RAISE EXCEPTION 'cashin failed: missing exchange account';
    END IF;
    IF balance_insufficient THEN
      RAISE EXCEPTION 'cashin failed: admin balance insufficient';
    END IF;

    RETURN NEW;
  END;
$$;

CREATE OR REPLACE TRIGGER cashin_link BEFORE INSERT ON libeufin_nexus.talerable_incoming_transactions
    FOR EACH ROW EXECUTE FUNCTION cashin_link();

COMMIT;