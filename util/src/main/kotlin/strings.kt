/*
 * This file is part of LibEuFin.
 * Copyright (C) 2020 Taler Systems S.A.
 *
 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.
 *
 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

package tech.libeufin.util

import logger
import java.math.BigInteger
import java.util.*

fun ByteArray.toHexString(): String {
    return this.joinToString("") {
        java.lang.String.format("%02X", it)
    }
}

private fun toDigit(hexChar: Char): Int {
    val digit = Character.digit(hexChar, 16)
    require(digit != -1) { "Invalid Hexadecimal Character: $hexChar" }
    return digit
}

private fun hexToByte(hexString: String): Byte {
    val firstDigit: Int = toDigit(hexString[0])
    val secondDigit: Int = toDigit(hexString[1])
    return ((firstDigit shl 4) + secondDigit).toByte()
}

fun decodeHexString(hexString: String): ByteArray {
    val hs = hexString.replace(" ", "").replace("\n", "")
    require(hs.length % 2 != 1) { "Invalid hexadecimal String supplied." }
    val bytes = ByteArray(hs.length / 2)
    var i = 0
    while (i < hs.length) {
        bytes[i / 2] = hexToByte(hs.substring(i, i + 2))
        i += 2
    }
    return bytes
}

fun bytesToBase64(bytes: ByteArray): String {
    return Base64.getEncoder().encodeToString(bytes)
}

fun base64ToBytes(encoding: String): ByteArray {
    return Base64.getDecoder().decode(encoding)
}

// used mostly in RSA math, never as amount.
fun BigInteger.toUnsignedHexString(): String {
    val signedValue = this.toByteArray()
    require(this.signum() > 0) { "number must be positive" }
    val start = if (signedValue[0] == 0.toByte()) {
        1
    } else {
        0
    }
    val bytes = Arrays.copyOfRange(signedValue, start, signedValue.size)
    return bytes.toHexString()
}

/**
 * Inserts spaces every 2 characters, and a newline after 8 pairs.
 */
fun chunkString(input: String): String {
    val ret = StringBuilder()
    var columns = 0
    for (i in input.indices) {
        if ((i + 1).rem(2) == 0) {
            if (columns == 15) {
                ret.append(input[i] + "\n")
                columns = 0
                continue
            }
            ret.append(input[i] + " ")
            columns++
            continue
        }
        ret.append(input[i])
    }
    return ret.toString().uppercase()
}

data class AmountWithCurrency(
    val currency: String,
    val amount: String
)

fun getRandomString(length: Int): String {
    val allowedChars = ('A' .. 'Z') + ('0' .. '9')
    return (1 .. length)
        .map { allowedChars.random() }
        .joinToString("")
}

// Taken from the ISO20022 XSD schema
private val bicRegex = Regex("^[A-Z]{6}[A-Z2-9][A-NP-Z0-9]([A-Z0-9]{3})?$")

fun validateBic(bic: String): Boolean {
    return bicRegex.matches(bic)
}

// Taken from the ISO20022 XSD schema
private val ibanRegex = Regex("^[A-Z]{2}[0-9]{2}[a-zA-Z0-9]{1,30}$")

fun validateIban(iban: String): Boolean {
    return ibanRegex.matches(iban)
}

fun isValidResourceName(name: String): Boolean {
    return name.matches(Regex("[a-z]([-a-z0-9]*[a-z0-9])?"))
}

// Sanity-check user's credentials.
fun sanityCheckCredentials(credentials: Pair<String, String>): Boolean {
    val allowedChars = Regex("^[a-zA-Z0-9]+$")
    if (!allowedChars.matches(credentials.first)) return false
    if (!allowedChars.matches(credentials.second)) return false
    return true
}

/**
 * Parses string into java.util.UUID format or throws 400 Bad Request.
 * The output is usually consumed in database queries.
 */
fun parseUuid(maybeUuid: String): UUID? {
    val uuid = try {
        UUID.fromString(maybeUuid)
    } catch (e: Exception) {
        logger.error("'$maybeUuid' is an invalid UUID.")
        return null
    }
    return uuid
}

fun hasWopidPlaceholder(captchaUrl: String): Boolean {
    if (captchaUrl.contains("{wopid}", ignoreCase = true))
        return true
    return false
}

// Tries to extract a valid reserve public key from the raw subject line
// or returns null if the input is invalid.
fun extractReservePubFromSubject(rawSubject: String): String? {
    val re = "\\b[a-z0-9A-Z]{52}\\b".toRegex()
    val result = re.find(rawSubject.replace("[\n]+".toRegex(), "")) ?: return null
    return result.value.uppercase()
}

fun getQueryParam(uriQueryString: String, param: String): String? {
    uriQueryString.split('&').forEach {
        val kv = it.split('=')
        if (kv[0] == param)
            return kv[1]
    }
    return null
}
