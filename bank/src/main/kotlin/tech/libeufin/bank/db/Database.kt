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

import org.postgresql.jdbc.PgConnection
import org.postgresql.ds.PGSimpleDataSource
import org.postgresql.util.PSQLState
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.*
import java.time.*
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import tech.libeufin.common.*
import io.ktor.http.HttpStatusCode
import tech.libeufin.bank.*

private val logger: Logger = LoggerFactory.getLogger("libeufin-bank-db")

/**
 * This error occurs in case the timestamp took by the bank for some
 * event could not be converted in microseconds.  Note: timestamp are
 * taken via the Instant.now(), then converted to nanos, and then divided
 * by 1000 to obtain the micros.
 *
 * It could be that a legitimate timestamp overflows in the process of
 * being converted to micros - as described above.  In the case of a timestamp,
 * the fault lies to the bank, because legitimate timestamps must (at the
 * time of writing!) go through the conversion to micros.
 *
 * On the other hand (and for the sake of completeness), in the case of a
 * timestamp that was calculated after a client-submitted duration, the overflow
 * lies to the client, because they must have specified a gigantic amount of time
 * that overflew the conversion to micros and should simply have specified "forever".
 */
internal fun faultyTimestampByBank() = internalServerError("Bank took overflowing timestamp")
internal fun faultyDurationByClient() = badRequest("Overflowing duration, please specify 'forever' instead.")

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
            FROM stats_get_frame(NULL, ?::stat_timeframe_enum, ?)
        """)
        stmt.setString(1, params.timeframe.name)
        if (params.which != null) {
            stmt.setInt(2, params.which)
        } else {
            stmt.setNull(2, java.sql.Types.INTEGER)
        }
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
                        polling.join()
                        load()
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
    return TalerProtocolTimestamp(
        getLong(name).microsToJavaInstant() ?: throw faultyTimestampByBank()
    )
}