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

package tech.libeufin.common

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertEquals

/* ----- Json DSL ----- */

inline fun obj(from: JsonObject = JsonObject(emptyMap()), builderAction: JsonBuilder.() -> Unit): JsonObject {
    val builder = JsonBuilder(from)
    builder.apply(builderAction)
    return JsonObject(builder.content)
}

class JsonBuilder(from: JsonObject) {
    val content: MutableMap<String, JsonElement> = from.toMutableMap()

    inline infix fun <reified T> String.to(v: T) {
        val json = Json.encodeToJsonElement(kotlinx.serialization.serializer<T>(), v)
        content[this] = json
    }
}

/* ----- Json body helper ----- */

inline fun <reified B> HttpRequestBuilder.json(b: B) {
    val json = Json.encodeToString(kotlinx.serialization.serializer<B>(), b)
    contentType(ContentType.Application.Json)
    setBody(json)
}

inline fun HttpRequestBuilder.json(
    from: JsonObject = JsonObject(emptyMap()),
    builderAction: JsonBuilder.() -> Unit
) {
    json(obj(from, builderAction))
}

suspend inline fun <reified B> HttpResponse.json(): B =
    Json.decodeFromString(kotlinx.serialization.serializer<B>(), bodyAsText())

suspend inline fun <reified B> HttpResponse.assertOkJson(lambda: (B) -> Unit = {}): B {
    assertEquals(HttpStatusCode.OK, status)
    val body = json<B>()
    lambda(body)
    return body
}

/* ----- Assert ----- */

suspend fun HttpResponse.assertStatus(status: HttpStatusCode, err: TalerErrorCode?): HttpResponse {
    assertEquals(status, this.status, "$err")
    if (err != null) {
        val body = json<TalerError>()
        assertEquals(err.code, body.code)
    }
    return this
}
suspend fun HttpResponse.assertOk(): HttpResponse
    = assertStatus(HttpStatusCode.OK, null)
suspend fun HttpResponse.assertNoContent(): HttpResponse 
    = assertStatus(HttpStatusCode.NoContent, null)
suspend fun HttpResponse.assertAccepted(): HttpResponse 
    = assertStatus(HttpStatusCode.Accepted, null)
suspend fun HttpResponse.assertNotFound(err: TalerErrorCode): HttpResponse 
    = assertStatus(HttpStatusCode.NotFound, err)
suspend fun HttpResponse.assertUnauthorized(err: TalerErrorCode = TalerErrorCode.GENERIC_UNAUTHORIZED): HttpResponse 
    = assertStatus(HttpStatusCode.Unauthorized, err)
suspend fun HttpResponse.assertConflict(err: TalerErrorCode): HttpResponse 
    = assertStatus(HttpStatusCode.Conflict, err)
suspend fun HttpResponse.assertBadRequest(err: TalerErrorCode = TalerErrorCode.GENERIC_JSON_INVALID): HttpResponse 
    = assertStatus(HttpStatusCode.BadRequest, err)
suspend fun HttpResponse.assertForbidden(err: TalerErrorCode): HttpResponse 
    = assertStatus(HttpStatusCode.Forbidden, err)
suspend fun HttpResponse.assertNotImplemented(err: TalerErrorCode = TalerErrorCode.END): HttpResponse 
    = assertStatus(HttpStatusCode.NotImplemented, err)
