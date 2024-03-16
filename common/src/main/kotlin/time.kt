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

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

private val logger: Logger = LoggerFactory.getLogger("libeufin-common")

/** 
 * Convert Instant to microseconds since the epoch.
 * 
 * Returns Long.MAX_VALUE if instant is Instant.MAX
 **/
fun Instant.micros(): Long {
    if (this == Instant.MAX) 
        return Long.MAX_VALUE
    try {
        val micros = ChronoUnit.MICROS.between(Instant.EPOCH, this)
        if (micros == Long.MAX_VALUE) throw ArithmeticException()
        return micros
    } catch (e: ArithmeticException) {
        throw Exception("${this} is too big to be converted to micros resolution", e)
    }
}

/** 
 * Convert microsecons to Instant.
 * 
 * Returns Instant.MAX if microseconds is Long.MAX_VALUE
 */
fun Long.asInstant(): Instant {
    if (this == Long.MAX_VALUE)
        return Instant.MAX
    return try {
        Instant.EPOCH.plus(this, ChronoUnit.MICROS)
    } catch (e: ArithmeticException ) {
        throw Exception("${this} is too big to be converted to Instant", e)
    }
}

/**
 * Returns the minimum instant between two.
 *
 * @param a input [Instant]
 * @param b input [Instant]
 * @return the minimum [Instant] or null if even one is null.
 */
fun minTimestamp(a: Instant?, b: Instant?): Instant? {
    if (a == null || b == null) return null
    if (a.isBefore(b)) return a
    return b // includes the case where a == b.
}

/**
 * Returns the max instant between two.
 *
 * @param a input [Instant]
 * @param b input [Instant]
 * @return the max [Instant] or null if both are null
 */
fun maxTimestamp(a: Instant?, b: Instant?): Instant? {
    if (a == null) return b
    if (b == null) return a
    if (a.isAfter(b)) return a
    return b // includes the case where a == b
}