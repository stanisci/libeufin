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

import org.junit.Ignore
import org.junit.Test
import kotlin.io.path.*
import tech.libeufin.common.*
import tech.libeufin.common.crypto.*
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateCrtKey
import java.util.*
import javax.crypto.EncryptedPrivateKeyInfo
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CryptoUtilTest {

    @Test
    fun loadFromModulusAndExponent() {
        val keyPair = CryptoUtil.generateRsaKeyPair(1024)
        val pub2 = CryptoUtil.RSAPublicFromComponents(
            keyPair.public.modulus.toByteArray(),
            keyPair.public.publicExponent.toByteArray()
        )
        assertEquals(keyPair.public, pub2)
    }

    @Test
    fun keyGeneration() {
        val gen: KeyPairGenerator = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val pair = gen.genKeyPair()
        println(pair.private)
        assertTrue(pair.private is RSAPrivateCrtKey)
    }

    @Test
    fun testCryptoUtilBasics() {
        val keyPair = CryptoUtil.generateRsaKeyPair(1024)
        val encodedPriv = keyPair.private.encoded
        val encodedPub = keyPair.public.encoded
        val otherKeyPair =
            CryptoUtil.RsaCrtKeyPair(CryptoUtil.loadRSAPrivate(encodedPriv), CryptoUtil.loadRSAPublic(encodedPub))
        assertEquals(keyPair.private, otherKeyPair.private)
        assertEquals(keyPair.public, otherKeyPair.public)
    }

    @Test
    fun testEbicsE002() {
        val data = "Hello, World!".toByteArray()
        val keyPair = CryptoUtil.generateRsaKeyPair(1024)
        val enc = CryptoUtil.encryptEbicsE002(data.inputStream(), keyPair.public)
        val dec = CryptoUtil.decryptEbicsE002(enc, keyPair.private)
        assertTrue(data.contentEquals(dec))
    }

    @Test
    fun testEbicsA006() {
        val keyPair = CryptoUtil.generateRsaKeyPair(1024)
        val data = "Hello, World".toByteArray(Charsets.UTF_8)
        val sig = CryptoUtil.signEbicsA006(data, keyPair.private)
        assertTrue(CryptoUtil.verifyEbicsA006(sig, data, keyPair.public))
    }

    @Test
    fun testPassphraseEncryption() {

        val keyPair = CryptoUtil.generateRsaKeyPair(1024)

        /* encrypt with original key */
        val data = "Hello, World!".toByteArray(Charsets.UTF_8)
        val secret = CryptoUtil.encryptEbicsE002(data.inputStream(), keyPair.public)

        /* encrypt and decrypt private key */
        val encPriv = CryptoUtil.encryptKey(keyPair.private.encoded, "secret")
        val plainPriv = CryptoUtil.decryptKey(EncryptedPrivateKeyInfo(encPriv), "secret")

        /* decrypt with decrypted private key */
        val revealed = CryptoUtil.decryptEbicsE002(secret, plainPriv)

        assertEquals(
            String(revealed, charset = Charsets.UTF_8),
            String(data, charset = Charsets.UTF_8)
        )
    }

    @Test
    fun testEbicsPublicKeyHashing() {
        val exponentStr = "01 00 01".replace(" ", "")
        val moduloStr = """
            EB BD B8 E3 73 45 60 06 44 A1 AD 6A 25 33 65 F5
            9C EB E5 93 E0 51 72 77 90 6B F0 58 A8 89 EB 00
            C6 0B 37 38 F3 3C 55 F2 4D 83 D0 33 C3 A8 F0 3C
            82 4E AF 78 51 D6 F4 71 6A CC 9C 10 2A 58 C9 5F
            3D 30 B4 31 D7 1B 79 6D 43 AA F9 75 B5 7E 0B 4A
            55 52 1D 7C AC 8F 92 B0 AE 9F CF 5F 16 5C 6A D1
            88 DB E2 48 E7 78 43 F9 18 63 29 45 ED 6C 08 6C
            16 1C DE F3 02 01 23 8A 58 35 43 2B 2E C5 3F 6F
            33 B7 A3 46 E1 75 BD 98 7C 6D 55 DE 71 11 56 3D
            7A 2C 85 42 98 42 DF 94 BF E8 8B 76 84 13 3E CA
            0E 8D 12 57 D6 8A CF 82 DE B7 D7 BB BC 45 AE 25
            95 76 00 19 08 AA D2 C8 A7 D8 10 37 88 96 B9 98
            14 B4 B0 65 F3 36 CE 93 F7 46 12 58 9F E7 79 33
            D5 BE 0D 0E F8 E7 E0 A9 C3 10 51 A1 3E A4 4F 67
            5E 75 8C 9D E6 FE 27 B6 3C CF 61 9B 31 D4 D0 22
            B9 2E 4C AF 5F D6 4B 1F F0 4D 06 5F 68 EB 0B 71
        """.trimIndent().replace(" ", "").replace("\n", "")
        val expectedHashStr = """
            72 71 D5 83 B4 24 A6 DA 0B 7B 22 24 3B E2 B8 8C
            6E A6 0F 9F 76 11 FD 18 BE 2C E8 8B 21 03 A9 41
        """.trimIndent()

        val expectedHash = expectedHashStr.replace(" ", "").replace("\n", "").toByteArray(Charsets.UTF_8)

        val pub = CryptoUtil.RSAPublicFromComponents(moduloStr.decodeUpHex(), exponentStr.decodeUpHex())

        println("echoed pub exp: ${pub.publicExponent.encodeHex()}")
        println("echoed pub mod: ${pub.modulus.encodeHex()}")

        val pubHash = CryptoUtil.getEbicsPublicKeyHash(pub)

        println("our pubHash: ${pubHash.encodeUpHex()}")
        println("expected pubHash: ${expectedHash.toString(Charsets.UTF_8)}")

        assertEquals(expectedHash.toString(Charsets.UTF_8), pubHash.encodeUpHex())
    }

    @Test
    fun base32Test() {
        val validKey = "4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCEG0"
        val enc = validKey
        val obj = Base32Crockford.decode(enc)
        assertTrue(obj.size == 32)
        val roundTrip = Base32Crockford.encode(obj)
        assertEquals(enc, roundTrip)
        val invalidShorterKey = "4MZT6RS3RVB3B0E2RDMYW0YRA3Y0VPHYV0CYDE6XBB0YMPFXCE"
        val shorterBlob = Base32Crockford.decode(invalidShorterKey)
        assertTrue(shorterBlob.size < 32) // See #7980
    }

    @Test
    fun blobRoundTrip() {
        val blob = ByteArray(30)
        Random().nextBytes(blob)
        val enc = Base32Crockford.encode(blob)
        val blobAgain = Base32Crockford.decode(enc)
        assertTrue(blob.contentEquals(blobAgain))
    }

    /**
     * Manual test: tests that gnunet-base32 and
     * libeufin encode to the same string.
     */
    @Ignore
    fun gnunetEncodeCheck() {
        val blob = ByteArray(30)
        Random().nextBytes(blob)
        val b = Path("/tmp/libeufin-blob.bin")
        b.writeBytes(blob)
        val enc = Base32Crockford.encode(blob)
        // The following output needs to match the one from
        // "gnunet-base32 /tmp/libeufin-blob.bin"
        println(enc)
    }

    /**
     * Manual test: tests that gnunet-base32 and
     * libeufin decode to the same value
     */
    @Ignore
    fun gnunetDecodeCheck() {
        // condition: "gnunet-base32 -d /tmp/blob.enc" needs to decode to /tmp/blob.bin
        val blob = Path("/tmp/blob.bin").readBytes()
        val blobEnc = Path("/tmp/blob.enc").readText(Charsets.UTF_8)
        val dec = Base32Crockford.decode(blobEnc)
        assertTrue(blob.contentEquals(dec))
    }

    @Test
    fun emptyBase32Test() {
        val enc = Base32Crockford.encode(ByteArray(0))
        assert(enc.isEmpty())
        val blob = Base32Crockford.decode("")
        assert(blob.isEmpty())
    }

    @Test
    fun passwordHashing() {
        val x = PwCrypto.hashpw("myinsecurepw")
        assertTrue(PwCrypto.checkpw("myinsecurepw", x))
    }
}
