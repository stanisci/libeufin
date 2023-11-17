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

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.postgresql.ds.PGSimpleDataSource
import tech.libeufin.util.*

/** Postgres notification collector and distributor */
internal class NotificationWatcher(private val pgSource: PGSimpleDataSource) {
    // Transaction id ShareFlow that are manually counted for manual garbage collection
    private class CountedSharedFlow(val flow: MutableSharedFlow<Long>, var count: Int)

    // Transaction flows, the keys are the bank account id
    private val bankTxFlows = ConcurrentHashMap<Long, CountedSharedFlow>()
    private val outgoingTxFlows = ConcurrentHashMap<Long, CountedSharedFlow>()
    private val incomingTxFlows = ConcurrentHashMap<Long, CountedSharedFlow>()
    private val revenueTxFlows = ConcurrentHashMap<Long, CountedSharedFlow>()
    // Withdrawal confirmation flow, the key is the public withdrawal UUID
    private val withdrawalFlow = MutableSharedFlow<UUID>()

    init {
        // Run notification logic in a separated thread
        kotlin.concurrent.thread(isDaemon = true) { 
            runBlocking {
                while (true) {
                    try {
                        val conn = pgSource.pgConnection()

                        // Listen to all notifications channels
                        conn.execSQLUpdate("LISTEN bank_tx")
                        conn.execSQLUpdate("LISTEN outgoing_tx")
                        conn.execSQLUpdate("LISTEN incoming_tx")
                        conn.execSQLUpdate("LISTEN withdrawal_confirm")

                        while (true) {
                            conn.getNotifications(0) // Block until we receive at least one notification
                                .forEach {
                                // Extract informations and dispatch
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

    /** Listen to transaction ids flow from [map] for [account] using [lambda]*/
    private suspend fun <R> listen(map: ConcurrentHashMap<Long, CountedSharedFlow>, account: Long, lambda: suspend (Flow<Long>) -> R): R {
        // Register listener, create a new flow if missing
        val flow = map.compute(account) { _, v ->
            val tmp = v ?: CountedSharedFlow(MutableSharedFlow(), 0);
            tmp.count++;
            tmp
        }!!.flow;

        try {
            return lambda(flow)
        } finally {
            // Unregister listener, removing unused flow
            map.compute(account) { _, v ->
                v!!;
                v.count--;
                if (v.count > 0) v else null
            }
        }
    } 

    /** Listen for new bank transactions for [account] */
    suspend fun <R> listenBank(account: Long, lambda: suspend (Flow<Long>) -> R): R
        = listen(bankTxFlows, account, lambda)
    /** Listen for new taler outgoing transactions from [account] */
    suspend fun <R> listenOutgoing(exchange: Long, lambda: suspend (Flow<Long>) -> R): R
        = listen(outgoingTxFlows, exchange, lambda)
    /** Listen for new taler incoming transactions to [account] */
    suspend fun <R> listenIncoming(exchange: Long, lambda: suspend (Flow<Long>) -> R): R
        = listen(incomingTxFlows, exchange, lambda)
    /** Listen for new taler outgoing transactions to [account] */
    suspend fun <R> listenRevenue(merchant: Long, lambda: suspend (Flow<Long>) -> R): R
        = listen(revenueTxFlows, merchant, lambda)
    /** Listen for new withdrawal confirmations */
    suspend fun <R> listenWithdrawals(lambda: suspend (Flow<UUID>) -> R): R
        = lambda(withdrawalFlow)
}