/*
 * This file is part of LibEuFin.
 * Copyright (C) 2024 Taler Systems S.A.
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

package tech.libeufin.common.db

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.postgresql.ds.PGSimpleDataSource
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.common.*
import tech.libeufin.common.db.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap

// SharedFlow that are manually counted for manual garbage collection
class CountedSharedFlow<T>(val flow: MutableSharedFlow<T>, var count: Int)

fun watchNotifications(
    pgSource: PGSimpleDataSource, 
    schema: String,
    logger: Logger,
    listeners: Map<String, (suspend (String) -> Unit)>
) {
    val backoff = ExpoBackoffDecorr()
    // Run notification logic in a separated thread
    kotlin.concurrent.thread(isDaemon = true) {
        runBlocking {
            while (true) {
                try {
                    val conn = pgSource.pgConnection(schema)

                    // Listen to all notifications channels
                    for (channel in listeners.keys) {
                        conn.execSQLUpdate("LISTEN $channel")
                    }

                    backoff.reset()

                    while (true) {
                        conn.getNotifications(0) // Block until we receive at least one notification
                            .forEach {
                            // Dispatch
                            listeners[it.name]!!(it.parameter)
                        }
                    }
                } catch (e: Exception) {
                    e.fmtLog(logger)
                    delay(backoff.next())
                }
            }
        }
    }
}

/** Listen to flow from [map] for [key] using [lambda]*/
suspend fun <R, K, V> listen(map: ConcurrentHashMap<K, CountedSharedFlow<V>>, key: K, lambda: suspend (Flow<V>) -> R): R {
    // Register listener, create a new flow if missing
    val flow = map.compute(key) { _, v ->
        val tmp = v ?: CountedSharedFlow(MutableSharedFlow(), 0)
        tmp.count++
        tmp
    }!!.flow

    try {
        return lambda(flow)
    } finally {
        // Unregister listener, removing unused flow
        map.compute(key) { _, v ->
            v!!
            v.count--
            if (v.count > 0) v else null
        }
    }
}