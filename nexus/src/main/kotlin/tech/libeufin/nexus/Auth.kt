package tech.libeufin.nexus

import UtilError
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.request.*
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.nexus.server.Permission
import tech.libeufin.nexus.server.PermissionQuery
import tech.libeufin.util.*

/**
 * HTTP basic auth.  Throws error if password is wrong,
 * and makes sure that the user exists in the system.
 *
 * @return user entity
 */
fun authenticateRequest(request: ApplicationRequest): NexusUserEntity {
    return transaction {
        val (username, password) = getHTTPBasicAuthCredentials(request)
        val user = NexusUserEntity.find {
            NexusUsersTable.username eq username
        }.firstOrNull()
        if (user == null) {
            throw UtilError(HttpStatusCode.Unauthorized,
                "Unknown user '$username'",
                LibeufinErrorCode.LIBEUFIN_EC_AUTHENTICATION_FAILED
            )
        }
        CryptoUtil.checkPwOrThrow(password, user.passwordHash)
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
                    and (NexusPermissionsTable.permissionName eq p.permissionName.lowercase()))

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
            val p = Permission("user", user.username, pr.resourceType, pr.resourceId, pr.permissionName.lowercase())
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