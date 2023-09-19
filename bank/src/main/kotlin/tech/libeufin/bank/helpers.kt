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
import io.ktor.server.util.*
import net.taler.common.errorcodes.TalerErrorCode
import net.taler.wallet.crypto.Base32Crockford
import tech.libeufin.util.*
import java.lang.NumberFormatException
import java.net.URL

fun ApplicationCall.expectUriComponent(componentName: String) =
    this.maybeUriComponent(componentName) ?: throw badRequest(
        hint = "No username found in the URI",
        talerErrorCode = TalerErrorCode.TALER_EC_GENERIC_PARAMETER_MISSING
)
// Get the auth token (stripped of the bearer-token:-prefix)
// IF the call was authenticated with it.
fun ApplicationCall.getAuthToken(): String? {
    val h = getAuthorizationRawHeader(this.request) ?: return null
    val authDetails = getAuthorizationDetails(h) ?: throw badRequest(
        "Authorization header is malformed.",
        TalerErrorCode.TALER_EC_GENERIC_HTTP_HEADERS_MALFORMED
    )
    if (authDetails.scheme == "Bearer")
        return splitBearerToken(authDetails.content) ?: throw
        throw badRequest(
            "Authorization header is malformed (could not strip the prefix from Bearer token).",
            TalerErrorCode.TALER_EC_GENERIC_HTTP_HEADERS_MALFORMED
        )
    return null // Not a Bearer token case.
}


/**
 * Performs the HTTP basic authentication.  Returns the
 * authenticated customer on success, or null otherwise.
 */
fun doBasicAuth(encodedCredentials: String): Customer? {
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
    if (userAndPassSplit.size != 2)
        throw LibeufinBankException(
            httpStatus = HttpStatusCode.BadRequest,
            talerError = TalerError(
                code = TalerErrorCode.TALER_EC_GENERIC_HTTP_HEADERS_MALFORMED.code,
                "Malformed Basic auth credentials found in the Authorization header."
            )
        )
    val login = userAndPassSplit[0]
    val plainPassword = userAndPassSplit[1]
    val maybeCustomer = db.customerGetFromLogin(login) ?: return null
    if (!CryptoUtil.checkpw(plainPassword, maybeCustomer.passwordHash)) return null
    return maybeCustomer
}

/**
 * This function takes a prefixed Bearer token, removes the
 * bearer-token:-prefix and returns it.  Returns null, if the
 * input is invalid.
 */
private fun splitBearerToken(tok: String): String? {
    val tokenSplit = tok.split(":", limit = 2)
    if (tokenSplit.size != 2) return null
    if (tokenSplit[0] != "bearer-token") return null
    return tokenSplit[1]
}

/* Performs the bearer-token authentication.  Returns the
 * authenticated customer on success, null otherwise. */
fun doTokenAuth(
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
            e.message,
            TalerErrorCode.TALER_EC_GENERIC_HTTP_HEADERS_MALFORMED
        )
    }
    val maybeToken: BearerToken? = db.bearerTokenGet(tokenBytes)
    if (maybeToken == null) {
        logger.error("Auth token not found")
        return null
    }
    if (maybeToken.expirationTime - getNowUs() < 0) {
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
    return db.customerGetFromRowId(maybeToken.bankCustomer)
        ?: throw LibeufinBankException(
            httpStatus = HttpStatusCode.InternalServerError,
            talerError = TalerError(
                code = TalerErrorCode.TALER_EC_GENERIC_INTERNAL_INVARIANT_FAILURE.code,
                hint = "Customer not found, despite token mentions it.",
            ))
}

fun forbidden(
    hint: String = "No rights on the resource",
    // FIXME: create a 'generic forbidden' Taler EC.
    talerErrorCode: TalerErrorCode = TalerErrorCode.TALER_EC_END
): LibeufinBankException =
    LibeufinBankException(
        httpStatus = HttpStatusCode.Forbidden,
        talerError = TalerError(
            code = talerErrorCode.code,
            hint = hint
        )
    )

fun unauthorized(hint: String = "Login failed"): LibeufinBankException =
    LibeufinBankException(
        httpStatus = HttpStatusCode.Unauthorized,
        talerError = TalerError(
            code = TalerErrorCode.TALER_EC_BANK_LOGIN_FAILED.code,
            hint = hint
        )
    )
fun internalServerError(hint: String?): LibeufinBankException =
    LibeufinBankException(
        httpStatus = HttpStatusCode.InternalServerError,
        talerError = TalerError(
            code = TalerErrorCode.TALER_EC_GENERIC_INTERNAL_INVARIANT_FAILURE.code,
            hint = hint
        )
    )


fun notFound(
    hint: String?,
    talerEc: TalerErrorCode
): LibeufinBankException =
    LibeufinBankException(
        httpStatus = HttpStatusCode.NotFound,
        talerError = TalerError(
            code = talerEc.code,
            hint = hint
        )
    )

fun conflict(
    hint: String?,
    talerEc: TalerErrorCode
): LibeufinBankException =
    LibeufinBankException(
        httpStatus = HttpStatusCode.Conflict,
        talerError = TalerError(
            code = talerEc.code,
            hint = hint
        )
    )
fun badRequest(
    hint: String? = null,
    talerErrorCode: TalerErrorCode = TalerErrorCode.TALER_EC_GENERIC_JSON_INVALID
): LibeufinBankException =
    LibeufinBankException(
        httpStatus = HttpStatusCode.BadRequest,
        talerError = TalerError(
            code = talerErrorCode.code,
            hint = hint
        )
    )
// Generates a new Payto-URI with IBAN scheme.
fun genIbanPaytoUri(): String = "payto://iban/SANDBOXX/${getIban()}"

/**
 * This helper takes the serialized version of a Taler Amount
 * type and parses it into Libeufin's internal representation.
 * It returns a TalerAmount type, or throws a LibeufinBankException
 * it the input is invalid.  Such exception will be then caught by
 * Ktor, transformed into the appropriate HTTP error type, and finally
 * responded to the client.
 */
fun parseTalerAmount(
    amount: String,
    fracDigits: FracDigits = FracDigits.EIGHT
): TalerAmount {
    val format = when (fracDigits) {
        FracDigits.TWO -> "^([A-Z]+):([0-9]+)(\\.[0-9][0-9]?)?$"
        FracDigits.EIGHT -> "^([A-Z]+):([0-9]+)(\\.[0-9][0-9]?[0-9]?[0-9]?[0-9]?[0-9]?[0-9]?[0-9]?)?$"
    }
    val match = Regex(format).find(amount) ?: throw LibeufinBankException(
        httpStatus = HttpStatusCode.BadRequest,
        talerError = TalerError(
            code = TalerErrorCode.TALER_EC_BANK_BAD_FORMAT_AMOUNT.code,
            hint = "Invalid amount: $amount"
        ))
    val _value = match.destructured.component2()
    // Fraction is at most 8 digits, so it's always < than MAX_INT.
    val fraction: Int = match.destructured.component3().run {
        var frac = 0
        var power = FRACTION_BASE
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
        throw LibeufinBankException(
            httpStatus = HttpStatusCode.BadRequest,
            talerError = TalerError(
                code = TalerErrorCode.TALER_EC_BANK_BAD_FORMAT_AMOUNT.code,
                hint = "Invalid amount: ${amount}, could not extract the value part."
            )
        )
    }
    return TalerAmount(
        value = value,
        frac = fraction,
        maybeCurrency = match.destructured.component1()
    )
}

private fun normalizeAmount(amt: TalerAmount): TalerAmount {
    if (amt.frac > FRACTION_BASE) {
        val normalValue = amt.value + (amt.frac / FRACTION_BASE)
        val normalFrac = amt.frac % FRACTION_BASE
        return TalerAmount(
            value = normalValue,
            frac = normalFrac,
            maybeCurrency = amt.currency
        )
    }
    return amt
}


// Adds two amounts and returns the normalized version.
private fun amountAdd(first: TalerAmount, second: TalerAmount): TalerAmount {
    if (first.currency != second.currency)
        throw badRequest(
            "Currency mismatch, balance '${first.currency}', price '${second.currency}'",
            TalerErrorCode.TALER_EC_GENERIC_CURRENCY_MISMATCH
        )
    val valueAdd = first.value + second.value
    if (valueAdd < first.value)
        throw badRequest("Amount value overflowed")
    val fracAdd = first.frac + second.frac
    if (fracAdd < first.frac)
        throw badRequest("Amount fraction overflowed")
    return normalizeAmount(TalerAmount(
        value = valueAdd,
        frac = fracAdd,
        maybeCurrency = first.currency
    ))
}

/**
 * Checks whether the balance could cover the due amount.  Returns true
 * when it does, false otherwise.  Note: this function is only a checker,
 * meaning that no actual business state should change after it runs.
 * The place where business states change is in the SQL that's loaded in
 * the database.
 */
fun isBalanceEnough(
    balance: TalerAmount,
    due: TalerAmount,
    maxDebt: TalerAmount,
    hasBalanceDebt: Boolean
): Boolean {
    val normalMaxDebt = normalizeAmount(maxDebt) // Very unlikely to be needed.
    if (hasBalanceDebt) {
        val chargedBalance = amountAdd(balance, due)
        if (chargedBalance.value > normalMaxDebt.value) return false // max debt surpassed
        if (
            (chargedBalance.value == normalMaxDebt.value) &&
            (chargedBalance.frac > maxDebt.frac)
            )
            return false
        return true
    }
    /**
     * Balance doesn't have debt, but it MIGHT get one.  The following
     * block calculates how much debt the balance would get, should a
     * subtraction of 'due' occur.
     */
    if (balance.currency != due.currency)
        throw badRequest(
            "Currency mismatch, balance '${balance.currency}', due '${due.currency}'",
            TalerErrorCode.TALER_EC_GENERIC_CURRENCY_MISMATCH
        )
    val valueDiff = if (balance.value < due.value) due.value - balance.value else 0L
    val fracDiff = if (balance.frac < due.frac) due.frac - balance.frac else 0
    // Getting the normalized version of such diff.
    val normalDiff = normalizeAmount(TalerAmount(valueDiff, fracDiff, balance.currency))
    // Failing if the normalized diff surpasses the max debt.
    if (normalDiff.value > normalMaxDebt.value) return false
    if ((normalDiff.value == normalMaxDebt.value) &&
        (normalDiff.frac > normalMaxDebt.frac)) return false
    return true
}
fun getBankCurrency(): String = db.configGet("internal_currency") ?: throw internalServerError("Bank lacks currency")

/**
 *  Builds the taler://withdraw-URI.  Such URI will serve the requests
 *  from wallets, when they need to manage the operation.  For example,
 *  a URI like taler://withdraw/$BANK_URL/taler-integration/$WO_ID needs
 *  the bank to implement the Taler integratino API at the following base URL:
 *
 *      https://$BANK_URL/taler-integration
 */
fun getTalerWithdrawUri(baseUrl: String, woId: String) =
    url {
        val baseUrlObj = URL(baseUrl)
        protocol = URLProtocol(
            name = "taler".plus(if (baseUrlObj.protocol.lowercase() == "http") "+http" else ""),
            defaultPort = -1
        )
        host = "withdraw"
        val pathSegments = mutableListOf(
            // adds the hostname(+port) of the actual bank that will serve the withdrawal request.
            baseUrlObj.host.plus(
                if (baseUrlObj.port != -1)
                    ":${baseUrlObj.port}"
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