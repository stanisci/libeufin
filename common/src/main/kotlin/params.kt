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

package tech.libeufin.common

import io.ktor.http.*
import tech.libeufin.common.TalerAmount
import tech.libeufin.common.TalerErrorCode
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.util.*

fun Parameters.expect(name: String): String 
    = get(name) ?: throw badRequest("Missing '$name' parameter", TalerErrorCode.GENERIC_PARAMETER_MISSING)
fun Parameters.int(name: String): Int? 
    = get(name)?.run { toIntOrNull() ?: throw badRequest("Param '$name' not a number", TalerErrorCode.GENERIC_PARAMETER_MALFORMED) }
fun Parameters.expectInt(name: String): Int 
    = int(name) ?: throw badRequest("Missing '$name' number parameter", TalerErrorCode.GENERIC_PARAMETER_MISSING)
fun Parameters.long(name: String): Long? 
    = get(name)?.run { toLongOrNull() ?: throw badRequest("Param '$name' not a number", TalerErrorCode.GENERIC_PARAMETER_MALFORMED) }
fun Parameters.expectLong(name: String): Long 
    = long(name) ?: throw badRequest("Missing '$name' number parameter", TalerErrorCode.GENERIC_PARAMETER_MISSING)
fun Parameters.uuid(name: String): UUID? {
    return get(name)?.run {
        try {
            UUID.fromString(this)
        } catch (e: Exception) {
            throw badRequest("Param '$name' not an UUID", TalerErrorCode.GENERIC_PARAMETER_MALFORMED)
        }
    } 
}
fun Parameters.expectUuid(name: String): UUID 
    = uuid(name) ?: throw badRequest("Missing '$name' UUID parameter", TalerErrorCode.GENERIC_PARAMETER_MISSING)
fun Parameters.amount(name: String): TalerAmount? 
    = get(name)?.run { 
        try {
            TalerAmount(this)
        } catch (e: Exception) {
            throw badRequest("Param '$name' not a taler amount", TalerErrorCode.GENERIC_PARAMETER_MALFORMED)
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