package tech.libeufin.sandbox

import UtilError
import io.ktor.http.*
import io.ktor.request.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.util.CryptoUtil
import tech.libeufin.util.LibeufinErrorCode
import tech.libeufin.util.getHTTPBasicAuthCredentials


/**
 * HTTP basic auth.  Throws error if password is wrong,
 * and makes sure that the user exists in the system.
 *
 * @return user entity
 */
fun authenticateRequest(request: ApplicationRequest): SandboxUserEntity {
    return transaction {
        val (username, password) = getHTTPBasicAuthCredentials(request)
        val user = SandboxUserEntity.find {
            SandboxUsersTable.username eq username
        }.firstOrNull()
        if (user == null) {
            throw UtilError(
                HttpStatusCode.Unauthorized,
                "Unknown user '$username'",
                LibeufinErrorCode.LIBEUFIN_EC_AUTHENTICATION_FAILED
            )
        }
        CryptoUtil.checkPwOrThrow(password, username)
        user
    }
}

fun requireSuperuser(request: ApplicationRequest): SandboxUserEntity {
    return transaction {
        val user = authenticateRequest(request)
        if (!user.superuser) {
            throw SandboxError(HttpStatusCode.Forbidden, "must be superuser")
        }
        user
    }
}
