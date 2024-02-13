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
import tech.libeufin.nexus.getFrequencyInSeconds
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConfigLoading {
    // Checks converting human-readable durations to seconds.
    @Test
    fun timeParsing() {
        assertEquals(1, getFrequencyInSeconds("1s"))
        assertEquals(1, getFrequencyInSeconds("       1           s       "))
        assertEquals(10*60, getFrequencyInSeconds("10m"))
        assertEquals(10*60, getFrequencyInSeconds("10 m"))
        assertEquals(24*60*60, getFrequencyInSeconds("24h"))
        assertEquals(24*60*60, getFrequencyInSeconds(" 24h"))
        assertEquals(60*60, getFrequencyInSeconds("      1h      "))
        assertEquals(60*60, getFrequencyInSeconds("01h"))
        assertNull(getFrequencyInSeconds("1.1s"))
        assertNull(getFrequencyInSeconds("         "))
        assertNull(getFrequencyInSeconds("m"))
        assertNull(getFrequencyInSeconds(""))
        assertNull(getFrequencyInSeconds("0"))
    }
}