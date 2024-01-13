import org.junit.Test
import tech.libeufin.nexus.*
import tech.libeufin.util.CryptoUtil
import java.io.File
import kotlin.test.*

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
        syncJsonToDisk(fileContent, "/tmp/nexus-tests-bank-keys.json")
        // loading them and check that values are the same.
        val fromDisk = loadBankKeys("/tmp/nexus-tests-bank-keys.json")
        assertNotNull(fromDisk)
        assertTrue {
            fromDisk.accepted &&
                    fromDisk.bank_encryption_public_key == fileContent.bank_encryption_public_key &&
                    fromDisk.bank_authentication_public_key == fileContent.bank_authentication_public_key
        }
    }
    @Test
    fun loadNotFound() {
        assertNull(loadBankKeys("/tmp/highly-unlikely-to-be-found.json"))
    }
}
class PrivateKeys {
    val f = File("/tmp/nexus-privs-test.json")
    init {
        if (f.exists())
            f.delete()
    }

    // Testing write failure due to insufficient permissions.
    @Test
    fun createWrongPermissions() {
        f.writeText("won't be overridden")
        f.setReadOnly()
        try {
            syncJsonToDisk(clientKeys, f.path)
            throw Exception("Should have failed")
        } catch (e: Exception) { }
    }

    /**
     * Tests whether loading keys from disk yields the same
     * values that were stored to the file.
     */
    @Test
    fun load() {
        assertFalse(f.exists())
        syncJsonToDisk(clientKeys, f.path) // Artificially storing this to the file.
        val fromDisk = loadPrivateKeysFromDisk(f.path) // loading it via the tested routine.
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
        assertNull(loadPrivateKeysFromDisk("/tmp/highly-unlikely-to-be-found.json"))
    }
}