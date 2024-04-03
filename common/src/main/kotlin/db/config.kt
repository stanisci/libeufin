/*
 * This file is part of LibEuFin.
 * Copyright (C) 2024 Taler Systems S.A.
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

package tech.libeufin.common.db

import tech.libeufin.common.*
import org.postgresql.ds.PGSimpleDataSource
import org.postgresql.jdbc.PgConnection
import org.postgresql.util.PSQLState
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Path
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import kotlin.io.path.Path

fun getCurrentUser(): String = System.getProperty("user.name")

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
        throw Exception("Not a Postgres connection string: $pgConn")
    }
    var maybeUnixSocket = false
    val parsed = URI(pgConn)
    var hostAsParam: String? = if (parsed.query != null) {
        getQueryParam(parsed.query, "host")
    } else {
        null
    }
    var pgHost = System.getenv("PGHOST")
    if (null == pgHost)
      pgHost = parsed.host
    var pgPort = System.getenv("PGPORT")
    if (null == pgPort) {
      if (-1 == parsed.port)
        pgPort = "5432"
      else
        pgPort = parsed.port.toString()
    }

    /**
     * In some cases, it is possible to leave the hostname empty
     * and specify it via a query param, therefore a "postgresql:///"-starting
     * connection string does NOT always mean Unix domain socket.
     * https://www.postgresql.org/docs/current/libpq-connect.html#LIBPQ-CONNSTRING
     */
    if (pgHost == null &&
        (hostAsParam == null || hostAsParam.startsWith('/'))
    ) {
        maybeUnixSocket = true
    }
    if (pgHost != null &&
        (pgHost.startsWith('/'))
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
        if ( (null == hostAsParam) && (null != pgHost) )
          hostAsParam = pgHost + "/.s.PGSQL." + pgPort
        val socketLocation = hostAsParam ?: "/var/run/postgresql/.s.PGSQL." + pgPort
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
    logger.info("connecting to database via JDBC string '$pgConn'")
    return "jdbc:$pgConn"
}

data class DatabaseConfig(
    val dbConnStr: String,
    val sqlDir: Path
)

fun pgDataSource(dbConfig: String): PGSimpleDataSource {
    val jdbcConnStr = getJdbcConnectionFromPg(dbConfig)
    logger.debug("connecting to database via JDBC string '$jdbcConnStr'")
    val pgSource = PGSimpleDataSource()
    pgSource.setUrl(jdbcConnStr)
    pgSource.prepareThreshold = 1
    return pgSource
}

fun PGSimpleDataSource.pgConnection(schema: String? = null): PgConnection {
    val conn = connection.unwrap(PgConnection::class.java)
    if (schema != null) conn.execSQLUpdate("SET search_path TO $schema")
    return conn
}
