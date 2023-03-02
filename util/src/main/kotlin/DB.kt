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
import UtilError
import io.ktor.http.*
import kotlinx.coroutines.delay
import logger
import org.postgresql.jdbc.PgConnection
import java.lang.Long.max

// This class abstracts the LISTEN/NOTIFY construct supported
class PostgresListenNotify(
    private val conn: PgConnection,
    private val channel: String
) {
    fun postrgesListen() {
        val stmt = conn.createStatement()
        stmt.execute("LISTEN $channel")
        stmt.close()
    }
    fun postgresNotify() {
        val stmt = conn.createStatement()
        stmt.execute("NOTIFY $channel")
        stmt.close()
    }

    suspend fun postgresWaitNotification(timeoutMs: Long) {
        // Splits the checks into 10ms chunks.
        val sleepTimeMs = 10L
        var notificationFound = false
        val iterations = timeoutMs / sleepTimeMs
        for (i in 0..iterations) {
            val maybeNotifications = conn.notifications
            // If a notification arrived, stop fetching for it.
            if (maybeNotifications.isNotEmpty()) {
                // Double checking that the channel is correct.
                // Notification(s) arrived, double-check channel name.
                maybeNotifications.forEach {
                    if (it.name != channel) {
                        throw UtilError(
                            statusCode = HttpStatusCode.InternalServerError,
                            reason = "Listener got wrong notification.  Expected: $channel, but got: ${it.name}"
                        )
                    }
                }
                notificationFound = true
                break
            }
            /* Notification didn't arrive, release the thread and
             * retry in the next chunk.  */
            delay(sleepTimeMs)
        }

        if (!notificationFound) {
            throw UtilError(
                statusCode = HttpStatusCode.NotFound,
                reason = "Timeout expired for notification on channel $channel",
                ec = LibeufinErrorCode.LIBEUFIN_EC_TIMEOUT_EXPIRED
            )
        }
        /* Notification arrived.  In this current version
         * we don't pass any data to the caller; the channel
         * name itself means that the awaited information arrived.
         * */
        return
        }
    }