package tech.libeufin.nexus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.postgresql.jdbc.PgConnection
import tech.libeufin.util.pgDataSource
import com.zaxxer.hikari.*
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
    val executionTime: Instant,
    val creditPaytoUri: String,
    val clientRequestUuid: String? = null
)

/**
 * Possible outcomes for inserting a initiated payment
 * into the database.
 */
enum class PaymentInitiationOutcome {
    BAD_TIMESTAMP,
    BAD_CREDIT_PAYTO,
    UNIQUE_CONSTRAINT_VIOLATION,
    SUCCESS
}

/**
 * Performs a INSERT, UPDATE, or DELETE operation.
 *
 * @return true on success, false on unique constraint violation,
 *         rethrows on any other issue.
 */
private fun PreparedStatement.maybeUpdate(): Boolean {
    try {
        this.executeUpdate()
    } catch (e: SQLException) {
        logger.error(e.message)
        if (e.sqlState == "23505") return false // unique_violation
        throw e // rethrowing, not to hide other types of errors.
    }
    return true
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
     * Initiate a payment in the database.  The "submit"
     * command is then responsible to pick it up and submit
     * it at the bank.
     *
     * @param paymentData any data that's used to prepare the payment.
     * @return true if the insertion went through, false in case of errors.
     */
    suspend fun initiatePayment(paymentData: InitiatedPayment): PaymentInitiationOutcome = runConn { conn ->
        val stmt = conn.prepareStatement("""
           INSERT INTO initiated_outgoing_transactions (
             amount
             ,wire_transfer_subject
             ,execution_time
             ,credit_payto_uri
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
        val executionTime = paymentData.executionTime.toDbMicros() ?: run {
            logger.error("Execution time could not be converted to microseconds for the database.")
            return@runConn PaymentInitiationOutcome.BAD_TIMESTAMP // nexus fault.
        }
        stmt.setLong(4, executionTime)
        val paytoOnlyIban = stripIbanPayto(paymentData.creditPaytoUri) ?: run {
            logger.error("Credit Payto address is invalid.")
            return@runConn PaymentInitiationOutcome.BAD_CREDIT_PAYTO // client fault.
        }
        stmt.setString(5, paytoOnlyIban)
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