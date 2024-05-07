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

import tech.libeufin.common.asInstant
import tech.libeufin.common.db.all
import tech.libeufin.common.db.executeUpdateViolation
import tech.libeufin.common.db.oneUniqueViolation
import tech.libeufin.common.db.getAmount
import tech.libeufin.common.db.oneOrNull
import tech.libeufin.common.micros
import java.sql.ResultSet
import java.time.Instant

/** Data access logic for initiated outgoing payments */
class InitiatedDAO(private val db: Database) {

    /** Outgoing payments initiation result */
    sealed interface PaymentInitiationResult {
        data class Success(val id: Long): PaymentInitiationResult
        data object RequestUidReuse: PaymentInitiationResult
    }

    /** Register a new pending payment in the database */
    suspend fun create(paymentData: InitiatedPayment): PaymentInitiationResult = db.conn { conn ->
        val stmt = conn.prepareStatement("""
           INSERT INTO initiated_outgoing_transactions (
             amount
             ,wire_transfer_subject
             ,credit_payto_uri
             ,initiation_time
             ,request_uid
           ) VALUES ((?,?)::taler_amount,?,?,?,?)
           RETURNING initiated_outgoing_transaction_id
        """)
        // TODO check payto uri
        stmt.setLong(1, paymentData.amount.value)
        stmt.setInt(2, paymentData.amount.frac)
        stmt.setString(3, paymentData.wireTransferSubject)
        stmt.setString(4, paymentData.creditPaytoUri.toString())
        stmt.setLong(5, paymentData.initiationTime.micros())
        stmt.setString(6, paymentData.requestUid)
        stmt.oneUniqueViolation(PaymentInitiationResult.RequestUidReuse) {
            PaymentInitiationResult.Success(it.getLong("initiated_outgoing_transaction_id"))
        }
    }

    /** Register EBICS submission success */
    suspend fun submissionSuccess(
        id: Long,
        now: Instant,
        orderId: String
    ) = db.conn { conn ->
        val stmt = conn.prepareStatement("""
            UPDATE initiated_outgoing_transactions SET 
                 submitted = 'success'::submission_state
                ,last_submission_time = ?
                ,failure_message = NULL
                ,order_id = ?
                ,submission_counter = submission_counter + 1
            WHERE initiated_outgoing_transaction_id = ?
        """)
        stmt.setLong(1, now.micros())
        stmt.setString(2, orderId)
        stmt.setLong(3, id)
        stmt.execute()
    }

    /** Register EBICS submission failure */
    suspend fun submissionFailure(
        id: Long,
        now: Instant,
        msg: String?
    ) = db.conn { conn ->
        val stmt = conn.prepareStatement("""
            UPDATE initiated_outgoing_transactions SET 
                 submitted = 'transient_failure'::submission_state
                ,last_submission_time = ?
                ,failure_message = ?
                ,submission_counter = submission_counter + 1
            WHERE initiated_outgoing_transaction_id = ?
        """)
        stmt.setLong(1, now.micros())
        stmt.setString(2, msg)
        stmt.setLong(3, id)
        stmt.execute()
    }

    /** Register EBICS log status message */
    suspend fun logMessage(orderId: String, msg: String) = db.conn { conn ->
        val stmt = conn.prepareStatement("""
            UPDATE initiated_outgoing_transactions SET failure_message = ?
            WHERE order_id = ?
        """)
        stmt.setString(1, msg)
        stmt.setString(2, orderId)
        stmt.execute()
    }

    /** Register EBICS log success and return request_uid if found */
    suspend fun logSuccess(orderId: String): String? = db.conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT request_uid FROM initiated_outgoing_transactions
            WHERE order_id = ?
        """)
        stmt.setString(1, orderId)
        stmt.oneOrNull { it.getString(1) }
    }

    /** Register EBICS log failure and return request_uid and previous message if found */
    suspend fun logFailure(orderId: String): Pair<String, String?>? = db.conn { conn ->
        val stmt = conn.prepareStatement("""
            UPDATE initiated_outgoing_transactions 
                SET submitted = 'permanent_failure'::submission_state
            WHERE order_id = ?
            RETURNING request_uid, failure_message
        """)
        stmt.setString(1, orderId)
        stmt.oneOrNull { Pair(it.getString(1), it.getString(2)) }
    }

    /** Register bank status message */
    suspend fun bankMessage(requestUID: String, msg: String) = db.conn { conn ->
        val stmt = conn.prepareStatement("""
            UPDATE initiated_outgoing_transactions 
                SET failure_message = ?
            WHERE request_uid = ?
        """)
        stmt.setString(1, msg)
        stmt.setString(2, requestUID)
        stmt.execute()
    }

    /** Register bank failure */
    suspend fun bankFailure(requestUID: String, msg: String) = db.conn { conn ->
        val stmt = conn.prepareStatement("""
            UPDATE initiated_outgoing_transactions SET 
                 submitted = 'permanent_failure'::submission_state
                ,failure_message = ?
            WHERE request_uid = ?
        """)
        stmt.setString(1, msg)
        stmt.setString(2, requestUID)
        stmt.execute()
    }

    /** Register reversal */
    suspend fun reversal(requestUID: String, msg: String) = db.conn { conn ->
        val stmt = conn.prepareStatement("""
            UPDATE initiated_outgoing_transactions SET
                 submitted = 'permanent_failure'::submission_state
                ,failure_message = ?
            WHERE request_uid = ?
        """)
        stmt.setString(1, msg)
        stmt.setString(2, requestUID)
        stmt.execute()
    }

    /** List every initiated payment pending submission in the order they should be submitted */
    suspend fun submittable(currency: String): List<InitiatedPayment> = db.conn { conn ->
        fun extract(it: ResultSet): InitiatedPayment {
            val rowId = it.getLong("initiated_outgoing_transaction_id")
            val initiationTime = it.getLong("initiation_time").asInstant()
            return InitiatedPayment(
                id = it.getLong("initiated_outgoing_transaction_id"),
                amount = it.getAmount("amount", currency),
                creditPaytoUri = it.getString("credit_payto_uri"),
                wireTransferSubject = it.getString("wire_transfer_subject"),
                initiationTime = initiationTime,
                requestUid = it.getString("request_uid")
            )
        }
        val selectPart = """
            SELECT
                initiated_outgoing_transaction_id
                ,(amount).val as amount_val
                ,(amount).frac as amount_frac
                ,wire_transfer_subject
                ,credit_payto_uri
                ,initiation_time
                ,request_uid
            FROM initiated_outgoing_transactions
        """
        // We want to maximize the number of successfully submitted transactions in the event 
        // of a malformed transaction or a persistent error classified as transient. We send 
        // the unsubmitted transactions first, starting with the oldest by creation time.
        // This is the happy  path, giving every transaction a chance while being fair on the
        // basis of creation date. 
        // Then we retry the failed transaction, starting with the oldest by submission time.
        // This the bad path retrying each failed transaction applying a rotation based on 
        // resubmission time.
        val unsubmitted = conn.prepareStatement(
            "$selectPart WHERE submitted='unsubmitted' ORDER BY initiation_time"
        ).all(::extract)
        val failed = conn.prepareStatement(
            "$selectPart WHERE submitted='transient_failure' ORDER BY last_submission_time"
        ).all(::extract)
        unsubmitted + failed
    }
}