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
import java.util.UUID
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import tech.libeufin.util.*

internal class NotificationWatcher(private val pgSource: PGSimpleDataSource) {
    private class CountedSharedFlow(val flow: MutableSharedFlow<Long>, var count: Int)

    private val bankTxFlows = ConcurrentHashMap<Long, CountedSharedFlow>()
    private val outgoingTxFlows = ConcurrentHashMap<Long, CountedSharedFlow>()
    private val incomingTxFlows = ConcurrentHashMap<Long, CountedSharedFlow>()
    private val revenueTxFlows = ConcurrentHashMap<Long, CountedSharedFlow>()
    private val withdrawalFlow = MutableSharedFlow<UUID>()

    init {
        kotlin.concurrent.thread(isDaemon = true) { 
            runBlocking {
                while (true) {
                    try {
                        val conn = pgSource.pgConnection()
                        conn.execSQLUpdate("LISTEN bank_tx")
                        conn.execSQLUpdate("LISTEN outgoing_tx")
                        conn.execSQLUpdate("LISTEN incoming_tx")
                        conn.execSQLUpdate("LISTEN withdrawal_confirm")

                        while (true) {
                            conn.getNotifications(0) // Block until we receive at least one notification
                                .forEach {
                                when (it.name) {
                                    "bank_tx" -> {
                                        val (debtor, creditor, debitRow, creditRow) = it.parameter.split(' ', limit = 4).map { it.toLong() }
                                        bankTxFlows[debtor]?.run {
                                            flow.emit(debitRow)
                                        }
                                        bankTxFlows[creditor]?.run {
                                            flow.emit(creditRow)
                                        }
                                    }
                                    "outgoing_tx" -> {
                                        val (account, merchant, debitRow, creditRow) = it.parameter.split(' ', limit = 4).map { it.toLong() }
                                        outgoingTxFlows[account]?.run {
                                            flow.emit(debitRow)
                                        }
                                        revenueTxFlows[merchant]?.run {
                                            flow.emit(creditRow)
                                        }
                                    }
                                    "incoming_tx" -> {
                                        val (account, row) = it.parameter.split(' ', limit = 2).map { it.toLong() }
                                        incomingTxFlows[account]?.run {
                                            flow.emit(row)
                                        }
                                    }
                                    "withdrawal_confirm" -> {
                                        val uuid = UUID.fromString(it.parameter)
                                        withdrawalFlow.emit(uuid)
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

    private suspend fun <R> listen(map: ConcurrentHashMap<Long, CountedSharedFlow>, account: Long, lambda: suspend (Flow<Long>) -> R): R {
        // Register listener
        val flow = map.compute(account) { _, v ->
            val tmp = v ?: CountedSharedFlow(MutableSharedFlow(), 0);
            tmp.count++;
            tmp
        }!!.flow;

        try {
            return lambda(flow)
        } finally {
            // Unregister listener
            map.compute(account) { _, v ->
                v!!;
                v.count--;
                if (v.count > 0) v else null
            }
        }
    } 

    suspend fun <R> listenBank(account: Long, lambda: suspend (Flow<Long>) -> R): R
        = listen(bankTxFlows, account, lambda)

    suspend fun <R> listenOutgoing(account: Long, lambda: suspend (Flow<Long>) -> R): R
        = listen(outgoingTxFlows, account, lambda)

    suspend fun <R> listenIncoming(account: Long, lambda: suspend (Flow<Long>) -> R): R
        = listen(incomingTxFlows, account, lambda)

    suspend fun <R> listenRevenue(account: Long, lambda: suspend (Flow<Long>) -> R): R
        = listen(revenueTxFlows, account, lambda)

    suspend fun <R> listenWithdrawals(lambda: suspend (Flow<UUID>) -> R): R
        = lambda(withdrawalFlow)
}