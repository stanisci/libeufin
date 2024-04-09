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

import io.ktor.http.*
import org.junit.Test
import tech.libeufin.bank.RevenueIncomingHistory
import tech.libeufin.common.*

class RevenueApiTest {
    // GET /accounts/{USERNAME}/taler-revenue/config
    @Test
    fun config() = bankSetup { _ ->
        authRoutine(HttpMethod.Get, "/accounts/merchant/taler-revenue/config")

        client.getA("/accounts/merchant/taler-revenue/config").assertOk()
    }

    // GET /accounts/{USERNAME}/taler-revenue/history
    @Test
    fun history() = bankSetup {
        setMaxDebt("exchange", "KUDOS:1000000")
        authRoutine(HttpMethod.Get, "/accounts/merchant/taler-revenue/history")
        historyRoutine<RevenueIncomingHistory>(
            url = "/accounts/merchant/taler-revenue/history",
            ids = { it.incoming_transactions.map { it.row_id } },
            registered = listOf(
                { 
                    // Transactions using clean transfer logic
                    transfer("KUDOS:10")
                },
                { 
                    // Common credit transactions
                    tx("exchange", "KUDOS:10", "merchant", "ignored")
                }
            ),
            ignored = listOf(
                {
                    // Ignore debit transactions
                    tx("merchant", "KUDOS:10", "customer")
                }
            )
        )
    }
}