/*
 * This file is part of LibEuFin.
 * Copyright (C) 2020 Taler Systems S.A.
 *
 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.
 *
 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

package tech.libeufin.util

import java.time.*
import java.time.format.DateTimeFormatter

private var LIBEUFIN_CLOCK = Clock.system(ZoneOffset.UTC)

fun setClock(rel: Duration) {
    LIBEUFIN_CLOCK = Clock.offset(LIBEUFIN_CLOCK, rel)
}
fun getNow(): ZonedDateTime {
    return ZonedDateTime.now(LIBEUFIN_CLOCK)
}
fun getUTCnow(): ZonedDateTime {
    return ZonedDateTime.now(ZoneOffset.UTC)
}

fun ZonedDateTime.toZonedString(): String {
    return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(this)
}

fun ZonedDateTime.toDashedDate(): String {
    return DateTimeFormatter.ISO_DATE.format(this)
}

fun parseDashedDate(date: String): ZonedDateTime {
    val dtf = DateTimeFormatter.ISO_DATE
    return ZonedDateTime.parse(date, dtf)
}

fun importDateFromMillis(millis: Long): ZonedDateTime {
    return ZonedDateTime.ofInstant(
        Instant.ofEpochMilli(millis),
        ZoneOffset.UTC
    )
}

fun LocalDateTime.millis(): Long {
    val instant = Instant.from(this.atZone(ZoneOffset.UTC))
    return instant.toEpochMilli()
}