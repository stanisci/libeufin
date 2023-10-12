/*
 * This file is part of LibEuFin.
 * Copyright (C) 2019 Stanisci and Dold.

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
import tech.libeufin.bank.*
import kotlin.test.assertEquals

class AmountTest {
    @Test
    fun amountAdditionTest() {
        // Balance enough, assert for true
        assert(isBalanceEnough(
            balance = TalerAmount(10, 0, "KUDOS"),
            due = TalerAmount(8, 0, "KUDOS"),
            hasBalanceDebt = false,
            maxDebt = TalerAmount(100, 0, "KUDOS")
        ))
        // Balance still sufficient, thanks for big enough debt permission.  Assert true.
        assert(isBalanceEnough(
            balance = TalerAmount(10, 0, "KUDOS"),
            due = TalerAmount(80, 0, "KUDOS"),
            hasBalanceDebt = false,
            maxDebt = TalerAmount(100, 0, "KUDOS")
        ))
        // Balance not enough, max debt cannot cover, asserting for false.
        assert(!isBalanceEnough(
            balance = TalerAmount(10, 0, "KUDOS"),
            due = TalerAmount(80, 0, "KUDOS"),
            hasBalanceDebt = true,
            maxDebt = TalerAmount(50, 0, "KUDOS")
        ))
        // Balance becomes enough, due to a larger max debt, asserting for true.
        assert(isBalanceEnough(
            balance = TalerAmount(10, 0, "KUDOS"),
            due = TalerAmount(80, 0, "KUDOS"),
            hasBalanceDebt = false,
            maxDebt = TalerAmount(70, 0, "KUDOS")
        ))
        // Max debt not enough for the smallest fraction, asserting for false
        assert(!isBalanceEnough(
            balance = TalerAmount(0, 0, "KUDOS"),
            due = TalerAmount(0, 2, "KUDOS"),
            hasBalanceDebt = false,
            maxDebt = TalerAmount(0, 1, "KUDOS")
        ))
        // Same as above, but already in debt.
        assert(!isBalanceEnough(
            balance = TalerAmount(0, 1, "KUDOS"),
            due = TalerAmount(0, 1, "KUDOS"),
            hasBalanceDebt = true,
            maxDebt = TalerAmount(0, 1, "KUDOS")
        ))


    }

    @Test
    fun parseValid() {
        assertEquals(TalerAmount("EUR:4"), TalerAmount(4L, 0, "EUR"))
        assertEquals(TalerAmount("EUR:0.02"), TalerAmount(0L, 2000000, "EUR"))
        assertEquals(TalerAmount("EUR:4.12"), TalerAmount(4L, 12000000, "EUR"))
        assertEquals(TalerAmount("LOCAL:4444.1000"), TalerAmount(4444L, 10000000, "LOCAL"))
    }

    @Test
    fun parseInvalid() {
        assertException("Invalid amount format") {TalerAmount("")}
        assertException("Invalid amount format") {TalerAmount("EUR")}
        assertException("Invalid amount format") {TalerAmount("eur:12")}
        assertException("Invalid amount format") {TalerAmount(" EUR:12")}
        assertException("Invalid amount format") {TalerAmount("AZERTYUIOPQSD:12")}
        assertException("Value specified in amount is too large") {TalerAmount("EUR:${Long.MAX_VALUE}")}
        assertException("Invalid amount format") {TalerAmount("EUR:4.000000000")}
        assertException("Invalid amount format") {TalerAmount("EUR:4.4a")}
    }

    @Test
    fun normalize() {
        assertEquals(TalerAmount("EUR:6"), TalerAmount(4L, 2 * TalerAmount.FRACTION_BASE, "EUR").normalize())
        assertEquals(TalerAmount("EUR:6.00000001"), TalerAmount(4L, 2 * TalerAmount.FRACTION_BASE + 1, "EUR").normalize())
        assertException("Amount value overflowed") { TalerAmount(Long.MAX_VALUE, 2 * TalerAmount.FRACTION_BASE + 1, "EUR").normalize() }
        assertException("Amount value overflowed") { TalerAmount(MAX_SAFE_INTEGER, 2 * TalerAmount.FRACTION_BASE + 1, "EUR").normalize() }
    }

    @Test
    fun add() {
        assertEquals(TalerAmount("EUR:6.41") + TalerAmount("EUR:4.69"), TalerAmount("EUR:11.1"))
        assertException("Amount value overflowed") { TalerAmount(MAX_SAFE_INTEGER - 5, 0, "EUR") + TalerAmount(6, 0, "EUR") }
        assertException("Amount value overflowed") { TalerAmount(Long.MAX_VALUE, 0, "EUR") + TalerAmount(1, 0, "EUR") }
        assertException("Amount value overflowed") { TalerAmount(MAX_SAFE_INTEGER - 5, TalerAmount.FRACTION_BASE - 1, "EUR") + TalerAmount(5, 2, "EUR") }
        assertException("Amount fraction overflowed") { TalerAmount(0, Int.MAX_VALUE, "EUR") + TalerAmount(0, 1, "EUR") }
    }
}