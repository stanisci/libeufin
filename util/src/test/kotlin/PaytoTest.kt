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
import tech.libeufin.util.IbanPayto
import tech.libeufin.util.parsePayto

class PaytoTest {

    @Test
    fun wrongCases() {
        assert(parsePayto("http://iban/BIC123/IBAN123?receiver-name=The%20Name") == null)
        assert(parsePayto("payto:iban/BIC123/IBAN123?receiver-name=The%20Name&address=house") == null)
        assert(parsePayto("payto://wrong/BIC123/IBAN123?sender-name=Foo&receiver-name=Foo") == null)
    }

    @Test
    fun parsePaytoTest() {
        val withBic: IbanPayto = parsePayto("payto://iban/BIC123/IBAN123?receiver-name=The%20Name")!!
        assert(withBic.iban == "IBAN123")
        assert(withBic.bic == "BIC123")
        assert(withBic.receiverName == "The Name")
        val complete = parsePayto("payto://iban/BIC123/IBAN123?sender-name=The%20Name&amount=EUR:1&message=donation")!!
        assert(withBic.iban == "IBAN123")
        assert(withBic.bic == "BIC123")
        assert(withBic.receiverName == "The Name")
        assert(complete.message == "donation")
        assert(complete.amount == "EUR:1")
        val withoutOptionals = parsePayto("payto://iban/IBAN123")!!
        assert(withoutOptionals.bic == null)
        assert(withoutOptionals.message == null)
        assert(withoutOptionals.receiverName == null)
        assert(withoutOptionals.amount == null)
    }
}