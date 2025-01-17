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

import io.ktor.client.request.*
import org.junit.Test
import tech.libeufin.bank.BankAccountTransactionInfo
import tech.libeufin.bank.RegisterAccountResponse
import tech.libeufin.bank.TransactionCreateResponse
import tech.libeufin.common.*
import kotlin.test.assertEquals

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

        // Bad IBAN payto
        client.post("/accounts") {
            json {
                "username" to "foo"
                "password" to "foo-password"
                "name" to "Jane"
                "payto_uri" to IbanPayto.rand()
            }
        }.assertBadRequest()
        // Bad payto username
        client.post("/accounts") {
            json {
                "username" to "foo"
                "password" to "foo-password"
                "name" to "Jane"
                "payto_uri" to "payto://x-taler-bank/bank.hostname.test/not-foo"
            }
        }.assertBadRequest()
        // Check Ok
        client.post("/accounts") {
            json {
                "username" to "foo"
                "password" to "foo-password"
                "name" to "Jane"
                "payto_uri" to "payto://x-taler-bank/bank.hostname.test/foo"
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