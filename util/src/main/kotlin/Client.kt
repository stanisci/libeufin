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

package tech.libeufin.util

import io.ktor.http.*
import kotlinx.serialization.json.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import kotlin.test.assertEquals
import net.taler.common.errorcodes.TalerErrorCode

/* ----- Json DSL ----- */

inline fun obj(from: JsonObject = JsonObject(emptyMap()), builderAction: JsonBuilder.() -> Unit): JsonObject {
    val builder = JsonBuilder(from)
    builder.apply(builderAction)
    return JsonObject(builder.content)
}

class JsonBuilder(from: JsonObject) {
    val content: MutableMap<String, JsonElement> = from.toMutableMap()

    infix inline fun <reified T> String.to(v: T) {
        val json = Json.encodeToJsonElement(kotlinx.serialization.serializer<T>(), v);
        content.put(this, json)
    }
}

/* ----- Json body helper ----- */

inline fun <reified B> HttpRequestBuilder.json(b: B, deflate: Boolean = false) {
    val json = Json.encodeToString(kotlinx.serialization.serializer<B>(), b);
    contentType(ContentType.Application.Json)
    if (deflate) {
        headers.set("Content-Encoding", "deflate")
        val bos = ByteArrayOutputStream()
        val ios = DeflaterOutputStream(bos)
        ios.write(json.toByteArray())
        ios.finish()
        setBody(bos.toByteArray())
    } else {
        setBody(json)
    }
}

inline fun HttpRequestBuilder.json(
    from: JsonObject = JsonObject(emptyMap()), 
    deflate: Boolean = false, 
    builderAction: JsonBuilder.() -> Unit
) {
    json(obj(from, builderAction), deflate)
}

inline suspend fun <reified B> HttpResponse.json(): B =
    Json.decodeFromString(kotlinx.serialization.serializer<B>(), bodyAsText())

inline suspend fun <reified B> HttpResponse.assertOkJson(lambda: (B) -> Unit = {}): B {
    assertEquals(status, HttpStatusCode.OK)
    val body = json<B>()
    lambda(body)
    return body
}