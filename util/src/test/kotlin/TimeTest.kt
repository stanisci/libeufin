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

import org.junit.Test
import tech.libeufin.util.maxTimestamp
import tech.libeufin.util.minTimestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimeTest {
    @Test
    fun cmp() {
        val now = Instant.now()
        val inOneMinute = now.plus(1, ChronoUnit.MINUTES)

        // testing the "min" function
        assertNull(minTimestamp(null, null))
        assertEquals(now, minTimestamp(now, inOneMinute))
        assertNull(minTimestamp(now, null))
        assertNull(minTimestamp(null, now))
        assertEquals(inOneMinute, minTimestamp(inOneMinute, inOneMinute))

        // testing the "max" function
        assertNull(maxTimestamp(null, null))
        assertEquals(inOneMinute, maxTimestamp(now, inOneMinute))
        assertEquals(now, maxTimestamp(now, null))
        assertEquals(now, maxTimestamp(null, now))
        assertEquals(now, minTimestamp(now, now))
    }
}