package tech.libeufin.util

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.util.Loader
import io.ktor.util.*
import org.slf4j.LoggerFactory

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
 * Set level of any logger belonging to LibEuFin (= has "libeufin" in name)
 * _and_ found under the calling classpath (= obeying to the same logback.xml)
 */
fun setLogLevel(logLevel: String?) {
    when (logLevel) {
        is String -> {
            val ctx = LoggerFactory.getILoggerFactory() as LoggerContext
            val loggers: List<ch.qos.logback.classic.Logger> = ctx.loggerList
            loggers.forEach {
                if (it.name.contains("libeufin")) {
                    it.level = Level.toLevel(logLevel)
                }
            }
        }
    }
}