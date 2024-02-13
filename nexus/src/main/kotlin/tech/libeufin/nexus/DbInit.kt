/*
 * This file is part of LibEuFin.
 * Copyright (C) 2024 Taler Systems S.A.

 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.

 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.

 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */
package tech.libeufin.nexus

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import tech.libeufin.common.*

/**
 * This subcommand tries to load the SQL files that define
 * the Nexus DB schema.  Admits the --reset option to delete
 * the data first.
 */
class DbInit : CliktCommand("Initialize the libeufin-nexus database", name = "dbinit") {
    private val common by CommonOption()
    private val requestReset by option(
        "--reset", "-r",
        help = "Reset database (DANGEROUS: All existing data is lost)"
    ).flag()

    override fun run() = cliCmd(logger, common.log) {
        val cfg = loadConfig(common.config).dbConfig()
        pgDataSource(cfg.dbConnStr).pgConnection().use { conn ->
            if (requestReset) {
                resetDatabaseTables(conn, cfg, sqlFilePrefix = "libeufin-nexus")
            }
            initializeDatabaseTables(conn, cfg, sqlFilePrefix = "libeufin-nexus")
        }
    }
}