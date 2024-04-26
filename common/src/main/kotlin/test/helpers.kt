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

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import tech.libeufin.common.*

/* ----- Assert ----- */

suspend fun assertTime(min: Int, max: Int, lambda: suspend () -> Unit) {
    val start = System.currentTimeMillis()
    lambda()
    val end = System.currentTimeMillis()
    val time = end - start
    assert(time >= min) { "Expected to last at least $min ms, lasted $time" }
    assert(time <= max) { "Expected to last at most $max ms, lasted $time" }
}

suspend inline fun <reified B> HttpResponse.assertHistoryIds(size: Int, ids: (B) -> List<Long>): B {
    assertOk()
    val body = json<B>()
    val history = ids(body)
    val params = PageParams.extract(call.request.url.parameters)

    // testing the size is like expected.
    assertEquals(size, history.size, "bad history length: $history")
    if (params.delta < 0) {
        // testing that the first id is at most the 'start' query param.
        assert(history[0] <= params.start) { "bad history start: $params $history" }
        // testing that the id decreases.
        if (history.size > 1)
            assert(history.windowed(2).all { (a, b) -> a > b }) { "bad history order: $history" }
    } else {
        // testing that the first id is at least the 'start' query param.
        assert(history[0] >= params.start) { "bad history start: $params $history" }
        // testing that the id increases.
        if (history.size > 1)
            assert(history.windowed(2).all { (a, b) -> a < b }) { "bad history order: $history" }
    }

    return body
}