/*
 * This file is part of LibEuFin.
 * Copyright (C) 2019 Stanisci and Dold.

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
import net.taler.common.errorcodes.TalerErrorCode
import net.taler.wallet.crypto.Base32Crockford
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.util.*
import java.net.URL
import java.time.Instant
import java.util.*


const val AMOUNT_FRACTION_BASE = 100000000

private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.bank.helpers")

fun ApplicationCall.expectUriComponent(componentName: String) =
    this.maybeUriComponent(componentName) ?: throw badRequest(
        hint = "No username found in the URI", talerErrorCode = TalerErrorCode.TALER_EC_GENERIC_PARAMETER_MISSING
    )

// Get the auth token (stripped of the bearer-token:-prefix)
// IF the call was authenticated with it.
fun ApplicationCall.getAuthToken(): String? {
    val h = getAuthorizationRawHeader(this.request) ?: return null
    val authDetails = getAuthorizationDetails(h) ?: throw badRequest(
        "Authorization header is malformed.", TalerErrorCode.TALER_EC_GENERIC_HTTP_HEADERS_MALFORMED
    )
    if (authDetails.scheme == "Bearer") return splitBearerToken(authDetails.content) ?: throw throw badRequest(
        "Authorization header is malformed (could not strip the prefix from Bearer token).",
        TalerErrorCode.TALER_EC_GENERIC_HTTP_HEADERS_MALFORMED
    )
    return null // Not a Bearer token case.
}

/**
 * Performs the HTTP basic authentication.  Returns the
 * authenticated customer on success, or null otherwise.
 */
fun doBasicAuth(db: Database, encodedCredentials: String): Customer? {
    val plainUserAndPass = String(base64ToBytes(encodedCredentials), Charsets.UTF_8) // :-separated
    val userAndPassSplit = plainUserAndPass.split(
        ":",
        /**
         * this parameter allows colons to occur in passwords.
         * Without this, passwords that have colons would be split
         * and become meaningless.
         */
        limit = 2
    )
    if (userAndPassSplit.size != 2) throw LibeufinBankException(
        httpStatus = HttpStatusCode.BadRequest, talerError = TalerError(
            code = TalerErrorCode.TALER_EC_GENERIC_HTTP_HEADERS_MALFORMED.code,
            "Malformed Basic auth credentials found in the Authorization header."
        )
    )
    val login = userAndPassSplit[0]
    val plainPassword = userAndPassSplit[1]
    val maybeCustomer = db.customerGetFromLogin(login) ?: throw unauthorized()
    if (!CryptoUtil.checkpw(plainPassword, maybeCustomer.passwordHash)) return null
    return maybeCustomer
}

/**
 * This function takes a prefixed Bearer token, removes the
 * secret-token:-prefix and returns it.  Returns null, if the
 * input is invalid.
 */
private fun splitBearerToken(tok: String): String? {
    val tokenSplit = tok.split(":", limit = 2)
    if (tokenSplit.size != 2) return null
    if (tokenSplit[0] != "secret-token") return null
    return tokenSplit[1]
}

/* Performs the secret-token authentication.  Returns the
 * authenticated customer on success, null otherwise. */
fun doTokenAuth(
    db: Database,
    token: String,
    requiredScope: TokenScope,
): Customer? {
    val bareToken = splitBearerToken(token) ?: throw badRequest(
        "Bearer token malformed",
        talerErrorCode = TalerErrorCode.TALER_EC_GENERIC_HTTP_HEADERS_MALFORMED
    )
    val tokenBytes = try {
        Base32Crockford.decode(bareToken)
    } catch (e: Exception) {
        throw badRequest(
            e.message, TalerErrorCode.TALER_EC_GENERIC_HTTP_HEADERS_MALFORMED
        )
    }
    val maybeToken: BearerToken? = db.bearerTokenGet(tokenBytes)
    if (maybeToken == null) {
        logger.error("Auth token not found")
        return null
    }
    if (maybeToken.expirationTime.isBefore(Instant.now())) {
        logger.error("Auth token is expired")
        return null
    }
    if (maybeToken.scope == TokenScope.readonly && requiredScope == TokenScope.readwrite) {
        logger.error("Auth token has insufficient scope")
        return null
    }
    if (!maybeToken.isRefreshable && requiredScope == TokenScope.refreshable) {
        logger.error("Could not refresh unrefreshable token")
        return null
    }
    // Getting the related username.
    return db.customerGetFromRowId(maybeToken.bankCustomer) ?: throw LibeufinBankException(
        httpStatus = HttpStatusCode.InternalServerError, talerError = TalerError(
            code = TalerErrorCode.TALER_EC_GENERIC_INTERNAL_INVARIANT_FAILURE.code,
            hint = "Customer not found, despite token mentions it.",
        )
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

// Generates a new Payto-URI with IBAN scheme.
fun genIbanPaytoUri(): String = "payto://iban/SANDBOXX/${getIban()}"

// Parses Taler amount, returning null if the input is invalid.
fun parseTalerAmount2(
    amount: String, fracDigits: FracDigits
): TalerAmount? {
    val format = when (fracDigits) {
        FracDigits.TWO -> "^([A-Z]+):([0-9]+)(\\.[0-9][0-9]?)?$"
        FracDigits.EIGHT -> "^([A-Z]+):([0-9]+)(\\.[0-9][0-9]?[0-9]?[0-9]?[0-9]?[0-9]?[0-9]?[0-9]?)?$"
    }
    val match = Regex(format).find(amount) ?: return null
    val _value = match.destructured.component2()
    // Fraction is at most 8 digits, so it's always < than MAX_INT.
    val fraction: Int = match.destructured.component3().run {
        var frac = 0
        var power = AMOUNT_FRACTION_BASE
        if (this.isNotEmpty())
        // Skips the dot and processes the fractional chars.
            this.substring(1).forEach { chr ->
                power /= 10
                frac += power * chr.digitToInt()
            }
        return@run frac
    }
    val value: Long = try {
        _value.toLong()
    } catch (e: NumberFormatException) {
        return null
    }
    return TalerAmount(
        value = value, frac = fraction, currency = match.destructured.component1()
    )
}

/**
 * This helper takes the serialized version of a Taler Amount
 * type and parses it into Libeufin's internal representation.
 * It returns a TalerAmount type, or throws a LibeufinBankException
 * it the input is invalid.  Such exception will be then caught by
 * Ktor, transformed into the appropriate HTTP error type, and finally
 * responded to the client.
 */
fun parseTalerAmount(
    amount: String, fracDigits: FracDigits = FracDigits.EIGHT // FIXME: fracDigits should come from config.
): TalerAmount {
    val maybeAmount = parseTalerAmount2(amount, fracDigits) ?: throw LibeufinBankException(
        httpStatus = HttpStatusCode.BadRequest, talerError = TalerError(
            code = TalerErrorCode.TALER_EC_BANK_BAD_FORMAT_AMOUNT.code, hint = "Invalid amount: $amount"
        )
    )
    return maybeAmount
}

private fun normalizeAmount(amt: TalerAmount): TalerAmount {
    if (amt.frac > AMOUNT_FRACTION_BASE) {
        val normalValue = amt.value + (amt.frac / AMOUNT_FRACTION_BASE)
        val normalFrac = amt.frac % AMOUNT_FRACTION_BASE
        return TalerAmount(
            value = normalValue, frac = normalFrac, currency = amt.currency
        )
    }
    return amt
}


// Adds two amounts and returns the normalized version.
private fun amountAdd(first: TalerAmount, second: TalerAmount): TalerAmount {
    if (first.currency != second.currency) throw badRequest(
        "Currency mismatch, balance '${first.currency}', price '${second.currency}'",
        TalerErrorCode.TALER_EC_GENERIC_CURRENCY_MISMATCH
    )
    val valueAdd = first.value + second.value
    if (valueAdd < first.value) throw badRequest("Amount value overflowed")
    val fracAdd = first.frac + second.frac
    if (fracAdd < first.frac) throw badRequest("Amount fraction overflowed")
    return normalizeAmount(
        TalerAmount(
            value = valueAdd, frac = fracAdd, currency = first.currency
        )
    )
}

/**
 * Checks whether the balance could cover the due amount.  Returns true
 * when it does, false otherwise.  Note: this function is only a checker,
 * meaning that no actual business state should change after it runs.
 * The place where business states change is in the SQL that's loaded in
 * the database.
 */
fun isBalanceEnough(
    balance: TalerAmount, due: TalerAmount, maxDebt: TalerAmount, hasBalanceDebt: Boolean
): Boolean {
    val normalMaxDebt = normalizeAmount(maxDebt) // Very unlikely to be needed.
    if (hasBalanceDebt) {
        val chargedBalance = amountAdd(balance, due)
        if (chargedBalance.value > normalMaxDebt.value) return false // max debt surpassed
        if ((chargedBalance.value == normalMaxDebt.value) && (chargedBalance.frac > maxDebt.frac)) return false
        return true
    }
    /**
     * Balance doesn't have debt, but it MIGHT get one.  The following
     * block calculates how much debt the balance would get, should a
     * subtraction of 'due' occur.
     */
    if (balance.currency != due.currency) throw badRequest(
        "Currency mismatch, balance '${balance.currency}', due '${due.currency}'",
        TalerErrorCode.TALER_EC_GENERIC_CURRENCY_MISMATCH
    )
    val valueDiff = if (balance.value < due.value) due.value - balance.value else 0L
    val fracDiff = if (balance.frac < due.frac) due.frac - balance.frac else 0
    // Getting the normalized version of such diff.
    val normalDiff = normalizeAmount(TalerAmount(valueDiff, fracDiff, balance.currency))
    // Failing if the normalized diff surpasses the max debt.
    if (normalDiff.value > normalMaxDebt.value) return false
    if ((normalDiff.value == normalMaxDebt.value) && (normalDiff.frac > normalMaxDebt.frac)) return false
    return true
}

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
fun getWithdrawal(db: Database, opIdParam: String): TalerWithdrawalOperation {
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

data class HistoryParams(
    val delta: Long, val start: Long, val poll_ms: Long
)

/**
 * Extracts the query parameters from "history-like" endpoints,
 * providing the defaults according to the API specification.
 */
fun getHistoryParams(req: ApplicationRequest): HistoryParams {
    val deltaParam: String =
        req.queryParameters["delta"] ?: throw MissingRequestParameterException(parameterName = "delta")
    val delta: Long = try {
        deltaParam.toLong()
    } catch (e: Exception) {
        logger.error(e.message)
        throw badRequest("Param 'delta' not a number")
    }
    // Note: minimum 'start' is zero, as database IDs start from 1.
    val start: Long = when (val param = req.queryParameters["start"]) {
        null -> if (delta >= 0) 0L else Long.MAX_VALUE
        else -> try {
            param.toLong()
        } catch (e: Exception) {
            logger.error(e.message)
            throw badRequest("Param 'start' not a number")
        }
    }
    val poll_ms: Long = when (val param = req.queryParameters["long_poll_ms"]) {
        null -> 0
        else -> try {
            param.toLong()
        } catch (e: Exception) {
            logger.error(e.message)
            throw badRequest("Param 'long_poll_ms' not a number")
        }
    }
    return HistoryParams(delta = delta, start = start, poll_ms = poll_ms)
}

/**
 * This function creates the admin account ONLY IF it was
 * NOT found in the database.  It sets it to a random password that
 * is only meant to be overridden by a dedicated CLI tool.
 *
 * It returns false in case of problems, true otherwise.
 */
fun maybeCreateAdminAccount(db: Database, ctx: BankApplicationContext): Boolean {
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
            internalPaytoUri = adminInternalPayto,
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