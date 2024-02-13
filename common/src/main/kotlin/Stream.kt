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

import java.io.InputStream
import java.io.FilterInputStream
import java.util.zip.DeflaterInputStream
import java.util.zip.InflaterInputStream
import java.util.zip.*
import java.util.Base64

/** Unzip an input stream and run [lambda] over each entry */
fun InputStream.unzipEach(lambda: (String, InputStream) -> Unit) {
    ZipInputStream(this).use { zip ->
        while (true) {
            val entry = zip.getNextEntry()
            if (entry == null) break;
            val entryStream = object: FilterInputStream(zip) {
                override fun close() {
                    zip.closeEntry();
                }
            }
            lambda(entry.name, entryStream)
        }
    }
}

/** Decode a base64 an input stream */
fun InputStream.decodeBase64(): InputStream 
    = Base64.getDecoder().wrap(this)

/** Deflate an input stream */
fun InputStream.deflate(): DeflaterInputStream 
    = DeflaterInputStream(this)

/** Inflate an input stream */
fun InputStream.inflate(): InflaterInputStream 
    = InflaterInputStream(this)