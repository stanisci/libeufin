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
import kotlin.test.*

class PaytoTest {
    @Test
    fun wrongCases() {
        assertFailsWith<CommonError.Payto> { Payto.parse("http://iban/BIC123/IBAN123?receiver-name=The%20Name") }
        assertFailsWith<CommonError.Payto> { Payto.parse("payto:iban/BIC123/IBAN123?receiver-name=The%20Name&address=house") }
        assertFailsWith<CommonError.Payto> { Payto.parse("payto://wrong/BIC123/IBAN123?sender-name=Foo&receiver-name=Foo") }
    }

    @Test
    fun parsePaytoTest() {
        val withBic = Payto.parse("payto://iban/BIC123/CH9300762011623852957?receiver-name=The%20Name").expectIban()
        assertEquals(withBic.iban.value, "CH9300762011623852957")
        assertEquals(withBic.receiverName, "The Name")
        val complete = Payto.parse("payto://iban/BIC123/CH9300762011623852957?sender-name=The%20Name&amount=EUR:1&message=donation").expectIban()
        assertEquals(withBic.iban.value, "CH9300762011623852957")
        assertEquals(withBic.receiverName, "The Name")
        assertEquals(complete.message, "donation")
        assertEquals(complete.amount.toString(), "EUR:1")
        val withoutOptionals = Payto.parse("payto://iban/CH9300762011623852957").expectIban()
        assertNull(withoutOptionals.message)
        assertNull(withoutOptionals.receiverName)
        assertNull(withoutOptionals.amount)
    }

    @Test
    fun forms() {
        val ctx = BankPaytoCtx(
            bic = "TESTBIC"
        )
        val canonical = "payto://iban/CH9300762011623852957"
        val bank = "payto://iban/TESTBIC/CH9300762011623852957?receiver-name=Name"
        val inputs = listOf(
            "payto://iban/BIC/CH9300762011623852957?receiver-name=NotGiven",
            "payto://iban/CH9300762011623852957?receiver-name=Grothoff%20Hans",
            "payto://iban/ch%209300-7620-1162-3852-957",
        )
        val names = listOf(
            "NotGiven", "Grothoff Hans", null
        )
        val full = listOf(
            "payto://iban/BIC/CH9300762011623852957?receiver-name=NotGiven",
            "payto://iban/CH9300762011623852957?receiver-name=Grothoff%20Hans",
            "payto://iban/CH9300762011623852957?receiver-name=Santa%20Claus",
        )
        for ((i, input) in inputs.withIndex()) {
            val payto = Payto.parse(input).expectIban()
            assertEquals(canonical, payto.canonical)
            assertEquals(bank, payto.bank("Name", ctx))
            assertEquals(full[i], payto.full("Santa Claus"))
            assertEquals(names[i], payto.receiverName)
        }
    }
}