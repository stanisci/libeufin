import kotlin.system.exitProcess
import org.slf4j.Logger
import org.slf4j.LoggerFactory

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

val logger: Logger = LoggerFactory.getLogger("tech.libeufin.util")
/**
 * Helper function that wraps throwable code and
 * (1) prints the error message and (2) terminates
 * the current process, should one exception occur.
 *
 * Note: should be called when it is REALLY required
 * to stop the process when the exception cannot be
 * handled.  Notably, when the database cannot be reached.
 */
fun execThrowableOrTerminate(func: () -> Unit) {
    try {
        func()
    } catch (e: Exception) {
        println(e.message)
        exitProcess(1)
    }
}

fun printLnErr(errorMessage: String) {
    System.err.println(errorMessage)
}