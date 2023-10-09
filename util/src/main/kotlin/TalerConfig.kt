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

import java.io.File
import java.nio.file.Paths
import kotlin.io.path.Path
import kotlin.io.path.isReadable
import kotlin.io.path.listDirectoryEntries

private data class Entry(val value: String)

private data class Section(
    val entries: MutableMap<String, Entry>,
)

private val reEmptyLine = Regex("^\\s*$")
private val reComment = Regex("^\\s*#.*$")
private val reSection = Regex("^\\s*\\[\\s*([^]]*)\\s*]\\s*$")
private val reParam = Regex("^\\s*([^=]+?)\\s*=\\s*(.*?)\\s*$")
private val reDirective = Regex("^\\s*@([a-zA-Z-_]+)@\\s*(.*?)\\s*$")

class TalerConfigError(m: String) : Exception(m)

data class ConfigSource(
    val componentName: String = "taler",
    val installPathBinary: String = "taler-config",
)

/**
 * Reader and writer for Taler-style configuration files.
 *
 * The configuration file format is similar to INI files
 * and fully described in the taler.conf man page.
 *
 * @param configSource information about where to load configuration defaults from
 */
class TalerConfig(
    private val configSource: ConfigSource,
) {
    private val sectionMap: MutableMap<String, Section> = mutableMapOf()

    private val componentName = configSource.componentName
    private val installPathBinary = configSource.installPathBinary

    private fun internalLoadFromString(s: String, sourceFilename: String?) {
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
                if (sourceFilename == null) {
                    throw TalerConfigError("Directives are only supported when loading from file")
                }
                val directiveName = directiveMatch.groups[1]!!.value.lowercase()
                val directiveArg = directiveMatch.groups[2]!!.value
                when (directiveName) {
                    "inline" -> {
                        val innerFilename = normalizeInlineFilename(sourceFilename, directiveArg.trim())
                        this.loadFromFilename(innerFilename)
                    }

                    "inline-matching" -> {
                        val glob = directiveArg.trim()
                        this.loadFromGlob(sourceFilename, glob)
                    }

                    "inline-secret" -> {
                        val arg = directiveArg.trim()
                        val sp = arg.split(" ")
                        if (sp.size != 2) {
                            throw TalerConfigError("invalid configuration, @inline-secret@ directive requires exactly two arguments")
                        }
                        val sectionName = sp[0]
                        val secretFilename = normalizeInlineFilename(sourceFilename, sp[1])
                        loadSecret(sectionName, secretFilename)
                    }

                    else -> {
                        throw TalerConfigError("unsupported directive '$directiveName'")
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
                throw TalerConfigError("section expected")
            }

            val paramMatch = reParam.matchEntire(line)

            if (paramMatch != null) {
                val optName = paramMatch.groups[1]!!.value.uppercase()
                var optVal = paramMatch.groups[2]!!.value
                if (optVal.startsWith('"') && optVal.endsWith('"')) {
                    optVal = optVal.substring(1, optVal.length - 1)
                }
                val section = provideSection(currentSection)
                section.entries[optName] = Entry(
                    value = optVal
                )
                continue
            }
            throw TalerConfigError("expected section header, option assignment or directive in line $lineNum file ${sourceFilename ?: "<input>"}")
        }
    }

    private fun loadFromGlob(parentFilename: String, glob: String) {
        val fullFileglob: String
        val parentDir = Path(parentFilename).parent!!.toString()
        if (glob.startsWith("/")) {
            fullFileglob = glob
        } else {
            fullFileglob = Paths.get(parentDir, glob).toString()
        }

        val head = Path(fullFileglob).parent.toString()
        val tail = Path(fullFileglob).fileName.toString()

        // FIXME: Check that the Kotlin glob matches the glob from our spec
        for (entry in Path(head).listDirectoryEntries(tail)) {
            loadFromFilename(entry.toString())
        }
    }

    private fun normalizeInlineFilename(parentFilename: String, f: String): String {
        if (f[0] == '/') {
            return f
        }
        val parentDirPath = Path(parentFilename).toRealPath().parent
        if (parentDirPath == null) {
            throw TalerConfigError("unable to normalize inline path, cannot resolve parent directory of $parentFilename")
        }
        val parentDir = parentDirPath.toString()
        return Paths.get(parentDir, f).toRealPath().toString()
    }

    private fun loadSecret(sectionName: String, secretFilename: String) {
        if (!Path(secretFilename).isReadable()) {
            logger.warn("unable to read secrets from $secretFilename")
        } else {
            this.loadFromFilename(secretFilename)
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

    fun loadFromString(s: String) {
        internalLoadFromString(s, null)
    }

    private fun lookupEntry(section: String, option: String): Entry? {
        val canonSection = section.uppercase()
        val canonOption = option.uppercase()
        return this.sectionMap[canonSection]?.entries?.get(canonOption)
    }

    /**
     * Look up a string value from the configuration.
     *
     * Return null if the value was not found in the configuration.
     */
    fun lookupValueString(section: String, option: String): String? {
        return lookupEntry(section, option)?.value
    }

    fun requireValueString(section: String, option: String): String {
        val entry = lookupEntry(section, option)
        if (entry == null) {
            throw TalerConfigError("expected string in configuration section $section option $option")
        }
        return entry.value
    }

    fun requireValueNumber(section: String, option: String): Long {
        val entry = lookupEntry(section, option)
        if (entry == null) {
            throw TalerConfigError("expected string in configuration section $section option $option")
        }
        return entry.value.toLong(10)
    }

    fun lookupValueBooleanDefault(section: String, option: String, default: Boolean): Boolean {
        val entry = lookupEntry(section, option)
        if (entry == null) {
            return default
        }
        val v = entry.value.lowercase()
        if (v == "yes") {
            return true;
        }
        if (v == "false") {
            return false;
        }
        throw TalerConfigError("expected yes/no in configuration section $section option $option but got $v")
    }

    private fun setSystemDefault(section: String, option: String, value: String) {
        // FIXME: The value should be marked as a system default for diagnostics pretty printing
        val sec = provideSection(section)
        sec.entries[option.uppercase()] = Entry(value = value)
    }

    fun putValueString(section: String, option: String, value: String) {
        val sec = provideSection(section)
        sec.entries[option.uppercase()] = Entry(value = value)
    }

    fun lookupValuePath(section: String, option: String): String? {
        val entry = lookupEntry(section, option)
        if (entry == null) {
            return null
        }
        return pathsub(entry.value)
    }

    fun requireValuePath(section: String, option: String): String {
        val res = lookupValuePath(section, option)
        if (res == null) {
            throw TalerConfigError("expected path for section $section option $option")
        }
        return res
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
                outStr.appendLine("$optionName = ${entry.value}")
            }
            if (headerWritten) {
                outStr.appendLine()
            }
        }
        return outStr.toString()
    }

    /**
     * Read values into the configuration from the given entry point
     * filename.  Defaults are *not* loaded automatically.
     */
    fun loadFromFilename(filename: String) {
        val f = File(filename)
        val contents = f.readText()
        internalLoadFromString(contents, filename)
    }

    private fun loadDefaultsFromDir(dirname: String) {
        for (filePath in Path(dirname).listDirectoryEntries()) {
            loadFromFilename(filePath.toString())
        }
    }

    /**
     * Load configuration defaults from the file system
     * and populate the PATHS section based on the installation path.
     */
    fun loadDefaults() {
        val installDir = getInstallPath()
        val baseConfigDir = Paths.get(installDir, "share/$componentName/config.d").toString()
        setSystemDefault("PATHS", "PREFIX", "${installDir}/")
        setSystemDefault("PATHS", "BINDIR", "${installDir}/bin/")
        setSystemDefault("PATHS", "LIBEXECDIR", "${installDir}/$componentName/libexec/")
        setSystemDefault("PATHS", "DOCDIR", "${installDir}/share/doc/$componentName/")
        setSystemDefault("PATHS", "ICONDIR", "${installDir}/share/icons/")
        setSystemDefault("PATHS", "LOCALEDIR", "${installDir}/share/locale/")
        setSystemDefault("PATHS", "LIBDIR", "${installDir}/lib/$componentName/")
        setSystemDefault("PATHS", "DATADIR", "${installDir}/share/$componentName/")
        loadDefaultsFromDir(baseConfigDir)
    }

    private fun variableLookup(x: String, recursionDepth: Int = 0): String? {
        val pathRes = this.lookupValueString("PATHS", x)
        if (pathRes != null) {
            return pathsub(pathRes, recursionDepth + 1)
        }
        val envVal = System.getenv(x)
        if (envVal != null) {
            return envVal
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
    fun pathsub(x: String, recursionDepth: Int = 0): String {
        if (recursionDepth > 128) {
            throw TalerConfigError("recursion limit in path substitution exceeded")
        }
        val result = StringBuilder()
        var l = 0
        val s = x
        while (l < s.length) {
            if (s[l] != '$') {
                // normal character
                result.append(s[l])
                l++;
                continue
            }
            if (l + 1 < s.length && s[l + 1] == '{') {
                // ${var}
                var depth = 1
                val start = l
                var p = start + 2;
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
                        throw TalerConfigError("malformed variable expression can't resolve variable '$varName'")
                    }
                }
                throw TalerConfigError("malformed variable expression (unbalanced)")
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
        return result.toString()
    }

    /**
     * Load configuration values from the file system.
     * If no entrypoint is specified, the default entrypoint
     * is used.
     */
    fun load(entrypoint: String? = null) {
        loadDefaults()
        if (entrypoint != null) {
            loadFromFilename(entrypoint)
        } else {
            val defaultFilename = findDefaultConfigFilename()
            if (defaultFilename != null) {
                loadFromFilename(defaultFilename)
            }
        }
    }

    /**
     * Determine the filename of the default configuration file.
     *
     * If no such file can be found, return null.
     */
    private fun findDefaultConfigFilename(): String? {
        val xdg = System.getenv("XDG_CONFIG_HOME")
        val home = System.getenv("HOME")

        var filename: String? = null
        if (xdg != null) {
            filename = Paths.get(xdg, "$componentName.conf").toString()
        } else if (home != null) {
            filename = Paths.get(home, ".config/$componentName.conf").toString()
        }
        if (filename != null && File(filename).exists()) {
            return filename
        }
        val etc1 = "/etc/$componentName.conf"
        if (File(etc1).exists()) {
            return etc1
        }
        val etc2 = "/etc/$componentName/$componentName.conf"
        if (File(etc2).exists()) {
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
            val possiblePath = Paths.get(p, name).toString()
            if (File(possiblePath).exists()) {
                return Paths.get(p, "..").toRealPath().toString()
            }
        }
        return "/usr"
    }
}
