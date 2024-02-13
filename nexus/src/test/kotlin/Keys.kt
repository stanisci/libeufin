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
import tech.libeufin.common.CryptoUtil
import tech.libeufin.nexus.*
import kotlin.io.path.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.notExists
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PublicKeys {

    // Tests intermittent spaces in public keys fingerprint.
    @Test
    fun splitTest() {
        assertEquals("0099887766".spaceEachTwo(), "00 99 88 77 66") // even
        assertEquals("ZZYYXXWWVVU".spaceEachTwo(), "ZZ YY XX WW VV U") // odd
    }

    // Tests loading the bank public keys from disk.
    @Test
    fun loadBankKeys() {
        // artificially creating the keys.
        val fileContent = BankPublicKeysFile(
            accepted = true,
            bank_authentication_public_key = CryptoUtil.generateRsaKeyPair(2028).public,
            bank_encryption_public_key = CryptoUtil.generateRsaKeyPair(2028).public
        )
        // storing them on disk.
        persistBankKeys(fileContent, Path("/tmp/nexus-tests-bank-keys.json"))
        // loading them and check that values are the same.
        val fromDisk = loadBankKeys(Path("/tmp/nexus-tests-bank-keys.json"))
        assertNotNull(fromDisk)
        assertTrue {
            fromDisk.accepted &&
                    fromDisk.bank_encryption_public_key == fileContent.bank_encryption_public_key &&
                    fromDisk.bank_authentication_public_key == fileContent.bank_authentication_public_key
        }
    }
    @Test
    fun loadNotFound() {
        assertNull(loadBankKeys(Path("/tmp/highly-unlikely-to-be-found.json")))
    }
}
class PrivateKeys {
    val f = Path("/tmp/nexus-privs-test.json")
    init {
        f.deleteIfExists()
    }

    /**
     * Tests whether loading keys from disk yields the same
     * values that were stored to the file.
     */
    @Test
    fun load() {
        assert(f.notExists())
        persistClientKeys(clientKeys, f) // Artificially storing this to the file.
        val fromDisk = loadClientKeys(f) // loading it via the tested routine.
        assertNotNull(fromDisk)
        // Checking the values from disk match the initial object.
        assertTrue {
            clientKeys.authentication_private_key == fromDisk.authentication_private_key &&
                    clientKeys.encryption_private_key == fromDisk.encryption_private_key &&
                    clientKeys.signature_private_key == fromDisk.signature_private_key &&
                    clientKeys.submitted_ini == fromDisk.submitted_ini &&
                    clientKeys.submitted_hia == fromDisk.submitted_hia
        }
    }

    // Testing failure on file not found.
    @Test
    fun loadNotFound() {
        assertNull(loadClientKeys(Path("/tmp/highly-unlikely-to-be-found.json")))
    }
}