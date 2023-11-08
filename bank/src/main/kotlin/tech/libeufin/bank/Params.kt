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

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.util.*
import io.ktor.util.valuesOf
import net.taler.common.errorcodes.TalerErrorCode
import net.taler.wallet.crypto.Base32Crockford
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.util.*
import java.net.URL
import java.time.*
import java.time.temporal.*
import java.util.*

fun Parameters.expect(name: String): String 
    = get(name) ?: throw badRequest("Missing '$name' parameter")
fun Parameters.int(name: String): Int? 
    = get(name)?.run { toIntOrNull() ?: throw badRequest("Param 'which' not a number") }
fun Parameters.expectInt(name: String): Int 
    = int(name) ?: throw badRequest("Missing '$name' number parameter")
fun Parameters.long(name: String): Long? 
    = get(name)?.run { toLongOrNull() ?: throw badRequest("Param 'which' not a number") }
fun Parameters.expectLong(name: String): Long 
    = long(name) ?: throw badRequest("Missing '$name' number parameter")
fun Parameters.amount(name: String): TalerAmount? 
    = get(name)?.run { 
        try {
            TalerAmount(this)
        } catch (e: Exception) {
            throw badRequest("Param '$name' not a taler amount")
        }
    }

data class MonitorParams(
    val timeframe: Timeframe,
    val which: Int?
) {
    companion object {
        fun extract(params: Parameters): MonitorParams {
            val timeframe = Timeframe.valueOf(params["timeframe"] ?: "hour")
            val which = params.int("which")
            if (which != null) {
                val lastDayOfMonth = OffsetDateTime.now(ZoneOffset.UTC).with(TemporalAdjusters.lastDayOfMonth()).dayOfMonth
                when {
                    timeframe == Timeframe.hour && (0 > which || which > 23) -> 
                        throw badRequest("For hour timestamp param 'which' must be between 00 to 23")
                    timeframe == Timeframe.day && (1 > which || which > 23) -> 
                        throw badRequest("For day timestamp param 'which' must be between 1 to $lastDayOfMonth")
                    timeframe == Timeframe.month && (1 > which || which > lastDayOfMonth) -> 
                        throw badRequest("For month timestamp param 'which' must be between 1 to 12")
                    timeframe == Timeframe.year && (1 > which|| which > 9999) -> 
                        throw badRequest("For year timestamp param 'which' must be between 0001 to 9999")
                    else -> {}
                }
            }
            return MonitorParams(timeframe, which)
        }
    }
}

data class HistoryParams(
    val page: PageParams, val poll_ms: Long
) {
    companion object {
        fun extract(params: Parameters): HistoryParams {
            val poll_ms: Long = params.long("long_poll_ms") ?: 0
            // TODO check poll_ms range
            return HistoryParams(PageParams.extract(params), poll_ms)
        }
    }
}

data class PageParams(
    val delta: Int, val start: Long
) {
    companion object {
        fun extract(params: Parameters): PageParams {
            val delta: Int = params.int("delta") ?: -20
            val start: Long = params.long("start") ?: if (delta >= 0) 0L else Long.MAX_VALUE
            // TODO enforce delta limit
            return PageParams(delta, start)
        }
    }
}

data class RateParams(
    val debit: TalerAmount?, val credit: TalerAmount?
) {
    companion object {
        fun extract(params: Parameters): RateParams {
            val debit = params.amount("amount_debit")
            val credit =  params.amount("amount_credit")
            if (debit == null && credit == null) {
                throw badRequest("Either param 'amount_debit' or 'amount_credit' is required")
            } 
            return RateParams(debit, credit)
        }
    }
}