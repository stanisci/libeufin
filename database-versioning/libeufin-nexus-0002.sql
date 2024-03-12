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

SELECT _v.register_patch('libeufin-nexus-0002', NULL, NULL);

SET search_path TO libeufin_nexus;

-- Add order ID
ALTER TABLE initiated_outgoing_transactions
  ADD order_id TEXT NULL UNIQUE;
COMMENT ON COLUMN initiated_outgoing_transactions.order_id
  IS 'Order ID of the EBICS upload transaction, used to track EBICS order status.';

COMMIT;
