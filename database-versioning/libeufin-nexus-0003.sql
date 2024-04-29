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

SELECT _v.register_patch('libeufin-nexus-0003', NULL, NULL);

SET search_path TO libeufin_nexus;

CREATE TABLE talerable_outgoing_transactions
  ( outgoing_transaction_id INT8 UNIQUE NOT NULL REFERENCES outgoing_transactions(outgoing_transaction_id) ON DELETE CASCADE
   ,wtid BYTEA NOT NULL UNIQUE CHECK (LENGTH(wtid)=32)
   ,exchange_base_url TEXT NOT NULL
  );

CREATE TABLE transfer_operations
  ( initiated_outgoing_transaction_id INT8 UNIQUE NOT NULL REFERENCES initiated_outgoing_transactions(initiated_outgoing_transaction_id) ON DELETE CASCADE
   ,request_uid BYTEA UNIQUE NOT NULL CHECK (LENGTH(request_uid)=64)
   ,wtid BYTEA UNIQUE NOT NULL CHECK (LENGTH(wtid)=32)
   ,exchange_base_url TEXT NOT NULL
  );
COMMENT ON TABLE transfer_operations
  IS 'Operation table for idempotent wire gateway transfers.';
COMMIT;
