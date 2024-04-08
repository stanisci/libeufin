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
import java.util.zip.DeflaterInputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/* ----- String ----- */

fun String.decodeBase64(): ByteArray = Base64.getDecoder().decode(this)
fun String.decodeUpHex(): ByteArray = HexFormat.of().withUpperCase().parseHex(this)

fun String.splitOnce(pat: String): Pair<String, String>? {
    val split = split(pat, limit=2)
    if (split.size != 2) return null
    return Pair(split[0], split[1])
}

/* ----- BigInteger -----*/

fun BigInteger.encodeHex(): String = this.toByteArray().encodeHex()
fun BigInteger.encodeBase64(): String = this.toByteArray().encodeBase64()

/* ----- ByteArray ----- */

fun ByteArray.rand(): ByteArray {
    Random.nextBytes(this)
    return this
}
fun ByteArray.encodeHex(): String = HexFormat.of().formatHex(this)
fun ByteArray.encodeUpHex(): String = HexFormat.of().withUpperCase().formatHex(this)
fun ByteArray.encodeBase64(): String = Base64.getEncoder().encodeToString(this)

/* ----- InputStream ----- */

/** Unzip an input stream and run [lambda] over each entry */
inline fun InputStream.unzipEach(lambda: (String, InputStream) -> Unit) {
    ZipInputStream(this).use { zip ->
        while (true) {
            val entry = zip.getNextEntry()
            if (entry == null) break
            val entryStream = object: FilterInputStream(zip) {
                override fun close() {
                    zip.closeEntry()
                }
            }
            lambda(entry.name, entryStream)
        }
    }
}

/** Decode a base64 an input stream */
fun InputStream.decodeBase64(): InputStream 
    = Base64.getDecoder().wrap(this)

/** Decode a base64 an input stream */
fun InputStream.encodeBase64(): String {
    val w = ByteArrayOutputStream()
    val encoded = Base64.getEncoder().wrap(w)
    transferTo(encoded)
    encoded.close()
    return w.toString(Charsets.UTF_8)
}

/** Deflate an input stream */
fun InputStream.deflate(): DeflaterInputStream 
    = DeflaterInputStream(this)

/** Inflate an input stream */
fun InputStream.inflate(): InflaterInputStream 
    = InflaterInputStream(this)