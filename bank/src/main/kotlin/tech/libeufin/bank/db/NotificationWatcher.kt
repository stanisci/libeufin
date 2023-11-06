/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023 Taler Systems S.A.

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

package tech.libeufin.bank

import org.postgresql.ds.PGSimpleDataSource
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import tech.libeufin.util.*

internal class NotificationWatcher(private val pgSource: PGSimpleDataSource) {
    private class CountedSharedFlow(val flow: MutableSharedFlow<Long>, var count: Int)

    private val bankTxFlows = ConcurrentHashMap<Long, CountedSharedFlow>()
    private val outgoingTxFlows = ConcurrentHashMap<Long, CountedSharedFlow>()
    private val incomingTxFlows = ConcurrentHashMap<Long, CountedSharedFlow>()

    init {
        kotlin.concurrent.thread(isDaemon = true) { 
            runBlocking {
                while (true) {
                    try {
                        val conn = pgSource.pgConnection()
                        conn.execSQLUpdate("LISTEN bank_tx")
                        conn.execSQLUpdate("LISTEN outgoing_tx")
                        conn.execSQLUpdate("LISTEN incoming_tx")

                        while (true) {
                            conn.getNotifications(0) // Block until we receive at least one notification
                                .forEach {
                                if (it.name == "bank_tx") {
                                    val info = it.parameter.split(' ', limit = 4).map { it.toLong() }
                                    val debtorAccount = info[0];
                                    val creditorAccount = info[1];
                                    val debitRow = info[2];
                                    val creditRow = info[3];
                                    
                                    bankTxFlows[debtorAccount]?.run {
                                        flow.emit(debitRow)
                                        flow.emit(creditRow)
                                    }
                                    bankTxFlows[creditorAccount]?.run {
                                        flow.emit(debitRow)
                                        flow.emit(creditRow)
                                    }
                                } else {
                                    val info = it.parameter.split(' ', limit = 2).map { it.toLong() }
                                    val account = info[0];
                                    val row = info[1];
                                    if (it.name == "outgoing_tx") {
                                        outgoingTxFlows[account]?.run {
                                            flow.emit(row)
                                        }
                                    } else {
                                        incomingTxFlows[account]?.run {
                                            flow.emit(row)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logger.warn("notification_watcher failed: $e")
                    }
                }
            }
        }
    }

    private suspend fun listen(map: ConcurrentHashMap<Long, CountedSharedFlow>, account: Long, lambda: suspend (Flow<Long>) -> Unit) {
        // Register listener
        val flow = map.compute(account) { _, v ->
            val tmp = v ?: CountedSharedFlow(MutableSharedFlow(), 0);
            tmp.count++;
            tmp
        }!!.flow;

        try {
            lambda(flow)
        } finally {
            // Unregister listener
            map.compute(account) { _, v ->
                v!!;
                v.count--;
                if (v.count > 0) v else null
            }
        }
    } 

    suspend fun listenBank(account: Long, lambda: suspend (Flow<Long>) -> Unit) {
        listen(bankTxFlows, account, lambda)
    }

    suspend fun listenOutgoing(account: Long, lambda: suspend (Flow<Long>) -> Unit) {
        listen(outgoingTxFlows, account, lambda)
    }

    suspend fun listenIncoming(account: Long, lambda: suspend (Flow<Long>) -> Unit) {
        listen(incomingTxFlows, account, lambda)
    }
}