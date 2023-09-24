package tech.libeufin.bank

import io.ktor.http.*
import io.ktor.server.application.*
import net.taler.common.errorcodes.TalerErrorCode
import tech.libeufin.util.getAuthorizationDetails
import tech.libeufin.util.getAuthorizationRawHeader

/**
 * This function tries to authenticate the call according
 * to the scheme that is mentioned in the Authorization header.
 * The allowed schemes are either 'HTTP basic auth' or 'bearer token'.
 *
 * requiredScope can be either "readonly" or "readwrite".
 *
 * Returns the authenticated customer, or null if they failed.
 */
fun ApplicationCall.authenticateBankRequest(db: Database, requiredScope: TokenScope): Customer? {
    // Extracting the Authorization header.
    val header = getAuthorizationRawHeader(this.request) ?: throw badRequest(
        "Authorization header not found.",
        TalerErrorCode.TALER_EC_GENERIC_HTTP_HEADERS_MALFORMED
    )
    val authDetails = getAuthorizationDetails(header) ?: throw badRequest(
        "Authorization is invalid.",
        TalerErrorCode.TALER_EC_GENERIC_HTTP_HEADERS_MALFORMED
    )
    return when (authDetails.scheme) {
        "Basic" -> doBasicAuth(db, authDetails.content)
        "Bearer" -> doTokenAuth(db, authDetails.content, requiredScope)
        else -> throw LibeufinBankException(
            httpStatus = HttpStatusCode.Unauthorized,
            talerError = TalerError(
                code = TalerErrorCode.TALER_EC_GENERIC_UNAUTHORIZED.code,
                hint = "Authorization method wrong or not supported."
            )
        )
    }
}