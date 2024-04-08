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
import kotlin.test.assertEquals

class AmountTest {
    @Test
    fun parse() {
        assertEquals(TalerAmount("EUR:4"), TalerAmount(4L, 0, "EUR"))
        assertEquals(TalerAmount("EUR:0.02"), TalerAmount(0L, 2000000, "EUR"))
        assertEquals(TalerAmount("EUR:4.12"), TalerAmount(4L, 12000000, "EUR"))
        assertEquals(TalerAmount("LOCAL:4444.1000"), TalerAmount(4444L, 10000000, "LOCAL"))
        assertEquals(TalerAmount("EUR:${TalerAmount.MAX_VALUE}.99999999"), TalerAmount(TalerAmount.MAX_VALUE, 99999999, "EUR"))

        assertException("Invalid amount format") {TalerAmount("")}
        assertException("Invalid amount format") {TalerAmount("EUR")}
        assertException("Invalid amount format") {TalerAmount("eur:12")}
        assertException("Invalid amount format") {TalerAmount(" EUR:12")}
        assertException("Invalid amount format") {TalerAmount("EUR:1.")}
        assertException("Invalid amount format") {TalerAmount("EUR:.1")}
        assertException("Invalid amount format") {TalerAmount("AZERTYUIOPQSD:12")}
        assertException("Value specified in amount is too large") {TalerAmount("EUR:${Long.MAX_VALUE}")}
        assertException("Invalid amount format") {TalerAmount("EUR:4.000000000")}
        assertException("Invalid amount format") {TalerAmount("EUR:4.4a")}
    }

    @Test
    fun parseRoundTrip() {
        for (amount in listOf("EUR:4", "EUR:0.02", "EUR:4.12")) {
            assertEquals(amount, TalerAmount(amount).toString())
        }
    }

    fun assertException(msg: String, lambda: () -> Unit) {
        try {
            lambda()
            throw Exception("Expected failure")
        } catch (e: Exception) {
            assert(e.message!!.startsWith(msg)) { "${e.message}" }
        }
    }
}