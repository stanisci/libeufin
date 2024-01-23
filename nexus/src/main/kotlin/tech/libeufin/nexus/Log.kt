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

package tech.libeufin.nexus

import tech.libeufin.nexus.ebics.unzipForEach
import tech.libeufin.util.*
import java.io.*
import java.nio.file.*
import kotlin.io.path.*
import kotlin.io.*
import java.time.*

/** 
 * Log EBICS files for debugging
 * 
 * Only log if [path] is not null
 */
class FileLogger(path: String?) {
    private val dir = if (path != null) Path(path) else null

    init {
        println("$path $dir")
                if (dir != null) {
            try {
                // Create logging directory if missing
                dir.createDirectories()
            } catch (e: Exception) {
                throw Exception("Failed to init EBICS debug logging directory", e)
            }
            logger.info("Logging to '$dir'")
        }
    }

    /**
     * Logs EBICS fetch content if EBICS debug logging is enabled
     *
     * @param content EBICS fetch content
     * @param hac only true when downloading via HAC (EBICS 2)
     */
    fun logFetch(content: ByteArray, hac: Boolean = false) {
        if (dir == null) return;

        // Subdir based on current day.
        val now = Instant.now()
        val asUtcDate = LocalDate.ofInstant(now, ZoneId.of("UTC"))
        val nowMs = now.toDbMicros()
        // Creating the combined dir.
        val subDir = dir.resolve("${asUtcDate.year}-${asUtcDate.monthValue}-${asUtcDate.dayOfMonth}").resolve("fetch")
        subDir.createDirectories()
        if (hac) {
            subDir.resolve("${nowMs}_HAC_response.pain.002.xml").writeBytes(content)
        } else {
            // Write each ZIP entry in the combined dir.
            content.unzipForEach { fileName, xmlContent ->
                subDir.resolve("${nowMs}_$fileName").writeText(xmlContent)
            }
        }
    }

    /**
     * Logs EBICS submit content if EBICS debug logging is enabled
     *
     * @param content EBICS submit content
     */
    fun logSubmit(content: String) {
        if (dir == null) return;

        // Subdir based on current day.
        val now = Instant.now()
        val asUtcDate = LocalDate.ofInstant(now, ZoneId.of("UTC"))
        val nowMs = now.toDbMicros()
        // Creating the combined dir.
        val subDir = dir.resolve("${asUtcDate.year}-${asUtcDate.monthValue}-${asUtcDate.dayOfMonth}").resolve("submit")
        subDir.createDirectories()
        subDir.resolve("${nowMs}_pain.001.xml").writeText(content)
    }
}