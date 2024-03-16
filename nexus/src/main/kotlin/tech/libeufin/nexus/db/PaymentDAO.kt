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

import tech.libeufin.nexus.*
import tech.libeufin.common.*
import java.time.Instant

/** Data access logic for incoming & outgoing payments */
class PaymentDAO(private val db: Database) {
    /** Outgoing payments registration result */
    data class OutgoingRegistrationResult(
        val id: Long,
        val initiated: Boolean,
        val new: Boolean
    )

    /** Register an outgoing payment reconciling it with its initiated payment counterpart if present */
    suspend fun registerOutgoing(paymentData: OutgoingPayment): OutgoingRegistrationResult = db.conn {        
        val stmt = it.prepareStatement("""
            SELECT out_tx_id, out_initiated, out_found
            FROM register_outgoing((?,?)::taler_amount,?,?,?,?)
        """)
        val executionTime = paymentData.executionTime.micros()
        stmt.setLong(1, paymentData.amount.value)
        stmt.setInt(2, paymentData.amount.frac)
        stmt.setString(3, paymentData.wireTransferSubject)
        stmt.setLong(4, executionTime)
        stmt.setString(5, paymentData.creditPaytoUri)
        stmt.setString(6, paymentData.messageId)
        stmt.one {
            OutgoingRegistrationResult(
                it.getLong("out_tx_id"),
                it.getBoolean("out_initiated"),
                !it.getBoolean("out_found")
            )
        }
    }

    /** Incoming payments bounce registration result */
    data class IncomingBounceRegistrationResult(
        val id: Long,
        val bounceId: String,
        val new: Boolean
    )

    /** Register an incoming payment and bounce it */
    suspend fun registerMalformedIncoming(
        paymentData: IncomingPayment,
        bounceAmount: TalerAmount,
        now: Instant
    ): IncomingBounceRegistrationResult = db.conn {       
        val stmt = it.prepareStatement("""
            SELECT out_found, out_tx_id, out_bounce_id
            FROM register_incoming_and_bounce((?,?)::taler_amount,?,?,?,?,(?,?)::taler_amount,?)
        """)
        val refundTimestamp = now.micros()
        val executionTime = paymentData.executionTime.micros()
        stmt.setLong(1, paymentData.amount.value)
        stmt.setInt(2, paymentData.amount.frac)
        stmt.setString(3, paymentData.wireTransferSubject)
        stmt.setLong(4, executionTime)
        stmt.setString(5, paymentData.debitPaytoUri)
        stmt.setString(6, paymentData.bankId)
        stmt.setLong(7, bounceAmount.value)
        stmt.setInt(8, bounceAmount.frac)
        stmt.setLong(9, refundTimestamp)
        stmt.one {
            IncomingBounceRegistrationResult(
                it.getLong("out_tx_id"),
                it.getString("out_bounce_id"),
                !it.getBoolean("out_found")
            )
        }
    }

    /** Incoming payments registration result */
    data class IncomingRegistrationResult(
        val id: Long,
        val new: Boolean
    )

    /** Register an talerable incoming payment */
    suspend fun registerTalerableIncoming(
        paymentData: IncomingPayment,
        reservePub: EddsaPublicKey
    ): IncomingRegistrationResult = db.conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT out_found, out_tx_id
            FROM register_incoming_and_talerable((?,?)::taler_amount,?,?,?,?,?)
        """)
        val executionTime = paymentData.executionTime.micros()
        stmt.setLong(1, paymentData.amount.value)
        stmt.setInt(2, paymentData.amount.frac)
        stmt.setString(3, paymentData.wireTransferSubject)
        stmt.setLong(4, executionTime)
        stmt.setString(5, paymentData.debitPaytoUri)
        stmt.setString(6, paymentData.bankId)
        stmt.setBytes(7, reservePub.raw)
        stmt.one {
            IncomingRegistrationResult(
                it.getLong("out_tx_id"),
                !it.getBoolean("out_found")
            )
        }
    }
}