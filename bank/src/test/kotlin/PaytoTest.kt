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

import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.util.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test
import tech.libeufin.bank.*
import tech.libeufin.common.*
import kotlin.test.*

class PaytoTest {
    // x-taler-bank
    @Test
    fun xTalerBank() = bankSetup("test_x_taler_bank.conf") { _ ->
        // Check Ok
        client.post("/accounts") {
            json {
                "username" to "john"
                "password" to "john-password"
                "name" to "John"
            }
        }.assertOkJson<RegisterAccountResponse> {
            assertEquals("payto://x-taler-bank/bank.hostname.test/john?receiver-name=John", it.internal_payto_uri)
        }

        // Check payto_uri is ignored
        client.post("/accounts") {
            json {
                "username" to "foo"
                "password" to "foo-password"
                "name" to "Jane"
                "payto_uri" to IbanPayto.rand()
            }
        }.assertOkJson<RegisterAccountResponse> {
            assertEquals("payto://x-taler-bank/bank.hostname.test/foo?receiver-name=Jane", it.internal_payto_uri)
        }

        // Check payto canonicalisation 
        client.postA("/accounts/john/transactions") {
            json {
                "payto_uri" to "payto://x-taler-bank/ignored/foo?message=payout&amount=KUDOS:0.3"
            }
        }.assertOkJson<TransactionCreateResponse> {
            client.getA("/accounts/john/transactions/${it.row_id}")
                .assertOkJson<BankAccountTransactionInfo> { tx ->
                assertEquals("payout", tx.subject)
                assertEquals("payto://x-taler-bank/bank.hostname.test/foo?receiver-name=Jane", tx.creditor_payto_uri)
                assertEquals("payto://x-taler-bank/bank.hostname.test/john?receiver-name=John", tx.debtor_payto_uri)
                assertEquals(TalerAmount("KUDOS:0.3"), tx.amount)
            }
        }
    }
}