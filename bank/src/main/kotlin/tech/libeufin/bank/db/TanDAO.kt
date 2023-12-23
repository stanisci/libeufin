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
    /** Create new TAN challenge */
    suspend fun new(
        login: String, 
        body: String, 
        code: String,
        now: Instant,
        retryCounter: Int,
        validityPeriod: Duration
    ): Long = db.serializable { conn ->
        val stmt = conn.prepareStatement("SELECT tan_challenge_create(?, ?, ?, ?, ?, ?, NULL, NULL)")
        stmt.setString(1, body)
        stmt.setString(2, code)
        stmt.setLong(3, now.toDbMicros() ?: throw faultyTimestampByBank())
        stmt.setLong(4, TimeUnit.MICROSECONDS.convert(validityPeriod))
        stmt.setInt(5, retryCounter)
        stmt.setString(6, login)
        stmt.oneOrNull {
            it.getLong(1)
        }!! // TODO handle database weirdness
    }

    /** Result of TAN challenge transmission */
    sealed class TanSendResult {
        data class Success(val tanInfo: String, val tanChannel: TanChannel, val tanCode: String?): TanSendResult()
        object NotFound: TanSendResult()
    }

    /** Request TAN challenge transmission */
    suspend fun send(
        id: Long, 
        login: String, 
        code: String,
        now: Instant,
        retryCounter: Int,
        validityPeriod: Duration
    ) = db.serializable { conn ->
        val stmt = conn.prepareStatement("SELECT out_no_op, out_tan_code, out_tan_channel, out_tan_info FROM tan_challenge_send(?,?,?,?,?,?)")
        stmt.setLong(1, id)
        stmt.setString(2, login)
        stmt.setString(3, code)
        stmt.setLong(4, now.toDbMicros() ?: throw faultyTimestampByBank())
        stmt.setLong(5, TimeUnit.MICROSECONDS.convert(validityPeriod))
        stmt.setInt(6, retryCounter)
        stmt.executeQuery().use {
            when {
                !it.next() -> throw internalServerError("TAN send returned nothing.")
                it.getBoolean("out_no_op") -> TanSendResult.NotFound
                else -> TanSendResult.Success(
                    tanInfo = it.getString("out_tan_info"),
                    tanChannel = it.getString("out_tan_channel").run { TanChannel.valueOf(this) },
                    tanCode = it.getString("out_tan_code")
                )
            }
        }
    }

    /** Mark TAN challenge transmission */
    suspend fun markSent(
        id: Long,
        now: Instant,
        retransmissionPeriod: Duration
    ) = db.serializable { conn ->
        val stmt = conn.prepareStatement("SELECT tan_challenge_mark_sent(?,?,?)")
        stmt.setLong(1, id)
        stmt.setLong(2, now.toDbMicros() ?: throw faultyTimestampByBank())
        stmt.setLong(3, TimeUnit.MICROSECONDS.convert(retransmissionPeriod))
        stmt.executeQuery()
    }

    /** Result of TAN challenge solution */
    enum class TanSolveResult {
        Success,
        NotFound,
        NoRetry,
        Expired,
        BadCode
    }

    /** Solve TAN challenge */
    suspend fun solve(
        id: Long,
        login: String,
        code: String,
        now: Instant
    ) = db.serializable { conn ->
        val stmt = conn.prepareStatement("SELECT out_ok, out_no_op, out_no_retry, out_expired FROM tan_challenge_try(?,?,?,?)")
        stmt.setLong(1, id)
        stmt.setString(2, login)
        stmt.setString(3, code)
        stmt.setLong(4, now.toDbMicros() ?: throw faultyTimestampByBank())
        stmt.executeQuery().use {
            when {
                !it.next() -> throw internalServerError("TAN try returned nothing")
                it.getBoolean("out_ok") -> TanSolveResult.Success
                it.getBoolean("out_no_op") -> TanSolveResult.NotFound
                it.getBoolean("out_no_retry") -> TanSolveResult.NoRetry
                it.getBoolean("out_expired") -> TanSolveResult.Expired
                else -> TanSolveResult.BadCode
            }
        }
    }

    /** Get body of a solved TAN challenge */
    suspend fun body(
        id: Long,
        login: String
    ) = db.conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT body 
            FROM tan_challenges JOIN customers ON customer=customer_id
            WHERE challenge_id=? AND login=? AND confirmation_date IS NOT NULL
        """)
        stmt.setLong(1, id)
        stmt.setString(2, login)
        stmt.oneOrNull {
            it.getString(1)
        }
    }
}