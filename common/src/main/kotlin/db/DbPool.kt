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

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.postgresql.jdbc.PgConnection
import org.postgresql.util.PSQLState
import tech.libeufin.common.MIN_VERSION
import tech.libeufin.common.SERIALIZATION_RETRY
import java.sql.SQLException

open class DbPool(cfg: DatabaseConfig, schema: String) : java.io.Closeable {
    val pgSource = pgDataSource(cfg.dbConnStr)
    private val pool: HikariDataSource

    init {
        val config = HikariConfig()
        config.dataSource = pgSource
        config.schema = schema.replace("-", "_")
        config.transactionIsolation = "TRANSACTION_SERIALIZABLE"
        pool = HikariDataSource(config)
        pool.connection.use { con ->
            val meta = con.metaData
            val majorVersion = meta.databaseMajorVersion
            val minorVersion = meta.databaseMinorVersion
            if (majorVersion < MIN_VERSION) {
                throw Exception("postgres version must be at least $MIN_VERSION.0 got $majorVersion.$minorVersion")
            }
            checkMigrations(con, cfg, schema)
        }
    }

    suspend fun <R> conn(lambda: suspend (PgConnection) -> R): R {
        // Use a coroutine dispatcher that we can block as JDBC API is blocking
        return withContext(Dispatchers.IO) {
            pool.connection.use { lambda(it.unwrap(PgConnection::class.java)) }
        }
    }

    suspend fun <R> serializable(lambda: suspend (PgConnection) -> R): R = conn { conn ->
        repeat(SERIALIZATION_RETRY) {
            try {
                return@conn lambda(conn)
            } catch (e: SQLException) {
                if (e.sqlState != PSQLState.SERIALIZATION_FAILURE.state)
                    throw e
            }
        }
        try {
            return@conn lambda(conn)
        } catch (e: SQLException) {
            logger.warn("Serialization failure after $SERIALIZATION_RETRY retry")
            throw e
        }
    }

    override fun close() {
        pool.close()
    }
}