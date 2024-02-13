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
import tech.libeufin.common.TalerAmount
import tech.libeufin.nexus.getAmountNoCurrency
import tech.libeufin.nexus.isReservePub
import tech.libeufin.nexus.removeSubjectNoise
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Parsing {

    @Test
    fun reservePublicKey() {
        assertNull(removeSubjectNoise("does not contain any reserve"))
        // 4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0
        assertNotNull(removeSubjectNoise("4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0"))
        assertEquals(
            "4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0",
            removeSubjectNoise(
                "noise 4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0 noise"
            )
        )
        assertEquals(
            "4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0",
            removeSubjectNoise(
                "4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0 noise to the right"
            )
        )
        assertEquals(
            "4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0",
            removeSubjectNoise(
                "noise to the left 4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0"
            )
        )
        assertEquals(
            "4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0",
            removeSubjectNoise(
                "    4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0     "
            )
        )
        assertEquals(
            "4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0",
            removeSubjectNoise("""
                noise
                4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0
                noise
            """)
        )
        // Got the first char removed.
        assertNull(removeSubjectNoise("MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0"))
    }

    @Test // Could be moved in a dedicated Amounts.kt test module.
    fun generateCurrencyAgnosticAmount() {
        assertFailsWith<Exception> {
            // Too many fractional digits.
            getAmountNoCurrency(TalerAmount(1, 123456789, "KUDOS"))
        }
        assertFailsWith<Exception> {
            // Nexus doesn't support sub-cents.
            getAmountNoCurrency(TalerAmount(1, 12345678, "KUDOS"))
        }
        assertFailsWith<Exception> {
            // Nexus doesn't support sub-cents.
            getAmountNoCurrency(TalerAmount(0, 1, "KUDOS"))
        }
        assertEquals(
            "0.01",
            getAmountNoCurrency(TalerAmount(0, 1000000, "KUDOS"))
        )
        assertEquals(
            "0.1",
            getAmountNoCurrency(TalerAmount(0, 10000000, "KUDOS"))
        )
    }

    // Checks that the input decodes to a 32-bytes value.
    @Test
    fun validateReservePub() {
        val valid = "4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0"
        val validBytes = isReservePub(valid)
        assertNotNull(validBytes)
        assertEquals(32, validBytes.size)
        assertNull(isReservePub("noise"))
        val trimmedInput = valid.dropLast(10)
        assertNull(isReservePub(trimmedInput))
        val invalidChar = StringBuilder(valid)
        invalidChar.setCharAt(10, '*')
        assertNull(isReservePub(invalidChar.toString()))
        assertNull(isReservePub(valid.dropLast(1)))
    }
}