package tech.libeufin.util

import ch.qos.logback.core.util.Loader

/**
 * Putting those values into the 'attributes' container because they
 * are needed by the util routines that do NOT have Sandbox and Nexus
 * as dependencies, and therefore cannot access their global variables.
 *
 * Note: putting Sandbox and Nexus as Utils dependencies would result
 * into circular dependency.
 */
fun getVersion(): String {
    return Loader.getResource(
        "version.txt", ClassLoader.getSystemClassLoader()
    ).readText()
}