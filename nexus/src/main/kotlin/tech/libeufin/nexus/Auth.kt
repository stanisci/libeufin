package tech.libeufin.nexus

import io.ktor.application.*
import io.ktor.http.*
import io.ktor.request.*
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.nexus.server.Permission
import tech.libeufin.nexus.server.PermissionQuery
import tech.libeufin.util.CryptoUtil
import tech.libeufin.util.base64ToBytes
import tech.libeufin.util.constructXml


/**
 * This helper function parses a Authorization:-header line, decode the credentials
 * and returns a pair made of username and hashed (sha256) password.  The hashed value
 * will then be compared with the one kept into the database.
 */
private fun extractUserAndPassword(authorizationHeader: String): Pair<String, String> {
    logger.debug("Authenticating: $authorizationHeader")
    val (username, password) = try {
        val split = authorizationHeader.split(" ")
        val plainUserAndPass = String(base64ToBytes(split[1]), Charsets.UTF_8)
        plainUserAndPass.split(":")
    } catch (e: java.lang.Exception) {
        throw NexusError(
            HttpStatusCode.BadRequest,
            "invalid Authorization:-header received"
        )
    }
    return Pair(username, password)
}


/**
 * Test HTTP basic auth.  Throws error if password is wrong,
 * and makes sure that the user exists in the system.
 *
 * @return user entity
 */
fun authenticateRequest(request: ApplicationRequest): NexusUserEntity {
    return transaction {
        val authorization = request.headers["Authorization"]
        val headerLine = if (authorization == null) throw NexusError(
            HttpStatusCode.BadRequest, "Authorization header not found"
        ) else authorization
        val (username, password) = extractUserAndPassword(headerLine)
        val user = NexusUserEntity.find {
            NexusUsersTable.id eq username
        }.firstOrNull()
        if (user == null) {
            throw NexusError(HttpStatusCode.Unauthorized, "Unknown user '$username'")
        }
        if (!CryptoUtil.checkpw(password, user.passwordHash)) {
            throw NexusError(HttpStatusCode.Forbidden, "Wrong password")
        }
        user
    }
}

fun requireSuperuser(request: ApplicationRequest): NexusUserEntity {
    return transaction {
        val user = authenticateRequest(request)
        if (!user.superuser) {
            throw NexusError(HttpStatusCode.Forbidden, "must be superuser")
        }
        user
    }
}

fun findPermission(p: Permission): NexusPermissionEntity? {
    return transaction {
        NexusPermissionEntity.find {
            ((NexusPermissionsTable.subjectType eq p.subjectType)
                    and (NexusPermissionsTable.subjectId eq p.subjectId)
                    and (NexusPermissionsTable.resourceType eq p.resourceType)
                    and (NexusPermissionsTable.resourceId eq p.resourceId)
                    and (NexusPermissionsTable.permissionName eq p.permissionName))

        }.firstOrNull()
    }
}


/**
 * Require that the authenticated user has at least one of the listed permissions.
 *
 * Throws a NexusError if the authenticated user for the request doesn't have any of
 * listed the permissions.
 */
fun ApplicationRequest.requirePermission(vararg perms: PermissionQuery) {
    transaction {
        val user = authenticateRequest(this@requirePermission)
        if (user.superuser) {
            return@transaction
        }
        var foundPermission = false
        for (pr in perms) {
            val p = Permission("user", user.id.value, pr.resourceType, pr.resourceId, pr.permissionName)
            val existingPerm = findPermission(p)
            if (existingPerm != null) {
                foundPermission = true
                break
            }
        }
        if (!foundPermission) {
            val possiblePerms =
                perms.joinToString(" | ") { "${it.resourceId} ${it.resourceType} ${it.permissionName}" }
            throw NexusError(
                HttpStatusCode.Forbidden,
                "User ${user.id.value} has insufficient permissions (needs $possiblePerms."
            )
        }
    }
}