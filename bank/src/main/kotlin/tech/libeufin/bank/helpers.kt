/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023 Stanisci and Dold.

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
import io.ktor.server.util.*
import io.ktor.util.valuesOf
import net.taler.common.errorcodes.TalerErrorCode
import net.taler.wallet.crypto.Base32Crockford
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.util.*
import java.net.URL
import java.time.*
import java.time.temporal.*
import java.util.*

private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.bank.helpers")
val reservedAccounts = setOf("admin", "bank")

fun ApplicationCall.expectUriComponent(componentName: String) =
    this.maybeUriComponent(componentName) ?: throw badRequest(
        hint = "No username found in the URI", talerErrorCode = TalerErrorCode.TALER_EC_GENERIC_PARAMETER_MISSING
    )

typealias ResourceName = String

/**
 * Factors out the retrieval of the resource name from
 * the URI.  The resource looked for defaults to "USERNAME"
 * as this is frequently mentioned resource along the endpoints.
 *
 * This helper is recommended because it returns a ResourceName
 * type that then offers the ".canI()" helper to check if the user
 * has the rights on the resource.
 */
fun ApplicationCall.getResourceName(param: String): ResourceName =
    this.expectUriComponent(param)
    

/** Get account login from path */
suspend fun ApplicationCall.accountLogin(): String = getResourceName("USERNAME")

/** Retrieve the bank account info for the selected username*/
suspend fun ApplicationCall.bankAccount(db: Database): BankAccount {
    val login = accountLogin()
    return db.bankAccountGetFromCustomerLogin(login) ?: throw notFound(
        hint = "Bank account for customer $login not found",
        talerEc = TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT
    )
}

fun forbidden(
    hint: String = "No rights on the resource",
    talerErrorCode: TalerErrorCode = TalerErrorCode.TALER_EC_END
): LibeufinBankException = LibeufinBankException(
    httpStatus = HttpStatusCode.Forbidden, talerError = TalerError(
        code = talerErrorCode.code, hint = hint
    )
)

fun unauthorized(hint: String = "Login failed"): LibeufinBankException = LibeufinBankException(
    httpStatus = HttpStatusCode.Unauthorized, talerError = TalerError(
        code = TalerErrorCode.TALER_EC_GENERIC_UNAUTHORIZED.code, hint = hint
    )
)

fun internalServerError(hint: String?): LibeufinBankException = LibeufinBankException(
    httpStatus = HttpStatusCode.InternalServerError, talerError = TalerError(
        code = TalerErrorCode.TALER_EC_GENERIC_INTERNAL_INVARIANT_FAILURE.code, hint = hint
    )
)

fun notFound(
    hint: String?,
    talerEc: TalerErrorCode
): LibeufinBankException = LibeufinBankException(
    httpStatus = HttpStatusCode.NotFound, talerError = TalerError(
        code = talerEc.code, hint = hint
    )
)

fun conflict(
    hint: String?, talerEc: TalerErrorCode
): LibeufinBankException = LibeufinBankException(
    httpStatus = HttpStatusCode.Conflict, talerError = TalerError(
        code = talerEc.code, hint = hint
    )
)

fun badRequest(
    hint: String? = null, talerErrorCode: TalerErrorCode = TalerErrorCode.TALER_EC_GENERIC_JSON_INVALID
): LibeufinBankException = LibeufinBankException(
    httpStatus = HttpStatusCode.BadRequest, talerError = TalerError(
        code = talerErrorCode.code, hint = hint
    )
)

fun checkInternalCurrency(ctx: BankApplicationContext, amount: TalerAmount) {
    if (amount.currency != ctx.currency) throw badRequest(
        "Wrong currency: expected internal currency ${ctx.currency} got ${amount.currency}",
        talerErrorCode = TalerErrorCode.TALER_EC_GENERIC_CURRENCY_MISMATCH
    )
}

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
    baseUrl: String, wopId: String
): String {
    return baseUrl.replace("{woid}", wopId)
}


/**
 * This handler factors out the checking of the query param
 * and the retrieval of the related withdrawal database row.
 * It throws 404 if the operation is not found, and throws 400
 * if the query param doesn't parse into a UUID.  Currently
 * used by the Taler Web/SPA and Integration API handlers.
 */
suspend fun getWithdrawal(db: Database, opIdParam: String): TalerWithdrawalOperation {
    val opId = try {
        UUID.fromString(opIdParam)
    } catch (e: Exception) {
        logger.error(e.message)
        throw badRequest("withdrawal_id query parameter was malformed")
    }
    val op = db.talerWithdrawalGet(opId) ?: throw notFound(
        hint = "Withdrawal operation $opIdParam not found", talerEc = TalerErrorCode.TALER_EC_END
    )
    return op
}

enum class Timeframe {
    hour,
    day,
    month,
    year
}

data class MonitorParams(
    val timeframe: Timeframe,
    val which: Int?
) {
    companion object {
        fun extract(params: Parameters): MonitorParams {
            val timeframe = Timeframe.valueOf(params["timeframe"] ?: "hour")
            val which = params["which"]?.run { toIntOrNull() ?: throw badRequest("Param 'which' not a number") }
            if (which != null) {
                val lastDayOfMonth = OffsetDateTime.now(ZoneOffset.UTC).with(TemporalAdjusters.lastDayOfMonth()).dayOfMonth
                when {
                    timeframe == Timeframe.hour && (0 > which || which > 23) -> 
                        throw badRequest("For hour timestamp param 'which' must be between 00 to 23")
                    timeframe == Timeframe.day && (1 > which || which > 23) -> 
                        throw badRequest("For day timestamp param 'which' must be between 1 to $lastDayOfMonth")
                    timeframe == Timeframe.month && (1 > which || which > lastDayOfMonth) -> 
                        throw badRequest("For month timestamp param 'which' must be between 1 to 12")
                    timeframe == Timeframe.year && (1 > which|| which > 9999) -> 
                        throw badRequest("For year timestamp param 'which' must be between 0001 to 9999")
                    else -> {}
                }
            }
            return MonitorParams(timeframe, which)
        }
    }
}

data class HistoryParams(
    val delta: Int, val start: Long, val poll_ms: Long
) {
    companion object {
        fun extract(params: Parameters): HistoryParams {
            val deltaParam: String =
                params["delta"] ?: throw MissingRequestParameterException(parameterName = "delta")
            val delta: Int = deltaParam.toIntOrNull() ?: throw badRequest("Param 'delta' not a number")
            // Note: minimum 'start' is zero, as database IDs start from 1.
            val start: Long = when (val param = params["start"]) {
                null -> if (delta >= 0) 0L else Long.MAX_VALUE
                else -> param.toLongOrNull() ?: throw badRequest("Param 'start' not a number")
            }
            val poll_ms: Long = when (val param = params["long_poll_ms"]) {
                null -> 0
                else -> param.toLongOrNull() ?: throw badRequest("Param 'long_poll_ms' not a number")
            }
            // TODO check params range
            return HistoryParams(delta = delta, start = start, poll_ms = poll_ms)
        }
    }
}

/**
 * This function creates the admin account ONLY IF it was
 * NOT found in the database.  It sets it to a random password that
 * is only meant to be overridden by a dedicated CLI tool.
 *
 * It returns false in case of problems, true otherwise.
 */
suspend fun maybeCreateAdminAccount(db: Database, ctx: BankApplicationContext): Boolean {
    val maybeAdminCustomer = db.customerGetFromLogin("admin")
    val adminCustomerId: Long = if (maybeAdminCustomer == null) {
        logger.debug("Creating admin's customer row")
        val pwBuf = ByteArray(32)
        Random().nextBytes(pwBuf)
        val adminCustomer = Customer(
            login = "admin",
            /**
             * Hashing the password helps to avoid the "password not hashed"
             * error, in case the admin tries to authenticate.
             */
            passwordHash = CryptoUtil.hashpw(String(pwBuf, Charsets.UTF_8)), name = "Bank administrator"
        )
        val rowId = db.customerCreate(adminCustomer)
        if (rowId == null) {
            logger.error("Could not create the admin customer row.")
            return false
        }
        rowId
    } else maybeAdminCustomer.expectRowId()
    val maybeAdminBankAccount = db.bankAccountGetFromOwnerId(adminCustomerId)
    if (maybeAdminBankAccount == null) {
        logger.info("Creating admin bank account")
        val adminMaxDebtObj = ctx.defaultAdminDebtLimit
        val adminInternalPayto = stripIbanPayto(genIbanPaytoUri())
        if (adminInternalPayto == null) {
            logger.error("Bank generated invalid payto URI for admin")
            return false
        }
        val adminBankAccount = BankAccount(
            hasDebt = false,
            internalPaytoUri = IbanPayTo(adminInternalPayto),
            owningCustomerId = adminCustomerId,
            isPublic = false,
            isTalerExchange = false,
            maxDebt = adminMaxDebtObj
        )
        if (db.bankAccountCreate(adminBankAccount) == null) {
            logger.error("Failed to creating admin bank account.")
            return false
        }
    }
    return true
}