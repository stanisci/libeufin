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
import java.util.*
import kotlin.io.path.Path
import kotlin.io.path.listDirectoryEntries

private data class Entry(val value: String)

private data class Section(
    val entries: MutableMap<String, Entry>
)

private val reEmptyLine = Regex("^\\s*$")
private val reComment = Regex("^\\s*#.*$")
private val reSection = Regex("^\\s*\\[\\s*([^]]*)\\s*]\\s*$")
private val reParam = Regex("^\\s*([^=]+?)\\s*=\\s*(.*?)\\s*$")
private val reDirective = Regex("^\\s*@([a-zA-Z-_]+)@\\s*(.*?)\\s*$")

class TalerConfigError(m: String) : Exception(m)

/**
 * Reader and writer for Taler-style configuration files.
 *
 * The configuration file format is similar to INI files
 * and fully described in the taler.conf man page.
 */
class TalerConfig {
    private val sectionMap: MutableMap<String, Section> = mutableMapOf()

    private fun internalLoadFromString(s: String) {
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
                throw NotImplementedError("config directives are not implemented yet")
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
            throw TalerConfigError("expected section header, option assignment or directive in line $lineNum")
        }
    }

    private fun provideSection(name: String): Section {
        val existingSec = this.sectionMap[name]
        if (existingSec != null) {
            return existingSec
        }
        val newSection = Section(entries = mutableMapOf())
        this.sectionMap[name] = newSection
        return newSection
    }

    fun loadFromString(s: String) {
        internalLoadFromString(s)
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

    fun loadFromFilename(filename: String) {
        val f = File(filename)
        val contents = f.readText()
        loadFromString(contents)
    }

    private fun loadDefaultsFromDir(dirname: String) {
        for (filePath in Path(dirname).listDirectoryEntries()) {
            loadFromFilename(filePath.toString())
        }
    }

    fun loadDefaults() {
        val installDir = getTalerInstallPath()
        val baseConfigDir = Paths.get(installDir, "share/taler/config.d").toString()
        loadDefaultsFromDir(baseConfigDir)
    }

    companion object {
        /**
         * Load configuration values from the file system.
         * If no entrypoint is specified, the default entrypoint
         * is used.
         */
        fun load(entrypoint: String? = null): TalerConfig {
            val cfg = TalerConfig()
            cfg.loadDefaults()
            if (entrypoint != null) {
                cfg.loadFromFilename(entrypoint)
            } else {
                val defaultFilename = findDefaultConfigFilename()
                if (defaultFilename != null) {
                    cfg.loadFromFilename(defaultFilename)
                }
            }
            return cfg
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
                filename = Paths.get(xdg, "taler.conf").toString()
            } else if (home != null) {
                filename = Paths.get(home, ".config/taler.conf").toString()
            }
            if (filename != null && File(filename).exists()) {
                return filename
            }
            val etc1 = "/etc/taler.conf"
            if (File(etc1).exists()) {
                return etc1
            }
            val etc2 = "/etc/taler/taler.conf"
            if (File(etc2).exists()) {
                return etc2
            }
            return null
        }

        fun getTalerInstallPath(): String {
            val pathEnv = System.getenv("PATH")
            val paths = pathEnv.split(":")
            for (p in paths) {
                val possiblePath = Paths.get(p, "taler-config").toString()
                if (File(possiblePath).exists()) {
                    return Paths.get(p, "..").toRealPath().toString()
                }
            }
            return "/usr"
        }
    }
}
