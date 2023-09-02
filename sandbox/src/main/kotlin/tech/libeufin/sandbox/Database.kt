package tech.libeufin.sandbox

import org.postgresql.jdbc.PgConnection
import tech.libeufin.util.internalServerError

import java.sql.DriverManager
import java.sql.PreparedStatement
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
    val balance: TalerAmount? = null,
    val hasDebt: Boolean
)

enum class TransactionDirection {
    credit, debit
}

enum class TanChannel {
    sms, email, file
}

data class BankInternalTransaction(
    val creditorAccountId: Long,
    val debtorAccountId: Long,
    val subject: String,
    val amount: TalerAmount,
    val transactionDate: Long,
    val accountServicerReference: String,
    val endToEndId: String,
    val paymentInformationId: String
)

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
    val direction: TransactionDirection,
    val bankAccountId: Long,
)

data class TalerWithdrawalOperation(
    val withdrawalUuid: UUID,
    val amount: TalerAmount,
    val selectionDone: Boolean = false,
    val aborted: Boolean = false,
    val confirmationDone: Boolean = false,
    val reservePub: ByteArray?,
    val selectedExchangePayto: String?,
    val walletBankAccount: Long
)

data class Cashout(
    val cashoutUuid: UUID,
    val localTransaction: Long? = null,
    val amountDebit: TalerAmount,
    val amountCredit: TalerAmount,
    val buyAtRatio: Int,
    val buyInFee: TalerAmount,
    val sellAtRatio: Int,
    val sellOutFee: TalerAmount,
    val subject: String,
    val creationTime: Long,
    val tanConfirmationTime: Long? = null,
    val tanChannel: TanChannel,
    val tanCode: String,
    val bankAccount: Long,
    val cashoutAddress: String,
    val cashoutCurrency: String
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
     * succeeds.  Any other error (re)throws exception.
     */
    private fun myExecute(stmt: PreparedStatement): Boolean {
        try {
            stmt.execute()
        } catch (e: SQLException) {
            logger.error(e.message)
            // NOTE: it seems that _every_ error gets the 0 code.
            if (e.errorCode == 0) return false
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

    fun bankAccountSetMaxDebt(
        bankAccountLabel: String,
        maxDebt: TalerAmount
    ): Boolean {
        reconnect()
        val stmt = prepare("""
           UPDATE bank_accounts
           SET max_debt=(?,?)::taler_amount
           WHERE bank_account_label=?
        """)
        stmt.setLong(1, maxDebt.value)
        stmt.setInt(2, maxDebt.frac)
        stmt.setString(3, bankAccountLabel)
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
             ,has_debt
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
                owningCustomerId = it.getLong("owning_customer_id"),
                hasDebt = it.getBoolean("has_debt")
            )
        }
    }
    // More bankAccountGetFrom*() to come, on a needed basis.

    // BANK ACCOUNT TRANSACTIONS
    enum class BankTransactionResult {
        NO_CREDITOR,
        NO_DEBTOR,
        SUCCESS,
        CONFLICT
    }
    fun bankTransactionCreate(
        tx: BankInternalTransaction
    ): BankTransactionResult {
        reconnect()
        val stmt = prepare("""
            SELECT out_nx_creditor, out_nx_debtor, out_balance_insufficient
            FROM bank_wire_transfer(?,?,TEXT(?),(?,?)::taler_amount,?,TEXT(?),TEXT(?),TEXT(?))
        """
        )
        stmt.setLong(1, tx.creditorAccountId)
        stmt.setLong(2, tx.debtorAccountId)
        stmt.setString(3, tx.subject)
        stmt.setLong(4, tx.amount.value)
        stmt.setInt(5, tx.amount.frac)
        stmt.setLong(6, tx.transactionDate)
        stmt.setString(7, tx.accountServicerReference)
        stmt.setString(8, tx.paymentInformationId)
        stmt.setString(9, tx.endToEndId)
        val rs = stmt.executeQuery()
        rs.use {
            if (!rs.next()) throw internalServerError("Bank transaction didn't properly return")
            if (rs.getBoolean("out_nx_debtor")) return BankTransactionResult.NO_DEBTOR
            if (rs.getBoolean("out_nx_creditor")) return BankTransactionResult.NO_CREDITOR
            if (rs.getBoolean("out_balance_insufficient")) return BankTransactionResult.CONFLICT
            return BankTransactionResult.SUCCESS
        }
    }

    fun bankTransactionGetForHistoryPage(
        upperBound: Long,
        bankAccountId: Long,
        fromMs: Long,
        toMs: Long
    ): List<BankAccountTransaction> {
        reconnect()
        val stmt = prepare("""
            SELECT 
              creditor_iban
              ,creditor_bic
              ,creditor_name
              ,debtor_iban
              ,debtor_bic
              ,debtor_name
              ,subject
              ,(amount).val AS amount_val
              ,(amount).frac AS amount_frac
              ,transaction_date
              ,account_servicer_reference
              ,payment_information_id
              ,end_to_end_id
              ,direction
              ,bank_account_id
            FROM bank_account_transactions
	        WHERE bank_transaction_id < ?
              AND bank_account_id=?
              AND transaction_date BETWEEN ? AND ?
        """)
        stmt.setLong(1, upperBound)
        stmt.setLong(2, bankAccountId)
        stmt.setLong(3, fromMs)
        stmt.setLong(4, toMs)
        val rs = stmt.executeQuery()
        rs.use {
            val ret = mutableListOf<BankAccountTransaction>()
            if (!it.next()) return ret
            do {
                ret.add(
                    BankAccountTransaction(
                        creditorIban = it.getString("creditor_iban"),
                        creditorBic = it.getString("creditor_bic"),
                        creditorName = it.getString("creditor_name"),
                        debtorIban = it.getString("debtor_iban"),
                        debtorBic = it.getString("debtor_bic"),
                        debtorName = it.getString("debtor_name"),
                        amount = TalerAmount(
                            it.getLong("amount_val"),
                            it.getInt("amount_frac")
                        ),
                        accountServicerReference = it.getString("account_servicer_reference"),
                        endToEndId = it.getString("end_to_end_id"),
                        direction = it.getString("direction").run {
                            when(this) {
                                "credit" -> TransactionDirection.credit
                                "debit" -> TransactionDirection.debit
                                else -> throw internalServerError("Wrong direction in transaction: $this")
                            }
                        },
                        bankAccountId = it.getLong("bank_account_id"),
                        paymentInformationId = it.getString("payment_information_id"),
                        subject = it.getString("subject"),
                        transactionDate = it.getLong("transaction_date")
                ))
            } while (it.next())
            return ret
        }
    }

    // WITHDRAWALS
    fun talerWithdrawalCreate(
        opUUID: UUID,
        walletBankAccount: Long,
        amount: TalerAmount
    ): Boolean {
        reconnect()
        val stmt = prepare("""
            INSERT INTO
              taler_withdrawal_operations
              (withdrawal_uuid, wallet_bank_account, amount)
            VALUES (?,?,(?,?)::taler_amount)
        """) // Take all defaults from the SQL.
        stmt.setObject(1, opUUID)
        stmt.setLong(2, walletBankAccount)
        stmt.setLong(3, amount.value)
        stmt.setInt(4, amount.frac)

        return myExecute(stmt)
    }
    fun talerWithdrawalGet(opUUID: UUID): TalerWithdrawalOperation? {
        reconnect()
        val stmt = prepare("""
            SELECT
              (amount).val as amount_val
              ,(amount).frac as amount_frac
              ,withdrawal_uuid
              ,selection_done     
              ,aborted     
              ,confirmation_done     
              ,reserve_pub
              ,selected_exchange_payto 
	          ,wallet_bank_account
            FROM taler_withdrawal_operations
            WHERE withdrawal_uuid=?
        """)
        stmt.setObject(1, opUUID)
        stmt.executeQuery().use {
            if (!it.next()) return null
            return TalerWithdrawalOperation(
               amount = TalerAmount(
                   it.getLong("amount_val"),
                   it.getInt("amount_frac")
               ),
               selectionDone = it.getBoolean("selection_done"),
               selectedExchangePayto = it.getString("selected_exchange_payto"),
               walletBankAccount = it.getLong("wallet_bank_account"),
               confirmationDone = it.getBoolean("confirmation_done"),
               aborted = it.getBoolean("aborted"),
               reservePub = it.getBytes("reserve_pub"),
               withdrawalUuid = it.getObject("withdrawal_uuid") as UUID
            )
        }
    }

    // Values coming from the wallet.
    fun talerWithdrawalSetDetails(
        opUUID: UUID,
        exchangePayto: String,
        reservePub: ByteArray
    ): Boolean {
        reconnect()
        val stmt = prepare("""
            UPDATE taler_withdrawal_operations
            SET selected_exchange_payto = ?, reserve_pub = ?, selection_done = true
            WHERE withdrawal_uuid=?
        """
        )
        stmt.setString(1, exchangePayto)
        stmt.setBytes(2, reservePub)
        stmt.setObject(3, opUUID)
        return myExecute(stmt)
    }
    fun talerWithdrawalConfirm(opUUID: UUID): Boolean {
        reconnect()
        val stmt = prepare("""
            UPDATE taler_withdrawal_operations
            SET confirmation_done = true
            WHERE withdrawal_uuid=?
        """
        )
        stmt.setObject(1, opUUID)
        return myExecute(stmt)
    }

    fun cashoutCreate(op: Cashout): Boolean {
        reconnect()
        val stmt = prepare("""
            INSERT INTO cashout_operations (
              cashout_uuid
              ,amount_debit 
              ,amount_credit 
              ,buy_at_ratio
              ,buy_in_fee 
              ,sell_at_ratio
              ,sell_out_fee
              ,subject
              ,creation_time
              ,tan_channel
              ,tan_code
              ,bank_account
              ,cashout_address
              ,cashout_currency
	        )
            VALUES (
	      ?
	      ,(?,?)::taler_amount
	      ,(?,?)::taler_amount
	      ,?
	      ,(?,?)::taler_amount
	      ,?
	      ,(?,?)::taler_amount
	      ,?
	      ,?
	      ,?::tan_enum
	      ,?
	      ,?
	      ,?
	      ,?
	    );
        """)
        stmt.setObject(1, op.cashoutUuid)
        stmt.setLong(2, op.amountDebit.value)
        stmt.setInt(3, op.amountDebit.frac)
        stmt.setLong(4, op.amountCredit.value)
        stmt.setInt(5, op.amountCredit.frac)
        stmt.setInt(6, op.buyAtRatio)
        stmt.setLong(7, op.buyInFee.value)
        stmt.setInt(8, op.buyInFee.frac)
        stmt.setInt(9, op.sellAtRatio)
        stmt.setLong(10, op.sellOutFee.value)
        stmt.setInt(11, op.sellOutFee.frac)
        stmt.setString(12, op.subject)
        stmt.setLong(13, op.creationTime)
        stmt.setString(14, op.tanChannel.name)
        stmt.setString(15, op.tanCode)
        stmt.setLong(16, op.bankAccount)
        stmt.setString(17, op.cashoutAddress)
        stmt.setString(18, op.cashoutCurrency)
        return myExecute(stmt)
    }

    // NOTE: EBICS not needed for BFH and NB.

}
