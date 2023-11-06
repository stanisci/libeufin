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
    maybeUriComponent(componentName) ?: throw badRequest(
        "No username found in the URI", 
        TalerErrorCode.GENERIC_PARAMETER_MISSING
    )

/** Retrieve the bank account info for the selected username*/
suspend fun ApplicationCall.bankAccount(db: Database): BankAccount
    = db.bankAccountGetFromCustomerLogin(username) ?: throw notFound(
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

/**
 * This handler factors out the checking of the query param
 * and the retrieval of the related withdrawal database row.
 * It throws 404 if the operation is not found, and throws 400
 * if the query param doesn't parse into a UUID.  Currently
 * used by the Taler Web/SPA and Integration API handlers.
 */
suspend fun ApplicationCall.getWithdrawal(db: Database, name: String): TalerWithdrawalOperation {
    val opId = uuidUriComponent(name)
    val op = db.withdrawal.get(opId) ?: throw notFound(
        "Withdrawal operation $opId not found", 
        TalerErrorCode.BANK_TRANSACTION_NOT_FOUND
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

data class RateParams(
    val debit: TalerAmount?, val credit: TalerAmount?
) {
    companion object {
        fun extract(params: Parameters): RateParams {
            val debit = try {
                params["amount_debit"]?.run(::TalerAmount)
            } catch (e: Exception) {
                throw badRequest("Param 'amount_debit' not a taler amount")
            }
            val credit = try {
                params["amount_credit"]?.run(::TalerAmount)
            } catch (e: Exception) {
                throw badRequest("Param 'amount_credit' not a taler amount")
            }
            if (debit == null && credit == null) {
                throw badRequest("Either param 'amount_debit' or 'amount_credit' is required")
            } 
            return RateParams(debit, credit)
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
suspend fun maybeCreateAdminAccount(db: Database, ctx: BankConfig, pw: String? = null): Boolean {
    logger.debug("Creating admin's account")
    var pwStr = pw;
    if (pwStr == null) {
        val pwBuf = ByteArray(32)
        Random().nextBytes(pwBuf)
        pwStr = String(pwBuf, Charsets.UTF_8)
    }
    
    val res = db.accountCreate(
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
        CustomerCreationResult.BALANCE_INSUFFICIENT -> false
        CustomerCreationResult.CONFLICT_LOGIN -> true
        CustomerCreationResult.CONFLICT_PAY_TO -> false
        CustomerCreationResult.SUCCESS -> true
    }
}