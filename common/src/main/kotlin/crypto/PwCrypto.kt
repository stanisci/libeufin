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

package tech.libeufin.common.crypto

import tech.libeufin.common.encodeBase64
import java.security.SecureRandom

/** Cryptographic operations for secure password storage and verification */
object PwCrypto {
    // TODO Use a real password hashing method to store passwords

    private val SECURE_RNG = SecureRandom()

    /** Hash [pw] using the strongest supported hashing method */
    fun hashpw(pw: String): String {
        val saltBytes = ByteArray(8)
        SECURE_RNG.nextBytes(saltBytes)
        val salt = saltBytes.encodeBase64()
        val pwh = CryptoUtil.hashStringSHA256("$salt|$pw").encodeBase64()
        return "sha256-salted\$$salt\$$pwh"
    }

    /** Check whether [pw] match hashed [storedPwHash] */
    fun checkpw(pw: String, storedPwHash: String): Boolean {
        val components = storedPwHash.split('$')
        when (val algo = components[0]) {
            "sha256" -> {  // Support legacy unsalted passwords
                if (components.size != 2) throw Exception("bad password hash")
                val hash = components[1]
                val pwh = CryptoUtil.hashStringSHA256(pw).encodeBase64()
                return pwh == hash
            }
            "sha256-salted" -> {
                if (components.size != 3) throw Exception("bad password hash")
                val salt = components[1]
                val hash = components[2]
                val pwh = CryptoUtil.hashStringSHA256("$salt|$pw").encodeBase64()
                return pwh == hash
            }
            else -> throw Exception("unsupported hash algo: '$algo'")
        }
    }
}
