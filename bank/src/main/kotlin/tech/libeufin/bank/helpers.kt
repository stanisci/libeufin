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
import java.net.URL
import java.time.*
import java.time.temporal.*
import java.util.*
import net.taler.common.errorcodes.TalerErrorCode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.bank.AccountDAO.*
import tech.libeufin.util.*

private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.bank.helpers")
val reservedAccounts = setOf("admin", "bank")

fun ApplicationCall.expectUriComponent(componentName: String) =
    maybeUriComponent(componentName) ?: throw badRequest(
        "No username found in the URI", 
        TalerErrorCode.GENERIC_PARAMETER_MISSING
    )

/** Retrieve the bank account info for the selected username*/
suspend fun ApplicationCall.bankInfo(db: Database): BankInfo
    = db.account.bankInfo(username) ?: throw notFound(
        "Bank account for customer $username not found",
        TalerErrorCode.BANK_UNKNOWN_ACCOUNT
    )

// Generates a new Payto-URI with IBAN scheme.
fun genIbanPaytoUri(): String = "payto://iban/SANDBOXX/${getIban()}"

/**
 *  Builds the taler://withdraw-URI.  Such URI will serve the requests
 *  from wallets, when they need to manage the operation.  For example,
 *  a URI like taler://withdraw/$BANK_URL/taler-integration/$WO_ID needs
 *  the bank to implement the Taler integratino API at the following base URL:
 *
 *      https://$BANK_URL/taler-integration
 */
fun getTalerWithdrawUri(baseUrl: String, woId: String) = url {
    val baseUrlObj = URL(baseUrl)
    protocol = URLProtocol(
        name = "taler".plus(if (baseUrlObj.protocol.lowercase() == "http") "+http" else ""), defaultPort = -1
    )
    host = "withdraw"
    val pathSegments = mutableListOf(
        // adds the hostname(+port) of the actual bank that will serve the withdrawal request.
        baseUrlObj.host.plus(
            if (baseUrlObj.port != -1) ":${baseUrlObj.port}"
            else ""
        )
    )
    // Removing potential double slashes.
    baseUrlObj.path.split("/").forEach {
        if (it.isNotEmpty()) pathSegments.add(it)
    }
    pathSegments.add("taler-integration/${woId}")
    this.appendPathSegments(pathSegments)
}

// Builds a withdrawal confirm URL.
fun getWithdrawalConfirmUrl(
    baseUrl: String, wopId: UUID
): String {
    return baseUrl.replace("{woid}", wopId.toString())
}

fun ApplicationCall.uuidUriComponent(name: String): UUID {
    try {
        return UUID.fromString(expectUriComponent(name))
    } catch (e: Exception) {
        logger.error(e.message)
        throw badRequest("UUID uri component malformed")
    }
}

fun ApplicationCall.longUriComponent(name: String): Long {
    try {
        return expectUriComponent(name).toLong()
    } catch (e: Exception) {
        logger.error(e.message)
        throw badRequest("UUID uri component malformed")
    }
}

/**
 * This function creates the admin account ONLY IF it was
 * NOT found in the database.  It sets it to a random password that
 * is only meant to be overridden by a dedicated CLI tool.
 *
 * It returns false in case of problems, true otherwise.
 */
suspend fun maybeCreateAdminAccount(db: Database, ctx: BankConfig, pw: String? = null): Boolean {
    logger.debug("Creating admin's account")
    var pwStr = pw;
    if (pwStr == null) {
        val pwBuf = ByteArray(32)
        Random().nextBytes(pwBuf)
        pwStr = String(pwBuf, Charsets.UTF_8)
    }
    
    val res = db.account.create(
        login = "admin",
        password = pwStr,
        name = "Bank administrator",
        internalPaytoUri = IbanPayTo(genIbanPaytoUri()),
        isPublic = false,
        isTalerExchange = false,
        maxDebt = ctx.defaultAdminDebtLimit,
        bonus = null
    )
    return when (res) {
        AccountCreationResult.BonusBalanceInsufficient -> false
        AccountCreationResult.LoginReuse -> true
        AccountCreationResult.PayToReuse -> false
        AccountCreationResult.Success -> true
    }
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