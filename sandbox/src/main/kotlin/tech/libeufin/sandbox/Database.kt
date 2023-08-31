package tech.libeufin.sandbox

import org.postgresql.jdbc.PgConnection
import tech.libeufin.util.internalServerError

import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.*

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
    val value: Long,
    val frac: Int
)

data class BankAccount(
    val iban: String,
    val bic: String,
    val bankAccountLabel: String,
    val owningCustomerId: Long,
    val isPublic: Boolean = false,
    val lastNexusFetchRowId: Long,
    val balance: TalerAmount? = null
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

data class TalerWithdrawalOperation(
    val withdrawalId: UUID,
    val amount: TalerAmount,
    val selectionDone: Boolean = false,
    val aborted: Boolean = false,
    val confirmationDone: Boolean = false,
    val reservePub: ByteArray?,
    val selectedExchangePayto: String?,
    val walletBankAccount: Long
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
        dbConn?.execSQLUpdate("SET search_path TO libeufin_bank;")
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

    /**
     * Helper that returns false if the row to be inserted
     * hits a unique key constraint violation, true when it
     * succeeds.  Any other error throws exception.
     */
    private fun myExecute(stmt: PreparedStatement): Boolean {
        try {
            stmt.execute()
        } catch (e: SQLException) {
            logger.error(e.message)
            if (e.errorCode == 0) return false // unique key violation.
            // rethrowing, not to hide other types of errors.
            throw e
        }
        return true
    }

    // CONFIG
    fun configGet(configKey: String): String? {
        reconnect()
        val stmt = prepare("SELECT config_value FROM configuration WHERE config_key=?;")
        stmt.setString(1, configKey)
        val rs = stmt.executeQuery()
        rs.use {
            if(!it.next()) return null
            return it.getString("config_value")
        }
    }
    fun configSet(configKey: String, configValue: String) {
        reconnect()
        val stmt = prepare("CALL bank_set_config(TEXT(?), TEXT(?))")
        stmt.setString(1, configKey)
        stmt.setString(2, configValue)
        stmt.execute()
    }

    // CUSTOMERS
    fun customerCreate(customer: Customer): Boolean {
        reconnect()
        val stmt = prepare("""
            INSERT INTO customers (
              login
              ,password_hash
              ,name
              ,email
              ,phone
              ,cashout_payto
              ,cashout_currency
            )
            VALUES (?, ?, ?, ?, ?, ?, ?) 
        """
        )
        stmt.setString(1, customer.login)
        stmt.setString(2, customer.passwordHash)
        stmt.setString(3, customer.name)
        stmt.setString(4, customer.email)
        stmt.setString(5, customer.phone)
        stmt.setString(6, customer.cashoutPayto)
        stmt.setString(7, customer.cashoutCurrency)

        return myExecute(stmt)
    }
    fun customerGetFromLogin(login: String): Customer? {
        reconnect()
        val stmt = prepare("""
            SELECT
              password_hash,
              name,
              email,
              phone,
              cashout_payto,
              cashout_currency
            FROM customers
            WHERE login=?
        """)
        stmt.setString(1, login)
        val rs = stmt.executeQuery()
        rs.use {
            if (!rs.next()) return null
            return Customer(
                login = login,
                passwordHash = it.getString("password_hash"),
                name = it.getString("name"),
                phone = it.getString("phone"),
                email = it.getString("email"),
                cashoutCurrency = it.getString("cashout_currency"),
                cashoutPayto = it.getString("cashout_payto")
            )
        }
    }
    // Possibly more "customerGetFrom*()" to come.

    // BANK ACCOUNTS
    // Returns false on conflicts.
    fun bankAccountCreate(bankAccount: BankAccount): Boolean {
        reconnect()
        val stmt = prepare("""
            INSERT INTO bank_accounts
              (iban
              ,bic
              ,bank_account_label
              ,owning_customer_id
              ,is_public
              ,last_nexus_fetch_row_id
              )
            VALUES (?, ?, ?, ?, ?, ?)
        """)
        stmt.setString(1, bankAccount.iban)
        stmt.setString(2, bankAccount.bic)
        stmt.setString(3, bankAccount.bankAccountLabel)
        stmt.setLong(4, bankAccount.owningCustomerId)
        stmt.setBoolean(5, bankAccount.isPublic)
        stmt.setLong(6, bankAccount.lastNexusFetchRowId)
        // using the default zero value for the balance.
        return myExecute(stmt)
    }

    fun bankAccountGetFromLabel(bankAccountLabel: String): BankAccount? {
        reconnect()
        val stmt = prepare("""
            SELECT
             iban
             ,bic
             ,owning_customer_id
             ,is_public
             ,last_nexus_fetch_row_id
             ,(balance).val AS balance_value
             ,(balance).frac AS balance_frac
            FROM bank_accounts
            WHERE bank_account_label=?
        """)
        stmt.setString(1, bankAccountLabel)

        val rs = stmt.executeQuery()
        rs.use {
            if (!it.next()) return null
            return BankAccount(
                iban = it.getString("iban"),
                bic = it.getString("bic"),
                balance = TalerAmount(
                    it.getLong("balance_value"),
                    it.getInt("balance_frac")
                ),
                bankAccountLabel = bankAccountLabel,
                lastNexusFetchRowId = it.getLong("last_nexus_fetch_row_id"),
                owningCustomerId = it.getLong("owning_customer_id")
            )
        }
    }
    // More bankAccountGetFrom*() to come, on a needed basis.

    /*
    // BANK ACCOUNT TRANSACTIONS
    enum class BankTransactionResult {
        NO_CREDITOR,
        NO_DEBTOR,
        SUCCESS,
        CONFLICT
    }
    fun bankTransactionCreate(
        // tx: BankInternalTransaction
        creditTx: BankAccountTransaction,
        debitTx: BankAccountTransaction
    ): BankTransactionResult {
        reconnect()
        val stmt = prepare("""
            SELECT out_nx_creditor, out_nx_debtor, out_balance_insufficient
            FROM bank_wire_transfer(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """ // FIXME: adjust balances.
        )
        // FIXME: implement this operation with a stored procedure.
        // Credit side
        stmt.setString(1, tx.creditorAccountId)
        stmt.setString(1, tx.debitorAccountId)
        stmt.setString(7, tx.subject)
        stmt.setObject(8, tx.amount)
        stmt.setLong(9, tx.transactionDate)
        stmt.setString(10, tx.accountServicerReference)
        stmt.setString(11, tx.paymentInformationId)
        stmt.setString(12, tx.endToEndId)

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
        """)
        stmt.setLong(1, upperBound)
        stmt.setLong(2, bankAccountId)
        stmt.setLong(3, fromMs)
        stmt.setLong(4, toMs)
        if (!stmt.execute()) return
        stmt.use {
            cb(stmt.resultSet)
        }
    }

    // WITHDRAWALS
    fun talerWithdrawalCreate(opUUID: UUID, walletBankAccount: Long) {
        reconnect()
        val stmt = prepare("""
            INSERT INTO taler_withdrawals_operations (withdrawal_id, wallet_bank_account)
            VALUES (?,?)
        """) // Take all defaults from the SQL.
        stmt.setObject(1, opUUID)
        stmt.setObject(2, walletBankAccount)
        stmt.execute()
    }

    // Values coming from the wallet.
    fun talerWithdrawalSetDetails(
        opUUID: UUID,
        exchangePayto: String,
        reservePub: ByteArray
    ) {
        reconnect()
        val stmt = prepare("""
            UPDATE taler_withdrawal_operations
            SET selected_exchange_payto = ?, reserve_pub = ?, selection_done = true
            WHERE withdrawal_id=?
        """
        )
        stmt.setString(1, exchangePayto)
        stmt.setBytes(2, reservePub)
        stmt.setObject(3, opUUID)
        stmt.execute()
    }

    fun talerWithdrawalConfirm(opUUID: UUID) {
        reconnect()
        val stmt = prepare("""
            UPDATE taler_withdrawal_operations
            SET confirmation_done = true
            WHERE withdrawal_id=?
        """
        )
        stmt.setObject(1, opUUID)
        stmt.execute()
    }


    // NOTE: to run BFH, EBICS and cash-out tables can be postponed.
*/

}
