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
import io.ktor.http.parseQueryString
import java.net.URI
import java.nio.file.Path
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import kotlin.io.path.Path

fun currentUser(): String = System.getProperty("user.name")

/**
 * This function converts postgresql:// URIs to JDBC URIs.
 *
 * URIs that are already jdbc: URIs are passed through.
 *
 * This avoids the user having to create complex JDBC URIs for postgres connections.
 * They are especially complex when using unix domain sockets, as they're not really
 * supported natively by JDBC.
 */
fun jdbcFromPg(pgConn: String): String {
    // Pass through jdbc URIs.
    if (pgConn.startsWith("jdbc:")) {
        return pgConn
    }
    if (!pgConn.startsWith("postgresql://") && !pgConn.startsWith("postgres://")) {
        throw Exception("Not a Postgres connection string: $pgConn")
    }
    var maybeUnixSocket = false
    val uri = URI(pgConn)
    val params = parseQueryString(uri.query ?: "", decode = false)

    val host = uri.host ?: params["host"] ?: System.getenv("PGHOST")
    if (host == null || host.startsWith('/')) {
        val port = (if (uri.port == -1) null else uri.port.toString()) ?: params["port"] ?: System.getenv("PGPORT") ?: "5432"
        val user = params["user"] ?: currentUser()
        val unixPath = (host ?:"/var/run/postgresql") + "/.s.PGSQL.$port"
        return "jdbc:postgresql://localhost${uri.path}?user=$user&socketFactory=org.newsclub.net.unix." +
                "AFUNIXSocketFactory\$FactoryArg&socketFactoryArg=$unixPath"
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
    val jdbcConnStr = jdbcFromPg(dbConfig)
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
