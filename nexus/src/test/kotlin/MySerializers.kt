import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import net.taler.wallet.crypto.Base32Crockford
import org.junit.Test
import tech.libeufin.nexus.ClientPrivateKeysFile
import tech.libeufin.nexus.RSAPrivateCrtKeySerializer
import tech.libeufin.util.CryptoUtil
import java.security.interfaces.RSAPrivateCrtKey
import kotlin.test.assertEquals

class MySerializers {
    // Testing deserialization of RSA private keys.
    @Test
    fun rsaPrivDeserialization() {
        val s = Base32Crockford.encode(CryptoUtil.generateRsaKeyPair(2048).private.encoded)
        val a = Base32Crockford.encode(CryptoUtil.generateRsaKeyPair(2048).private.encoded)
        val e = Base32Crockford.encode(CryptoUtil.generateRsaKeyPair(2048).private.encoded)
        val obj = j.decodeFromString<ClientPrivateKeysFile>("""
            {
              "signature_private_key": "$s",
              "authentication_private_key": "$a",
              "encryption_private_key": "$e",
              "submitted_ini": true,
              "submitted_hia": true
            }
        """.trimIndent())
        assertEquals(obj.signature_private_key, CryptoUtil.loadRsaPrivateKey(Base32Crockford.decode(s)))
        assertEquals(obj.authentication_private_key, CryptoUtil.loadRsaPrivateKey(Base32Crockford.decode(a)))
        assertEquals(obj.encryption_private_key, CryptoUtil.loadRsaPrivateKey(Base32Crockford.decode(e)))
    }
}