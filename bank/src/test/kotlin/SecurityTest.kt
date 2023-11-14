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

import org.junit.Test
import org.postgresql.jdbc.PgConnection
import tech.libeufin.bank.*
import tech.libeufin.util.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.HttpClient
import io.ktor.http.content.*
import io.ktor.server.engine.*
import io.ktor.server.testing.*
import kotlin.test.*
import kotlinx.coroutines.*

class SecurityTest {
    @Test
    fun bodySizeLimit() = bankSetup { _ ->
        val valid_req = json {
            "payto_uri" to "payto://iban/EXCHANGE-IBAN-XYZ?message=payout"
            "amount" to "KUDOS:0.3"
        }
        client.post("/accounts/merchant/transactions") {
            basicAuth("merchant", "merchant-password")
            jsonBody(valid_req)
        }.assertOk()

        // Check body too big
        client.post("/accounts/merchant/transactions") {
            basicAuth("merchant", "merchant-password")
            jsonBody(valid_req) {
                "payto_uri" to "payto://iban/EXCHANGE-IBAN-XYZ?message=payout${"A".repeat(4100)}"
            }
        }.assertBadRequest()

        // Check body too big even after compression
        client.post("/accounts/merchant/transactions") {
            basicAuth("merchant", "merchant-password")
            jsonBody(valid_req, deflate = true) {
                "payto_uri" to "payto://iban/EXCHANGE-IBAN-XYZ?message=payout${"A".repeat(4100)}"
            }
        }.assertBadRequest()
    }
}



