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

import tech.libeufin.common.db.*
import tech.libeufin.common.*
import java.sql.ResultSet
import java.time.Instant

/** Data access logic for exchange specific logic */
class ExchangeDAO(private val db: Database) {
    /** Query history of taler incoming transactions  */
    suspend fun incomingHistory(
        params: HistoryParams
    ): List<IncomingReserveTransaction> 
        = db.poolHistoryGlobal(params, db::listenIncoming, """
            SELECT
                incoming_transaction_id
                ,execution_time
                ,(amount).val AS amount_val
                ,(amount).frac AS amount_frac
                ,debit_payto_uri
                ,reserve_public_key
            FROM talerable_incoming_transactions
                JOIN incoming_transactions USING(incoming_transaction_id)
            WHERE
        """, "incoming_transaction_id") {
            IncomingReserveTransaction(
                row_id = it.getLong("incoming_transaction_id"),
                date = it.getTalerTimestamp("execution_time"),
                amount = it.getAmount("amount", db.bankCurrency),
                debit_account = it.getString("debit_payto_uri"),
                reserve_pub = EddsaPublicKey(it.getBytes("reserve_public_key")),
            )
        }

    /** Query [exchangeId] history of taler outgoing transactions  */
    suspend fun outgoingHistory(
        params: HistoryParams
    ): List<OutgoingTransaction> 
        // Outgoing transactions can be initiated or recovered. We take the first data to
        // reach database : the initiation first else the recovered transaction.
        = db.poolHistoryGlobal(params, db::listenOutgoing,  """
            SELECT
                outgoing_transaction_id
                ,execution_time AS execution_time
                ,(amount).val AS amount_val
                ,(amount).frac AS amount_frac
                ,credit_payto_uri AS credit_payto_uri
                ,wtid
                ,exchange_base_url
            FROM talerable_outgoing_transactions
                JOIN outgoing_transactions USING(outgoing_transaction_id)
            WHERE
        """, "outgoing_transaction_id") {
            OutgoingTransaction(
                row_id = it.getLong("outgoing_transaction_id"),
                date = it.getTalerTimestamp("execution_time"),
                amount = it.getAmount("amount", db.bankCurrency),
                credit_account = it.getString("credit_payto_uri"),
                wtid = ShortHashCode(it.getBytes("wtid")),
                exchange_base_url = it.getString("exchange_base_url")
            )
        }

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