/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023 Taler Systems S.A.
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
import net.taler.wallet.crypto.Base32Crockford
import org.postgresql.ds.PGSimpleDataSource
import org.postgresql.jdbc.PgConnection
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

fun getCurrentUser(): String = System.getProperty("user.name")


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


/**
 * This function converts postgresql:// URIs to JDBC URIs.
 *
 * URIs that are already jdbc: URIs are passed through.
 *
 * This avoids the user having to create complex JDBC URIs for postgres connections.
 * They are especially complex when using unix domain sockets, as they're not really
 * supported natively by JDBC.
 */
fun getJdbcConnectionFromPg(pgConn: String): String {
    // Pass through jdbc URIs.
    if (pgConn.startsWith("jdbc:")) {
        return pgConn
    }
    if (!pgConn.startsWith("postgresql://") && !pgConn.startsWith("postgres://")) {
        logger.info("Not a Postgres connection string: $pgConn")
        throw Exception("Not a Postgres connection string: $pgConn")
    }
    var maybeUnixSocket = false
    val parsed = URI(pgConn)
    val hostAsParam: String? = if (parsed.query != null) {
        getQueryParam(parsed.query, "host")
    } else {
        null
    }
    /**
     * In some cases, it is possible to leave the hostname empty
     * and specify it via a query param, therefore a "postgresql:///"-starting
     * connection string does NOT always mean Unix domain socket.
     * https://www.postgresql.org/docs/current/libpq-connect.html#LIBPQ-CONNSTRING
     */
    if (parsed.host == null &&
        (hostAsParam == null || hostAsParam.startsWith('/'))
    ) {
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
            throw Exception("PG connection wants Unix domain socket, but non-null host doesn't start with slash")
        }
        return "jdbc:postgresql://localhost${parsed.path}?user=$pgUser&socketFactory=org.newsclub.net.unix." +
                "AFUNIXSocketFactory\$FactoryArg&socketFactoryArg=$socketLocation"
    }
    if (pgConn.startsWith("postgres://")) {
        // The JDBC driver doesn't like postgres://, only postgresql://.
        // For consistency with other components, we normalize the postgres:// URI
        // into one that the JDBC driver likes.
        return "jdbc:postgresql://" + pgConn.removePrefix("postgres://")
    }
    return "jdbc:$pgConn"
}

data class DatabaseConfig(
    val dbConnStr: String,
    val sqlDir: String
)

fun pgDataSource(dbConfig: String): PGSimpleDataSource {
    val jdbcConnStr = getJdbcConnectionFromPg(dbConfig)
    logger.info("connecting to database via JDBC string '$jdbcConnStr'")
    val pgSource = PGSimpleDataSource()
    pgSource.setUrl(jdbcConnStr)
    pgSource.prepareThreshold = 1
    return pgSource
}

fun PGSimpleDataSource.pgConnection(): PgConnection {
    val conn = connection.unwrap(PgConnection::class.java)
    // FIXME: bring the DB schema to a function argument.
    conn.execSQLUpdate("SET search_path TO libeufin_bank;")
    return conn
}

fun <R> PgConnection.transaction(lambda: (PgConnection) -> R): R {
    try {
        setAutoCommit(false);
        val result = lambda(this)
        commit();
        setAutoCommit(true);
        return result
    } catch(e: Exception){
        rollback();
        setAutoCommit(true);
        throw e;
    }
}

fun <T> PreparedStatement.oneOrNull(lambda: (ResultSet) -> T): T? {
    executeQuery().use {
        if (!it.next()) return null
        return lambda(it)
    }
}

fun <T> PreparedStatement.all(lambda: (ResultSet) -> T): List<T> {
    executeQuery().use {
        val ret = mutableListOf<T>()
        while (it.next()) {
            ret.add(lambda(it))
        }
        return ret
    }
}

fun PreparedStatement.executeQueryCheck(): Boolean {
    executeQuery().use {
        return it.next()
    }
}

fun PreparedStatement.executeUpdateCheck(): Boolean {
    executeUpdate()
    return updateCount > 0
}

/**
 * Helper that returns false if the row to be inserted
 * hits a unique key constraint violation, true when it
 * succeeds.  Any other error (re)throws exception.
 */
fun PreparedStatement.executeUpdateViolation(): Boolean {
    return try {
        executeUpdateCheck()
    } catch (e: SQLException) {
        logger.error(e.message)
        if (e.sqlState == "23505") return false // unique_violation
        throw e // rethrowing, not to hide other types of errors.
    }
}

// sqlFilePrefix is, for example, "libeufin-bank" or "libeufin-nexus" (no trailing dash).
fun initializeDatabaseTables(cfg: DatabaseConfig, sqlFilePrefix: String) {
    logger.info("doing DB initialization, sqldir ${cfg.sqlDir}, dbConnStr ${cfg.dbConnStr}")
    pgDataSource(cfg.dbConnStr).pgConnection().use { conn ->
        conn.transaction {
            val sqlVersioning = File("${cfg.sqlDir}/versioning.sql").readText()
            conn.execSQLUpdate(sqlVersioning)

            val checkStmt = conn.prepareStatement("SELECT count(*) as n FROM _v.patches where patch_name = ?")

            for (n in 1..9999) {
                val numStr = n.toString().padStart(4, '0')
                val patchName = "$sqlFilePrefix-$numStr"

                checkStmt.setString(1, patchName)
                val patchCount = checkStmt.oneOrNull { it.getInt(1) } ?: throw Error("unable to query patches");
                if (patchCount >= 1) {
                    logger.info("patch $patchName already applied")
                    continue
                }

                val path = File("${cfg.sqlDir}/$sqlFilePrefix-$numStr.sql")
                if (!path.exists()) {
                    logger.info("path $path doesn't exist anymore, stopping")
                    break
                }
                logger.info("applying patch $path")
                val sqlPatchText = path.readText()
                conn.execSQLUpdate(sqlPatchText)
            }
            val sqlProcedures = File("${cfg.sqlDir}/$sqlFilePrefix-procedures.sql")
            if (!sqlProcedures.exists()) {
                logger.info("No procedures.sql for the SQL collection: $sqlFilePrefix")
                return@transaction
            }
            conn.execSQLUpdate(sqlProcedures.readText())
        }
    }
}

// sqlFilePrefix is, for example, "libeufin-bank" or "libeufin-nexus" (no trailing dash).
fun resetDatabaseTables(cfg: DatabaseConfig, sqlFilePrefix: String) {
    logger.info("reset DB, sqldir ${cfg.sqlDir}, dbConnStr ${cfg.dbConnStr}")
    pgDataSource(cfg.dbConnStr).pgConnection().use { conn ->
        val count = conn.prepareStatement("SELECT count(*) FROM information_schema.schemata WHERE schema_name='_v'").oneOrNull {
            it.getInt(1)
        } ?: 0
        if (count == 0) {
            logger.info("versioning schema not present, not running drop sql")
            return
        }

        val sqlDrop = File("${cfg.sqlDir}/$sqlFilePrefix-drop.sql").readText()
        try {
        conn.execSQLUpdate(sqlDrop) // TODO can fail ?
        } catch (e: Exception) {
            
        }
    }
}