/*
 * This file is part of LibEuFin.
 * Copyright (C) 2024 Taler Systems S.A.

 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.

 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.

 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

package tech.libeufin.bank.db

import tech.libeufin.bank.*
import tech.libeufin.common.*
import tech.libeufin.common.crypto.*
import java.time.Instant
import java.time.ZoneOffset
import java.time.LocalDateTime
import java.time.temporal.TemporalAmount
import java.time.chrono.ChronoLocalDateTime

/** Data access logic for garbage collection */
class GcDAO(private val db: Database) {
    /** Run garbage collection  */
    suspend fun collect(
        now: Instant,
        abortAfter: TemporalAmount,
        cleanAfter: TemporalAmount,
        deleteAfter: TemporalAmount
    ) = db.conn { conn ->
        val dateTime = LocalDateTime.ofInstant(now, ZoneOffset.UTC)
        val abortAfterMicro = dateTime.minus(abortAfter).toInstant(ZoneOffset.UTC).micros()
        val cleanAfterMicro = dateTime.minus(cleanAfter).toInstant(ZoneOffset.UTC).micros()
        val deleteAfterMicro = dateTime.minus(deleteAfter).toInstant(ZoneOffset.UTC).micros()
        
        // Abort pending operations
        conn.prepareStatement(
            "UPDATE taler_withdrawal_operations SET aborted = true WHERE creation_date < ?"
        ).run {
            setLong(1, abortAfterMicro) 
            execute()
        }

        // Clean aborted operations, expired challenges and expired tokens
        for (smt in listOf(
            "DELETE FROM taler_withdrawal_operations WHERE aborted = true AND creation_date < ?",
            "DELETE FROM tan_challenges WHERE expiration_date < ?",
            "DELETE FROM bearer_tokens WHERE expiration_time < ?"
        )) {
            conn.prepareStatement(smt).run {
                setLong(1, cleanAfterMicro) 
                execute()
            }
        }

        // Delete old bank transactions, linked operations are deleted by CASCADE
        conn.prepareStatement(
            "DELETE FROM bank_account_transactions WHERE transaction_date < ?"
        ).run {
            setLong(1, deleteAfterMicro) 
            execute()
        }

        // Hard delete soft deleted customer without bank transactions, bank account are deleted by CASCADE
        conn.prepareStatement("""
            DELETE FROM customers WHERE deleted_at IS NOT NULL AND NOT EXISTS(
                SELECT 1 FROM bank_account_transactions NATURAL JOIN bank_accounts
                    WHERE owning_customer_id=customer_id
            )
        """).run {
            execute()
        }

        // TODO clean stats
    }
}