package tech.libeufin.nexus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.postgresql.jdbc.PgConnection
import com.zaxxer.hikari.*
import tech.libeufin.util.*
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.Instant

data class TalerAmount(
    val value: Long,
    val fraction: Int, // has at most 8 digits.
    val currency: String
)

// INCOMING PAYMENTS STRUCTS

/**
 * Represents an incoming payment in the database.
 */
data class IncomingPayment(
    val amount: TalerAmount,
    val wireTransferSubject: String,
    val debitPaytoUri: String,
    val executionTime: Instant,
    val bankTransferId: String
)

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
     * The Payto address to send the payment to was invalid.
     */
    BAD_CREDIT_PAYTO,

    /**
     * The receiver payto address lacks the name, that would
     * cause the bank to reject the pain.001.
     */
    RECEIVER_NAME_MISSING,

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

data class OutgoingPayment(
    val amount: TalerAmount,
    val wireTransferSubject: String?,
    val executionTime: Instant,
    val creditPaytoUri: String,
    val bankTransferId: String
)

/**
 * Witnesses the outcome of inserting an outgoing
 * payment into the database.
 */
enum class OutgoingPaymentOutcome {
    /**
     * The caller wanted to link a previously initiated payment
     * to this outgoing one, but the row ID passed to the inserting
     * function could not be found in the payment initiations table.
     * Note: NO insertion takes place in this case.
     */
    INITIATED_COUNTERPART_NOT_FOUND,
    /**
     * The outgoing payment got inserted and _in case_ the caller
     * wanted to link a previously initiated payment to this one, that
     * succeeded too.
     */
    SUCCESS
}

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
        if (e.sqlState == "23505") return false // unique_violation
        throw e // rethrowing, not to hide other types of errors.
    }
    return updateCount > 0
}

/**
 * Collects database connection steps and any operation on the Nexus tables.
 */
class Database(dbConfig: String): java.io.Closeable {
    val dbPool: HikariDataSource

    init {
        val pgSource = pgDataSource(dbConfig)
        val config = HikariConfig();
        config.dataSource = pgSource
        config.connectionInitSql = "SET search_path TO libeufin_nexus;"
        config.validate()
        dbPool = HikariDataSource(config);
    }

    /**
     * Closes the database connection.
     */
    override fun close() {
        dbPool.close()
    }

    /**
     * Moves the database operations where they can block, without
     * blocking the whole process.
     *
     * @param lambda actual statement preparation and execution logic.
     * @return what lambda returns.
     */
    suspend fun <R> runConn(lambda: suspend (PgConnection) -> R): R {
        // Use a coroutine dispatcher that we can block as JDBC API is blocking
        return withContext(Dispatchers.IO) {
            val conn = dbPool.getConnection()
            conn.use { it -> lambda(it.unwrap(PgConnection::class.java)) }
        }
    }

    // OUTGOING PAYMENTS METHODS

    /**
     * Creates one outgoing payment OPTIONALLY reconciling it with its
     * initiated payment counterpart.
     *
     * @param paymentData information about the outgoing payment.
     * @param reconcileId optional row ID of the initiated payment
     *        that will reference this one.  Note: if this value is
     *        not found, then NO row gets inserted in the database.
     * @return operation outcome enum.
     */
    suspend fun outgoingPaymentCreate(
        paymentData: OutgoingPayment,
        reconcileId: Long? = null
    ): OutgoingPaymentOutcome = runConn {
        val stmt = it.prepareStatement("""
            SELECT out_nx_initiated
              FROM create_outgoing_payment(
                (?,?)::taler_amount
                ,?
                ,?
                ,?
                ,?
                ,?
              )"""
        )
        val executionTime = paymentData.executionTime.toDbMicros()
            ?: throw Exception("Could not convert outgoing payment execution_time to microseconds")
        stmt.setLong(1, paymentData.amount.value)
        stmt.setInt(2, paymentData.amount.fraction)
        stmt.setString(3, paymentData.wireTransferSubject)
        stmt.setLong(4, executionTime)
        stmt.setString(5, paymentData.creditPaytoUri)
        stmt.setString(6, paymentData.bankTransferId)
        if (reconcileId == null)
            stmt.setNull(7, java.sql.Types.BIGINT)
        else
            stmt.setLong(7, reconcileId)

        stmt.executeQuery().use {
            if (!it.next()) throw Exception("Inserting outgoing payment gave no outcome.")
            if (it.getBoolean("out_nx_initiated"))
                return@runConn OutgoingPaymentOutcome.INITIATED_COUNTERPART_NOT_FOUND
        }
        return@runConn OutgoingPaymentOutcome.SUCCESS
    }

    // INCOMING PAYMENTS METHODS

    /**
     * Flags an incoming payment as bounced.  NOTE: the flag merely means
     * that the payment had an invalid subject for a Taler withdrawal _and_
     * it got initiated as an outgoing payments.  In NO way this flag
     * means that the actual value was returned to the initial debtor.
     *
     * @param rowId row ID of the payment to flag as bounced.
     * @param initiatedRequestUid unique identifier for the outgoing payment to
     *                            initiate for this bouncing.
     * @return true if the payment could be set as bounced, false otherwise.
     */
    suspend fun incomingPaymentSetAsBounced(rowId: Long, initiatedRequestUid: String): Boolean = runConn { conn ->
        val timestamp = Instant.now().toDbMicros()
            ?: throw Exception("Could not convert Instant.now() to microseconds, won't bounce this payment.")
        val stmt = conn.prepareStatement("""
             SELECT out_nx_incoming_payment
               FROM bounce_payment(?,?,?)
             """
        )
        stmt.setLong(1, rowId)
        stmt.setLong(2, timestamp)
        stmt.setString(3, initiatedRequestUid)
        stmt.executeQuery().use { maybeResult ->
            if (!maybeResult.next()) throw Exception("Expected outcome from the SQL bounce_payment function")
            return@runConn !maybeResult.getBoolean("out_nx_incoming_payment")
        }
    }

    /**
     * Creates an incoming payment as bounced _and_ initiates its
     * reimbursement.
     *
     * @param paymentData information related to the incoming payment.
     * @param requestUid unique identifier of the outgoing payment to
     *                   initiate, in order to reimburse the bounced tx.
     */
    suspend fun incomingPaymentCreateBounced(
        paymentData: IncomingPayment,
        requestUid: String
        ): Boolean = runConn { conn ->
        val refundTimestamp = Instant.now().toDbMicros()
            ?: throw Exception("Could not convert refund execution time from Instant.now() to microsends.")
        val executionTime = paymentData.executionTime.toDbMicros()
            ?: throw Exception("Could not convert payment execution time from Instant to microseconds.")
        val stmt = conn.prepareStatement("""
            SELECT out_ok FROM create_incoming_and_bounce (
              (?,?)::taler_amount
              ,?
              ,?
              ,?
              ,?
              ,?
              ,?
            )""")
        stmt.setLong(1, paymentData.amount.value)
        stmt.setInt(2, paymentData.amount.fraction)
        stmt.setString(3, paymentData.wireTransferSubject)
        stmt.setLong(4, executionTime)
        stmt.setString(5, paymentData.debitPaytoUri)
        stmt.setString(6, paymentData.bankTransferId)
        stmt.setLong(7, refundTimestamp)
        stmt.setString(8, requestUid)
        val res = stmt.executeQuery()
        res.use {
            if (!it.next()) return@runConn false
            return@runConn it.getBoolean("out_ok")
        }
    }

    /**
     * Get the last execution time of an incoming transaction.  This
     * serves as the start date for new requests to the bank.
     *
     * @return [Instant] or null if no results were found
     */
    suspend fun incomingPaymentLastExecTime(): Instant? = runConn { conn ->
        val stmt = conn.prepareStatement(
            "SELECT MAX(execution_time) as latest_execution_time FROM incoming_transactions"
        )
        stmt.executeQuery().use {
            if (!it.next()) return@runConn null
            val timestamp = it.getLong("latest_execution_time")
            if (timestamp == 0L) return@runConn null
            val asInstant = timestamp.microsToJavaInstant()
            if (asInstant == null)
                throw Exception("Could not convert latest_execution_time to Instant")
            return@runConn asInstant
        }
    }

    /**
     * Checks if the incoming payment was already processed by Nexus.
     *
     * @param bankUid unique identifier assigned by the bank to the payment.
     *        Normally, that's the <AcctSvcrRef> value found in camt.05x records.
     * @return true if found, false otherwise
     */
    suspend fun isIncomingPaymentSeen(bankUid: String): Boolean = runConn { conn ->
        val stmt = conn.prepareStatement("""
             SELECT 1
               FROM incoming_transactions
               WHERE bank_transfer_id = ?;
        """)
        stmt.setString(1, bankUid)
        val res = stmt.executeQuery()
        res.use {
            return@runConn it.next()
        }
    }

    /**
     * Checks if the reserve public key already exists.
     *
     * @param maybeReservePub reserve public key to look up
     * @return true if found, false otherwise
     */
    suspend fun isReservePubFound(maybeReservePub: ByteArray): Boolean = runConn { conn ->
        val stmt = conn.prepareStatement("""
             SELECT 1
               FROM talerable_incoming_transactions
               WHERE reserve_public_key = ?;
        """)
        stmt.setBytes(1, maybeReservePub)
        val res = stmt.executeQuery()
        res.use {
            return@runConn it.next()
        }
    }

    /**
     * Creates an incoming transaction row and  links a new talerable
     * row to it.
     *
     * @param paymentData incoming talerable payment.
     * @param reservePub reserve public key.  The caller is
     *        responsible to check it.
     */
    suspend fun incomingTalerablePaymentCreate(
        paymentData: IncomingPayment,
        reservePub: ByteArray
    ): Boolean = runConn { conn ->
        val stmt = conn.prepareStatement("""
           SELECT out_ok FROM create_incoming_talerable(
              (?,?)::taler_amount
              ,?
              ,?
              ,?
              ,?
              ,?
           )""")
        bindIncomingPayment(paymentData, stmt)
        stmt.setBytes(7, reservePub)
        stmt.executeQuery().use {
            if (!it.next()) return@runConn false
            return@runConn it.getBoolean("out_ok")
        }
    }

    /**
     * Binds the values of an incoming payment to the prepared
     * statement's placeholders.  Warn: may easily break in case
     * the placeholders get their positions changed!
     *
     * @param data incoming payment to bind to the placeholders
     * @param stmt statement to receive the values in its placeholders
     */
    private fun bindIncomingPayment(
        data: IncomingPayment,
        stmt: PreparedStatement
    ) {
        stmt.setLong(1, data.amount.value)
        stmt.setInt(2, data.amount.fraction)
        stmt.setString(3, data.wireTransferSubject)
        val executionTime = data.executionTime.toDbMicros() ?: run {
            throw Exception("Execution time could not be converted to microseconds for the database.")
        }
        stmt.setLong(4, executionTime)
        stmt.setString(5, data.debitPaytoUri)
        stmt.setString(6, data.bankTransferId)
    }
    /**
     * Creates a new incoming payment record in the database.  It does NOT
     * update the "talerable" table.
     *
     * @param paymentData information related to the incoming payment.
     * @return true on success, false otherwise.
     */
    suspend fun incomingPaymentCreate(paymentData: IncomingPayment): Boolean = runConn { conn ->
        val stmt = conn.prepareStatement("""
            INSERT INTO incoming_transactions (
              amount
              ,wire_transfer_subject
              ,execution_time
              ,debit_payto_uri
              ,bank_transfer_id
            ) VALUES (
              (?,?)::taler_amount
              ,?
              ,?
              ,?
              ,?
            )
        """)
        bindIncomingPayment(paymentData, stmt)
        return@runConn stmt.maybeUpdate()
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
    ): Boolean = runConn { conn ->
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
        return@runConn stmt.maybeUpdate()
    }

    /**
     * Sets the failure reason to an initiated payment.
     *
     * @param rowId row ID of the record to set.
     * @param failureMessage error associated to this initiated payment.
     * @return true on success, false if no payment was affected.
     */
    suspend fun initiatedPaymentSetFailureMessage(rowId: Long, failureMessage: String): Boolean = runConn { conn ->
        val stmt = conn.prepareStatement("""
             UPDATE initiated_outgoing_transactions
                      SET failure_message = ?
                      WHERE initiated_outgoing_transaction_id=?
             """
        )
        stmt.setString(1, failureMessage)
        stmt.setLong(2, rowId)
        return@runConn stmt.maybeUpdate()
    }

    /**
     * Gets any initiated payment that was not submitted to the
     * bank yet.
     *
     * @param currency in which currency should the payment be submitted to the bank.
     * @return [Map] of the initiated payment row ID and [InitiatedPayment]
     */
    suspend fun initiatedPaymentsUnsubmittedGet(currency: String): Map<Long, InitiatedPayment> = runConn { conn ->
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
             WHERE submitted='unsubmitted';
        """)
        val maybeMap = mutableMapOf<Long, InitiatedPayment>()
        stmt.executeQuery().use {
            if (!it.next()) return@use
            do {
                val rowId = it.getLong("initiated_outgoing_transaction_id")
                val initiationTime = it.getLong("initiation_time").microsToJavaInstant()
                if (initiationTime == null) { // nexus fault
                    throw Exception("Found invalid timestamp at initiated payment with ID: $rowId")
                }
                maybeMap[rowId] = InitiatedPayment(
                    amount = TalerAmount(
                        value = it.getLong("amount_val"),
                        fraction = it.getInt("amount_frac"),
                        currency = currency
                    ),
                    creditPaytoUri = it.getString("credit_payto_uri"),
                    wireTransferSubject = it.getString("wire_transfer_subject"),
                    initiationTime = initiationTime,
                    requestUid = it.getString("request_uid")
                )
            } while (it.next())
        }
        return@runConn maybeMap
    }
    /**
     * Initiate a payment in the database.  The "submit"
     * command is then responsible to pick it up and submit
     * it to the bank.
     *
     * @param paymentData any data that's used to prepare the payment.
     * @return true if the insertion went through, false in case of errors.
     */
    suspend fun initiatedPaymentCreate(paymentData: InitiatedPayment): PaymentInitiationOutcome = runConn { conn ->
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
        stmt.setInt(2, paymentData.amount.fraction)
        stmt.setString(3, paymentData.wireTransferSubject)
        parsePayto(paymentData.creditPaytoUri).apply {
            if (this == null) return@runConn PaymentInitiationOutcome.BAD_CREDIT_PAYTO
            if (this.receiverName == null) return@runConn PaymentInitiationOutcome.RECEIVER_NAME_MISSING
        }
        stmt.setString(4, paymentData.creditPaytoUri)
        val initiationTime = paymentData.initiationTime.toDbMicros() ?: run {
            throw Exception("Initiation time could not be converted to microseconds for the database.")
        }
        stmt.setLong(5, initiationTime)
        stmt.setString(6, paymentData.requestUid) // can be null.
        if (stmt.maybeUpdate())
            return@runConn PaymentInitiationOutcome.SUCCESS
        /**
         * _very_ likely, Nexus didn't check the request idempotency,
         * as the row ID would never fall into the following problem.
         */
        return@runConn PaymentInitiationOutcome.UNIQUE_CONSTRAINT_VIOLATION
    }
}