/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023 Taler Systems S.A.

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

package tech.libeufin.bank

import tech.libeufin.util.*
import tech.libeufin.bank.*
import java.util.concurrent.TimeUnit
import java.time.Duration
import java.time.Instant

/** Data access logic for tan challenged */
class TanDAO(private val db: Database) {
    /** Update in-db conversion config */
    suspend fun new(
        login: String, 
        body: String, 
        code: String,
        now: Instant,
        retryCounter: Int,
        validityPeriod: Duration
    ): Long = db.serializable {
        it.transaction { conn -> 
            // Get user ID 
            val customer_id = conn.prepareStatement("""
                SELECT customer_id FROM customers WHERE login = ?
            """).run {
                setString(1, login); 
                oneOrNull {
                    it.getLong(1)
                }!! // TODO handle case where account is deleted ? - HTTP status asking to retry
            }
            var stmt = conn.prepareStatement("SELECT tan_challenge_create(?, ?, ?, ?, ?, ?, NULL, NULL)")
            stmt.setString(1, body)
            stmt.setString(2, code)
            stmt.setLong(3, now.toDbMicros() ?: throw faultyTimestampByBank())
            stmt.setLong(4, TimeUnit.MICROSECONDS.convert(validityPeriod))
            stmt.setInt(5, retryCounter)
            stmt.setLong(6, customer_id)
            stmt.oneOrNull {
                it.getLong(1)
            }!! // TODO handle database weirdness
        }
    }
}