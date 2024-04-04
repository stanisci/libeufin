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
import java.sql.Types
import java.time.LocalDateTime
import kotlin.math.abs

private val logger: Logger = LoggerFactory.getLogger("libeufin-bank-db")

class Database(dbConfig: String, internal val bankCurrency: String, internal val fiatCurrency: String?): DbPool(dbConfig, "libeufin_bank") {
    internal val notifWatcher: NotificationWatcher = NotificationWatcher(pgSource)

    val cashout = CashoutDAO(this)
    val withdrawal = WithdrawalDAO(this)
    val exchange = ExchangeDAO(this)
    val conversion = ConversionDAO(this)
    val account = AccountDAO(this)
    val transaction = TransactionDAO(this)
    val token = TokenDAO(this)
    val tan = TanDAO(this)
    val gc = GcDAO(this)

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
            } ?:  MonitorNoConversion(
                talerInCount = it.getLong("taler_in_count"),
                talerInVolume = it.getAmount("taler_in_volume", bankCurrency),
                talerOutCount = it.getLong("taler_out_count"),
                talerOutVolume = it.getAmount("taler_out_volume", bankCurrency),
            )
        } ?: throw internalServerError("No result from DB procedure stats_get_frame")
    }

    /** Apply paging logic to a sql query */
    internal suspend fun <T> page(
        params: PageParams,
        idName: String,
        query: String,
        bind: PreparedStatement.() -> Int = { 0 },
        map: (ResultSet) -> T
    ): List<T> = conn { conn ->
        val backward = params.delta < 0
        val pageQuery = """
            $query
            $idName ${if (backward) '<' else '>'} ?
            ORDER BY $idName ${if (backward) "DESC" else "ASC"}
            LIMIT ?
        """
        conn.prepareStatement(pageQuery).run {
            val pad = bind()
            setLong(pad + 1, params.start)
            setInt(pad + 2, abs(params.delta))
            all { map(it) }
        }
    }

    /**
    * The following function returns the list of transactions, according
    * to the history parameters and perform long polling when necessary
    */
    internal suspend fun <T> poolHistory(
        params: HistoryParams, 
        bankAccountId: Long,
        listen: suspend NotificationWatcher.(Long, suspend (Flow<Long>) -> List<T>) -> List<T>,
        query: String,
        accountColumn: String = "bank_account_id",
        map: (ResultSet) -> T
    ): List<T> {

        suspend fun load(): List<T> = page(
            params.page, 
            "bank_transaction_id", 
            "$query $accountColumn=? AND", 
            {
                setLong(1, bankAccountId)
                1
            },
            map
        )
            

        // TODO do we want to handle polling when going backward and there is no transactions yet ?
        // When going backward there is always at least one transaction or none
        return if (params.page.delta >= 0 && params.polling.poll_ms > 0) {
            notifWatcher.(listen)(bankAccountId) { flow ->
                coroutineScope {
                    // Start buffering notification before loading transactions to not miss any
                    val polling = launch {
                        withTimeoutOrNull(params.polling.poll_ms) {
                            flow.first { it > params.page.start } // Always forward so >
                        }
                    }    
                    // Initial loading
                    val init = load()
                    // Long polling if we found no transactions
                    if (init.isEmpty()) {
                        if (polling.join() != null) {
                            load()
                        } else {
                            init
                        }
                    } else {
                        polling.cancel()
                        init
                    }
                }
            }
        } else {
            load()
        }
    }
}

/** Result status of withdrawal or cashout operation abortion */
enum class AbortResult {
    Success,
    UnknownOperation,
    AlreadyConfirmed
}

fun ResultSet.getTalerTimestamp(name: String): TalerProtocolTimestamp{
    return TalerProtocolTimestamp(getLong(name).asInstant())
}