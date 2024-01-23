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

import java.io.ByteArrayOutputStream

class EncodingException : Exception("Invalid encoding")

object Base32Crockford {

    private fun ByteArray.getIntAt(index: Int): Int {
        val x = this[index].toInt()
        return if (x >= 0) x else (x + 256)
    }

    private var encTable = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder()
        var inputChunkBuffer = 0
        var pendingBitsCount = 0
        var inputCursor = 0
        var inputChunkNumber = 0

        while (inputCursor < data.size) {
            // Read input
            inputChunkNumber = data.getIntAt(inputCursor++)
            inputChunkBuffer = (inputChunkBuffer shl 8) or inputChunkNumber
            pendingBitsCount += 8
            // Write symbols
            while (pendingBitsCount >= 5) {
                val symbolIndex = inputChunkBuffer.ushr(pendingBitsCount - 5) and 31
                sb.append(encTable[symbolIndex])
                pendingBitsCount -= 5
            }
        }
        if (pendingBitsCount >= 5)
            throw Exception("base32 encoder did not write all the symbols")

        if (pendingBitsCount > 0) {
            val symbolIndex = (inputChunkNumber shl (5 - pendingBitsCount)) and 31
            sb.append(encTable[symbolIndex])
        }
        val enc = sb.toString()
        val oneMore = ((data.size * 8) % 5) > 0
        val expectedLength = if (oneMore) {
            ((data.size * 8) / 5) + 1
        } else {
            (data.size * 8) / 5
        }
        if (enc.length != expectedLength)
            throw Exception("base32 encoding has wrong length")
        return enc
    }

    /**
     * Decodes the input to its binary representation, throws
     * net.taler.wallet.crypto.EncodingException on invalid encodings.
     */
    fun decode(
        encoded: String,
        out: ByteArrayOutputStream
    ) {
        var outBitsCount = 0
        var bitsBuffer = 0
        var inputCursor = 0

        while (inputCursor < encoded.length) {
            val decodedNumber = getValue(encoded[inputCursor++])
            bitsBuffer = (bitsBuffer shl 5) or decodedNumber
            outBitsCount += 5
            while (outBitsCount >= 8) {
                val outputChunk = (bitsBuffer ushr (outBitsCount - 8)) and 0xFF
                out.write(outputChunk)
                outBitsCount -= 8 // decrease of written bits.
            }
        }
        if ((encoded.length * 5) / 8 != out.size())
            throw Exception("base32 decoder: wrong output size")
    }

    fun decode(encoded: String): ByteArray {
        val out = ByteArrayOutputStream()
        decode(encoded, out)
        val blob = out.toByteArray()
        return blob
    }

    private fun getValue(chr: Char): Int {
        var a = chr
        when (a) {
            'O', 'o' -> a = '0'
            'i', 'I', 'l', 'L' -> a = '1'
            'u', 'U' -> a = 'V'
        }
        if (a in '0'..'9')
            return a - '0'
        if (a in 'a'..'z')
            a = Character.toUpperCase(a)
        var dec = 0
        if (a in 'A'..'Z') {
            if ('I' < a) dec++
            if ('L' < a) dec++
            if ('O' < a) dec++
            if ('U' < a) dec++
            return a - 'A' + 10 - dec
        }
        throw EncodingException()
    }

    /**
     * Compute the length of the resulting string when encoding data of the given size
     * in bytes.
     *
     * @param dataSize size of the data to encode in bytes
     * @return size of the string that would result from encoding
     */
    @Suppress("unused")
    fun calculateEncodedStringLength(dataSize: Int): Int {
        return (dataSize * 8 + 4) / 5
    }

    /**
     * Compute the length of the resulting data in bytes when decoding a (valid) string of the
     * given size.
     *
     * @param stringSize size of the string to decode
     * @return size of the resulting data in bytes
     */
    @Suppress("unused")
    fun calculateDecodedDataLength(stringSize: Int): Int {
        return stringSize * 5 / 8
    }
}

