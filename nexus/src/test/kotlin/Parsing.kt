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
import tech.libeufin.common.*
import tech.libeufin.nexus.getAmountNoCurrency
import kotlin.test.*

class Parsing {

    @Test
    fun reservePublicKey() {
        assertFails { parseIncomingTxMetadata("does not contain any reserve") }
        
        assertEquals(
            EddsaPublicKey("4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0"),
            parseIncomingTxMetadata(
                "noise 4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0 noise"
            )
        )
        assertEquals(
            EddsaPublicKey("4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0"),
            parseIncomingTxMetadata(
                "4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0 noise to the right"
            )
        )
        assertEquals(
            EddsaPublicKey("4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0"),
            parseIncomingTxMetadata(
                "noise to the left 4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0"
            )
        )
        assertEquals(
            EddsaPublicKey("4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0"),
            parseIncomingTxMetadata(
                "    4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0     "
            )
        )
        assertEquals(
            EddsaPublicKey("4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0"),
            parseIncomingTxMetadata("""
                noise
                4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0
                noise
            """)
        )
        // Got the first char removed.
        assertFails { parseIncomingTxMetadata("MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0") }
    }

    @Test // Could be moved in a dedicated Amounts.kt test module.
    fun generateCurrencyAgnosticAmount() {
        assertFails {
            // Too many fractional digits.
            getAmountNoCurrency(TalerAmount(1, 123456789, "KUDOS"))
        }
        assertFails {
            // Nexus doesn't support sub-cents.
            getAmountNoCurrency(TalerAmount(1, 12345678, "KUDOS"))
        }
        assertFails {
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
}