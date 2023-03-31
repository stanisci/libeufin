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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import logger
import net.taler.wallet.crypto.Base32Crockford
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.transactionManager
import org.postgresql.PGNotification
import org.postgresql.jdbc.PgConnection

fun Transaction.isPostgres(): Boolean {
    return this.db.vendor == "postgresql"
}

fun isPostgres(): Boolean {
    val db = TransactionManager.defaultDatabase ?: throw internalServerError(
        "Could not find the default database, can't check if that's Postgres."
    )
    return db.vendor == "postgresql"

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
    val ret = "X${Base32Crockford.encode(CryptoUtil.hashStringSHA256(channelElements))}"
    logger.debug("Defining db channel name for IBAN: $iban, domain: ${domain.name}, resulting in: $ret")
    return ret
}

fun Transaction.postgresNotify(channel: String) {
    this.exec("NOTIFY $channel")
}

/**
 * postgresListen() and postgresGetNotifications() appear to have
 * to use the same connection, in order for the notifications to
 * arrive.  Therefore, calling LISTEN inside one "transaction {}"
 * and postgresGetNotifications() outside of it did NOT work because
 * Exposed _closes_ the connection as soon as the transaction block
 * completes. OTOH, calling postgresGetNotifications() _inside_ the
 * same transaction block as LISTEN's would lead to keep the database
 * locked for the timeout duration.
 *
 * For this reason, opening and keeping one connection open for the
 * lifetime of this object and only executing postgresListen() and
 * postgresGetNotifications() _on that connection_ makes the event
 * delivery more reliable.
 */
class PostgresListenHandle(val channelName: String) {
    private val db = TransactionManager.defaultDatabase ?: throw internalServerError(
        "Could not find the default database, won't get Postgres notifications."
    )
    private val conn = db.connector().connection as PgConnection

    fun postgresListen() {
        val stmt = conn.createStatement()
        stmt.execute("LISTEN $channelName")
        stmt.close()
        logger.debug("LISTENing on channel: $channelName")
    }
    fun postgresUnlisten() {
        val stmt = conn.createStatement()
        stmt.execute("UNLISTEN $channelName")
        stmt.close()
        logger.debug("UNLISTENing on channel: $channelName")
        conn.close()
    }

    fun postgresGetNotifications(timeoutMs: Long): Boolean {
        if (timeoutMs == 0L)
            logger.warn("Database notification checker has timeout == 0," +
                    " that waits FOREVER until a notification arrives."
            )
        logger.debug("Waiting Postgres notifications on channel " +
                "'$channelName' for $timeoutMs millis.")
        val maybeNotifications = this.conn.getNotifications(timeoutMs.toInt())
        if (maybeNotifications == null || maybeNotifications.isEmpty()) {
            logger.debug("DB notification channel $channelName was found empty.")
            return false
        }
        for (n in maybeNotifications) {
            if (n.name.lowercase() != channelName.lowercase()) {
                throw internalServerError("Channel $channelName got notified from ${n.name}!")
            }
        }
        logger.debug("Found DB notifications on channel $channelName")
        return true
    }
}