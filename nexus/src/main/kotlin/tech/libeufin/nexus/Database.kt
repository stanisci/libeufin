package tech.libeufin.nexus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.postgresql.jdbc.PgConnection
import tech.libeufin.util.pgDataSource
import com.zaxxer.hikari.*
import tech.libeufin.util.microsToJavaInstant
import tech.libeufin.util.stripIbanPayto
import tech.libeufin.util.toDbMicros
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.Instant

data class TalerAmount(
    val value: Long,
    val fraction: Int,
    val currency: String
)

// INCOMING PAYMENTS STRUCTS

/**
 * Represents an incoming payment in the database.
 */
data class IncomingPayment(
    val amount: TalerAmount,
    val wireTransferSubject: String?,
    val debitPaytoUri: String,
    val executionTime: Instant,
    val bankTransferId: String,
    val bounced: Boolean
)

// INITIATED PAYMENTS STRUCTS

/**
 * Minimal set of information to initiate a new payment in
 * the database.
 */
data class InitiatedPayment(
    val amount: TalerAmount,
    val wireTransferSubject: String,
    val creditPaytoUri: String,
    val initiationTime: Instant,
    val clientRequestUuid: String? = null
)

/**
 * Possible outcomes for inserting a initiated payment
 * into the database.
 */
enum class PaymentInitiationOutcome {
    BAD_CREDIT_PAYTO,
    UNIQUE_CONSTRAINT_VIOLATION,
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
    INITIATED_COUNTERPART_NOT_FOUND,
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
              FROM create_outgoing_tx(
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
     * @return true if the payment could be set as bounced, false otherwise.
     */
    suspend fun incomingPaymentSetAsBounced(rowId: Long): Boolean = runConn { conn ->
        val timestamp = Instant.now().toDbMicros()
            ?: throw Exception("Could not convert Instant.now() to microseconds, won't bounce this payment.")
        val stmt = conn.prepareStatement("""
             SELECT out_nx_incoming_payment
               FROM bounce_payment(?,?)
             """
        )
        stmt.setLong(1, rowId)
        stmt.setLong(2, timestamp)
        stmt.executeQuery().use { maybeResult ->
            if (!maybeResult.next()) throw Exception("Expected outcome from the SQL bounce_payment function")
            return@runConn !maybeResult.getBoolean("out_nx_incoming_payment")
        }
    }

    /**
     * Creates a new incoming payment record in the database.
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
              ,bounced
            ) VALUES (
              (?,?)::taler_amount
              ,?
              ,?
              ,?
              ,?
              ,?
            )
        """)
        stmt.setLong(1, paymentData.amount.value)
        stmt.setInt(2, paymentData.amount.fraction)
        stmt.setString(3, paymentData.wireTransferSubject)
        val executionTime = paymentData.executionTime.toDbMicros() ?: run {
            throw Exception("Execution time could not be converted to microseconds for the database.")
        }
        stmt.setLong(4, executionTime)
        stmt.setString(5, paymentData.debitPaytoUri)
        stmt.setString(6, paymentData.bankTransferId)
        stmt.setBoolean(7, paymentData.bounced)
        return@runConn stmt.maybeUpdate()
    }

    // INITIATED PAYMENTS METHODS

    /**
     * Sets payment initiation as submitted.
     *
     * @param rowId row ID of the record to set.
     * @return true on success, false if no payment was affected.
     */
    suspend fun initiatedPaymentSetSubmitted(rowId: Long): Boolean = runConn { conn ->
        val stmt = conn.prepareStatement("""
             UPDATE initiated_outgoing_transactions
                      SET submitted = true
                      WHERE initiated_outgoing_transaction_id=?
             """
        )
        stmt.setLong(1, rowId)
        return@runConn stmt.maybeUpdate()
    }

    /**
     * Gets any initiated payment that was not submitted to the
     * bank yet.
     *
     * @param currency in which currency should the payment be submitted to the bank.
     * @return potentially empty list of initiated payments.
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
             ,client_request_uuid
             FROM initiated_outgoing_transactions
             WHERE submitted=false;
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
                    clientRequestUuid = it.getString("client_request_uuid")
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
             ,client_request_uuid
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
        val paytoOnlyIban = stripIbanPayto(paymentData.creditPaytoUri) ?: run {
            logger.error("Credit Payto address is invalid.")
            return@runConn PaymentInitiationOutcome.BAD_CREDIT_PAYTO // client fault.
        }
        stmt.setString(4, paytoOnlyIban)
        val initiationTime = paymentData.initiationTime.toDbMicros() ?: run {
            throw Exception("Initiation time could not be converted to microseconds for the database.")
        }
        stmt.setLong(5, initiationTime)
        stmt.setString(6, paymentData.clientRequestUuid) // can be null.
        if (stmt.maybeUpdate())
            return@runConn PaymentInitiationOutcome.SUCCESS
        /**
         * _very_ likely, Nexus didn't check the request idempotency,
         * as the row ID would never fall into the following problem.
         */
        return@runConn PaymentInitiationOutcome.UNIQUE_CONSTRAINT_VIOLATION
    }
}