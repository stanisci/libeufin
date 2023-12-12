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

package tech.libeufin.util

import ConfigSource
import TalerConfig
import TalerConfigError
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.types.*
import com.github.ajalt.clikt.parameters.arguments.*
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.groups.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.util.ConfigCli")

fun cliCmd(logger: Logger, lambda: () -> Unit) {
    try {
        lambda()
    } catch (e: Exception) {
        logger.error(e.message)
        exitProcess(1)
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


    override fun run() = cliCmd(logger) {
        val config = talerConfig(configSource, common.config)
        if (isPath) {
            val res = config.lookupPath(sectionName, optionName)
            if (res == null) {
                logger.error("value not found in config")
                exitProcess(2)
            }
            println(res)
        } else {
            val res = config.lookupString(sectionName, optionName)
            if (res == null) {
                logger.error("value not found in config")
                exitProcess(2)
            }
            println(res)
        }
    }
}



private class CliConfigPathsub(private val configSource: ConfigSource) : CliktCommand("Substitute variables in a path", name = "pathsub") {
    private val common by CommonOption()
    private val pathExpr by argument()

    override fun run() = cliCmd(logger) {
        val config = talerConfig(configSource, common.config)
        println(config.pathsub(pathExpr))
    }
}

private class CliConfigDump(private val configSource: ConfigSource) : CliktCommand("Dump the configuration", name = "dump") {
    private val common by CommonOption()

    override fun run() = cliCmd(logger) {
        val config = talerConfig(configSource, common.config)
        println("# install path: ${config.getInstallPath()}")
        println(config.stringify())
    }
}
