/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023 Taler Systems S.A.
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

package tech.libeufin.common

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.*
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeParseException
import kotlin.io.path.*

private val logger: Logger = LoggerFactory.getLogger("libeufin-config")

private data class Section(
    val entries: MutableMap<String, String>,
)

private val reEmptyLine = Regex("^\\s*$")
private val reComment = Regex("^\\s*#.*$")
private val reSection = Regex("^\\s*\\[\\s*([^]]*)\\s*]\\s*$")
private val reParam = Regex("^\\s*([^=]+?)\\s*=\\s*(.*?)\\s*$")
private val reDirective = Regex("^\\s*@([a-zA-Z-_]+)@\\s*(.*?)\\s*$")

class TalerConfigError private constructor (m: String) : Exception(m) {
    companion object {
        fun missing(type: String, section: String, option: String): TalerConfigError =
            TalerConfigError("Missing $type option '$option' in section '$section'")

        fun invalid(type: String, section: String, option: String, err: String): TalerConfigError =
            TalerConfigError("Expected $type option '$option' in section '$section': $err")

        fun generic(msg: String): TalerConfigError =
            TalerConfigError(msg)
    }
}

/**
 * Information about how the configuration is loaded.
 *
 * The entry point for the configuration will be the first file from this list:
 * - /etc/$projectName/$componentName.conf
 * - /etc/$componentName.conf
 */
data class ConfigSource(
    /**
     * Name of the high-level project.
     */
    val projectName: String = "taler",
    /**
     * Name of the component within the package.
     */
    val componentName: String = "taler",
    /**
     * Name of the binary that will be located on $PATH to
     * find the installation path of the package.
     */
    val installPathBinary: String = "taler-config",
)

fun ConfigSource.fromMem(content: String): TalerConfig {
    val cfg = TalerConfig(this)
    cfg.loadDefaults()
    cfg.loadFromMem(content, null)
    return cfg
}

fun ConfigSource.fromFile(file: Path?): TalerConfig {
    val cfg = TalerConfig(this)
    cfg.loadDefaults()
    val path = file ?: cfg.findDefaultConfigFilename()
    if (path != null) cfg.loadFromFile(path)
    return cfg
}

/**
 * Reader and writer for Taler-style configuration files.
 *
 * The configuration file format is similar to INI files
 * and fully described in the taler.conf man page.
 *
 * @param configSource information about where to load configuration defaults from
 */
class TalerConfig internal constructor(
    val configSource: ConfigSource
) {
    private val sectionMap: MutableMap<String, Section> = mutableMapOf()

    private val componentName = configSource.componentName
    private val projectName = configSource.projectName
    private val installPathBinary = configSource.installPathBinary
    val sections: Set<String> get() = sectionMap.keys

    /**
     * Load configuration defaults from the file system
     * and populate the PATHS section based on the installation path.
     */
    internal fun loadDefaults() {
        val installDir = getInstallPath()
        val baseConfigDir = Path(installDir, "share/$projectName/config.d")
        setSystemDefault("PATHS", "PREFIX", "$installDir/")
        setSystemDefault("PATHS", "BINDIR", "$installDir/bin/")
        setSystemDefault("PATHS", "LIBEXECDIR", "$installDir/$projectName/libexec/")
        setSystemDefault("PATHS", "DOCDIR", "$installDir/share/doc/$projectName/")
        setSystemDefault("PATHS", "ICONDIR", "$installDir/share/icons/")
        setSystemDefault("PATHS", "LOCALEDIR", "$installDir/share/locale/")
        setSystemDefault("PATHS", "LIBDIR", "$installDir/lib/$projectName/")
        setSystemDefault("PATHS", "DATADIR", "$installDir/share/$projectName/")
        for (filePath in baseConfigDir.listDirectoryEntries()) {
            loadFromFile(filePath)
        }
    }

    private fun loadFromGlob(source: Path, glob: String) {
        // FIXME: Check that the Kotlin glob matches the glob from our spec
        for (entry in source.parent.listDirectoryEntries(glob)) {
            loadFromFile(entry)
        }
    }

    private fun loadSecret(sectionName: String, secretFilename: Path) {
        if (!secretFilename.isReadable()) {
            logger.warn("unable to read secrets from $secretFilename")
        } else {
            loadFromFile(secretFilename)
        }
    }

    internal fun loadFromFile(file: Path) {
        val content =  try {
            file.readText()
        } catch (e: Exception) {
            when (e) {
                is NoSuchFileException -> throw Exception("Could not read config at '$file': no such file")
                is AccessDeniedException -> throw Exception("Could not read config at '$file': permission denied")
                else -> throw Exception("Could not read config at '$file'", e)
            }
        }
        loadFromMem(content, file)
    }

    internal fun loadFromMem(s: String, source: Path?) {
        val lines = s.lines()
        var lineNum = 0
        var currentSection: String? = null
        for (line in lines) {
            lineNum++
            if (reEmptyLine.matches(line)) {
                continue
            }
            if (reComment.matches(line)) {
                continue
            }

            val directiveMatch = reDirective.matchEntire(line)
            if (directiveMatch != null) {
                if (source == null) {
                    throw TalerConfigError.generic("Directives are only supported when loading from file")
                }
                val directiveName = directiveMatch.groups[1]!!.value.lowercase()
                val directiveArg = directiveMatch.groups[2]!!.value
                when (directiveName) {
                    "inline" -> {
                        val innerFilename = source.resolveSibling(directiveArg.trim())
                        loadFromFile(innerFilename)
                    }
                    "inline-matching" -> {
                        val glob = directiveArg.trim()
                        loadFromGlob(source, glob)
                    }
                    "inline-secret" -> {
                        val arg = directiveArg.trim()
                        val sp = arg.split(" ")
                        if (sp.size != 2) {
                            throw TalerConfigError.generic("invalid configuration, @inline-secret@ directive requires exactly two arguments")
                        }
                        val sectionName = sp[0]
                        val secretFilename = source.resolveSibling(sp[1])
                        loadSecret(sectionName, secretFilename)
                    }

                    else -> {
                        throw TalerConfigError.generic("unsupported directive '$directiveName'")
                    }
                }
                continue
            }

            val secMatch = reSection.matchEntire(line)
            if (secMatch != null) {
                currentSection = secMatch.groups[1]!!.value.uppercase()
                continue
            }
            if (currentSection == null) {
                throw TalerConfigError.generic("section expected")
            }

            val paramMatch = reParam.matchEntire(line)

            if (paramMatch != null) {
                val optName = paramMatch.groups[1]!!.value.uppercase()
                var optVal = paramMatch.groups[2]!!.value
                if (optVal.startsWith('"') && optVal.endsWith('"')) {
                    optVal = optVal.substring(1, optVal.length - 1)
                }
                val section = provideSection(currentSection)
                section.entries[optName] = optVal
                continue
            }
            throw TalerConfigError.generic("expected section header, option assignment or directive in line $lineNum file ${source ?: "<input>"}")
        }
    }

    private fun provideSection(name: String): Section {
        val canonSecName = name.uppercase()
        val existingSec = this.sectionMap[canonSecName]
        if (existingSec != null) {
            return existingSec
        }
        val newSection = Section(entries = mutableMapOf())
        this.sectionMap[canonSecName] = newSection
        return newSection
    }

    private fun setSystemDefault(section: String, option: String, value: String) {
        // FIXME: The value should be marked as a system default for diagnostics pretty printing
        val sec = provideSection(section)
        sec.entries[option.uppercase()] = value
    }

    fun putValueString(section: String, option: String, value: String) {
        val sec = provideSection(section)
        sec.entries[option.uppercase()] = value
    }

    /**
     * Create a string representation of the loaded configuration.
     */
    fun stringify(): String {
        val outStr = StringBuilder()
        this.sectionMap.forEach { (sectionName, section) ->
            var headerWritten = false
            section.entries.forEach { (optionName, entry) ->
                if (!headerWritten) {
                    outStr.appendLine("[$sectionName]")
                    headerWritten = true
                }
                outStr.appendLine("$optionName = $entry")
            }
            if (headerWritten) {
                outStr.appendLine()
            }
        }
        return outStr.toString()
    }

    private fun variableLookup(x: String, recursionDepth: Int = 0): Path? {
        val pathRes = this.lookupString("PATHS", x)
        if (pathRes != null) {
            return pathsub(pathRes, recursionDepth + 1)
        }
        val envVal = System.getenv(x)
        if (envVal != null) {
            return Path(envVal)
        }
        return null
    }

    /**
     * Substitute ${...} and $... placeholders in a string
     * with values from the PATHS section in the
     * configuration and environment variables
     *
     * This substitution is typically only done for paths.
     */
    fun pathsub(x: String, recursionDepth: Int = 0): Path {
        if (recursionDepth > 128) {
            throw TalerConfigError.generic("recursion limit in path substitution exceeded")
        }
        val result = StringBuilder()
        var l = 0
        val s = x
        while (l < s.length) {
            if (s[l] != '$') {
                // normal character
                result.append(s[l])
                l++
                continue
            }
            if (l + 1 < s.length && s[l + 1] == '{') {
                // ${var}
                var depth = 1
                val start = l
                var p = start + 2
                var hasDefault = false
                var insideNamePath = true
                // Find end of the ${...} expression
                while (p < s.length) {
                    if (s[p] == '}') {
                        insideNamePath = false
                        depth--
                    } else if (s.length > p + 1 && s[p] == '$' && s[p + 1] == '{') {
                        depth++
                        insideNamePath = false
                    } else if (s.length > p + 1 && insideNamePath && s[p] == ':' && s[p + 1] == '-') {
                        hasDefault = true
                    }
                    p++
                    if (depth == 0) {
                        break
                    }
                }
                if (depth == 0) {
                    val inner = s.substring(start + 2, p - 1)
                    val varName: String
                    val varDefault: String?
                    if (hasDefault) {
                        val res = inner.split(":-", limit = 2)
                        varName = res[0]
                        varDefault = res[1]
                    } else {
                        varName = inner
                        varDefault = null
                    }
                    val r = variableLookup(varName, recursionDepth + 1)
                    if (r != null) {
                        result.append(r)
                        l = p
                        continue
                    } else if (varDefault != null) {
                        val resolvedDefault = pathsub(varDefault, recursionDepth + 1)
                        result.append(resolvedDefault)
                        l = p
                        continue
                    } else {
                        throw TalerConfigError.generic("malformed variable expression can't resolve variable '$varName'")
                    }
                }
                throw TalerConfigError.generic("malformed variable expression (unbalanced)")
            } else {
                // $var
                var varEnd = l + 1
                while (varEnd < s.length && (s[varEnd].isLetterOrDigit() || s[varEnd] == '_')) {
                    varEnd++
                }
                val varName = s.substring(l + 1, varEnd)
                val res = variableLookup(varName)
                if (res != null) {
                    result.append(res)
                }
                l = varEnd
            }
        }
        return Path(result.toString())
    }

    /**
     * Determine the filename of the default configuration file.
     *
     * If no such file can be found, return null.
     */
    internal fun findDefaultConfigFilename(): Path? {
        val xdg = System.getenv("XDG_CONFIG_HOME")
        val home = System.getenv("HOME")

        var filename: Path? = null
        if (xdg != null) {
            filename = Path(xdg, "$componentName.conf")
        } else if (home != null) {
            filename = Path(home, ".config/$componentName.conf")
        }
        if (filename != null && filename.exists()) {
            return filename
        }
        val etc1 = Path("/etc/$componentName.conf")
        if (etc1.exists()) {
            return etc1
        }
        val etc2 = Path("/etc/$projectName/$componentName.conf")
        if (etc2.exists()) {
            return etc2
        }
        return null
    }

    /**
     * Guess the path that the component was installed to.
     */
    fun getInstallPath(): String {
        // We use the location of the libeufin-bank
        // binary to determine the installation prefix.
        // If for some weird reason it's now found, we
        // fall back to "/usr" as install prefix.
        return getInstallPathFromBinary(installPathBinary)
    }

    private fun getInstallPathFromBinary(name: String): String {
        val pathEnv = System.getenv("PATH")
        val paths = pathEnv.split(":")
        for (p in paths) {
            val possiblePath = Path(p, name)
            if (possiblePath.exists()) {
                return Path(p, "..").toRealPath().toString()
            }
        }
        return "/usr"
    }

    /* ----- Lookup ----- */

     /**
     * Look up a string value from the configuration.
     *
     * Return null if the value was not found in the configuration.
     */
    fun lookupString(section: String, option: String): String? {
        val canonSection = section.uppercase()
        val canonOption = option.uppercase()
        val str = this.sectionMap[canonSection]?.entries?.get(canonOption)
        if (str == null) return null
        if (str == "") return null
        return str
    }

    fun requireString(section: String, option: String): String  =
        lookupString(section, option) ?: throw TalerConfigError.missing("string", section, option)

    fun requireNumber(section: String, option: String): Int {
        val raw = lookupString(section, option) ?: throw TalerConfigError.missing("number", section, option)
        return raw.toIntOrNull() ?: throw TalerConfigError.invalid("number", section, option, "'$raw' not a valid number")
    }

    fun lookupBoolean(section: String, option: String): Boolean? {
        val entry = lookupString(section, option) ?: return null
        return when (val v = entry.lowercase()) {
            "yes" -> true
            "no" -> false
            else -> throw TalerConfigError.invalid("yes/no", section, option, "got '$v'")
        }
    }

    fun requireBoolean(section: String, option: String): Boolean =
        lookupBoolean(section, option) ?: throw TalerConfigError.missing("boolean", section, option)

    fun lookupPath(section: String, option: String): Path? {
        val entry = lookupString(section, option) ?: return null
        return pathsub(entry)
    }

    fun requirePath(section: String, option: String): Path =
        lookupPath(section, option) ?: throw TalerConfigError.missing("path", section, option)

    fun lookupDuration(section: String, option: String): Duration? {
        val entry = lookupString(section, option) ?: return null
        return TIME_AMOUNT_PATTERN.findAll(entry).map { match ->
            val (rawAmount, unit) = match.destructured
            val amount = rawAmount.toLongOrNull() ?: throw TalerConfigError.invalid("temporal", section, option, "'$rawAmount' not a valid temporal amount")
            val value = when (unit) {
                "us" -> 1
                "ms" -> 1000
                "s", "second", "seconds", "\"" -> 1000 * 1000L
                "m", "min", "minute", "minutes", "'" -> 60 * 1000 * 1000L
                "h", "hour", "hours" -> 60 * 60 * 1000 * 1000L
                "d", "day", "days" -> 24 * 60 * 60 * 1000L * 1000L
                "week", "weeks" ->  7 * 24 * 60 * 60 * 1000L * 1000L
                "year", "years", "a" -> 31536000000000L
                else -> throw TalerConfigError.invalid("temporal", section, option, "'$unit' not a valid temporal unit")
            }
            Duration.of(amount * value, ChronoUnit.MICROS)
        }.fold(Duration.ZERO) { a, b -> a.plus(b) }
    }

    fun requireDuration(section: String, option: String): Duration =
        lookupDuration(section, option) ?: throw TalerConfigError.missing("temporal", section, option)

    fun lookupDate(section: String, option: String): Instant? {
        val raw = lookupString(section, option) ?: return null
        val date = try {
            LocalDate.parse(raw)
        } catch (e: DateTimeParseException ) {
            throw TalerConfigError.invalid("date", section, option, "'$raw' not a valid date at index ${e.errorIndex}")
        }
        return date.atStartOfDay(ZoneId.of("UTC")).toInstant()
    }

    fun requireDate(section: String, option: String): Instant =
        lookupDate(section, option) ?: throw TalerConfigError.missing("date", section, option)
    

    companion object {
        private val TIME_AMOUNT_PATTERN = Regex("([0-9]+) ?([a-z'\"]+)")
    }
}
