package tech.libeufin.bank

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.taler.common.errorcodes.TalerErrorCode
import net.taler.wallet.crypto.Base32Crockford
import tech.libeufin.util.maybeUriComponent
import tech.libeufin.util.getNowUs

fun Routing.tokenHandlers() {
    delete("/accounts/{USERNAME}/token") {
        throw internalServerError("Token deletion not implemented.")
    }
    post("/accounts/{USERNAME}/token") {
        val customer = call.myAuth(TokenScope.refreshable) ?: throw unauthorized("Authentication failed")
        val endpointOwner = call.maybeUriComponent("USERNAME")
        if (customer.login != endpointOwner)
            throw forbidden(
                "User has no rights on this enpoint",
                TalerErrorCode.TALER_EC_END // FIXME: need generic forbidden
            )
        val maybeAuthToken = call.getAuthToken()
        val req = call.receive<TokenRequest>()
        /**
         * This block checks permissions ONLY IF the call was authenticated
         * with a token.  Basic auth gets always granted.
         */
        if (maybeAuthToken != null) {
            val tokenBytes = Base32Crockford.decode(maybeAuthToken)
            val refreshingToken = db.bearerTokenGet(tokenBytes) ?: throw internalServerError(
                "Token used to auth not found in the database!"
            )
            if (refreshingToken.scope == TokenScope.readonly && req.scope == TokenScope.readwrite)
                throw forbidden(
                    "Cannot generate RW token from RO",
                    TalerErrorCode.TALER_EC_GENERIC_TOKEN_PERMISSION_INSUFFICIENT
                )
        }
        val tokenBytes = ByteArray(32).apply {
            java.util.Random().nextBytes(this)
        }
        val maxDurationTime: Long = db.configGet("token_max_duration").run {
            if (this == null)
                return@run Long.MAX_VALUE
            return@run try {
                this.toLong()
            } catch (e: Exception) {
                tech.libeufin.bank.logger.error("Could not convert config's token_max_duration to Long")
                throw internalServerError(e.message)
            }
        }
        if (req.duration != null && req.duration.d_us.compareTo(maxDurationTime) == 1)
            throw forbidden(
                "Token duration bigger than bank's limit",
                // FIXME: define new EC for this case.
                TalerErrorCode.TALER_EC_END
            )
        val tokenDurationUs  = req.duration?.d_us ?: TOKEN_DEFAULT_DURATION_US
        val customerDbRow = customer.dbRowId ?: throw internalServerError(
            "Coud not resort customer '${customer.login}' database row ID"
        )
        val expirationTimestampUs: Long = getNowUs() + tokenDurationUs
        if (expirationTimestampUs < tokenDurationUs)
            throw badRequest(
                "Token duration caused arithmetic overflow",
                // FIXME: need dedicate EC (?)
                talerErrorCode = TalerErrorCode.TALER_EC_END
            )
        val token = BearerToken(
            bankCustomer = customerDbRow,
            content = tokenBytes,
            creationTime = expirationTimestampUs,
            expirationTime = expirationTimestampUs,
            scope = req.scope,
            isRefreshable = req.refreshable
        )
        if (!db.bearerTokenCreate(token))
            throw internalServerError("Failed at inserting new token in the database")
        call.respond(
            TokenSuccessResponse(
                access_token = Base32Crockford.encode(tokenBytes),
                expiration = Timestamp(
                    t_s = expirationTimestampUs / 1000000L
                )
            )
        )
        return@post
    }
}