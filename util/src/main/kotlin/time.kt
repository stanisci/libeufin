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
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Converts the 'this' Instant to the number of nanoseconds
 * since the Epoch.  It returns the result as Long, or null
 * if one arithmetic overflow occurred.
 */
private fun Instant.toNanos(): Long? {
    val oneSecNanos = ChronoUnit.SECONDS.duration.toNanos()
    val nanoBase: Long = this.epochSecond * oneSecNanos
    if (nanoBase != 0L && nanoBase / this.epochSecond != oneSecNanos) {
        logger.error("Multiplication overflow: could not convert Instant to nanos.")
        return null
    }
    val res = nanoBase + this.nano
    if (res < nanoBase) {
        logger.error("Addition overflow: could not convert Instant to nanos.")
        return null
    }
    return res
}

/**
 * This function converts an Instant input to the
 * number of microseconds since the Epoch, except that
 * it yields Long.MAX if the Input is Instant.MAX.
 *
 * Takes the name after the way timestamps are designed
 * in the database: micros since Epoch, or Long.MAX for
 * "never".
 *
 * Returns the Long representation of 'this' or null
 * if that would overflow.
 */
fun Instant.toDbMicros(): Long? {
    if (this == Instant.MAX)
        return Long.MAX_VALUE
    val nanos = this.toNanos() ?: return null
    return nanos / 1000L
}

/**
 * This helper is typically used to convert a timestamp expressed
 * in microseconds from the DB back to the Web application.  In case
 * of _any_ error, it logs it and returns null.
 */
fun Long.microsToJavaInstant(): Instant? {
    if (this == Long.MAX_VALUE)
        return Instant.MAX
    return try {
        Instant.EPOCH.plus(this, ChronoUnit.MICROS)
    } catch (e: Exception) {
        logger.error(e.message)
        return null
    }
}