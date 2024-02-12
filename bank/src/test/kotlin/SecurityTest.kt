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
import io.ktor.http.content.*
import io.ktor.server.engine.*
import io.ktor.server.testing.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test
import tech.libeufin.bank.*
import tech.libeufin.common.*
import tech.libeufin.common.*
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream

inline fun <reified B> HttpRequestBuilder.jsonDeflate(b: B) {
    val json = Json.encodeToString(kotlinx.serialization.serializer<B>(), b);
    contentType(ContentType.Application.Json)
    headers.set(HttpHeaders.ContentEncoding, "deflate")
    val bos = ByteArrayOutputStream()
    val ios = DeflaterOutputStream(bos)
    ios.write(json.toByteArray())
    ios.finish()
    setBody(bos.toByteArray())
}

class SecurityTest {
    @Test
    fun bodySizeLimit() = bankSetup { _ ->
        val valid_req = obj {
            "payto_uri" to "$exchangePayto?message=payout"
            "amount" to "KUDOS:0.3"
        }
        client.postA("/accounts/merchant/transactions") {
            json(valid_req)
        }.assertOk()

        // Check body too big
        client.postA("/accounts/merchant/transactions") {
            json(valid_req) {
                "payto_uri" to "$exchangePayto?message=payout${"A".repeat(4100)}"
            }
        }.assertBadRequest()

        // Check body too big even after compression
        client.postA("/accounts/merchant/transactions") {
            jsonDeflate(obj(valid_req) {
                "payto_uri" to "$exchangePayto?message=payout${"A".repeat(4100)}"
            })
        }.assertBadRequest()

        // Check uknown encoding
        client.postA("/accounts/merchant/transactions") {
            headers.set(HttpHeaders.ContentEncoding, "unknown")
            json(valid_req)
        }.assertStatus(HttpStatusCode.UnsupportedMediaType, TalerErrorCode.GENERIC_COMPRESSION_INVALID)
    }
}



