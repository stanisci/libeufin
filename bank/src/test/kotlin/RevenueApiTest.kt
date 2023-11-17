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
        setMaxDebt("exchange", TalerAmount("KUDOS:1000000"))

        suspend fun HttpResponse.assertHistory(size: Int) {
            assertHistoryIds<MerchantIncomingHistory>(size) {
                it.incoming_transactions.map { it.row_id }
            }
        }

        // TODO auth routine

        // Check error when no transactions
        client.getA("/accounts/merchant/taler-revenue/history?delta=7")
            .assertNoContent()

        // Gen three transactions using clean transfer logic
        repeat(3) {
            transfer("KUDOS:10")
        }
        // Should not show up in the revenue API history
        tx("exchange", "KUDOS:10", "merchant", "bogus")
        // Merchant pays exchange once, but that should not appear in the result
        tx("merchant", "KUDOS:10", "exchange", "ignored")
        // Gen two transactions using raw bank transaction logic
        repeat(2) {
            tx("exchange", "KUDOS:10", "merchant", OutgoingTxMetadata(randShortHashCode(), ExchangeUrl("http://exchange.example.com/")).encode())
        }

        // Check ignore bogus subject
        client.getA("/accounts/merchant/taler-revenue/history?delta=7")
            .assertHistory(5)
        
        // Check skip bogus subject
        client.getA("/accounts/merchant/taler-revenue/history?delta=5")
            .assertHistory(5)

        // Check no useless polling
        assertTime(0, 100) {
            client.getA("/accounts/merchant/taler-revenue/history?delta=-6&start=14&long_poll_ms=1000")
                .assertHistory(5)
        }

        // Check no polling when find transaction
        assertTime(0, 100) {
            client.getA("/accounts/merchant/taler-revenue/history?delta=6&long_poll_ms=1000")
                .assertHistory(5)
        }

        coroutineScope {
            launch {  // Check polling succeed forward
                assertTime(100, 200) {
                    client.getA("/accounts/merchant/taler-revenue/history?delta=2&start=13&long_poll_ms=1000")
                        .assertHistory(1)
                }
            }
            launch {  // Check polling timeout forward
                assertTime(200, 300) {
                    client.getA("/accounts/merchant/taler-revenue/history?delta=1&start=16&long_poll_ms=200")
                        .assertNoContent()
                }
            }
            delay(100)
            transfer("KUDOS:10")
        }

        // Testing ranges.
        repeat(20) {
            transfer("KUDOS:10")
        }

        // forward range:
        client.getA("/accounts/merchant/taler-revenue/history?delta=10&start=20") 
            .assertHistory(10)

        // backward range:
        client.getA("/accounts/merchant/taler-revenue/history?delta=-10&start=25")
            .assertHistory(10)
    }
}