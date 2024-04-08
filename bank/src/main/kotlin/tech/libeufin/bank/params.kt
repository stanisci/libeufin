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
import tech.libeufin.common.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.util.*

data class MonitorParams(
    val timeframe: Timeframe,
    val date: LocalDateTime
) {
    constructor(timeframe: Timeframe, now: LocalDateTime, which: Int) : this(
        timeframe,
        when (timeframe) {
            Timeframe.hour -> now.withHour(which)
            Timeframe.day -> now.withDayOfMonth(which)
            Timeframe.month -> now.withMonth(which)
            Timeframe.year -> now.withYear(which)
        }
    )
    constructor(timeframe: Timeframe, secs: Long) : this(
        timeframe,
        LocalDateTime.ofInstant(Instant.ofEpochSecond(secs), ZoneOffset.UTC)
    )
    companion object {
        val names = Timeframe.entries.map { it.name }
        val names_fmt = names.joinToString()
        
        fun extract(params: Parameters): MonitorParams {
            val raw = params.get("timeframe") ?: "hour"
            if (!names.contains(raw)) {
                throw badRequest("Param 'timeframe' must be one of $names_fmt", TalerErrorCode.GENERIC_PARAMETER_MALFORMED)
            }
            val timeframe = Timeframe.valueOf(raw)
            val now = LocalDateTime.now(ZoneOffset.UTC)
            val which = params.int("which")
            val dateS = params.long("date_s")
            if (which != null && dateS != null) {
                throw badRequest("Cannot use both 'date_s' and deprecated 'which'", TalerErrorCode.GENERIC_PARAMETER_MALFORMED)
            }
            return if (which != null) {
                val lastDayOfMonth = now.with(TemporalAdjusters.lastDayOfMonth()).dayOfMonth
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
                MonitorParams(timeframe, now, which)
            } else if (dateS != null) {
                MonitorParams(timeframe, dateS)
            } else {
                MonitorParams(timeframe, now)
            }
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
        val names = WithdrawalStatus.entries.map { it.name }
        val names_fmt = names.joinToString()
        fun extract(params: Parameters): StatusParams {
            val old_state = params.get("old_state") ?: "pending"
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