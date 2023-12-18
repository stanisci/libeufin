--
-- This file is part of TALER
-- Copyright (C) 2023 Taler Systems SA
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

SELECT _v.register_patch('libeufin-bank-0002', NULL, NULL);
SET search_path TO libeufin_bank;

-- TODO remove challenges table
-- TODO remove challenge and status columns from cashout_operations

CREATE TABLE tan_challenges
  (challenge_id INT8 GENERATED BY DEFAULT AS IDENTITY UNIQUE
  ,body TEXT NOT NULL
  ,code TEXT NOT NULL
  ,creation_date INT8 NOT NULL
  ,expiration_date INT8 NOT NULL
  ,retransmission_date INT8 NOT NULL DEFAULT 0
  ,confirmation_date INT8 DEFAULT NULL
  ,retry_counter INT4 NOT NULL
  ,customer INT8 NOT NULL
    REFERENCES customers(customer_id)
    ON DELETE CASCADE
    ON UPDATE RESTRICT
  ,tan_channel tan_enum NULL DEFAULT NULL
  ,tan_info TEXT NULL DEFAULT NULL
);
COMMENT ON TABLE tan_challenges IS 'Stores 2FA challenges';
COMMENT ON COLUMN tan_challenges.code IS 'The pin code sent to the user and verified';
COMMENT ON COLUMN tan_challenges.creation_date IS 'Creation date of the code';
COMMENT ON COLUMN tan_challenges.retransmission_date IS 'When did we last transmit the challenge to the user';
COMMENT ON COLUMN tan_challenges.expiration_date IS 'When will the code expire';
COMMENT ON COLUMN tan_challenges.confirmation_date IS 'When was this challenge successfully verified, NULL if pending';
COMMENT ON COLUMN tan_challenges.retry_counter IS 'How many tries are left for this code must be > 0';
COMMENT ON COLUMN tan_challenges.tan_channel IS 'TAN channel to use, if null use customer configured one';
COMMENT ON COLUMN tan_challenges.tan_info IS 'TAN info to use, if null use customer configured one';

COMMIT;