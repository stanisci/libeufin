package tech.libeufin.nexus

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import tech.libeufin.util.initializeDatabaseTables
import tech.libeufin.util.resetDatabaseTables
import kotlin.system.exitProcess

/**
 * Runs the argument and fails the process, if that throws
 * an exception.
 *
 * @param getLambda function that might return a value.
 * @return the value from getLambda.
 */
fun <T>doOrFail(getLambda: () -> T): T =
    try {
        getLambda()
    } catch (e: Exception) {
        logger.error(e.message)
        exitProcess(1)
    }

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
            if (requestReset) {
                resetDatabaseTables(cfg, sqlFilePrefix = "libeufin-nexus")
            }
            initializeDatabaseTables(cfg, sqlFilePrefix = "libeufin-nexus")
        }
    }
}