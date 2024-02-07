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

package tech.libeufin.bank

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.util.*
import io.ktor.util.pipeline.PipelineContext
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import java.net.*
import java.time.*
import java.time.temporal.*
import java.util.*
import tech.libeufin.common.*
import tech.libeufin.bank.db.*
import tech.libeufin.bank.db.AccountDAO.*
import tech.libeufin.bank.auth.*

fun ApplicationCall.expectParameter(name: String) =
    parameters[name] ?: throw badRequest(
        "Missing '$name' param", 
        TalerErrorCode.GENERIC_PARAMETER_MISSING
    )

/** Retrieve the bank account info for the selected username*/
suspend fun ApplicationCall.bankInfo(db: Database, ctx: BankPaytoCtx): BankInfo
    = db.account.bankInfo(username, ctx) ?: throw unknownAccount(username)

/**
 *  Builds the taler://withdraw-URI.  Such URI will serve the requests
 *  from wallets, when they need to manage the operation.  For example,
 *  a URI like taler://withdraw/$BANK_URL/taler-integration/$WO_ID needs
 *  the bank to implement the Taler integratino API at the following base URL:
 *
 *      https://$BANK_URL/taler-integration
 */
fun ApplicationRequest.talerWithdrawUri(id: UUID) = url {
    protocol = URLProtocol(
        name = if (origin.scheme == "http") "taler+http" else "taler",
        defaultPort = -1
    )
    host = "withdraw"
    appendPathSegments(origin.serverHost)
    headers["X-Forward-Prefix"]?.let {
        appendPathSegments(it)
    }
    appendPathSegments("taler-integration", id.toString())
}

fun ApplicationRequest.withdrawConfirmUrl(id: UUID) = url {
    protocol = URLProtocol(
        name = origin.scheme,
        defaultPort = -1
    )
    host = origin.serverHost
    headers["X-Forward-Prefix"]?.let {
        appendPathSegments(it)
    }
    appendEncodedPathSegments("webui", "#", "operation", id.toString())
}

fun ApplicationCall.uuidParameter(name: String): UUID {
    try {
        return UUID.fromString(expectParameter(name))
    } catch (e: Exception) {
        throw badRequest("UUID uri component malformed: ${e.message}")
    }
}

fun ApplicationCall.longParameter(name: String): Long {
    try {
        return expectParameter(name).toLong()
    } catch (e: Exception) {
        throw badRequest("Long uri component malformed: ${e.message}")
    }
}

/**
 * This function creates the admin account ONLY IF it was
 * NOT found in the database.  It sets it to a random password that
 * is only meant to be overridden by a dedicated CLI tool.
 *
 * It returns false in case of problems, true otherwise.
 */
suspend fun createAdminAccount(db: Database, cfg: BankConfig, pw: String? = null): AccountCreationResult {
    var pwStr = pw;
    if (pwStr == null) {
        val pwBuf = ByteArray(32)
        Random().nextBytes(pwBuf)
        pwStr = String(pwBuf, Charsets.UTF_8)
    }
    
    val payto = when (cfg.wireMethod) {
        WireMethod.IBAN -> IbanPayto.rand()
        WireMethod.X_TALER_BANK -> XTalerBankPayto.forUsername("admin")
    }

    return db.account.create(
        login = "admin",
        password = pwStr,
        name = "Bank administrator",
        internalPayto = payto,
        isPublic = false,
        isTalerExchange = false,
        maxDebt = cfg.defaultDebtLimit,
        bonus = TalerAmount(0, 0, cfg.regionalCurrency),
        checkPaytoIdempotent = false,
        email = null,
        phone = null,
        cashoutPayto = null,
        tanChannel = null
    )
}

fun Route.intercept(callback: Route.() -> Unit, interceptor: suspend PipelineContext<Unit, ApplicationCall>.() -> Unit): Route {
    val subRoute = createChild(object : RouteSelector() {
        override fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation =
            RouteSelectorEvaluation.Constant
    })
    subRoute.intercept(ApplicationCallPipeline.Plugins) {
        interceptor()
        proceed()
    }
    
    callback(subRoute)
    return subRoute
}

fun Route.conditional(implemented: Boolean, callback: Route.() -> Unit): Route =
    intercept(callback) {
        if (!implemented) {
            throw libeufinError(HttpStatusCode.NotImplemented, "API not implemented", TalerErrorCode.END)
        }
    }

@Serializable(with = StoredUUID.Serializer::class)
data class StoredUUID(val value: UUID) {
    internal object Serializer : KSerializer<StoredUUID> {
        override val descriptor: SerialDescriptor =
                PrimitiveSerialDescriptor("StoredUUID", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: StoredUUID) {
            encoder.encodeString(value.value.toString())
        }

        override fun deserialize(decoder: Decoder): StoredUUID {
            val string = decoder.decodeString()
            return StoredUUID(UUID.fromString(string))
        }
    }
}
