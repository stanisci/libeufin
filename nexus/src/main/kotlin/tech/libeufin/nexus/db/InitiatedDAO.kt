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

/** Data access logic for initiated outgoing payments */
class InitiatedDAO(private val db: Database) {

    /** Outgoing payments initiation result */
    enum class PaymentInitiationResult {
        REQUEST_UID_REUSE,
        SUCCESS
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
        """)
        stmt.setLong(1, paymentData.amount.value)
        stmt.setInt(2, paymentData.amount.frac)
        stmt.setString(3, paymentData.wireTransferSubject)
        stmt.setString(4, paymentData.creditPaytoUri.toString())
        val initiationTime = paymentData.initiationTime.toDbMicros() ?: run {
            throw Exception("Initiation time could not be converted to microseconds for the database.")
        }
        stmt.setLong(5, initiationTime)
        stmt.setString(6, paymentData.requestUid)
        if (stmt.executeUpdateViolation())
            return@conn PaymentInitiationResult.SUCCESS
        return@conn PaymentInitiationResult.REQUEST_UID_REUSE
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
        stmt.setLong(1, now.toDbMicros()!!)
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
        stmt.setLong(1, now.toDbMicros()!!)
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

    // TODO WIP
    suspend fun submittableGet(currency: String): List<InitiatedPayment> = db.conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
              initiated_outgoing_transaction_id
             ,(amount).val as amount_val
             ,(amount).frac as amount_frac
             ,wire_transfer_subject
             ,credit_payto_uri
             ,initiation_time
             ,request_uid
             FROM initiated_outgoing_transactions
             WHERE (submitted='unsubmitted' OR submitted='transient_failure')
               AND ((amount).val != 0 OR (amount).frac != 0);
        """)
        stmt.all {
            val rowId = it.getLong("initiated_outgoing_transaction_id")
            val initiationTime = it.getLong("initiation_time").microsToJavaInstant()
            if (initiationTime == null) { // nexus fault
                throw Exception("Found invalid timestamp at initiated payment with ID: $rowId")
            }
            InitiatedPayment(
                id = it.getLong("initiated_outgoing_transaction_id"),
                amount = it.getAmount("amount", currency),
                creditPaytoUri = it.getString("credit_payto_uri"),
                wireTransferSubject = it.getString("wire_transfer_subject"),
                initiationTime = initiationTime,
                requestUid = it.getString("request_uid")
            )
        }
    }
}