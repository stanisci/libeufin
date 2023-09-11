package tech.libeufin.bank

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
    val dbRowId: Long? = null, // mostly used when retrieving records.
    val email: String?,
    val phone: String?,
    val cashoutPayto: String?,
    val cashoutCurrency: String?
)
fun Customer.expectRowId(): Long = this.dbRowId ?: throw internalServerError("Cutsomer '${this.login}' had no DB row ID")

data class TalerAmount(
    val value: Long,
    val frac: Int
)

// BIC got removed, because it'll be expressed in the internal_payto_uri.
data class BankAccount(
    val internalPaytoUri: String,
    val owningCustomerId: Long,
    val isPublic: Boolean = false,
    val isTalerExchange: Boolean = false,
    val lastNexusFetchRowId: Long = 0L,
    val balance: TalerAmount? = null,
    val hasDebt: Boolean,
    val maxDebt: TalerAmount
)

enum class TransactionDirection {
    credit, debit
}

enum class TanChannel {
    sms, email, file
}

enum class TokenScope {
    readonly, readwrite
}

data class BearerToken(
    val content: ByteArray,
    val scope: TokenScope,
    val creationTime: Long,
    val expirationTime: Long,
    /**
     * Serial ID of the database row that hosts the bank customer
     * that is associated with this token.  NOTE: if the token is
     * refreshed by a client that doesn't have a user+password login
     * in the system, the creator remains always the original bank
     * customer that created the very first token.
     */
    val bankCustomer: Long
)

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
    val creditorPaytoUri: String,
    val creditorName: String,
    val debtorPaytoUri: String,
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
    val credit_payto_uri: String,
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
    /**
     * This method INSERTs a new customer into the database and
     * returns its row ID.  That is useful because often a new user
     * ID has to be specified in more database records, notably in
     * bank accounts to point at their owners.
     *
     * In case of conflict, this method returns null.
     */
    fun customerCreate(customer: Customer): Long? {
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
            RETURNING customer_id
        """
        )
        stmt.setString(1, customer.login)
        stmt.setString(2, customer.passwordHash)
        stmt.setString(3, customer.name)
        stmt.setString(4, customer.email)
        stmt.setString(5, customer.phone)
        stmt.setString(6, customer.cashoutPayto)
        stmt.setString(7, customer.cashoutCurrency)

        val res = try {
            stmt.executeQuery()
        } catch (e: SQLException) {
            logger.error(e.message)
            if (e.errorCode == 0) return null // unique constraint violation.
            throw e // rethrow on other errors.
        }
        res.use {
            if (!it.next())
                throw internalServerError("SQL RETURNING gave nothing.")
            return it.getLong("customer_id")
        }
    }

    fun customerPwAuth(login: String, pwHash: String): Customer? {
        reconnect()
        val stmt = prepare("""
            SELECT
              name,
              email,
              phone,
              cashout_payto,
              cashout_currency
            FROM customers
            WHERE login=? AND password_hash=?
        """)
        stmt.setString(1, login)
        stmt.setString(2, pwHash)
        val rs = stmt.executeQuery()
        rs.use {
            if (!rs.next()) return null
            return Customer(
                login = login,
                passwordHash = pwHash,
                name = it.getString("name"),
                phone = it.getString("phone"),
                email = it.getString("email"),
                cashoutCurrency = it.getString("cashout_currency"),
                cashoutPayto = it.getString("cashout_payto")
            )
        }
    }

    // Mostly used to get customers out of bearer tokens.
    fun customerGetFromRowId(customer_id: Long): Customer? {
        reconnect()
        val stmt = prepare("""
            SELECT
              login,
              password_hash,
              name,
              email,
              phone,
              cashout_payto,
              cashout_currency
            FROM customers
            WHERE customer_id=?
        """)
        stmt.setLong(1, customer_id)
        val rs = stmt.executeQuery()
        rs.use {
            if (!rs.next()) return null
            return Customer(
                login = it.getString("login"),
                passwordHash = it.getString("password_hash"),
                name = it.getString("name"),
                phone = it.getString("phone"),
                email = it.getString("email"),
                cashoutCurrency = it.getString("cashout_currency"),
                cashoutPayto = it.getString("cashout_payto")
            )
        }
    }
    fun customerGetFromLogin(login: String): Customer? {
        reconnect()
        val stmt = prepare("""
            SELECT
              customer_id,
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
                cashoutPayto = it.getString("cashout_payto"),
                dbRowId = it.getLong("customer_id")
            )
        }
    }
    // Possibly more "customerGetFrom*()" to come.

    // BEARER TOKEN
    fun bearerTokenCreate(token: BearerToken): Boolean {
        reconnect()
        val stmt = prepare("""
             INSERT INTO bearer_tokens
               (content,
                creation_time,
                expiration_time,
                scope,
                bank_customer              
               ) VALUES
               (?, ?, ?, ?::token_scope_enum, ?)
        """)
        stmt.setBytes(1, token.content)
        stmt.setLong(2, token.creationTime)
        stmt.setLong(3, token.expirationTime)
        stmt.setString(4, token.scope.name)
        stmt.setLong(5, token.bankCustomer)

        return myExecute(stmt)
    }
    fun bearerTokenGet(token: ByteArray): BearerToken? {
        reconnect()
        val stmt = prepare("""
            SELECT
              expiration_time,
              creation_time,
              bank_customer,
              scope
            FROM bearer_tokens
            WHERE content=?;            
        """)

        stmt.setBytes(1, token)
        stmt.executeQuery().use {
            if (!it.next()) return null
            return BearerToken(
                content = token,
                creationTime = it.getLong("creation_time"),
                expirationTime = it.getLong("expiration_time"),
                bankCustomer = it.getLong("bank_customer"),
                scope = it.getString("scope").run {
                    if (this == TokenScope.readwrite.name) return@run TokenScope.readwrite
                    if (this == TokenScope.readonly.name) return@run TokenScope.readonly
                    else throw internalServerError("Wrong token scope found in the database: $this")
                }
            )
        }
    }

    // BANK ACCOUNTS
    // Returns false on conflicts.
    fun bankAccountCreate(bankAccount: BankAccount): Boolean {
        reconnect()
        // FIXME: likely to be changed to only do internal_payto_uri
        val stmt = prepare("""
            INSERT INTO bank_accounts
              (internal_payto_uri
              ,owning_customer_id
              ,is_public
              ,is_taler_exchange
              ,max_debt
              )
            VALUES (?, ?, ?, ?, (?, ?)::taler_amount)
        """)
        stmt.setString(1, bankAccount.internalPaytoUri)
        stmt.setLong(2, bankAccount.owningCustomerId)
        stmt.setBoolean(3, bankAccount.isPublic)
        stmt.setBoolean(4, bankAccount.isTalerExchange)
        stmt.setLong(5, bankAccount.maxDebt.value)
        stmt.setInt(6, bankAccount.maxDebt.frac)
        // using the default zero value for the balance.
        return myExecute(stmt)
    }

    fun bankAccountSetMaxDebt(
        owningCustomerId: Long,
        maxDebt: TalerAmount
    ): Boolean {
        reconnect()
        val stmt = prepare("""
           UPDATE bank_accounts
           SET max_debt=(?,?)::taler_amount
           WHERE owning_customer_id=?
        """)
        stmt.setLong(1, maxDebt.value)
        stmt.setInt(2, maxDebt.frac)
        stmt.setLong(3, owningCustomerId)
        return myExecute(stmt)
    }

    fun bankAccountGetFromOwnerId(ownerId: Long): BankAccount? {
        reconnect()
        val stmt = prepare("""
            SELECT
             internal_payto_uri
             ,owning_customer_id
             ,is_public
             ,is_taler_exchange
             ,last_nexus_fetch_row_id
             ,(balance).val AS balance_val
             ,(balance).frac AS balance_frac
             ,has_debt
             ,(max_debt).val AS max_debt_val
             ,(max_debt).frac AS max_debt_frac
            FROM bank_accounts
            WHERE owning_customer_id=?
        """)
        stmt.setLong(1, ownerId)

        val rs = stmt.executeQuery()
        rs.use {
            if (!it.next()) return null
            return BankAccount(
                internalPaytoUri = it.getString("internal_payto_uri"),
                balance = TalerAmount(
                    it.getLong("balance_val"),
                    it.getInt("balance_frac")
                ),
                lastNexusFetchRowId = it.getLong("last_nexus_fetch_row_id"),
                owningCustomerId = it.getLong("owning_customer_id"),
                hasDebt = it.getBoolean("has_debt"),
                isTalerExchange = it.getBoolean("is_taler_exchange"),
                maxDebt = TalerAmount(
                    value = it.getLong("max_debt_val"),
                    frac = it.getInt("max_debt_frac")
                )
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
            if (rs.getBoolean("out_nx_debtor")) {
                logger.error("No debtor account found")
                return BankTransactionResult.NO_DEBTOR
            }
            if (rs.getBoolean("out_nx_creditor")) {
                logger.error("No creditor account found")
                return BankTransactionResult.NO_CREDITOR
            }
            if (rs.getBoolean("out_balance_insufficient")) {
                logger.error("Balance insufficient")
                return BankTransactionResult.CONFLICT
            }
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
              creditor_payto_uri
              ,creditor_name
              ,debtor_payto_uri
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
                        creditorPaytoUri = it.getString("creditor_payto_uri"),
                        creditorName = it.getString("creditor_name"),
                        debtorPaytoUri = it.getString("debtor_payto_uri"),
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
                )
                )
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
              ,credit_payto_uri
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
        stmt.setString(17, op.credit_payto_uri)
        stmt.setString(18, op.cashoutCurrency)
        return myExecute(stmt)
    }

    fun cashoutConfirm(
        opUuid: UUID,
        tanConfirmationTimestamp: Long,
        bankTransaction: Long // regional payment backing the operation
    ): Boolean {
        reconnect()
        val stmt = prepare("""
            UPDATE cashout_operations
              SET tan_confirmation_time = ?, local_transaction = ?
              WHERE cashout_uuid=?;
        """)
        stmt.setLong(1, tanConfirmationTimestamp)
        stmt.setLong(2, bankTransaction)
        stmt.setObject(3, opUuid)
        return myExecute(stmt)
    }
    // used by /abort
    enum class CashoutDeleteResult {
        SUCCESS,
        CONFLICT_ALREADY_CONFIRMED
    }
    fun cashoutDelete(opUuid: UUID): CashoutDeleteResult {
        val stmt = prepare("""
           SELECT out_already_confirmed
             FROM cashout_delete(?)
        """)
        stmt.setObject(1, opUuid)
        stmt.executeQuery().use {
            if (!it.next()) {
                throw internalServerError("Cashout deletion gave no result")
            }
            if (it.getBoolean("out_already_confirmed")) return CashoutDeleteResult.CONFLICT_ALREADY_CONFIRMED
            return CashoutDeleteResult.SUCCESS
        }
    }
    fun cashoutGetFromUuid(opUuid: UUID): Cashout? {
        val stmt = prepare("""
           SELECT
             (amount_debit).val as amount_debit_val
             ,(amount_debit).frac as amount_debit_frac
             ,(amount_credit).val as amount_credit_val
             ,(amount_credit).frac as amount_credit_frac
             ,buy_at_ratio
             ,(buy_in_fee).val as buy_in_fee_val
             ,(buy_in_fee).frac as buy_in_fee_frac
             ,sell_at_ratio
             ,(sell_out_fee).val as sell_out_fee_val
             ,(sell_out_fee).frac as sell_out_fee_frac
             ,subject
             ,creation_time
             ,tan_channel
             ,tan_code
             ,bank_account
             ,credit_payto_uri
             ,cashout_currency
	     ,tan_confirmation_time
	     ,local_transaction
	      FROM cashout_operations
	      WHERE cashout_uuid=?;
        """)
        stmt.setObject(1, opUuid)
        stmt.executeQuery().use {
            if (!it.next()) return null
            return Cashout(
                amountDebit = TalerAmount(
                    value = it.getLong("amount_debit_val"),
                    frac = it.getInt("amount_debit_frac")
                ),
                amountCredit = TalerAmount(
                    value = it.getLong("amount_credit_val"),
                    frac = it.getInt("amount_credit_frac")
                ),
                bankAccount = it.getLong("bank_account"),
                buyAtRatio = it.getInt("buy_at_ratio"),
                buyInFee = TalerAmount(
                    value = it.getLong("buy_in_fee_val"),
                    frac = it.getInt("buy_in_fee_frac")
                ),
                credit_payto_uri = it.getString("credit_payto_uri"),
                cashoutCurrency = it.getString("cashout_currency"),
                cashoutUuid = opUuid,
                creationTime = it.getLong("creation_time"),
                sellAtRatio = it.getInt("sell_at_ratio"),
                sellOutFee = TalerAmount(
                    value = it.getLong("sell_out_fee_val"),
                    frac = it.getInt("sell_out_fee_frac")
                ),
                subject = it.getString("subject"),
                tanChannel = it.getString("tan_channel").run {
                    when(this) {
                        "sms" -> TanChannel.sms
                        "email" -> TanChannel.email
                        "file" -> TanChannel.file
                        else -> throw internalServerError("TAN channel $this unsupported")
                    }
                },
                tanCode = it.getString("tan_code"),
                localTransaction = it.getLong("local_transaction"),
                tanConfirmationTime = it.getLong("tan_confirmation_time").run {
                    if (this == 0L) return@run null
                    return@run this
                }
            )
        }
    }
}
