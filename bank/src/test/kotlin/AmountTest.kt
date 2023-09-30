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
    fun parseTalerAmountTest() {
        val one = "EUR:1"
        var obj = parseTalerAmount2(one, FracDigits.TWO)
        assert(obj!!.value == 1L && obj.frac == 0 && obj.currency == "EUR")
        val onePointZero = "EUR:1.00"
        obj = parseTalerAmount2(onePointZero, FracDigits.TWO)
        assert(obj!!.value == 1L && obj.frac == 0)
        val onePointZeroOne = "EUR:1.01"
        obj = parseTalerAmount2(onePointZeroOne, FracDigits.TWO)
        assert(obj!!.value == 1L && obj.frac == 1000000)
        obj = parseTalerAmount2("EUR:0.00000001", FracDigits.EIGHT)
        assert(obj!!.value == 0L && obj.frac == 1)
        // Setting two fractional digits.
        obj = parseTalerAmount2("EUR:0.01", FracDigits.TWO) // one cent
        assert(obj!!.value == 0L && obj.frac == 1000000)
        obj = parseTalerAmount2("EUR:0.1", FracDigits.TWO) // ten cents
        assert(obj!!.value == 0L && obj.frac == 10000000)
    }
}