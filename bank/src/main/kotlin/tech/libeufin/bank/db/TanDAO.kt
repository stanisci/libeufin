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

package tech.libeufin.bank.db

import tech.libeufin.bank.Operation
import tech.libeufin.bank.TanChannel
import tech.libeufin.common.*
import tech.libeufin.common.db.*
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Data access logic for tan challenged */
class TanDAO(private val db: Database) {
    /** Create new TAN challenge */
    suspend fun new(
        login: String, 
        op: Operation,
        body: String, 
        code: String,
        now: Instant,
        retryCounter: Int,
        validityPeriod: Duration,
        channel: TanChannel? = null,
        info: String? = null
    ): Long = db.serializable { conn ->
        val stmt = conn.prepareStatement("SELECT tan_challenge_create(?,?::op_enum,?,?,?,?,?,?::tan_enum,?)")
        stmt.setString(1, body)
        stmt.setString(2, op.name)
        stmt.setString(3, code)
        stmt.setLong(4, now.micros())
        stmt.setLong(5, TimeUnit.MICROSECONDS.convert(validityPeriod))
        stmt.setInt(6, retryCounter)
        stmt.setString(7, login)
        stmt.setString(8, channel?.name)
        stmt.setString(9, info)
        stmt.oneOrNull {
            it.getLong(1)
        } ?: throw internalServerError("TAN challenge returned nothing.")
    }

    /** Result of TAN challenge transmission */
    sealed interface TanSendResult {
        data class Success(val tanInfo: String, val tanChannel: TanChannel, val tanCode: String?): TanSendResult
        data object NotFound: TanSendResult
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
        stmt.setLong(4, now.micros())
        stmt.setLong(5, TimeUnit.MICROSECONDS.convert(validityPeriod))
        stmt.setInt(6, retryCounter)
        stmt.executeQuery().use {
            when {
                !it.next() -> throw internalServerError("TAN send returned nothing.")
                it.getBoolean("out_no_op") -> TanSendResult.NotFound
                else ->  TanSendResult.Success(
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
        stmt.setLong(2, now.micros())
        stmt.setLong(3, TimeUnit.MICROSECONDS.convert(retransmissionPeriod))
        stmt.executeQuery()
    }

    /** Result of TAN challenge solution */
    sealed interface TanSolveResult {
        data class Success(val body: String, val op: Operation, val channel: TanChannel?, val info: String?): TanSolveResult
        data object NotFound: TanSolveResult
        data object NoRetry: TanSolveResult
        data object Expired: TanSolveResult
        data object BadCode: TanSolveResult
    }

    /** Solve TAN challenge */
    suspend fun solve(
        id: Long,
        login: String,
        code: String,
        now: Instant
    ) = db.serializable { conn ->
        val stmt = conn.prepareStatement("""
            SELECT 
                out_ok, out_no_op, out_no_retry, out_expired,
                out_body, out_op, out_channel, out_info
            FROM tan_challenge_try(?,?,?,?)""")
        stmt.setLong(1, id)
        stmt.setString(2, login)
        stmt.setString(3, code)
        stmt.setLong(4, now.micros())
        stmt.executeQuery().use {
            when {
                !it.next() -> throw internalServerError("TAN try returned nothing")
                it.getBoolean("out_ok") -> TanSolveResult.Success(
                    body = it.getString("out_body"),
                    op = Operation.valueOf(it.getString("out_op")),
                    channel = it.getString("out_channel")?.run { TanChannel.valueOf(this) },
                    info = it.getString("out_info")
                )
                it.getBoolean("out_no_op") -> TanSolveResult.NotFound
                it.getBoolean("out_no_retry") -> TanSolveResult.NoRetry
                it.getBoolean("out_expired") -> TanSolveResult.Expired
                else -> TanSolveResult.BadCode
            }
        }
    }

    data class Challenge (
        val body: String,
        val channel: TanChannel?,
        val info: String?
    )

    /** Get a solved TAN challenge [id] for account [login] and [op] */
    suspend fun challenge(
        id: Long,
        login: String,
        op: Operation
    ) = db.serializable { conn ->
        val stmt = conn.prepareStatement("""
            SELECT body, tan_challenges.tan_channel, tan_info
            FROM tan_challenges
                JOIN customers ON customer=customer_id
            WHERE challenge_id=? AND op=?::op_enum AND login=? AND deleted_at IS NULL
        """)
        stmt.setLong(1, id)
        stmt.setString(2, op.name)
        stmt.setString(3, login)
        stmt.oneOrNull { 
            Challenge(
                body = it.getString("body"),
                channel = it.getString("tan_channel")?.run { TanChannel.valueOf(this) },
                info = it.getString("tan_info")
            )
        }
    }
}