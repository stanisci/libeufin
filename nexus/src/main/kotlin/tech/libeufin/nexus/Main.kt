/*
 * This file is part of LibEuFin.
 * Copyright (C) 2019 Stanisci and Dold.

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

package tech.libeufin.nexus

import com.github.ajalt.clikt.output.CliktHelpFormatter
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.prompt
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.nexus.server.serverMain
import tech.libeufin.util.CryptoUtil.hashpw
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.ajalt.clikt.parameters.types.int
import execThrowableOrTerminate
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.options.versionOption
import startServer
import tech.libeufin.nexus.iso20022.parseCamtMessage
import tech.libeufin.nexus.server.client
import tech.libeufin.nexus.server.nexusApp
import tech.libeufin.util.*
import java.io.File
import kotlin.system.exitProcess

val logger: Logger = LoggerFactory.getLogger("tech.libeufin.nexus")
val NEXUS_DB_ENV_VAR_NAME = "LIBEUFIN_NEXUS_DB_CONNECTION"

class NexusCommand : CliktCommand() {
    init {
        versionOption(getVersion())
    }
    override fun run() = Unit
}

class Serve : CliktCommand("Run nexus HTTP server") {
    init {
        context {
            helpFormatter = CliktHelpFormatter(showDefaultValues = true)
        }
    }
    private val host by option().default("127.0.0.1")
    private val port by option().int().default(5001)
    private val withUnixSocket by option(
        help = "Bind the Sandbox to the Unix domain socket at PATH.  Overrides" +
                " --port, when both are given", metavar = "PATH"
    )
    private val logLevel by option()
    override fun run() {
        setLogLevel(logLevel)
        execThrowableOrTerminate {
            dbCreateTables(getDbConnFromEnv(NEXUS_DB_ENV_VAR_NAME))
        }
        startOperationScheduler(client)
        if (withUnixSocket != null) {
            startServer(
                withUnixSocket ?: throw Exception("Could not use the Unix domain socket path value!"),
                app = nexusApp
            )
            exitProcess(0)
        }
        serverMain(host, port)
    }
}

class ParseCamt : CliktCommand("Parse a camt file") {
    private val logLevel by option()
    private val filename by argument("FILENAME", "File in CAMT format")
    override fun run() {
        setLogLevel(logLevel)
        val camtText = File(filename).readText(Charsets.UTF_8)
        val res = parseCamtMessage(XMLUtil.parseStringIntoDom(camtText))
        println(jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(res))
    }
}

class ResetTables : CliktCommand("Drop all the tables from the database") {
    init {
        context {
            helpFormatter = CliktHelpFormatter(showDefaultValues = true)
        }
    }
    override fun run() {
        val dbConnString = getDbConnFromEnv(NEXUS_DB_ENV_VAR_NAME)
        execThrowableOrTerminate {
            dbDropTables(dbConnString)
            dbCreateTables(dbConnString)
        }
    }
}

class Superuser : CliktCommand("Add superuser or change pw") {
    private val username by argument("USERNAME", "User name of superuser")
    private val password by option().prompt(requireConfirmation = true, hideInput = true)
    override fun run() {
        execThrowableOrTerminate {
            dbCreateTables(getDbConnFromEnv(NEXUS_DB_ENV_VAR_NAME))
        }
        transaction {
            val hashedPw = hashpw(password)
            val user = NexusUserEntity.find { NexusUsersTable.username eq username }.firstOrNull()
            if (user == null) {
                NexusUserEntity.new {
                    this.username = this@Superuser.username
                    this.passwordHash = hashedPw
                    this.superuser = true
                }
            } else {
                if (!user.superuser) {
                    println("Can only change password for superuser with this command.")
                    throw ProgramResult(1)
                }
                user.passwordHash = hashedPw
            }
        }
    }
}

fun main(args: Array<String>) {
    NexusCommand()
        .subcommands(Serve(), Superuser(), ParseCamt(), ResetTables())
        .main(args)
}
