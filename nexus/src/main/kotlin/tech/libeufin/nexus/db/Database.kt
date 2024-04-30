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
package tech.libeufin.nexus.db

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.common.TalerAmount
import tech.libeufin.common.db.*
import java.time.Instant

/**
 * Minimal set of information to initiate a new payment in
 * the database.
 */
data class InitiatedPayment(
    val id: Long,
    val amount: TalerAmount,
    val wireTransferSubject: String,
    val creditPaytoUri: String,
    val initiationTime: Instant,
    val requestUid: String
)

/**
 * Collects database connection steps and any operation on the Nexus tables.
 */
class Database(dbConfig: DatabaseConfig, val bankCurrency: String): DbPool(dbConfig, "libeufin_nexus") {
    val payment = PaymentDAO(this)
    val initiated = InitiatedDAO(this)
    val exchange = ExchangeDAO(this)

    private val outgoingTxFlows: MutableSharedFlow<Long> = MutableSharedFlow()
    private val incomingTxFlows: MutableSharedFlow<Long> = MutableSharedFlow()
    private val revenueTxFlows: MutableSharedFlow<Long> = MutableSharedFlow()

    init {
        watchNotifications(pgSource, "libeufin_nexus", LoggerFactory.getLogger("libeufin-nexus-db-watcher"), mapOf(
            "revenue_tx" to {
                val id = it.toLong()
                revenueTxFlows.emit(id)
            },
            "outgoing_tx" to {
                val id = it.toLong()
                outgoingTxFlows.emit(id)
            },
            "incoming_tx" to {
                val id = it.toLong()
                incomingTxFlows.emit(id)
            }
        ))
    }

    /** Listen for new taler outgoing transactions */
    suspend fun <R> listenOutgoing(lambda: suspend (Flow<Long>) -> R): R
        = lambda(outgoingTxFlows)
    /** Listen for new taler incoming transactions */
    suspend fun <R> listenIncoming(lambda: suspend (Flow<Long>) -> R): R
        = lambda(incomingTxFlows)
    /** Listen for new incoming transactions */
    suspend fun <R> listenRevenue(lambda: suspend (Flow<Long>) -> R): R
        = lambda(revenueTxFlows)
}