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
import java.time.*
import java.time.temporal.*
import java.util.*
import tech.libeufin.common.*

fun Parameters.expect(name: String): String 
    = get(name) ?: throw badRequest("Missing '$name' parameter", TalerErrorCode.GENERIC_PARAMETER_MISSING)
fun Parameters.int(name: String): Int? 
    = get(name)?.run { toIntOrNull() ?: throw badRequest("Param 'which' not a number", TalerErrorCode.GENERIC_PARAMETER_MALFORMED) }
fun Parameters.expectInt(name: String): Int 
    = int(name) ?: throw badRequest("Missing '$name' number parameter", TalerErrorCode.GENERIC_PARAMETER_MISSING)
fun Parameters.long(name: String): Long? 
    = get(name)?.run { toLongOrNull() ?: throw badRequest("Param 'which' not a number", TalerErrorCode.GENERIC_PARAMETER_MALFORMED) }
fun Parameters.expectLong(name: String): Long 
    = long(name) ?: throw badRequest("Missing '$name' number parameter", TalerErrorCode.GENERIC_PARAMETER_MISSING)
fun Parameters.amount(name: String): TalerAmount? 
    = get(name)?.run { 
        try {
            TalerAmount(this)
        } catch (e: Exception) {
            throw badRequest("Param '$name' not a taler amount", TalerErrorCode.GENERIC_PARAMETER_MALFORMED)
        }
    }

data class MonitorParams(
    val timeframe: Timeframe,
    val which: Int?
) {
    companion object {
        val names = Timeframe.values().map { it.name }
        val names_fmt = names.joinToString()
        fun extract(params: Parameters): MonitorParams {
            val raw = params.get("timeframe") ?: "hour";
            if (!names.contains(raw)) {
                throw badRequest("Param 'timeframe' must be one of $names_fmt", TalerErrorCode.GENERIC_PARAMETER_MALFORMED)
            }
            val timeframe = Timeframe.valueOf(raw)
            val which = params.int("which")
            if (which != null) {
                val lastDayOfMonth = OffsetDateTime.now(ZoneOffset.UTC).with(TemporalAdjusters.lastDayOfMonth()).dayOfMonth
                when {
                    timeframe == Timeframe.hour && (0 > which || which > 23) -> 
                        throw badRequest("For hour timestamp param 'which' must be between 00 to 23", TalerErrorCode.GENERIC_PARAMETER_MALFORMED)
                    timeframe == Timeframe.day && (1 > which || which > lastDayOfMonth) -> 
                        throw badRequest("For day timestamp param 'which' must be between 1 to $lastDayOfMonth", TalerErrorCode.GENERIC_PARAMETER_MALFORMED)
                    timeframe == Timeframe.month && (1 > which || which > 12) -> 
                        throw badRequest("For month timestamp param 'which' must be between 1 to 12", TalerErrorCode.GENERIC_PARAMETER_MALFORMED)
                    timeframe == Timeframe.year && (1 > which|| which > 9999) -> 
                        throw badRequest("For year timestamp param 'which' must be between 0001 to 9999", TalerErrorCode.GENERIC_PARAMETER_MALFORMED)
                    else -> {}
                }
            }
            return MonitorParams(timeframe, which)
        }
    }
}

data class AccountParams(
    val page: PageParams, val loginFilter: String
) {
    companion object {
        fun extract(params: Parameters): AccountParams {
            val loginFilter = params.get("filter_name")?.run { "%$this%" } ?: "%"
            return AccountParams(PageParams.extract(params), loginFilter)
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
            if (start < 0) throw badRequest("Param 'start' must be a positive number", TalerErrorCode.GENERIC_PARAMETER_MALFORMED)
            // TODO enforce delta limit
            return PageParams(delta, start)
        }
    }
}

data class PollingParams(
    val poll_ms: Long
) {
    companion object {
        fun extract(params: Parameters): PollingParams {
            val poll_ms: Long = params.long("long_poll_ms") ?: 0
            if (poll_ms < 0) throw badRequest("Param 'long_poll_ms' must be a positive number", TalerErrorCode.GENERIC_PARAMETER_MALFORMED)
            return PollingParams(poll_ms)
        }
    }
}

data class HistoryParams(
    val page: PageParams, val polling: PollingParams
) {
    companion object {
        fun extract(params: Parameters): HistoryParams {
            return HistoryParams(PageParams.extract(params), PollingParams.extract(params))
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
                throw badRequest("Either param 'amount_debit' or 'amount_credit' is required", TalerErrorCode.GENERIC_PARAMETER_MISSING)
            } else if (debit != null && credit != null) {
                throw badRequest("Cannot have both 'amount_debit' and 'amount_credit' params", TalerErrorCode.GENERIC_PARAMETER_MALFORMED)
            }
            return RateParams(debit, credit)
        }
    }
}

data class StatusParams(
    val polling: PollingParams,
    val old_state: WithdrawalStatus
) {
    companion object {
        val names = WithdrawalStatus.values().map { it.name }
        val names_fmt = names.joinToString()
        fun extract(params: Parameters): StatusParams {
            val old_state = params.get("old_state") ?: "pending";
            if (!names.contains(old_state)) {
                throw badRequest("Param 'old_state' must be one of $names_fmt", TalerErrorCode.GENERIC_PARAMETER_MALFORMED)
            }
            return StatusParams(
                polling = PollingParams.extract(params),
                old_state = WithdrawalStatus.valueOf(old_state)
            )
        }
    }
}