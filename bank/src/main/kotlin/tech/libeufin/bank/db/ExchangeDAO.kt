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

import java.util.UUID
import java.time.Instant
import java.time.Duration
import java.util.concurrent.TimeUnit
import tech.libeufin.util.*

/** Result status of taler transfer transaction */
enum class TalerTransferResult {
    NO_DEBITOR,
    NOT_EXCHANGE,
    NO_CREDITOR,
    BOTH_EXCHANGE,
    REQUEST_UID_REUSE,
    BALANCE_INSUFFICIENT,
    SUCCESS
}

/** Result status of taler add incoming transaction */
enum class TalerAddIncomingResult {
    NO_DEBITOR,
    NOT_EXCHANGE,
    NO_CREDITOR,
    BOTH_EXCHANGE,
    RESERVE_PUB_REUSE,
    BALANCE_INSUFFICIENT,
    SUCCESS
}

class ExchangeDAO(private val db: Database) {
    suspend fun incomingHistory(
        params: HistoryParams, 
        bankAccountId: Long
    ): List<IncomingReserveTransaction> 
        = db.poolHistory(params, bankAccountId, NotificationWatcher::listenIncoming,  """
            SELECT
                bank_transaction_id
                ,transaction_date
                ,(amount).val AS amount_val
                ,(amount).frac AS amount_frac
                ,debtor_payto_uri
                ,reserve_pub
            FROM taler_exchange_incoming AS tfr
                JOIN bank_account_transactions AS txs
                    ON bank_transaction=txs.bank_transaction_id
        """) {
            IncomingReserveTransaction(
                row_id = it.getLong("bank_transaction_id"),
                date = TalerProtocolTimestamp(
                    it.getLong("transaction_date").microsToJavaInstant() ?: throw faultyTimestampByBank()
                ),
                amount = TalerAmount(
                    it.getLong("amount_val"),
                    it.getInt("amount_frac"),
                    db.bankCurrency
                ),
                debit_account = it.getString("debtor_payto_uri"),
                reserve_pub = EddsaPublicKey(it.getBytes("reserve_pub")),
            )
        }

    suspend fun outgoingHistory(
        params: HistoryParams, 
        bankAccountId: Long
    ): List<OutgoingTransaction> 
        = db.poolHistory(params, bankAccountId, NotificationWatcher::listenOutgoing,  """
            SELECT
                bank_transaction_id
                ,transaction_date
                ,(amount).val AS amount_val
                ,(amount).frac AS amount_frac
                ,creditor_payto_uri
                ,wtid
                ,exchange_base_url
            FROM taler_exchange_outgoing AS tfr
                JOIN bank_account_transactions AS txs
                    ON bank_transaction=txs.bank_transaction_id
        """) {
            OutgoingTransaction(
                row_id = it.getLong("bank_transaction_id"),
                date = TalerProtocolTimestamp(
                    it.getLong("transaction_date").microsToJavaInstant() ?: throw faultyTimestampByBank()
                ),
                amount = TalerAmount(
                    it.getLong("amount_val"),
                    it.getInt("amount_frac"),
                    db.bankCurrency
                ),
                credit_account = IbanPayTo(it.getString("creditor_payto_uri")),
                wtid = ShortHashCode(it.getBytes("wtid")),
                exchange_base_url = ExchangeUrl(it.getString("exchange_base_url"))
            )
        }

    data class TransferResult(
        val txResult: TalerTransferResult,
        /**
         * bank transaction that backs this Taler transfer request.
         * This is the debit transactions associated to the exchange
         * bank account.
         */
        val txRowId: Long? = null,
        val timestamp: TalerProtocolTimestamp? = null
    )

    suspend fun transfer(
        req: TransferRequest,
        username: String,
        timestamp: Instant
    ): TransferResult = db.conn { conn ->
        val subject = OutgoingTxMetadata(req.wtid, req.exchange_base_url).encode()
        val stmt = conn.prepareStatement("""
            SELECT
                out_debtor_not_found
                ,out_debtor_not_exchange
                ,out_creditor_not_found
                ,out_both_exchanges
                ,out_request_uid_reuse
                ,out_exchange_balance_insufficient
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
        stmt.setString(8, username)
        stmt.setLong(9, timestamp.toDbMicros() ?: throw faultyTimestampByBank())

        stmt.executeQuery().use {
            when {
                !it.next() ->
                    throw internalServerError("SQL function taler_transfer did not return anything.")
                it.getBoolean("out_debtor_not_found") ->
                    TransferResult(TalerTransferResult.NO_DEBITOR)
                it.getBoolean("out_debtor_not_exchange") ->
                    TransferResult(TalerTransferResult.NOT_EXCHANGE)
                it.getBoolean("out_creditor_not_found") ->
                    TransferResult(TalerTransferResult.NO_CREDITOR)
                it.getBoolean("out_both_exchanges") ->
                    TransferResult(TalerTransferResult.BOTH_EXCHANGE)
                it.getBoolean("out_exchange_balance_insufficient") ->
                    TransferResult(TalerTransferResult.BALANCE_INSUFFICIENT)
                it.getBoolean("out_request_uid_reuse") ->
                    TransferResult(TalerTransferResult.REQUEST_UID_REUSE)
                else -> {
                    TransferResult(
                        txResult = TalerTransferResult.SUCCESS,
                        txRowId = it.getLong("out_tx_row_id"),
                        timestamp = TalerProtocolTimestamp(
                            it.getLong("out_timestamp").microsToJavaInstant() ?: throw faultyTimestampByBank()
                        )
                    )
                }
            }
        }
    }

    data class AddIncomingResult(
        val txResult: TalerAddIncomingResult,
        val txRowId: Long? = null
    )

    suspend fun addIncoming(
        req: AddIncomingRequest,
        username: String,
        timestamp: Instant
        ): AddIncomingResult = db.conn { conn ->
            val subject = IncomingTxMetadata(req.reserve_pub).encode()
        val stmt = conn.prepareStatement("""
            SELECT
                out_creditor_not_found
                ,out_creditor_not_exchange
                ,out_debtor_not_found
                ,out_both_exchanges
                ,out_reserve_pub_reuse
                ,out_debitor_balance_insufficient
                ,out_tx_row_id
            FROM
            taler_add_incoming (
                ?, ?,
                (?,?)::taler_amount,
                ?, ?, ?
                );
        """)

        stmt.setBytes(1, req.reserve_pub.raw)
        stmt.setString(2, subject)
        stmt.setLong(3, req.amount.value)
        stmt.setInt(4, req.amount.frac)
        stmt.setString(5, req.debit_account.canonical)
        stmt.setString(6, username)
        stmt.setLong(7, timestamp.toDbMicros() ?: throw faultyTimestampByBank())

        stmt.executeQuery().use {
            when {
                !it.next() ->
                    throw internalServerError("SQL function taler_add_incoming did not return anything.")
                it.getBoolean("out_creditor_not_found") ->
                    AddIncomingResult(TalerAddIncomingResult.NO_CREDITOR)
                it.getBoolean("out_creditor_not_exchange") ->
                    AddIncomingResult(TalerAddIncomingResult.NOT_EXCHANGE)
                it.getBoolean("out_debtor_not_found") ->
                    AddIncomingResult(TalerAddIncomingResult.NO_DEBITOR)
                it.getBoolean("out_both_exchanges") ->
                    AddIncomingResult(TalerAddIncomingResult.BOTH_EXCHANGE)
                it.getBoolean("out_debitor_balance_insufficient") ->
                    AddIncomingResult(TalerAddIncomingResult.BALANCE_INSUFFICIENT)
                it.getBoolean("out_reserve_pub_reuse") ->
                    AddIncomingResult(TalerAddIncomingResult.RESERVE_PUB_REUSE)
                else -> {
                    AddIncomingResult(
                        txResult = TalerAddIncomingResult.SUCCESS,
                        txRowId = it.getLong("out_tx_row_id")
                    )
                }
            }
        }
    }
}