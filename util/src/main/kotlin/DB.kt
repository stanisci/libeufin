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
import org.postgresql.util.PSQLState
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
        if (e.sqlState == PSQLState.UNIQUE_VIOLATION.state) return false
        throw e // rethrowing, not to hide other types of errors.
    }
}

fun PreparedStatement.executeProcedureViolation(): Boolean {
    val savepoint = connection.setSavepoint();
    return try {
        executeUpdate()
        connection.releaseSavepoint(savepoint)
        true
    } catch (e: SQLException) {
        connection.rollback(savepoint);
        if (e.sqlState == PSQLState.UNIQUE_VIOLATION.state) return false
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
        val isInitialized = conn.prepareStatement("SELECT EXISTS(SELECT 1 FROM information_schema.schemata WHERE schema_name='_v')").oneOrNull {
            it.getBoolean(1)
        }!!
        if (!isInitialized) {
            logger.info("versioning schema not present, not running drop sql")
            return
        }

        val sqlDrop = File("${cfg.sqlDir}/$sqlFilePrefix-drop.sql").readText()
        conn.execSQLUpdate(sqlDrop) // TODO can fail ?
    }
}