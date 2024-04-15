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

package tech.libeufin.nexus.db

import tech.libeufin.common.db.one
import tech.libeufin.common.db.getTalerTimestamp
import tech.libeufin.common.micros
import tech.libeufin.common.TalerProtocolTimestamp
import tech.libeufin.common.TransferRequest
import java.sql.ResultSet
import java.time.Instant

/** Data access logic for exchange specific logic */
class ExchangeDAO(private val db: Database) {

    /** Result of taler transfer transaction creation */
    sealed interface TransferResult {
        /** Transaction [id] and wire transfer [timestamp] */
        data class Success(val id: Long, val timestamp: TalerProtocolTimestamp): TransferResult
        data object RequestUidReuse: TransferResult
    }

    /** Perform a Taler transfer */
    suspend fun transfer(
        req: TransferRequest,
        bankId: String,
        now: Instant
    ): TransferResult = db.serializable { conn ->
        val subject = "${req.wtid} ${req.exchange_base_url.url}"
        val stmt = conn.prepareStatement("""
            SELECT
                out_request_uid_reuse
                ,out_tx_row_id
                ,out_timestamp
            FROM
            taler_transfer (
                ?, ?, ?,
                (?,?)::taler_amount,
                ?, ?, ?, ?
            );
        """)

        stmt.setBytes(1, req.request_uid.raw)
        stmt.setBytes(2, req.wtid.raw)
        stmt.setString(3, subject)
        stmt.setLong(4, req.amount.value)
        stmt.setInt(5, req.amount.frac)
        stmt.setString(6, req.exchange_base_url.url)
        stmt.setString(7, req.credit_account.canonical)
        stmt.setString(8, bankId)
        stmt.setLong(9, now.micros())

        stmt.one {
            when {
                it.getBoolean("out_request_uid_reuse") -> TransferResult.RequestUidReuse
                else -> TransferResult.Success(
                    id = it.getLong("out_tx_row_id"),
                    timestamp = it.getTalerTimestamp("out_timestamp")
                )
            }
        }
    }
}