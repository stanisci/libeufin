--
-- This file is part of TALER
-- Copyright (C) 2024 Taler Systems SA
--
-- TALER is free software; you can redistribute it and/or modify it under the
-- terms of the GNU General Public License as published by the Free Software
-- Foundation; either version 3, or (at your option) any later version.
--
-- TALER is distributed in the hope that it will be useful, but WITHOUT ANY
-- WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
-- A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
--
-- You should have received a copy of the GNU General Public License along with
-- TALER; see the file COPYING.  If not, see <http://www.gnu.org/licenses/>

BEGIN;

SELECT _v.register_patch('libeufin-bank-0003', NULL, NULL);
SET search_path TO libeufin_bank;

CREATE TABLE bank_transaction_operations
  (request_uid BYTEA UNIQUE CHECK (LENGTH(request_uid)=32)
  ,bank_transaction INT8 UNIQUE NOT NULL
    REFERENCES bank_account_transactions(bank_transaction_id)
      ON DELETE CASCADE
  );
COMMENT ON TABLE bank_transaction_operations
  IS 'Operation table for idempotent bank transactions.';

ALTER TABLE customers ADD deleted_at INT8;
COMMENT ON COLUMN customers.deleted_at
  IS 'Indicates a deletion request, we keep the account in the database until all its transactions have been deleted for compliance.';

COMMIT;
