package tech.libeufin.sandbox

import org.postgresql.jdbc.PgConnection
import tech.libeufin.util.internalServerError

import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

private const val DB_CTR_LIMIT = 1000000

data class Customer(
    val login: String,
    val passwordHash: String,
    val name: String,
    val email: String,
    val phone: String,
    val cashoutPayto: String,
    val cashoutCurrency: String
)

data class TalerAmount(
    val value: Int, // maps to INT4
    val frac: Long // maps to INT8
)

data class BankAccount(
    val iban: String,
    val bic: String,
    val bankAccountLabel: String,
    val owningCustomerId: Long,
    val isPublic: Boolean = false,
    val lastNexusFetchRowId: Long,
    val balance: TalerAmount
)

enum class TransactionDirection {
    Credit, Debit
}

data class BankAccountTransaction(
    val creditorIban: String,
    val creditorBic: String,
    val creditorName: String,
    val debtorIban: String,
    val debtorBic: String,
    val debtorName: String,
    val subject: String,
    val amount: TalerAmount,
    val transactionDate: Long, // microseconds
    val accountServicerReference: String,
    val paymentInformationId: String,
    val endToEndId: String,
    val isPending: Boolean,
    val direction: TransactionDirection,
    val bankAccountId: Long,
)

class Database(private val dbConfig: String) {
    private var dbConn: PgConnection? = null
    private var dbCtr: Int = 0
    private val preparedStatements: MutableMap<String, PreparedStatement> = mutableMapOf()

    init {
        Class.forName("org.postgresql.Driver")
    }
    private fun reconnect() {
        dbCtr++
        val myDbConn = dbConn
        if ((dbCtr < DB_CTR_LIMIT && myDbConn != null) && !(myDbConn.isClosed))
            return
        dbConn?.close()
        preparedStatements.clear()
        dbConn = DriverManager.getConnection(dbConfig).unwrap(PgConnection::class.java)
        dbCtr = 0
    }

    private fun prepare(sql: String): PreparedStatement {
        var ps = preparedStatements[sql]
        if (ps != null) return ps
        val myDbConn = dbConn
        if (myDbConn == null) throw internalServerError("DB connection down")
        ps = myDbConn.prepareStatement(sql)
        preparedStatements[sql] = ps
        return ps
    }

    // CONFIG
    fun configGet(configKey: String): String? {
        reconnect()
        val stmt = prepare("""
            SELECT value FROM configuration WHERE key=?;
        """.trimIndent())
        stmt.setString(1, configKey)
        if (!stmt.execute()) return null
        stmt.use {
            return stmt.resultSet.getString("value")
        }
    }
    fun configSet(configKey: String, configValue: String) {
        reconnect()
        val stmt = prepare("""
            UPDATE configuration SET value=? WHERE key=? 
        """.trimIndent())
        stmt.setString(1, configValue)
        stmt.setString(2, configKey)
        stmt.execute()
    }

    // CUSTOMERS
    fun customerCreate(customer: Customer) {
        reconnect()
        val stmt = prepare("""
            INSERT INTO customers VALUES (?, ?, ?, ?, ?, ?, ?) 
        """.trimIndent()
        )
        stmt.setString(1, customer.login)
        stmt.setString(2, customer.passwordHash)
        stmt.setString(3, customer.name)
        stmt.setString(4, customer.email)
        stmt.setString(5, customer.phone)
        stmt.setString(6, customer.cashoutPayto)
        stmt.setString(7, customer.cashoutCurrency)
        stmt.execute()
    }
    fun customerGetFromLogin(login: String, cb: (ResultSet)->Unit) {
        reconnect()
        val stmt = prepare("""
            SELECT * FROM customers WHERE login=?
        """.trimIndent())
        stmt.setString(1, login)
        if (!stmt.execute()) return
        stmt.use { // why .use{} and not directly access .resultSet?
            cb(stmt.resultSet)
        }
    }
    // Possibly more "customerGetFrom*()" to come.

    // BANK ACCOUNTS
    fun bankAccountCreate(bankAccount: BankAccount) {
        reconnect()
        val stmt = prepare("""
            INSERT INTO bank_accounts VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent())
        stmt.setString(1, bankAccount.iban)
        stmt.setString(1, bankAccount.bic)
        stmt.setString(1, bankAccount.bankAccountLabel)
        stmt.setLong(1, bankAccount.owningCustomerId)
        stmt.setLong(1, bankAccount.lastNexusFetchRowId)
        // Following might fail and need a "?::taler_amount" on the last parameter.
        // See: https://stackoverflow.com/questions/10571821/inserting-into-custom-sql-types-with-prepared-statements-in-java
        stmt.setObject(1, bankAccount.balance)
        stmt.execute()
    }
    fun bankAccountGetFromLabel(bankAccountLabel: String, cb: (ResultSet) -> Unit) {
        reconnect()
        val stmt = prepare("""
            SELECT * FROM bank_accounts WHERE bank_account_label=?
        """.trimIndent())
        stmt.setString(1, bankAccountLabel)
        if (!stmt.execute()) return
        stmt.use { // why .use{} and not directly access .resultSet?
            cb(stmt.resultSet)
        }
    }
    // More bankAccountGetFrom*() to come, on a needed basis.

    // BANK ACCOUNT TRANSACTIONS
    fun bankTransactionCreate(tx: BankAccountTransaction) {
        reconnect()
        val stmt = prepare("""
            INSERT INTO bank_account_transactions VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """.trimIndent())
        stmt.setString(1, tx.creditorIban)
        stmt.setString(2, tx.creditorBic)
        stmt.setString(3, tx.creditorName)
        stmt.setString(4, tx.debtorIban)
        stmt.setString(5, tx.debtorBic)
        stmt.setString(6, tx.debtorName)
        stmt.setString(7, tx.subject)
        stmt.setObject(8, tx.amount)
        stmt.setLong(9, tx.transactionDate)
        stmt.setString(10, tx.accountServicerReference)
        stmt.setString(11, tx.paymentInformationId)
        stmt.setString(12, tx.endToEndId)
        stmt.setBoolean(13, tx.isPending)
        stmt.setObject(14, tx.direction)
        stmt.setLong(15, tx.bankAccountId)
        stmt.execute()
    }

    fun bankTransactionGetForHistoryPage(
        upperBound: Long,
        bankAccountId: Long,
        fromMs: Long,
        toMs: Long,
        cb: (ResultSet) -> Unit
    ) {
        reconnect()
        val stmt = prepare("""
            SELECT * FROM bank_account_transactions WHERE
            bankAccountTransactionId < ?
            AND bank_account_id=?
            AND transaction_date BETWEEN ? AND ?
        """.trimIndent())
        stmt.setLong(1, upperBound)
        stmt.setLong(2, bankAccountId)
        stmt.setLong(3, fromMs)
        stmt.setLong(4, toMs)
        if (!stmt.execute()) return
        stmt.use {
            cb(stmt.resultSet)
        }
    }
    // NOTE: to run BFH, EBICS and cash-out tables can be postponed.


}
