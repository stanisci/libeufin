/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023 Stanisci and Dold.

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
package tech.libeufin.bank

import java.security.SecureRandom
import java.util.UUID
import java.text.DecimalFormat

object Tan {
    private val CODE_FORMAT = DecimalFormat("00000000");  
    private val SECURE_RNG = SecureRandom()

    fun genCode(): String {
        val rand = SECURE_RNG.nextInt(100000000)
        val code = CODE_FORMAT.format(rand)
        return code
    }
}

