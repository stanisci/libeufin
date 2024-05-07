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
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import tech.libeufin.common.*
import tech.libeufin.common.test.*
import kotlin.test.assertEquals


// Test endpoint is correctly authenticated 
suspend fun ApplicationTestBuilder.authRoutine(
    method: HttpMethod, 
    path: String
) { 
    // No header
    client.request(path) {
        this.method = method
    }.assertUnauthorized(TalerErrorCode.GENERIC_PARAMETER_MISSING)

    // Bad header
    client.request(path) {
        this.method = method
        headers["Authorization"] = "WTF"
    }.assertBadRequest(TalerErrorCode.GENERIC_HTTP_HEADERS_MALFORMED)

    // Bad token
    client.request(path) {
        this.method = method
        headers["Authorization"] = "Bearer bad-token"
    }.assertUnauthorized()

    // GLS deployment
    // - testing did work ?
    // token - basic bearer 
    // libeufin-nexus  
    // - wire gateway try camt.052 files
}


suspend inline fun <reified B> ApplicationTestBuilder.historyRoutine(
    url: String,
    crossinline ids: (B) -> List<Long>,
    registered: List<suspend () -> Unit>,
    ignored: List<suspend () -> Unit> = listOf(),
    polling: Boolean = true
) {
    abstractHistoryRoutine(ids, registered, ignored, polling) { params: String ->
        client.getA("$url?$params")
    }
}