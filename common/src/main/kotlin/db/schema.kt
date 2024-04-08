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

import org.postgresql.ds.PGSimpleDataSource
import org.postgresql.jdbc.PgConnection
import java.sql.Connection
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Only runs versioning.sql if the _v schema is not found.
 *
 * @param conn database connection
 * @param cfg database configuration
 */
private fun maybeApplyV(conn: PgConnection, cfg: DatabaseConfig) {
    conn.transaction {
        val checkVSchema = conn.prepareStatement(
            "SELECT schema_name FROM information_schema.schemata WHERE schema_name = '_v'"
        )
        if (!checkVSchema.executeQueryCheck()) {
            logger.debug("_v schema not found, applying versioning.sql")
            val sqlVersioning = Path("${cfg.sqlDir}/versioning.sql").readText()
            conn.execSQLUpdate(sqlVersioning)
        }
    }
}

private fun migrationsPath(sqlFilePrefix: String): Sequence<String> = sequence {
    for (n in 1..9999) {
        val padded = n.toString().padStart(4, '0')
        yield("$sqlFilePrefix-$padded")
    }
}

// sqlFilePrefix is, for example, "libeufin-bank" or "libeufin-nexus" (no trailing dash).
private fun initializeDatabaseTables(conn: PgConnection, cfg: DatabaseConfig, sqlFilePrefix: String) {
    logger.info("doing DB initialization, sqldir ${cfg.sqlDir}")
    maybeApplyV(conn, cfg)
    conn.transaction {
        val checkStmt = conn.prepareStatement("SELECT EXISTS(SELECT FROM _v.patches where patch_name = ?)")

        for (patchName in migrationsPath(sqlFilePrefix)) {
            checkStmt.setString(1, patchName)
            val applied = checkStmt.one { it.getBoolean(1) }
            if (applied) {
                logger.debug("patch $patchName already applied")
                continue
            }

            val path = Path("${cfg.sqlDir}/$patchName.sql")
            if (!path.exists()) {
                logger.debug("path $path doesn't exist anymore, stopping")
                break
            }
            logger.info("applying patch $path")
            conn.execSQLUpdate(path.readText())
        }
        val sqlProcedures = Path("${cfg.sqlDir}/$sqlFilePrefix-procedures.sql")
        if (!sqlProcedures.exists()) {
            logger.warn("no procedures.sql for the SQL collection: $sqlFilePrefix")
            return@transaction
        }
        logger.info("run procedure.sql")
        conn.execSQLUpdate(sqlProcedures.readText())
    }
}

internal fun checkMigrations(conn: Connection, cfg: DatabaseConfig, sqlFilePrefix: String) {
    val checkStmt = conn.prepareStatement("SELECT EXISTS(SELECT FROM _v.patches where patch_name = ?)")

    for (patchName in migrationsPath(sqlFilePrefix)) {
        checkStmt.setString(1, patchName)
        val path = Path("${cfg.sqlDir}/$patchName.sql")
        if (!path.exists()) break
        val applied = checkStmt.one { it.getBoolean(1) }
        if (!applied) {
            throw Exception("patch $patchName not applied, run '$sqlFilePrefix dbinit'")
        }
    }
}

// sqlFilePrefix is, for example, "libeufin-bank" or "libeufin-nexus" (no trailing dash).
private fun resetDatabaseTables(conn: PgConnection, cfg: DatabaseConfig, sqlFilePrefix: String) {
    logger.info("reset DB, sqldir ${cfg.sqlDir}")
    val sqlDrop = Path("${cfg.sqlDir}/$sqlFilePrefix-drop.sql").readText()
    conn.execSQLUpdate(sqlDrop)
}

fun PGSimpleDataSource.dbInit(cfg: DatabaseConfig, sqlFilePrefix: String, reset: Boolean) {
    pgConnection().use { conn ->
        if (reset) {
            resetDatabaseTables(conn, cfg, sqlFilePrefix)
        }
        initializeDatabaseTables(conn, cfg, sqlFilePrefix)
    }
}