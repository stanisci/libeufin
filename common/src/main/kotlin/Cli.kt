/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023 Taler Systems S.A.

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

package tech.libeufin.common

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.types.*
import com.github.ajalt.clikt.parameters.arguments.*
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.groups.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

private val logger: Logger = LoggerFactory.getLogger("libeufin-config")

fun cliCmd(logger: Logger, level: Level, lambda: () -> Unit) {
    // Set root log level
    val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
    root.setLevel(ch.qos.logback.classic.Level.convertAnSLF4JLevel(level));
    // Run cli command catching all errors
    try {
        lambda()
    } catch (e: Throwable) {
        var msg = StringBuilder(e.message ?: e::class.simpleName)
        var cause = e.cause;
        while (cause != null) {
            msg.append(": ")
            msg.append(cause.message ?: cause::class.simpleName)
            cause = cause.cause
        }
        logger.error(msg.toString())
        logger.debug("$e", e)
        throw ProgramResult(1)
    }
}

private fun talerConfig(configSource: ConfigSource, configPath: String?): TalerConfig {
    val config = TalerConfig(configSource)
    config.load(configPath)
    return config
}

class CommonOption: OptionGroup() {
    val config by option(
        "--config", "-c",
        help = "Specifies the configuration file"
    ).path(
        mustExist = true, 
        canBeDir = false, 
        mustBeReadable = true,
    ).convert { it.toString() } // TODO take path to load config
    val log by option(
        "--log", "-L",
        help = "Configure logging to use LOGLEVEL"
    ).enum<Level>().default(Level.INFO)
}

class CliConfigCmd(configSource: ConfigSource) : CliktCommand("Inspect or change the configuration", name = "config") {
    init {
        subcommands(CliConfigDump(configSource), CliConfigPathsub(configSource), CliConfigGet(configSource))
    }

    override fun run() = Unit
}

private class CliConfigGet(private val configSource: ConfigSource) : CliktCommand("Lookup config value", name = "get") {
    private val common by CommonOption()
    private val isPath by option(
        "--filename", "-f",
        help = "Interpret value as path with dollar-expansion"
    ).flag()
    private val sectionName by argument()
    private val optionName by argument()


    override fun run() = cliCmd(logger, common.log) {
        val config = talerConfig(configSource, common.config)
        if (isPath) {
            val res = config.lookupPath(sectionName, optionName)
            if (res == null) {
                throw Exception("value not found in config")
            }
            println(res)
        } else {
            val res = config.lookupString(sectionName, optionName)
            if (res == null) {
                throw Exception("value not found in config")
            }
            println(res)
        }
    }
}



private class CliConfigPathsub(private val configSource: ConfigSource) : CliktCommand("Substitute variables in a path", name = "pathsub") {
    private val common by CommonOption()
    private val pathExpr by argument()

    override fun run() = cliCmd(logger, common.log) {
        val config = talerConfig(configSource, common.config)
        println(config.pathsub(pathExpr))
    }
}

private class CliConfigDump(private val configSource: ConfigSource) : CliktCommand("Dump the configuration", name = "dump") {
    private val common by CommonOption()

    override fun run() = cliCmd(logger, common.log) {
        val config = talerConfig(configSource, common.config)
        println("# install path: ${config.getInstallPath()}")
        println(config.stringify())
    }
}
