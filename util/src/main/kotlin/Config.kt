package tech.libeufin.util

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.util.ContextInitializer
import ch.qos.logback.core.util.Loader
import io.ktor.server.application.*
import io.ktor.util.*
import org.slf4j.LoggerFactory
import printLnErr
import kotlin.system.exitProcess

/**
 * Putting those values into the 'attributes' container because they
 * are needed by the util routines that do NOT have Sandbox and Nexus
 * as dependencies, and therefore cannot access their global variables.
 *
 * Note: putting Sandbox and Nexus as Utils dependencies would result
 * into circular dependency.
 */
val WITH_AUTH_ATTRIBUTE_KEY = AttributeKey<Boolean>("withAuth")
val ADMIN_PASSWORD_ATTRIBUTE_KEY = AttributeKey<String>("adminPassword")

fun getVersion(): String {
    return Loader.getResource(
        "version.txt", ClassLoader.getSystemClassLoader()
    ).readText()
}

/**
 * Set system properties to wanted values, and load logback configuration after.
 * While it can set any system property, it is used only to set the log file name.
 *
 * @param logFile filename of logfile.  If null, then no logfile will be produced.
 * @param logFileNameAsProperty property that indicates the logfile name in logback configuration.
 * @param configFileName name of logback's config file.  Typically something different
 * from "logback.xml" (otherwise logback will load it by itself upon startup.)
 */
fun setLogFile(logFile: String?, logFileNameAsProperty: String, configFileName: String) {
    if (logFile != null) System.setProperty(logFileNameAsProperty, logFile)
    val configFilePath = Loader.getResource(configFileName, ClassLoader.getSystemClassLoader())
    if (configFilePath == null) {
        println("Warning: could not find log config file")
    }
    System.setProperty(ContextInitializer.CONFIG_FILE_PROPERTY, configFilePath.toString())
}

/**
 * Set level of any logger belonging to LibEuFin (= has "libeufin" in name)
 * _and_ found under the calling classpath (= obeying to the same logback.xml)
 */
fun setLogLevel(logLevel: String?) {
    when (val immutable = logLevel) {
        is String -> {
            val ctx = LoggerFactory.getILoggerFactory() as LoggerContext
            val loggers: List<ch.qos.logback.classic.Logger> = ctx.loggerList
            loggers.forEach {
                if (it.name.contains("libeufin")) {
                    it.level = Level.toLevel(immutable)
                }
            }
        }
    }
}

/**
 * Retun the attribute, or throw 500 Internal server error.
 */
fun <T : Any>ApplicationCall.ensureAttribute(key: AttributeKey<T>): T {
    if (!this.attributes.contains(key)) {
        println("Error: attribute $key not found along the call.")
        throw internalServerError("Attribute $key not found along the call.")
    }
    return this.attributes[key]
}

fun getValueFromEnv(varName: String): String? {
    val ret = System.getenv(varName)
    if (ret.isNullOrBlank() or ret.isNullOrEmpty()) {
        println("WARNING, $varName was not found in the environment. Will stay unknown")
        return null
    }
    return ret
}

fun getDbConnFromEnv(varName: String): String {
    val dbConnStr = System.getenv(varName)
    if (dbConnStr.isNullOrBlank() or dbConnStr.isNullOrEmpty()) {
        printLnErr("\nError: DB connection string undefined or invalid in the env variable $varName.")
        printLnErr("\nThe following two examples are valid connection strings:")
        printLnErr("\njdbc:sqlite:/tmp/libeufindb.sqlite3")
        printLnErr("jdbc:postgresql://localhost:5432/libeufindb?user=Foo&password=secret\n")
        exitProcess(1)
    }
    return dbConnStr
}