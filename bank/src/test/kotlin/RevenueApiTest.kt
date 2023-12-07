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
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test
import tech.libeufin.bank.*

class RevenueApiTest {
    // GET /accounts/{USERNAME}/taler-revenue/history
    @Test
    fun history() = bankSetup {
        setMaxDebt("exchange", "KUDOS:1000000")
        authRoutine(HttpMethod.Get, "/accounts/merchant/taler-revenue/history")
        historyRoutine<MerchantIncomingHistory>(
            url = "/accounts/merchant/taler-revenue/history",
            ids = { it.incoming_transactions.map { it.row_id } },
            registered = listOf(
                { 
                    // Transactions using clean transfer logic
                    transfer("KUDOS:10")
                }
            ),
            ignored = listOf(
                {
                    // Ignore manual incoming transaction
                    tx("exchange", "KUDOS:10", "merchant", "${randShortHashCode()} http://exchange.example.com/")
                },
                {
                    // Ignore malformed incoming transaction
                    tx("merchant", "KUDOS:10", "exchange", "ignored")
                },
                {
                    // Ignore malformed outgoing transaction
                    tx("exchange", "KUDOS:10", "merchant", "ignored")
                }
            )
        )
    }
}