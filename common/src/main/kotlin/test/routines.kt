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

package tech.libeufin.common.test

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.libeufin.common.*

suspend inline fun <reified B> ApplicationTestBuilder.abstractHistoryRoutine(
    crossinline ids: (B) -> List<Long>,
    registered: List<suspend () -> Unit>,
    ignored: List<suspend () -> Unit> = listOf(),
    polling: Boolean = true,
    crossinline history: suspend (String) -> HttpResponse,
) {
    // Check history is following specs
    val assertHistory: suspend HttpResponse.(Int) -> Unit = { size: Int ->
        assertHistoryIds<B>(size, ids)
    }
    // Get latest registered id
    val latestId: suspend () -> Long = {
        history("delta=-1").assertOkJson<B>().run { ids(this)[0] }
    }

    // Check error when no transactions
    history("delta=7").assertNoContent()

    // Run interleaved registered and ignore transactions
    val registered_iter = registered.iterator()
    val ignored_iter = ignored.iterator()
    while (registered_iter.hasNext() || ignored_iter.hasNext()) {
        if (registered_iter.hasNext()) registered_iter.next()()
        if (ignored_iter.hasNext()) ignored_iter.next()()
    }

    val nbRegistered = registered.size
    val nbIgnored = ignored.size
    val nbTotal = nbRegistered + nbIgnored

    // Check ignored
    history("delta=$nbTotal").assertHistory(nbRegistered)
    // Check skip ignored
    history("delta=$nbRegistered").assertHistory(nbRegistered)

    if (polling) {
        // Check no polling when we cannot have more transactions
        assertTime(0, 100) {
            history("delta=-${nbRegistered+1}&long_poll_ms=1000")
                .assertHistory(nbRegistered)
        }
        // Check no polling when already find transactions even if less than delta
        assertTime(0, 100) {
            history("delta=${nbRegistered+1}&long_poll_ms=1000")
                .assertHistory(nbRegistered)
        }

        // Check polling
        coroutineScope {
            val id = latestId()
            launch {  // Check polling succeed
                assertTime(100, 200) {
                    history("delta=2&start=$id&long_poll_ms=1000")
                        .assertHistory(1)
                }
            }
            launch {  // Check polling timeout
                assertTime(200, 300) {
                    history("delta=1&start=${id+nbTotal*3}&long_poll_ms=200")
                        .assertNoContent()
                }
            }
            delay(100)
            registered[0]()
        }

        // Test triggers
        for (register in registered) {
            coroutineScope {
                val id = latestId()
                launch {
                    assertTime(100, 200) {
                        history("delta=7&start=$id&long_poll_ms=1000") 
                            .assertHistory(1)
                    }
                }
                delay(100)
                register()
            }
        }

        // Test doesn't trigger
        coroutineScope {
            val id = latestId()
            launch {
                assertTime(200, 300) {
                    history("delta=7&start=$id&long_poll_ms=200") 
                        .assertNoContent()
                }
            }
            delay(100)
            for (ignore in ignored) {
                ignore()
            }
        }
    }

    // Testing ranges.
    repeat(20) {
        registered[0]()
    }
    val id = latestId()
    // Default
    history("").assertHistory(20)
    // forward range:
    history("delta=10").assertHistory(10)
    history("delta=10&start=4").assertHistory(10)
    // backward range:
    history("delta=-10").assertHistory(10)
    history("delta=-10&start=${id-4}").assertHistory(10)
}