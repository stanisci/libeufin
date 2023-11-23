/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023 Taler Systems S.A.

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

import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.util.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import net.taler.common.errorcodes.TalerErrorCode
import org.junit.Test
import tech.libeufin.bank.*

class ConversionApiTest {
    // GET /conversion-info/config
    @Test
    fun config() = bankSetup { _ ->
        client.get("/conversion-info/config").assertOk()
    }
    
    // GET /conversion-info/cashout-rate
    @Test
    fun cashoutRate() = bankSetup { _ ->
        // Check conversion to
        client.get("/conversion-info/cashout-rate?amount_debit=KUDOS:1").assertOkJson<ConversionResponse> {
            assertEquals(TalerAmount("KUDOS:1"), it.amount_debit)
            assertEquals(TalerAmount("EUR:1.247"), it.amount_credit)
        }
        // Check conversion from
        client.get("/conversion-info/cashout-rate?amount_credit=EUR:1.247").assertOkJson<ConversionResponse> {
            assertEquals(TalerAmount("KUDOS:1"), it.amount_debit)
            assertEquals(TalerAmount("EUR:1.247"), it.amount_credit)
        }

        // Too small
        client.get("/conversion-info/cashout-rate?amount_debit=KUDOS:0.08")
            .assertConflict(TalerErrorCode.BANK_BAD_CONVERSION)
        // No amount
        client.get("/conversion-info/cashout-rate")
            .assertBadRequest(TalerErrorCode.GENERIC_PARAMETER_MISSING)
        // Both amount
        client.get("/conversion-info/cashout-rate?amount_debit=EUR:1&amount_credit=KUDOS:1")
            .assertBadRequest(TalerErrorCode.GENERIC_PARAMETER_MALFORMED)
        // Wrong format
        client.get("/conversion-info/cashout-rate?amount_debit=1")
            .assertBadRequest(TalerErrorCode.GENERIC_PARAMETER_MALFORMED)
        client.get("/conversion-info/cashout-rate?amount_credit=1")
            .assertBadRequest(TalerErrorCode.GENERIC_PARAMETER_MALFORMED)
        // Wrong currency
        client.get("/conversion-info/cashout-rate?amount_debit=EUR:1")
            .assertBadRequest(TalerErrorCode.GENERIC_CURRENCY_MISMATCH)
        client.get("/conversion-info/cashout-rate?amount_credit=KUDOS:1")
            .assertBadRequest(TalerErrorCode.GENERIC_CURRENCY_MISMATCH)
    }

    // GET /conversion-info/cashin-rate
    @Test
    fun cashinRate() = bankSetup { _ ->
        for ((amount, converted) in listOf(
            Pair(0.75, 0.58), Pair(0.32, 0.24), Pair(0.66, 0.51)
        )) {
                // Check conversion to
            client.get("/conversion-info/cashin-rate?amount_debit=EUR:$amount").assertOkJson<ConversionResponse> {
                assertEquals(TalerAmount("KUDOS:$converted"), it.amount_credit)
                assertEquals(TalerAmount("EUR:$amount"), it.amount_debit)
            }
            // Check conversion from
            client.get("/conversion-info/cashin-rate?amount_credit=KUDOS:$converted").assertOkJson<ConversionResponse> {
                assertEquals(TalerAmount("KUDOS:$converted"), it.amount_credit)
                assertEquals(TalerAmount("EUR:$amount"), it.amount_debit)
            }
        }

        // No amount
        client.get("/conversion-info/cashin-rate")
            .assertBadRequest(TalerErrorCode.GENERIC_PARAMETER_MISSING)
        // Both amount
        client.get("/conversion-info/cashin-rate?amount_debit=KUDOS:1&amount_credit=EUR:1")
            .assertBadRequest(TalerErrorCode.GENERIC_PARAMETER_MALFORMED)
        // Wrong format
        client.get("/conversion-info/cashin-rate?amount_debit=1")
            .assertBadRequest(TalerErrorCode.GENERIC_PARAMETER_MALFORMED)
        client.get("/conversion-info/cashin-rate?amount_credit=1")
            .assertBadRequest(TalerErrorCode.GENERIC_PARAMETER_MALFORMED)
        // Wrong currency
        client.get("/conversion-info/cashin-rate?amount_debit=KUDOS:1")
            .assertBadRequest(TalerErrorCode.GENERIC_CURRENCY_MISMATCH)
        client.get("/conversion-info/cashin-rate?amount_credit=EUR:1")
            .assertBadRequest(TalerErrorCode.GENERIC_CURRENCY_MISMATCH)
    }

    @Test
    fun noRate() = bankSetup { db ->
        db.conversion.clearConfig()
        client.get("/conversion-info/cashin-rate")
            .assertBadRequest()
        client.get("/conversion-info/cashout-rate")
            .assertBadRequest()
        client.get("/conversion-info/cashin-rate?amount_credit=KUDOS:1")
            .assertNotImplemented()
        client.get("/conversion-info/cashout-rate?amount_credit=EUR:1")
            .assertNotImplemented()
    }

    @Test
    fun notImplemented() = bankSetup("test_restrict.conf") { _ ->
        client.get("/conversion-info/cashin-rate")
            .assertNotImplemented()
        client.get("/conversion-info/cashout-rate")
            .assertNotImplemented()
    }
}