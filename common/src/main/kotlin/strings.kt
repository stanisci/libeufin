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


fun ByteArray.encodeBase64(): String {
    return Base64.getEncoder().encodeToString(this)
}

fun String.decodeBase64(): ByteArray {
    return Base64.getDecoder().decode(this)
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

fun getQueryParam(uriQueryString: String, param: String): String? {
    uriQueryString.split('&').forEach {
        val kv = it.split('=')
        if (kv[0] == param)
            return kv[1]
    }
    return null
}

fun String.splitOnce(pat: String): Pair<String, String>? {
    val split = split(pat, limit=2)
    if (split.size != 2) return null
    return Pair(split[0], split[1])
}