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

/* only importing TalerAmount from bank ONCE that Nexus has
* its httpd component.  */
data class TalerAmount(
    val value: Long,
    val fraction: Int,
    val currency: String
)

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