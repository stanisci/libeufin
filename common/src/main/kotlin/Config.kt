/*
 * This file is part of LibEuFin.
 * Copyright (C) 2024 Taler Systems S.A.

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