/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023-2024 Taler Systems S.A.

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
import tech.libeufin.common.db.*
import java.time.Instant

/** Data access logic for exchange specific logic */
class ExchangeDAO(private val db: Database) {
    /** Query [exchangeId] history of taler incoming transactions  */
    suspend fun incomingHistory(
        params: HistoryParams, 
        exchangeId: Long,
        ctx: BankPaytoCtx
    ): List<IncomingReserveTransaction> 
        = db.poolHistory(params, exchangeId, db::listenIncoming,  """
            SELECT
                bank_transaction_id
                ,transaction_date
                ,(amount).val AS amount_val
                ,(amount).frac AS amount_frac
                ,debtor_payto_uri
                ,debtor_name
                ,reserve_pub
            FROM taler_exchange_incoming AS tfr
                JOIN bank_account_transactions AS txs
                    ON bank_transaction=txs.bank_transaction_id
            WHERE
        """) {
            IncomingReserveTransaction(
                row_id = it.getLong("bank_transaction_id"),
                date = it.getTalerTimestamp("transaction_date"),
                amount = it.getAmount("amount", db.bankCurrency),
                debit_account = it.getBankPayto("debtor_payto_uri", "debtor_name", ctx),
                reserve_pub = EddsaPublicKey(it.getBytes("reserve_pub")),
            )
        }
    
    /** Query [exchangeId] history of taler outgoing transactions  */
    suspend fun outgoingHistory(
        params: HistoryParams, 
        exchangeId: Long,
        ctx: BankPaytoCtx
    ): List<OutgoingTransaction> 
        = db.poolHistory(params, exchangeId, db::listenOutgoing,  """
            SELECT
                bank_transaction_id
                ,transaction_date
                ,(amount).val AS amount_val
                ,(amount).frac AS amount_frac
                ,creditor_payto_uri
                ,creditor_name
                ,wtid
                ,exchange_base_url
            FROM taler_exchange_outgoing AS tfr
                JOIN bank_account_transactions AS txs
                    ON bank_transaction=txs.bank_transaction_id
            WHERE
        """) {
            OutgoingTransaction(
                row_id = it.getLong("bank_transaction_id"),
                date = it.getTalerTimestamp("transaction_date"),
                amount = it.getAmount("amount", db.bankCurrency),
                credit_account = it.getBankPayto("creditor_payto_uri", "creditor_name", ctx),
                wtid = ShortHashCode(it.getBytes("wtid")),
                exchange_base_url = it.getString("exchange_base_url")
            )
        }

    /** Result of taler transfer transaction creation */
    sealed interface TransferResult {
        /** Transaction [id] and wire transfer [timestamp] */
        data class Success(val id: Long, val timestamp: TalerProtocolTimestamp): TransferResult
        data object NotAnExchange: TransferResult
        data object UnknownExchange: TransferResult
        data object UnknownCreditor: TransferResult
        data object BothPartyAreExchange: TransferResult
        data object BalanceInsufficient: TransferResult
        data object ReserveUidReuse: TransferResult
    }

    /** Perform a Taler transfer */
    suspend fun transfer(
        req: TransferRequest,
        login: String,
        now: Instant
    ): TransferResult = db.serializable { conn ->
        val subject = "${req.wtid} ${req.exchange_base_url.url}"
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
        stmt.setString(8, login)
        stmt.setLong(9, now.micros())

        stmt.executeQuery().use {
            when {
                !it.next() ->
                    throw internalServerError("SQL function taler_transfer did not return anything.")
                it.getBoolean("out_debtor_not_found") -> TransferResult.UnknownExchange
                it.getBoolean("out_debtor_not_exchange") -> TransferResult.NotAnExchange
                it.getBoolean("out_creditor_not_found") -> TransferResult.UnknownCreditor
                it.getBoolean("out_both_exchanges") -> TransferResult.BothPartyAreExchange
                it.getBoolean("out_exchange_balance_insufficient") -> TransferResult.BalanceInsufficient
                it.getBoolean("out_request_uid_reuse") -> TransferResult.ReserveUidReuse
                else -> TransferResult.Success(
                    id = it.getLong("out_tx_row_id"),
                    timestamp = it.getTalerTimestamp("out_timestamp")
                )
            }
        }
    }

    /** Result of taler add incoming transaction creation */
    sealed interface AddIncomingResult {
        /** Transaction [id] and wire transfer [timestamp] */
        data class Success(val id: Long, val timestamp: TalerProtocolTimestamp): AddIncomingResult
        data object NotAnExchange: AddIncomingResult
        data object UnknownExchange: AddIncomingResult
        data object UnknownDebtor: AddIncomingResult
        data object BothPartyAreExchange: AddIncomingResult
        data object ReservePubReuse: AddIncomingResult
        data object BalanceInsufficient: AddIncomingResult
    }

     /** Add a new taler incoming transaction */
    suspend fun addIncoming(
        req: AddIncomingRequest,
        login: String,
        now: Instant
    ): AddIncomingResult = db.serializable { conn ->
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
        stmt.setString(2, "Manual incoming ${req.reserve_pub}")
        stmt.setLong(3, req.amount.value)
        stmt.setInt(4, req.amount.frac)
        stmt.setString(5, req.debit_account.canonical)
        stmt.setString(6, login)
        stmt.setLong(7, now.micros())

        stmt.one {
            when {
                it.getBoolean("out_creditor_not_found") -> AddIncomingResult.UnknownExchange 
                it.getBoolean("out_creditor_not_exchange") -> AddIncomingResult.NotAnExchange
                it.getBoolean("out_debtor_not_found") -> AddIncomingResult.UnknownDebtor
                it.getBoolean("out_both_exchanges") -> AddIncomingResult.BothPartyAreExchange
                it.getBoolean("out_debitor_balance_insufficient") -> AddIncomingResult.BalanceInsufficient
                it.getBoolean("out_reserve_pub_reuse") -> AddIncomingResult.ReservePubReuse
                else -> AddIncomingResult.Success(
                    id = it.getLong("out_tx_row_id"),
                    timestamp = TalerProtocolTimestamp(now)
                )
            }
        }
    }
}