package tech.libeufin.nexus

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import tech.libeufin.util.*
import kotlin.system.exitProcess

/**
 * This subcommand tries to load the SQL files that define
 * the Nexus DB schema.  Admits the --reset option to delete
 * the data first.
 */
class DbInit : CliktCommand("Initialize the libeufin-nexus database", name = "dbinit") {
    private val configFile by option(
        "--config", "-c",
        help = "set the configuration file"
    )
    private val requestReset by option(
        "--reset", "-r",
        help = "reset database (DANGEROUS: All existing data is lost)"
    ).flag()

    override fun run() {
        val cfg = loadConfigOrFail(configFile).extractDbConfigOrFail()
        doOrFail {
            pgDataSource(cfg.dbConnStr).pgConnection().use { conn ->
                if (requestReset) {
                    resetDatabaseTables(conn, cfg, sqlFilePrefix = "libeufin-nexus")
                }
                initializeDatabaseTables(conn, cfg, sqlFilePrefix = "libeufin-nexus")
            }
        }
    }
}