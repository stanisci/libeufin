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

import org.junit.Test
import tech.libeufin.common.Base32Crockford
import tech.libeufin.common.crypto.CryptoUtil
import tech.libeufin.nexus.ClientPrivateKeysFile
import tech.libeufin.nexus.JSON
import kotlin.test.assertEquals

class MySerializers {
    // Testing deserialization of RSA private keys.
    @Test
    fun rsaPrivDeserialization() {
        val s = Base32Crockford.encode(CryptoUtil.genRSAPrivate(2048).encoded)
        val a = Base32Crockford.encode(CryptoUtil.genRSAPrivate(2048).encoded)
        val e = Base32Crockford.encode(CryptoUtil.genRSAPrivate(2048).encoded)
        val obj = JSON.decodeFromString<ClientPrivateKeysFile>("""
            {
              "signature_private_key": "$s",
              "authentication_private_key": "$a",
              "encryption_private_key": "$e",
              "submitted_ini": true,
              "submitted_hia": true
            }
        """.trimIndent())
        assertEquals(obj.signature_private_key, CryptoUtil.loadRSAPrivate(Base32Crockford.decode(s)))
        assertEquals(obj.authentication_private_key, CryptoUtil.loadRSAPrivate(Base32Crockford.decode(a)))
        assertEquals(obj.encryption_private_key, CryptoUtil.loadRSAPrivate(Base32Crockford.decode(e)))
    }
}