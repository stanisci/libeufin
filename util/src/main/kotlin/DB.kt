/*
 * This file is part of LibEuFin.
 * Copyright (C) 2020 Taler Systems S.A.
 *
 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.
 *
 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

package tech.libeufin.util
import UtilError
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import logger
import net.taler.wallet.crypto.Base32Crockford
import org.jetbrains.exposed.sql.Transaction
import org.postgresql.jdbc.PgConnection

fun Transaction.isPostgres(): Boolean {
    return this.db.vendor == "postgresql"
}

fun Transaction.getPgConnection(): PgConnection {
    if (!this.isPostgres()) throw UtilError(
        HttpStatusCode.InternalServerError,
        "Unexpected non-postgresql connection: ${this.db.vendor}"
    )
    return this.db.connector().connection as PgConnection
}

// Check GANA (https://docs.gnunet.org/gana/index.html) for numbers allowance.
enum class NotificationsChannelDomains(val value: Int) {
    LIBEUFIN_TALER_INCOMING(3000)
}

// Helper that builds a LISTEN-NOTIFY channel name.
fun buildChannelName(
    domain: NotificationsChannelDomains,
    iban: String,
    separator: String = "_"
): String {
    val channelElements = "${domain.value}$separator$iban"
    return "X${Base32Crockford.encode(CryptoUtil.hashStringSHA256(channelElements))}"
}

// This class abstracts Postgres' LISTEN/NOTIFY.
// FIXME: find facts where Exposed provides always a live 'conn'.
class PostgresListenNotify(val conn: PgConnection, val channel: String) {
    fun postrgesListen() {
        val stmt = conn.createStatement()
        stmt.execute("LISTEN $channel")
        stmt.close()
    }
    fun postgresNotify() {
        val stmt = conn.createStatement()
        stmt.execute("NOTIFY $channel")
        stmt.close()
    }

    fun postgresUnlisten() {
        val stmt = conn.createStatement()
        stmt.execute("UNLISTEN $channel")
        stmt.close()
    }

    /**
     * Asks Postgres for notifications with a timeout.  Returns
     * true when there have been, false otherwise.
     */
    fun postgresWaitNotification(timeoutMs: Long): Boolean {
        if (timeoutMs == 0L)
            logger.warn("Database notification checker has timeout == 0," +
                    " that waits FOREVER until a notification arrives."
            )
        val maybeNotifications = conn.getNotifications(timeoutMs.toInt())

        /**
         * This check works around the apparent API inconsistency
         * where instead of returning null, a empty array is given
         * back when there have been no notifications.
         */
        val noResultWorkaround = maybeNotifications.isEmpty()
        /*if (noResultWorkaround) {
            logger.warn("JDBC+Postgres: empty array from getNotifications() despite docs suggest null.")
        }*/
        if (maybeNotifications == null || noResultWorkaround) return false

        for (n in maybeNotifications) {
            if (n.name.lowercase() != this.channel.lowercase())
                throw internalServerError("Channel ${this.channel} got notified from ${n.name}!")
        }
        return true
    }
}