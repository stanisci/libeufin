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

import tech.libeufin.common.db.*
import tech.libeufin.common.*
import tech.libeufin.nexus.IncomingPayment
import tech.libeufin.nexus.OutgoingPayment
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
    sealed interface IncomingRegistrationResult {
        data class Success(val id: Long, val new: Boolean): IncomingRegistrationResult
        data object ReservePubReuse: IncomingRegistrationResult
    }

    /** Register an talerable incoming payment */
    suspend fun registerTalerableIncoming(
        paymentData: IncomingPayment,
        reservePub: EddsaPublicKey
    ): IncomingRegistrationResult = db.conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT out_reserve_pub_reuse, out_found, out_tx_id
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
            when {
                it.getBoolean("out_reserve_pub_reuse") -> IncomingRegistrationResult.ReservePubReuse
                else -> IncomingRegistrationResult.Success(
                    it.getLong("out_tx_id"),
                    !it.getBoolean("out_found")
                )
            }
        }
    }

    /** List incoming transaction metadata for debugging */
    suspend fun metadataIncoming(): List<IncomingTxMetadata> = db.conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
                (amount).val as amount_val
                ,(amount).frac AS amount_frac
                ,wire_transfer_subject
                ,execution_time
                ,debit_payto_uri
                ,bank_id
                ,reserve_public_key
            FROM incoming_transactions
                LEFT OUTER JOIN talerable_incoming_transactions using (incoming_transaction_id)
            ORDER BY execution_time
        """)
        stmt.all {
            IncomingTxMetadata(
                date = it.getLong("execution_time").asInstant(),
                amount = it.getDecimal("amount"),
                subject = it.getString("wire_transfer_subject"),
                debtor = it.getString("debit_payto_uri"),
                id = it.getString("bank_id"),
                reservePub = it.getBytes("reserve_public_key")?.run { EddsaPublicKey(this) }
            )
        }
    }

    /** List outgoing transaction metadata for debugging */
    suspend fun metadataOutgoing(): List<OutgoingTxMetadata> = db.conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
                (amount).val as amount_val
                ,(amount).frac AS amount_frac
                ,wire_transfer_subject
                ,execution_time
                ,credit_payto_uri
                ,message_id
            FROM outgoing_transactions
            ORDER BY execution_time
        """)
        stmt.all {
            OutgoingTxMetadata(
                date = it.getLong("execution_time").asInstant(),
                amount = it.getDecimal("amount"),
                subject = it.getString("wire_transfer_subject"),
                creditor = it.getString("credit_payto_uri"),
                id = it.getString("message_id")
            )
        }
    }

    /** List initiated transaction metadata for debugging */
    suspend fun metadataInitiated(): List<InitiatedTxMetadata> = db.conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
                (amount).val as amount_val
                ,(amount).frac AS amount_frac
                ,wire_transfer_subject
                ,initiation_time
                ,last_submission_time
                ,submission_counter
                ,credit_payto_uri
                ,submitted
                ,request_uid
                ,failure_message
            FROM initiated_outgoing_transactions
            ORDER BY initiation_time
        """)
        stmt.all {
            InitiatedTxMetadata(
                date = it.getLong("initiation_time").asInstant(),
                amount = it.getDecimal("amount"),
                subject = it.getString("wire_transfer_subject"),
                creditor = it.getString("credit_payto_uri"),
                id = it.getString("request_uid"),
                status = it.getString("submitted"),
                msg = it.getString("failure_message"),
                submissionTime = it.getLong("last_submission_time").asInstant(),
                submissionCounter = it.getInt("submission_counter")
            )
        }
    }
}

/** Incoming transaction metadata for debugging */
data class IncomingTxMetadata(
    val date: Instant,
    val amount: DecimalNumber,
    val subject: String,
    val debtor: String,
    val id: String,
    val reservePub: EddsaPublicKey?
)

/** Outgoing transaction metadata for debugging */
data class OutgoingTxMetadata(
    val date: Instant,
    val amount: DecimalNumber,
    val subject: String?,
    val creditor: String?,
    val id: String,
    // TODO when merged with v11
)

/** Initiated metadata for debugging */
data class InitiatedTxMetadata(
    val date: Instant,
    val amount: DecimalNumber,
    val subject: String,
    val creditor: String,
    val id: String,
    val status: String,
    val msg: String?,
    val submissionTime: Instant,
    val submissionCounter: Int
)