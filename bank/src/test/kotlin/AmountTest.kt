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
import tech.libeufin.bank.FracDigits
import tech.libeufin.bank.TalerAmount
import tech.libeufin.bank.parseTalerAmount

class AmountTest {
    /* Testing that currency is fetched from the config
       and set in the TalerAmount dedicated field. */
    @Test
    fun testAutoCurrency() {
        val db = initDb()
        db.configSet("internal_currency", "KUDOS")
        val a = TalerAmount(1L, 0)
        assert(a.currency == "KUDOS")
    }

    @Test
    fun parseTalerAmountTest() {
        val one = "EUR:1"
        var obj = parseTalerAmount(one)
        assert(obj.value == 1L && obj.frac == 0 && obj.currency == "EUR")
        val onePointZero = "EUR:1.00"
        obj = parseTalerAmount(onePointZero)
        assert(obj.value == 1L && obj.frac == 0)
        val onePointZeroOne = "EUR:1.01"
        obj = parseTalerAmount(onePointZeroOne)
        assert(obj.value == 1L && obj.frac == 1000000)
        obj = parseTalerAmount("EUR:0.00000001")
        assert(obj.value == 0L && obj.frac == 1)
        // Setting two fractional digits.
        obj = parseTalerAmount("EUR:0.01", FracDigits.TWO) // one cent
        assert(obj.value == 0L && obj.frac == 1000000)
        obj = parseTalerAmount("EUR:0.1", FracDigits.TWO) // ten cents
        assert(obj.value == 0L && obj.frac == 10000000)
    }
}