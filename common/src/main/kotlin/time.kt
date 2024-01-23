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
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val logger: Logger = LoggerFactory.getLogger("libeufin-common")

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
    val nanos = this.toNanos() ?: run {
        logger.error("Could not obtain micros to store to database, convenience conversion to nanos overflew.")
        return null
    }
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

/**
 * Parses timestamps found in camt.054 documents.  They have
 * the following format: yyy-MM-ddThh:mm:ss, without any timezone.
 *
 * @param timeFromXml input time string from the XML
 * @return [Instant] in the UTC timezone
 */
fun parseCamtTime(timeFromCamt: String): Instant {
    val t = LocalDateTime.parse(timeFromCamt)
    val utc = ZoneId.of("UTC")
    return t.toInstant(utc.rules.getOffset(t))
}

/**
 * Parses a date string as found in the booking date of
 * camt.054 reports.  They have this format: yyyy-MM-dd.
 *
 * @param bookDate input to parse
 * @return [Instant] to the UTC.
 */
fun parseBookDate(bookDate: String): Instant {
    val l = LocalDate.parse(bookDate)
    return Instant.from(l.atStartOfDay(ZoneId.of("UTC")))
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