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

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.cert.jcajce.*
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigInteger
import java.util.*
import java.security.*
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.cert.*
import java.security.spec.*
import javax.crypto.*
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.PBEParameterSpec
import javax.crypto.spec.SecretKeySpec
import tech.libeufin.common.*

/** Helpers for dealing with cryptographic operations in EBICS / LibEuFin */
object CryptoUtil {

    private val provider = BouncyCastleProvider()

    /** Load an RSA private key from its binary PKCS#8 encoding  */
    fun loadRSAPrivate(encodedPrivateKey: ByteArray): RSAPrivateCrtKey {
        val spec = PKCS8EncodedKeySpec(encodedPrivateKey)
        val priv = KeyFactory.getInstance("RSA").generatePrivate(spec)
        if (priv !is RSAPrivateCrtKey)
            throw Exception("wrong encoding")
        return priv
    }

    /** Load an RSA public key from its binary X509 encoding */
    fun loadRSAPublic(encodedPublicKey: ByteArray): RSAPublicKey {
        val spec = X509EncodedKeySpec(encodedPublicKey)
        val pub = KeyFactory.getInstance("RSA").generatePublic(spec)
        if (pub !is RSAPublicKey)
            throw Exception("wrong encoding")
        return pub
    }

    /** Create an RSA public key from its components: [modulus] and [exponent] */
    fun RSAPublicFromComponents(modulus: ByteArray, exponent: ByteArray): RSAPublicKey {
        val modulusBigInt = BigInteger(1, modulus)
        val exponentBigInt = BigInteger(1, exponent)
        val spec = RSAPublicKeySpec(modulusBigInt, exponentBigInt)
        return KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
    }

    /** Extract an RSA public key from a [raw] X.509 certificate */
    fun RSAPublicFromCertificate(raw: ByteArray): RSAPublicKey {
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(raw.inputStream());
        return certificate.getPublicKey() as RSAPublicKey
    }

    /** Generate an RSA public key from a [private] one */
    fun RSAPublicFromPrivate(private: RSAPrivateCrtKey): RSAPublicKey {
        val spec = RSAPublicKeySpec(private.modulus, private.publicExponent)
        return KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
    }

    /** Generate a self-signed X.509 certificate from an RSA [private] key */
    fun X509CertificateFromRSAPrivate(private: RSAPrivateCrtKey, name: String): X509Certificate {
        val start = Date()
        val calendar = Calendar.getInstance()
        calendar.time = start
        calendar.add(Calendar.YEAR, 1_000)
        val end = calendar.time

        val name = X500Name("CN=$name")
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger(20, Random()),
            start,
            end, 
            name,
            RSAPublicFromPrivate(private)
        )

        
        builder.addExtension(Extension.keyUsage, true, KeyUsage(
            KeyUsage.digitalSignature 
                or KeyUsage.nonRepudiation
                or KeyUsage.keyEncipherment
                or KeyUsage.dataEncipherment
                or KeyUsage.keyAgreement
                or KeyUsage.keyCertSign
                or KeyUsage.cRLSign
                or KeyUsage.encipherOnly
                or KeyUsage.decipherOnly
        ))
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))

        val certificate = JcaContentSignerBuilder("SHA256WithRSA").build(private)
        return JcaX509CertificateConverter()
            .setProvider(provider)
            .getCertificate(builder.build(certificate))

    }

    /** Generate an RSA key pair of [keysize] */
    fun genRSAPair(keysize: Int): Pair<RSAPrivateCrtKey, RSAPublicKey> {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(keysize)
        val pair = gen.genKeyPair()
        return Pair(pair.private as RSAPrivateCrtKey, pair.public as RSAPublicKey)
    }
    /** Generate an RSA private key of [keysize] */
    fun genRSAPrivate(keysize: Int): RSAPrivateCrtKey = genRSAPair(keysize).first
    /** Generate an RSA public key of [keysize] */
    fun genRSAPublic(keysize: Int): RSAPublicKey = genRSAPair(keysize).second

    /**
     * Hash an RSA public key according to the EBICS standard (EBICS 2.5: 4.4.1.2.3).
     */
    fun getEbicsPublicKeyHash(publicKey: RSAPublicKey): ByteArray {
        val keyBytes = ByteArrayOutputStream()
        keyBytes.writeBytes(publicKey.publicExponent.encodeHex().trimStart('0').toByteArray())
        keyBytes.write(' '.code)
        keyBytes.writeBytes(publicKey.modulus.encodeHex().trimStart('0').toByteArray())
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(keyBytes.toByteArray())
    }

    fun genEbicsE002Key(encryptionPublicKey: RSAPublicKey): Pair<SecretKey, ByteArray> {
        // Gen transaction key
        val keygen = KeyGenerator.getInstance("AES", provider)
        keygen.init(128)
        val transactionKey = keygen.generateKey()
        // Encrypt transaction keyA
        val cipher = Cipher.getInstance(
            "RSA/None/PKCS1Padding",
            provider
        )
        cipher.init(Cipher.ENCRYPT_MODE, encryptionPublicKey)
        val encryptedTransactionKey = cipher.doFinal(transactionKey.encoded)
        return Pair(transactionKey, encryptedTransactionKey)
    }
    
    /**
     * Encrypt data according to the EBICS E002 encryption process.
     */
    fun encryptEbicsE002(
        transactionKey: SecretKey,
        data: InputStream
    ): CipherInputStream {
        val cipher = Cipher.getInstance(
            "AES/CBC/X9.23Padding",
            provider
        )
        val ivParameterSpec = IvParameterSpec(ByteArray(16))
        cipher.init(Cipher.ENCRYPT_MODE, transactionKey, ivParameterSpec)
        return CipherInputStream(data, cipher)
    }

    fun decryptEbicsE002Key(
        privateKey: RSAPrivateCrtKey,
        encryptedTransactionKey: ByteArray
    ): SecretKeySpec {
        val cipher = Cipher.getInstance(
            "RSA/None/PKCS1Padding",
            provider
        )
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        val transactionKeyBytes = cipher.doFinal(encryptedTransactionKey)
        return SecretKeySpec(transactionKeyBytes, "AES")
    }

    fun decryptEbicsE002(
        transactionKey: SecretKeySpec,
        encryptedData: InputStream
    ): CipherInputStream {
        val cipher = Cipher.getInstance(
            "AES/CBC/X9.23Padding",
            provider
        )
        val ivParameterSpec = IvParameterSpec(ByteArray(16))
        cipher.init(Cipher.DECRYPT_MODE, transactionKey, ivParameterSpec)
        return CipherInputStream(encryptedData, cipher)
    }

    /**
     * Signing algorithm corresponding to the EBICS A006 signing process.
     *
     * Note that while [data] can be arbitrary-length data, in EBICS, the order
     * data is *always* hashed *before* passing it to the signing algorithm, which again
     * uses a hash internally.
     */
    fun signEbicsA006(data: ByteArray, privateKey: RSAPrivateCrtKey): ByteArray {
        val signature = Signature.getInstance("SHA256withRSA/PSS", provider)
        signature.setParameter(PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
        signature.initSign(privateKey)
        signature.update(data)
        return signature.sign()
    }

    fun verifyEbicsA006(sig: ByteArray, data: ByteArray, publicKey: RSAPublicKey): Boolean {
        val signature = Signature.getInstance("SHA256withRSA/PSS", provider)
        signature.setParameter(PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
        signature.initVerify(publicKey)
        signature.update(data)
        return signature.verify(sig)
    }

    fun digestEbicsOrderA006(orderData: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        for (b in orderData) {
            when (b) {
                '\r'.code.toByte(), '\n'.code.toByte(), (26).toByte() -> Unit
                else -> digest.update(b)
            }
        }
        return digest.digest()
    }

    fun decryptKey(data: EncryptedPrivateKeyInfo, passphrase: String): RSAPrivateCrtKey {
        /* make key out of passphrase */
        val pbeKeySpec = PBEKeySpec(passphrase.toCharArray())
        val keyFactory = SecretKeyFactory.getInstance(data.algName)
        val secretKey = keyFactory.generateSecret(pbeKeySpec)
        /* Make a cipher */
        val cipher = Cipher.getInstance(data.algName)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey,
            data.algParameters // has hash count and salt
        )
        /* Ready to decrypt */
        val decryptedKeySpec: PKCS8EncodedKeySpec = data.getKeySpec(cipher)
        val priv = KeyFactory.getInstance("RSA").generatePrivate(decryptedKeySpec)
        if (priv !is RSAPrivateCrtKey)
            throw Exception("wrong encoding")
        return priv
    }

    fun hashStringSHA256(input: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
}