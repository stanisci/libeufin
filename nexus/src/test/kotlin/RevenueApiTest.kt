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

import io.ktor.http.*
import org.junit.Test
import tech.libeufin.common.*
import tech.libeufin.nexus.*

class RevenueApiTest {
    // GET /taler-revenue/config
    @Test
    fun config() = serverSetup {
        authRoutine(HttpMethod.Get, "/taler-revenue/config")

        client.getA("/taler-revenue/config").assertOk()
    }

    // GET /taler-revenue/history
    @Test
    fun history() = serverSetup { db ->
        authRoutine(HttpMethod.Get, "/taler-revenue/history")

        historyRoutine<RevenueIncomingHistory>(
            url = "/taler-revenue/history",
            ids = { it.incoming_transactions.map { it.row_id } },
            registered = listOf(
                { 
                    // Transactions using clean transfer logic
                    talerableIn(db)
                },
                { 
                    // Common credit transactions
                    ingestIn(db)
                }
            ),
            ignored = listOf(
                {
                    // Ignore debit transactions
                    talerableOut(db)
                }
            )
        )
    }

    @Test
    fun noApi() = serverSetup("mini.conf") {
        client.getA("/taler-revenue/config").assertNotImplemented()
    }
}