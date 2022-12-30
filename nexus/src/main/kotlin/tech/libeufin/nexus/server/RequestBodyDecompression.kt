/*
 * This file is part of LibEuFin.
 * Copyright (C) 2021 Taler Systems S.A.
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

package tech.libeufin.nexus.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.InflaterInputStream

val LibeufinDecompressionPlugin = createApplicationPlugin("RequestingBodyDecompression") {
    onCallReceive { call ->
        transformBody { data ->
            if (call.request.headers[HttpHeaders.ContentEncoding] == "deflate") {
                val brc = withContext(Dispatchers.IO) {
                    val inflated = InflaterInputStream(data.toInputStream())
                    @Suppress("BlockingMethodInNonBlockingContext")
                    val bytes = inflated.readAllBytes()
                    ByteReadChannel(bytes)
                }
                brc
            } else data
        }
    }
}