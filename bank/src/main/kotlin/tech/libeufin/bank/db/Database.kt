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

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.bank.*
import tech.libeufin.common.*
import tech.libeufin.common.db.*
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.concurrent.ConcurrentHashMap
import java.util.*

private val logger: Logger = LoggerFactory.getLogger("libeufin-bank-db")

class Database(dbConfig: DatabaseConfig, internal val bankCurrency: String, internal val fiatCurrency: String?): DbPool(dbConfig, "libeufin-bank") {
    // DAOs
    val cashout = CashoutDAO(this)
    val withdrawal = WithdrawalDAO(this)
    val exchange = ExchangeDAO(this)
    val conversion = ConversionDAO(this)
    val account = AccountDAO(this)
    val transaction = TransactionDAO(this)
    val token = TokenDAO(this)
    val tan = TanDAO(this)
    val gc = GcDAO(this)

    // Transaction flows, the keys are the bank account id
    private val bankTxFlows = ConcurrentHashMap<Long, CountedSharedFlow<Long>>()
    private val outgoingTxFlows = ConcurrentHashMap<Long, CountedSharedFlow<Long>>()
    private val incomingTxFlows = ConcurrentHashMap<Long, CountedSharedFlow<Long>>()
    private val revenueTxFlows = ConcurrentHashMap<Long, CountedSharedFlow<Long>>()
    // Withdrawal confirmation flow, the key is the public withdrawal UUID
    private val withdrawalFlow = ConcurrentHashMap<UUID, CountedSharedFlow<WithdrawalStatus>>()
 
    init {
        watchNotifications(pgSource, "libeufin_bank", LoggerFactory.getLogger("libeufin-bank-db-watcher"), mapOf(
            "bank_tx" to {
                val (debtor, creditor, debitRow, creditRow) = it.split(' ', limit = 4).map { it.toLong() }
                bankTxFlows[debtor]?.run {
                    flow.emit(debitRow)
                }
                bankTxFlows[creditor]?.run {
                    flow.emit(creditRow)
                }
                revenueTxFlows[creditor]?.run {
                    flow.emit(creditRow)
                }
            },
            "outgoing_tx" to {
                val (account, merchant, debitRow, creditRow) = it.split(' ', limit = 4).map { it.toLong() }
                outgoingTxFlows[account]?.run {
                    flow.emit(debitRow)
                }
            },
            "incoming_tx" to {
                val (account, row) = it.split(' ', limit = 2).map { it.toLong() }
                incomingTxFlows[account]?.run {
                    flow.emit(row)
                }
            },
            "withdrawal_status" to {
                val raw = it.split(' ', limit = 2)
                val uuid = UUID.fromString(raw[0])
                val status = WithdrawalStatus.valueOf(raw[1])
                withdrawalFlow[uuid]?.run {
                    flow.emit(status)
                }
            }
        ))
    }

    /** Listen for new bank transactions for [account] */
    suspend fun <R> listenBank(account: Long, lambda: suspend (Flow<Long>) -> R): R
        = listen(bankTxFlows, account, lambda)
    /** Listen for new taler outgoing transactions from [exchange] */
    suspend fun <R> listenOutgoing(exchange: Long, lambda: suspend (Flow<Long>) -> R): R
        = listen(outgoingTxFlows, exchange, lambda)
    /** Listen for new taler incoming transactions to [exchange] */
    suspend fun <R> listenIncoming(exchange: Long, lambda: suspend (Flow<Long>) -> R): R
        = listen(incomingTxFlows, exchange, lambda)
    /** Listen for new incoming transactions to [merchant] */
    suspend fun <R> listenRevenue(merchant: Long, lambda: suspend (Flow<Long>) -> R): R
        = listen(revenueTxFlows, merchant, lambda)
    /** Listen for new withdrawal confirmations */
    suspend fun <R> listenWithdrawals(withdrawal: UUID, lambda: suspend (Flow<WithdrawalStatus>) -> R): R
        = listen(withdrawalFlow, withdrawal, lambda)

    suspend fun monitor(
        params: MonitorParams
    ): MonitorResponse = conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
                cashin_count
                ,(cashin_regional_volume).val as cashin_regional_volume_val
                ,(cashin_regional_volume).frac as cashin_regional_volume_frac
                ,(cashin_fiat_volume).val as cashin_fiat_volume_val
                ,(cashin_fiat_volume).frac as cashin_fiat_volume_frac
                ,cashout_count
                ,(cashout_regional_volume).val as cashout_regional_volume_val
                ,(cashout_regional_volume).frac as cashout_regional_volume_frac
                ,(cashout_fiat_volume).val as cashout_fiat_volume_val
                ,(cashout_fiat_volume).frac as cashout_fiat_volume_frac
                ,taler_in_count
                ,(taler_in_volume).val as taler_in_volume_val
                ,(taler_in_volume).frac as taler_in_volume_frac
                ,taler_out_count
                ,(taler_out_volume).val as taler_out_volume_val
                ,(taler_out_volume).frac as taler_out_volume_frac
            FROM stats_get_frame(?::timestamp, ?::stat_timeframe_enum)
        """)
        stmt.setObject(1, params.date)
        stmt.setString(2, params.timeframe.name)
        stmt.oneOrNull {
            fiatCurrency?.run {
                MonitorWithConversion(
                    cashinCount = it.getLong("cashin_count"),
                    cashinRegionalVolume = it.getAmount("cashin_regional_volume", bankCurrency),
                    cashinFiatVolume = it.getAmount("cashin_fiat_volume", this),
                    cashoutCount = it.getLong("cashout_count"),
                    cashoutRegionalVolume = it.getAmount("cashout_regional_volume", bankCurrency),
                    cashoutFiatVolume = it.getAmount("cashout_fiat_volume", this),
                    talerInCount = it.getLong("taler_in_count"),
                    talerInVolume = it.getAmount("taler_in_volume", bankCurrency),
                    talerOutCount = it.getLong("taler_out_count"),
                    talerOutVolume = it.getAmount("taler_out_volume", bankCurrency),
                )
            } ?: MonitorNoConversion(
                talerInCount = it.getLong("taler_in_count"),
                talerInVolume = it.getAmount("taler_in_volume", bankCurrency),
                talerOutCount = it.getLong("taler_out_count"),
                talerOutVolume = it.getAmount("taler_out_volume", bankCurrency),
            )
        } ?: throw internalServerError("No result from DB procedure stats_get_frame")
    }
}

/** Result status of withdrawal or cashout operation abortion */
enum class AbortResult {
    Success,
    UnknownOperation,
    AlreadyConfirmed
}