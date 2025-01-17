/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023-2024 Stanisci and Dold.

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
package tech.libeufin.bank

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.json.Json
import tech.libeufin.bank.auth.username
import tech.libeufin.bank.db.Database
import tech.libeufin.bank.db.TanDAO.Challenge
import java.security.SecureRandom
import java.text.DecimalFormat
import java.time.Instant

/**
 * Generate a TAN challenge for an [op] request with [body] and 
 * respond to the HTTP request with a TAN challenge.
 * 
 * If [channel] and [info] are present, they will be used 
 * to send the TAN code, otherwise defaults will be used.
 */
suspend inline fun <reified B> ApplicationCall.respondChallenge(
    db: Database, 
    op: Operation, 
    body: B, 
    channel: TanChannel? = null,
    info: String? = null
) {
    val json = Json.encodeToString(kotlinx.serialization.serializer<B>(), body)
    val code = Tan.genCode()
    val id = db.tan.new(
        login = username, 
        op = op,
        body = json,
        code = code,
        now = Instant.now(), 
        retryCounter = TAN_RETRY_COUNTER,
        validityPeriod = TAN_VALIDITY_PERIOD,
        channel = channel,
        info = info
    )
    respond(
        status = HttpStatusCode.Accepted,
        message = TanChallenge(id)
    )
}

/**
 * Retrieve a confirmed challenge and its body for [op] from the database
 * if the challenge header is defined, otherwise extract the HTTP body.
 */
suspend inline fun <reified B> ApplicationCall.receiveChallenge(
    db: Database,
    op: Operation
): Pair<B, Challenge?> {
    val id = request.headers["X-Challenge-Id"]?.toLongOrNull()
    return if (id != null) {
        val challenge = db.tan.challenge(id, username, op)!!
        Pair(Json.decodeFromString(challenge.body), challenge)
    } else {
        Pair(this.receive(), null)
    }
}

/**
 * Retrieve a confirmed challenge body for [op] if the challenge header is defined
 */
suspend fun ApplicationCall.checkChallenge(
    db: Database,
    op: Operation
): Challenge? {
    val id = request.headers["X-Challenge-Id"]?.toLongOrNull()
    return if (id != null) {
        db.tan.challenge(id, username, op)!!
    } else {
        null
    }
}

object Tan {
    private val CODE_FORMAT = DecimalFormat("00000000")
    private val SECURE_RNG = SecureRandom()

    /** Generate a secure random TAN code */
    fun genCode(): String {
        val rand = SECURE_RNG.nextInt(100000000)
        val code = CODE_FORMAT.format(rand)
        return code
    }
}

