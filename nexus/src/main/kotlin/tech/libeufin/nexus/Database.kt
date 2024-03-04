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
package tech.libeufin.nexus

import org.postgresql.util.PSQLState
import tech.libeufin.common.*
import java.sql.PreparedStatement
import java.sql.SQLException
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.*

fun Instant.fmtDate(): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd")
    return formatter.format(Date.from(this))
}

fun Instant.fmtDateTime(): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS")
    return formatter.format(Date.from(this))
}

// INCOMING PAYMENTS STRUCTS

/**
 * Represents an incoming payment in the database.
 */
data class IncomingPayment(
    val amount: TalerAmount,
    val wireTransferSubject: String,
    val debitPaytoUri: String,
    val executionTime: Instant,
    /** ISO20022 AccountServicerReference */
    val bankId: String
)  {
    override fun toString(): String {
        return "IN ${executionTime.fmtDate()} $amount '$bankId' debitor=$debitPaytoUri subject=$wireTransferSubject"
    }
}


// INITIATED PAYMENTS STRUCTS

enum class DatabaseSubmissionState {
    /**
     * Submission got both EBICS_OK.
     */
    success,
    /**
     * Submission can be retried (network issue, for example)
     */
    transient_failure,
    /**
     * Submission got at least one error code which was not
     * EBICS_OK.
     */
    permanent_failure,
    /**
     * The submitted payment was never witnessed by a camt.5x
     * or pain.002 report.
     */
    never_heard_back
}

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
 * Possible outcomes for inserting a initiated payment
 * into the database.
 */
enum class PaymentInitiationOutcome {

    /**
     * The row contains a client_request_uid that exists
     * already in the database.
     */
    UNIQUE_CONSTRAINT_VIOLATION,
    /**
     * Record successfully created.
     */
    SUCCESS
}

// OUTGOING PAYMENTS STRUCTS

/**
 * Collects data of a booked outgoing payment.
 */
data class OutgoingPayment(
    val amount: TalerAmount,
    val executionTime: Instant,
    /** ISO20022 MessageIdentification */
    val messageId: String,
    val creditPaytoUri: String? = null, // not showing in camt.054
    val wireTransferSubject: String? = null // not showing in camt.054
) {
    override fun toString(): String {
        return "OUT ${executionTime.fmtDate()} $amount '$messageId' creditor=$creditPaytoUri subject=$wireTransferSubject"
    }
}

/** Outgoing payments registration result */
data class OutgoingRegistrationResult(
    val id: Long,
    val initiated: Boolean,
    val new: Boolean
)

/** Incoming payments registration result */
data class IncomingRegistrationResult(
    val id: Long,
    val new: Boolean
)

/** Incoming payments bounce registration result */
data class IncomingBounceRegistrationResult(
    val id: Long,
    val bounceId: String,
    val new: Boolean
)

/**
 * Performs a INSERT, UPDATE, or DELETE operation.
 *
 * @return true if at least one row was affected by this operation,
 *         false on unique constraint violation or no rows were affected.
 *
 */
private fun PreparedStatement.maybeUpdate(): Boolean {
    try {
        this.executeUpdate()
    } catch (e: SQLException) {
        logger.error(e.message)
        if (e.sqlState == PSQLState.UNIQUE_VIOLATION.state) return false
        throw e // rethrowing, not to hide other types of errors.
    }
    return updateCount > 0
}

/**
 * Collects database connection steps and any operation on the Nexus tables.
 */
class Database(dbConfig: String): DbPool(dbConfig, "libeufin_nexus") {

    // Temporary in memory database to store EBICS order status until we modify the schema to actually store it in the database
    var mem: MutableMap<String, String> = mutableMapOf()

    // OUTGOING PAYMENTS METHODS

    /**
     * Register an outgoing payment OPTIONALLY reconciling it with its
     * initiated payment counterpart.
     *
     * @param paymentData information about the outgoing payment.
     * @return operation outcome enum.
     */
    suspend fun registerOutgoing(paymentData: OutgoingPayment): OutgoingRegistrationResult = conn {        
        val stmt = it.prepareStatement("""
            SELECT out_tx_id, out_initiated, out_found
              FROM register_outgoing(
                (?,?)::taler_amount
                ,?
                ,?
                ,?
                ,?
              )"""
        )
        val executionTime = paymentData.executionTime.toDbMicros()
            ?: throw Exception("Could not convert outgoing payment execution_time to microseconds")
        stmt.setLong(1, paymentData.amount.value)
        stmt.setInt(2, paymentData.amount.frac)
        stmt.setString(3, paymentData.wireTransferSubject)
        stmt.setLong(4, executionTime)
        stmt.setString(5, paymentData.creditPaytoUri)
        stmt.setString(6, paymentData.messageId)

        stmt.executeQuery().use {
            when {
                !it.next() -> throw Exception("Inserting outgoing payment gave no outcome.")
                else -> OutgoingRegistrationResult(
                    it.getLong("out_tx_id"),
                    it.getBoolean("out_initiated"),
                    !it.getBoolean("out_found")
                )
            }
        }
    }

    // INCOMING PAYMENTS METHODS

    /**
     * Register an incoming payment and bounce it
     *
     * @param paymentData information about the incoming payment
     * @param requestUid unique identifier of the bounce outgoing payment to
     *                   initiate
     * @param bounceAmount amount to send back to the original debtor
     * @param bounceSubject subject of the bounce outhoing payment
     * @return true if new
     */
    suspend fun registerMalformedIncoming(
        paymentData: IncomingPayment,
        bounceAmount: TalerAmount,
        now: Instant
    ): IncomingBounceRegistrationResult = conn {       
        val stmt = it.prepareStatement("""
            SELECT out_found, out_tx_id, out_bounce_id
              FROM register_incoming_and_bounce(
                (?,?)::taler_amount
                ,?
                ,?
                ,?
                ,?
                ,(?,?)::taler_amount
                ,?
              )"""
        )
        val refundTimestamp = now.toDbMicros()
            ?: throw Exception("Could not convert refund execution time from Instant.now() to microsends.")
        val executionTime = paymentData.executionTime.toDbMicros()
            ?: throw Exception("Could not convert payment execution time from Instant to microseconds.")
        stmt.setLong(1, paymentData.amount.value)
        stmt.setInt(2, paymentData.amount.frac)
        stmt.setString(3, paymentData.wireTransferSubject)
        stmt.setLong(4, executionTime)
        stmt.setString(5, paymentData.debitPaytoUri)
        stmt.setString(6, paymentData.bankId)
        stmt.setLong(7, bounceAmount.value)
        stmt.setInt(8, bounceAmount.frac)
        stmt.setLong(9, refundTimestamp)
        stmt.executeQuery().use {
            when {
                !it.next() -> throw Exception("Inserting malformed incoming payment gave no outcome")
                else -> IncomingBounceRegistrationResult(
                    it.getLong("out_tx_id"),
                    it.getString("out_bounce_id"),
                    !it.getBoolean("out_found")
                )
            }
        }
    }

    /**
     * Register an talerable incoming payment
     *
     * @param paymentData incoming talerable payment.
     * @param reservePub reserve public key.  The caller is
     *        responsible to check it.
     */
    suspend fun registerTalerableIncoming(
        paymentData: IncomingPayment,
        reservePub: EddsaPublicKey
    ): IncomingRegistrationResult = conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT out_found, out_tx_id
              FROM register_incoming_and_talerable(
                (?,?)::taler_amount
                ,?
                ,?
                ,?
                ,?
                ,?
              )"""
        )
        val executionTime = paymentData.executionTime.toDbMicros()
            ?: throw Exception("Could not convert payment execution time from Instant to microseconds.")
        stmt.setLong(1, paymentData.amount.value)
        stmt.setInt(2, paymentData.amount.frac)
        stmt.setString(3, paymentData.wireTransferSubject)
        stmt.setLong(4, executionTime)
        stmt.setString(5, paymentData.debitPaytoUri)
        stmt.setString(6, paymentData.bankId)
        stmt.setBytes(7, reservePub.raw)
        stmt.executeQuery().use {
            when {
                !it.next() -> throw Exception("Inserting talerable incoming payment gave no outcome")
                else -> IncomingRegistrationResult(
                    it.getLong("out_tx_id"),
                    !it.getBoolean("out_found")
                )
            }
        }
    }

    /**
     * Get the last execution time of outgoing transactions.
     *
     * @return [Instant] or null if no results were found
     */
    suspend fun outgoingPaymentLastExecTime(): Instant? = conn { conn ->
        val stmt = conn.prepareStatement(
            "SELECT MAX(execution_time) as latest_execution_time FROM outgoing_transactions"
        )
        stmt.executeQuery().use {
            if (!it.next()) return@conn null
            val timestamp = it.getLong("latest_execution_time")
            if (timestamp == 0L) return@conn null
            return@conn timestamp.microsToJavaInstant()
                ?: throw Exception("Could not convert latest_execution_time to Instant")
        }
    }

    /**
     * Get the last execution time of an incoming transaction.
     *
     * @return [Instant] or null if no results were found
     */
    suspend fun incomingPaymentLastExecTime(): Instant? = conn { conn ->
        val stmt = conn.prepareStatement(
            "SELECT MAX(execution_time) as latest_execution_time FROM incoming_transactions"
        )
        stmt.executeQuery().use {
            if (!it.next()) return@conn null
            val timestamp = it.getLong("latest_execution_time")
            if (timestamp == 0L) return@conn null
            return@conn timestamp.microsToJavaInstant()
                ?: throw Exception("Could not convert latest_execution_time to Instant")
        }
    }

    /**
     * Checks if the reserve public key already exists.
     *
     * @param maybeReservePub reserve public key to look up
     * @return true if found, false otherwise
     */
    suspend fun isReservePubFound(maybeReservePub: EddsaPublicKey): Boolean = conn { conn ->
        val stmt = conn.prepareStatement("""
             SELECT 1
               FROM talerable_incoming_transactions
               WHERE reserve_public_key = ?;
        """)
        stmt.setBytes(1, maybeReservePub.raw)
        val res = stmt.executeQuery()
        res.use {
            return@conn it.next()
        }
    }

    // INITIATED PAYMENTS METHODS

    /**
     * Represents all the states but "unsubmitted" related to an
     * initiated payment.  Unsubmitted gets set by default by the
     * database and there's no case where it has to be reset to an
     * initiated payment.
     */

    /**
     * Sets the submission state of an initiated payment.  Transparently
     * sets the last_submission_time column too, as this corresponds to the
     * time when we set the state.
     *
     * @param rowId row ID of the record to set.
     * @param submissionState which state to set.
     * @return true on success, false if no payment was affected.
     */
    suspend fun initiatedPaymentSetSubmittedState(
        rowId: Long,
        submissionState: DatabaseSubmissionState
    ): Boolean = conn { conn ->
        val stmt = conn.prepareStatement("""
             UPDATE initiated_outgoing_transactions
                      SET submitted = submission_state(?), last_submission_time = ?
                      WHERE initiated_outgoing_transaction_id = ?
             """
        )
        val now = Instant.now()
        stmt.setString(1, submissionState.name)
        stmt.setLong(2, now.toDbMicros() ?: run {
            throw Exception("Submission time could not be converted to microseconds for the database.")
        })
        stmt.setLong(3, rowId)
        return@conn stmt.maybeUpdate()
    }

    /**
     * Sets the failure reason to an initiated payment.
     *
     * @param rowId row ID of the record to set.
     * @param failureMessage error associated to this initiated payment.
     * @return true on success, false if no payment was affected.
     */
    suspend fun initiatedPaymentSetFailureMessage(rowId: Long, failureMessage: String): Boolean = conn { conn ->
        val stmt = conn.prepareStatement("""
             UPDATE initiated_outgoing_transactions
                      SET failure_message = ?
                      WHERE initiated_outgoing_transaction_id=?
             """
        )
        stmt.setString(1, failureMessage)
        stmt.setLong(2, rowId)
        return@conn stmt.maybeUpdate()
    }

    /**
     * Gets any initiated payment that was not submitted to the
     * bank yet.
     *
     * @param currency in which currency should the payment be submitted to the bank.
     * @return [Map] of the initiated payment row ID and [InitiatedPayment]
     */
    suspend fun initiatedPaymentsSubmittableGet(currency: String): List<InitiatedPayment> = conn { conn ->
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
    /**
     * Initiate a payment in the database.  The "submit"
     * command is then responsible to pick it up and submit
     * it to the bank.
     *
     * @param paymentData any data that's used to prepare the payment.
     * @return true if the insertion went through, false in case of errors.
     */
    suspend fun initiatedPaymentCreate(paymentData: InitiatedPayment): PaymentInitiationOutcome = conn { conn ->
        val stmt = conn.prepareStatement("""
           INSERT INTO initiated_outgoing_transactions (
             amount
             ,wire_transfer_subject
             ,credit_payto_uri
             ,initiation_time
             ,request_uid
           ) VALUES (
             (?,?)::taler_amount
             ,?
             ,?
             ,?
             ,?
           )
        """)
        stmt.setLong(1, paymentData.amount.value)
        stmt.setInt(2, paymentData.amount.frac)
        stmt.setString(3, paymentData.wireTransferSubject)
        stmt.setString(4, paymentData.creditPaytoUri.toString())
        val initiationTime = paymentData.initiationTime.toDbMicros() ?: run {
            throw Exception("Initiation time could not be converted to microseconds for the database.")
        }
        stmt.setLong(5, initiationTime)
        stmt.setString(6, paymentData.requestUid) // can be null.
        if (stmt.maybeUpdate())
            return@conn PaymentInitiationOutcome.SUCCESS
        /**
         * _very_ likely, Nexus didn't check the request idempotency,
         * as the row ID would never fall into the following problem.
         */
        return@conn PaymentInitiationOutcome.UNIQUE_CONSTRAINT_VIOLATION
    }

    /**
     * Gets the ID of an initiated payment.  Useful to link it to its
     * outgoing payment witnessed in a bank record.
     *
     * @param uid UID as given by Nexus when it initiated the payment.
     *        This value then gets specified as the MsgId of pain.001,
     *        and it gets associated by the bank to the booked entries
     *        in camt.05x reports.
     * @return the initiated payment row ID, or null if not found.  NOTE:
     *         null gets returned even when the initiated payment exists,
     *         *but* it was NOT flagged as submitted.
     */
    suspend fun initiatedPaymentGetFromUid(uid: String): Long? = conn { conn ->
        val stmt = conn.prepareStatement("""
           SELECT initiated_outgoing_transaction_id
             FROM initiated_outgoing_transactions
             WHERE request_uid = ? AND submitted = 'success';
        """)
        stmt.setString(1, uid)
        val res = stmt.executeQuery()
        res.use {
            if (!it.next()) return@conn null
            return@conn it.getLong("initiated_outgoing_transaction_id")
        }
    }
}