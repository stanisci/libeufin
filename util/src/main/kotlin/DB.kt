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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import logger
import net.taler.wallet.crypto.Base32Crockford
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.name
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.postgresql.jdbc.PgConnection
import java.net.URI
import kotlin.system.exitProcess

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
/**
 * Note: every domain is ALWAYS meant to be salted with
 * a unique identifier that points to the user waiting for
 * a notification.  The reference function for salting is:
 * "buildChannelName()", in this file.
 */
enum class NotificationsChannelDomains(val value: Int) {
    // When payments with well-formed Taler subject arrive.
    LIBEUFIN_TALER_INCOMING(3000),
    // A transaction happened for a particular user.  The payload
    // informs about the direction.
    LIBEUFIN_REGIO_TX(3001),
    // When an incoming fiat payment is downloaded from Nexus.
    // Happens when a customer wants to withdraw Taler coins in the
    // regional currency.
    LIBEUFIN_SANDBOX_FIAT_INCOMING(3002),
    // When Nexus has ingested a new transactions from the bank it
    // is connected to.  This event carries incoming and outgoing
    // payments, and it specifies that in its payload.  The direction
    // codename is the same as CaMt (DBIT, CRDT), as that is also
    // used in the database.
    LIBEUFIN_NEXUS_TX(3003)
}

/**
 * Helper that builds a LISTEN-NOTIFY channel name.
 * 'salt' should be any value that would uniquely deliver the
 * message to its receiver.  IBANs are ideal, but they cost DB queries.
 */

fun buildChannelName(
    domain: NotificationsChannelDomains,
    salt: String,
    separator: String = "_"
): String {
    val channelElements = "${domain.value}$separator$salt"
    val ret = "X${Base32Crockford.encode(CryptoUtil.hashStringSHA256(channelElements))}"
    logger.debug("Defining db channel name for salt: $salt, domain: ${domain.name}, resulting in: $ret")
    return ret
}

fun Transaction.postgresNotify(
    channel: String,
    payload: String? = null
    ) {
    logger.debug("Sending NOTIFY on channel '$channel' with payload '$payload'")
    if (payload != null) {
        val argEnc = Base32Crockford.encode(payload.toByteArray())
        if (payload.toByteArray().size > 8000)
            throw internalServerError(
                "DB notification on channel $channel used >8000 bytes payload '$payload'"
            )
        this.exec("NOTIFY $channel, '$argEnc'")
        return
    }
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
    // Gets set to the NOTIFY's payload, in case one exists.
    var receivedPayload: String? = null
    // Signals whether the connection should be kept open,
    // after one (and possibly not expected) event arrives.
    // This gives more flexibility to the caller.
    var keepConnection: Boolean = false

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

    private fun likelyCloseConnection() {
        if (this.keepConnection)
            return
        this.conn.close()
    }

    fun postgresGetNotifications(timeoutMs: Long): Boolean {
        if (timeoutMs == 0L)
            logger.info("Database notification checker has timeout == 0," +
                    " that waits FOREVER until a notification arrives."
            )
        logger.debug("Waiting Postgres notifications on channel " +
                "'$channelName' for $timeoutMs millis.")
        val maybeNotifications = this.conn.getNotifications(timeoutMs.toInt())
        if (maybeNotifications == null || maybeNotifications.isEmpty()) {
            logger.debug("DB notifications not found on channel $channelName.")
            this.likelyCloseConnection()
            return false
        }
        for (n in maybeNotifications) {
            if (n.name.lowercase() != channelName.lowercase()) {
                conn.close() // always close on error, without the optional check.
                throw internalServerError("Channel $channelName got notified from ${n.name}!")
            }
        }
        logger.debug("Found DB notifications on channel $channelName")
        // Only ever used for singleton notifications.
        assert(maybeNotifications.size == 1)
        if(maybeNotifications[0].parameter.isNotEmpty())
            this.receivedPayload = maybeNotifications[0].parameter
        this.likelyCloseConnection()
        return true
    }

    // Wrapper around the core method "postgresGetNotifications()" that
    // sets up the coroutine environment to wait and release the execution.
    suspend fun waitOnIODispatchers(timeoutMs: Long): Boolean =
        coroutineScope {
            async(Dispatchers.IO) {
                postgresGetNotifications(timeoutMs)
            }.await()
        }

    /**
     * Waits at most 'timeoutMs' on 'this.channelName' for
     * the one particular payload that's passed in the 'payload'
     * argument.  FIXME: will be used along the fiat side of cash-outs.
     */
    suspend fun waitOnIoDispatchersForPayload(
        timeoutMs: Long,
        expectedPayload: String
    ): Boolean {
        var leftTime = timeoutMs
        val expectedPayloadEnc = Base32Crockford.encode(expectedPayload.toByteArray())
        /**
         * This setting allows the loop to reuse the open connection,
         * otherwise the internal loop would close it if one unexpected
         * payload wakes it up.
         */
        this.keepConnection = true
        while (leftTime > 0) {
            val loopStart = System.currentTimeMillis()
            // Ask for notifications.
            val maybeNotification = waitOnIODispatchers(leftTime)
            // One arrived, check the payload.
            if (maybeNotification) {
                if (this.receivedPayload != null && this.receivedPayload == expectedPayloadEnc) {
                    conn.close()
                    return true
                }
            }
            val loopEnd = System.currentTimeMillis()
            // Account the spent time.
            leftTime -= loopEnd - loopStart
        }
        conn.close()
        return false
    }
}

fun getDatabaseName(): String {
    var maybe_db_name: String? = null
    transaction {
        this.exec("SELECT current_database() AS database_name;") { oneLineRes ->
            if (oneLineRes.next())
                maybe_db_name = oneLineRes.getString("database_name")
        }
    }
    return maybe_db_name ?: throw internalServerError("Could not find current DB name")
}

/**
 * Abstracts over the Exposed details to connect
 * to a database and ONLY use the passed schema
 * WHEN PostgreSQL is the DBMS.
 */
fun connectWithSchema(jdbcConn: String, schemaName: String? = null) {
    Database.connect(
        jdbcConn,
        setupConnection = { conn ->
            if (isPostgres() && schemaName != null)
                conn.schema = schemaName
        }
    )
    try { transaction { this.db.name } }
    catch (e: Throwable) {
        logger.error("Test query failed: ${e.message}")
        throw internalServerError("Failed connection to: $jdbcConn")
    }
}

/**
 * This function converts a postgresql://-URI to a JDBC one.
 * It is only needed because JDBC strings based on Unix domain
 * sockets need individual intervention.
 */
fun getJdbcConnectionFromPg(pgConn: String): String {
    if (!pgConn.startsWith("postgresql://")) {
        logger.info("Not a Postgres connection string: $pgConn")
        throw internalServerError("Not a Postgres connection string: $pgConn")
    }
    var maybeUnixSocket = false
    val parsed = URI(pgConn)
    val hostAsParam: String? = if (parsed.query != null)
        getQueryParam(parsed.query, "host")
    else null
    /**
     * In some cases, it is possible to leave the hostname empty
     * and specify it via a query param, therefore a "postgresql:///"-starting
     * connection string does NOT always mean Unix domain socket.
     * https://www.postgresql.org/docs/current/libpq-connect.html#LIBPQ-CONNSTRING
     */
    if (parsed.host == null &&
        (hostAsParam == null || hostAsParam.startsWith('/'))) {
        maybeUnixSocket = true
    }
    if (maybeUnixSocket) {
        // Check whether the database user should differ from the process user.
        var pgUser = getCurrentUser()
        if (parsed.query != null) {
            val maybeUserParam = getQueryParam(parsed.query, "user")
            if (maybeUserParam != null) pgUser = maybeUserParam
        }
        // Check whether the Unix domain socket location was given non-standard.
        val socketLocation = hostAsParam ?: "/var/run/postgresql/.s.PGSQL.5432"
        if (!socketLocation.startsWith('/')) {
            throw internalServerError("PG connection wants Unix domain socket, but non-null host doesn't start with slash")
        }
        return "jdbc:postgresql://localhost${parsed.path}?user=$pgUser&socketFactory=org.newsclub.net.unix." +
                "AFUNIXSocketFactory\$FactoryArg&socketFactoryArg=$socketLocation"
    }
    return "jdbc:$pgConn"
}