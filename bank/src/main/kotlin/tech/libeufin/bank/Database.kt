/*
 * This file is part of LibEuFin.
 * Copyright (C) 2019 Stanisci and Dold.

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


package tech.libeufin.bank

import org.postgresql.jdbc.PgConnection
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.util.getJdbcConnectionFromPg
import java.io.File
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.*
import kotlin.math.abs

private const val DB_CTR_LIMIT = 1000000


fun Customer.expectRowId(): Long = this.dbRowId ?: throw internalServerError("Cutsomer '$login' had no DB row ID.")
fun BankAccount.expectBalance(): TalerAmount = this.balance ?: throw internalServerError("Bank account '${this.internalPaytoUri}' lacks balance.")
fun BankAccount.expectRowId(): Long = this.bankAccountId ?: throw internalServerError("Bank account '${this.internalPaytoUri}' lacks database row ID.")
fun BankAccountTransaction.expectRowId(): Long = this.dbRowId ?: throw internalServerError("Bank account transaction (${this.subject}) lacks database row ID.")

private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.bank.Database")

fun initializeDatabaseTables(dbConfig: String, sqlDir: String) {
    logger.info("doing DB initialization, sqldir $sqlDir, dbConfig $dbConfig")
    val jdbcConnStr = getJdbcConnectionFromPg(dbConfig)
    logger.info("connecting to database via JDBC string '$jdbcConnStr'")
    val dbConn = DriverManager.getConnection(jdbcConnStr).unwrap(PgConnection::class.java)
    if (dbConn == null) {
        throw Error("could not open database")
    }
    val sqlVersioning = File("$sqlDir/versioning.sql").readText()
    dbConn.execSQLUpdate(sqlVersioning)

    val checkStmt = dbConn.prepareStatement("SELECT count(*) as n FROM _v.patches where patch_name = ?")

    for (n in 1..9999) {
        val numStr = n.toString().padStart(4, '0')
        val patchName = "libeufin-bank-$numStr"

        checkStmt.setString(1, patchName)
        val res = checkStmt.executeQuery()
        if (!res.next()) {
            throw Error("unable to query patches")
        }

        val patchCount = res.getInt("n")
        if (patchCount >= 1) {
            logger.info("patch $patchName already applied")
            continue
        }

        val path = File("$sqlDir/libeufin-bank-$numStr.sql")
        if (!path.exists()) {
            logger.info("path $path doesn't exist anymore, stopping")
            break
        }
        logger.info("applying patch $path")
        val sqlPatchText = path.readText()
        dbConn.execSQLUpdate(sqlPatchText)
    }
    val sqlProcedures = File("$sqlDir/procedures.sql").readText()
    dbConn.execSQLUpdate(sqlProcedures)
}

private fun countRows(rs: ResultSet): Int {
    var size = 0
    while (rs.next()) {
        size++
    }
    return size
}

fun resetDatabaseTables(dbConfig: String, sqlDir: String) {
    logger.info("doing DB initialization, sqldir $sqlDir, dbConfig $dbConfig")
    val jdbcConnStr = getJdbcConnectionFromPg(dbConfig)
    logger.info("connecting to database via JDBC string '$jdbcConnStr'")
    val dbConn = DriverManager.getConnection(jdbcConnStr).unwrap(PgConnection::class.java)
    if (dbConn == null) {
        throw Error("could not open database")
    }

    val queryRes = dbConn.execSQLQuery("SELECT schema_name FROM information_schema.schemata WHERE schema_name='_v'")
    if (countRows(queryRes) == 0) {
        logger.info("versioning schema not present, not running drop sql")
        return
    }

    val sqlDrop = File("$sqlDir/libeufin-bank-drop.sql").readText()
    dbConn.execSQLUpdate(sqlDrop)
}

class Database(private val dbConfig: String, private val bankCurrency: String) {
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
        // Translate "normal" postgresql:// connection URI to something that JDBC likes.
        val jdbcConnStr = getJdbcConnectionFromPg(dbConfig)
        logger.info("connecting to database via JDBC string '$jdbcConnStr'")
        dbConn = DriverManager.getConnection(jdbcConnStr).unwrap(PgConnection::class.java)
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
                throw internalServerError("SQL RETURNING gave no customer_id.")
            return it.getLong("customer_id")
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
                cashoutPayto = it.getString("cashout_payto"),
                dbRowId = customer_id
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
                bank_customer,
                is_refreshable
               ) VALUES
               (?, ?, ?, ?::token_scope_enum, ?, ?)
        """)
        stmt.setBytes(1, token.content)
        stmt.setLong(2, token.creationTime)
        stmt.setLong(3, token.expirationTime)
        stmt.setString(4, token.scope.name)
        stmt.setLong(5, token.bankCustomer)
        stmt.setBoolean(6, token.isRefreshable)
        return myExecute(stmt)
    }
    fun bearerTokenGet(token: ByteArray): BearerToken? {
        reconnect()
        val stmt = prepare("""
            SELECT
              expiration_time,
              creation_time,
              bank_customer,
              scope,
              is_refreshable
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
                },
                isRefreshable = it.getBoolean("is_refreshable")
            )
        }
    }

    // BANK ACCOUNTS

    /**
     * Inserts a new bank account in the database, returning its
     * row ID in the successful case.  If of unique constrain violation,
     * it returns null and any other error will be thrown as 500.
     */
    fun bankAccountCreate(bankAccount: BankAccount): Long? {
        reconnect()
        if (bankAccount.balance != null)
            throw internalServerError(
                "Do not pass a balance upon bank account creation, do a wire transfer instead."
            )
        // FIXME: likely to be changed to only do internal_payto_uri
        val stmt = prepare("""
            INSERT INTO bank_accounts
              (internal_payto_uri
              ,owning_customer_id
              ,is_public
              ,is_taler_exchange
              ,max_debt
              )
            VALUES
              (?, ?, ?, ?, (?, ?)::taler_amount)
            RETURNING bank_account_id;
        """)
        stmt.setString(1, bankAccount.internalPaytoUri)
        stmt.setLong(2, bankAccount.owningCustomerId)
        stmt.setBoolean(3, bankAccount.isPublic)
        stmt.setBoolean(4, bankAccount.isTalerExchange)
        stmt.setLong(5, bankAccount.maxDebt.value)
        stmt.setInt(6, bankAccount.maxDebt.frac)
        // using the default zero value for the balance.
        val res = try {
            stmt.executeQuery()
        } catch (e: SQLException) {
            logger.error(e.message)
            if (e.errorCode == 0) return null // unique constraint violation.
            throw e // rethrow on other errors.
        }
        res.use {
            if (!it.next())
                throw internalServerError("SQL RETURNING gave no bank_account_id.")
            return it.getLong("bank_account_id")
        }
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

    private fun getCurrency(): String {
        return bankCurrency
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
             ,bank_account_id
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
                    it.getInt("balance_frac"),
                    getCurrency()
                ),
                lastNexusFetchRowId = it.getLong("last_nexus_fetch_row_id"),
                owningCustomerId = it.getLong("owning_customer_id"),
                hasDebt = it.getBoolean("has_debt"),
                isTalerExchange = it.getBoolean("is_taler_exchange"),
                maxDebt = TalerAmount(
                    value = it.getLong("max_debt_val"),
                    frac = it.getInt("max_debt_frac"),
                    getCurrency()
                ),
                bankAccountId = it.getLong("bank_account_id")
            )
        }
    }
    fun bankAccountGetFromInternalPayto(internalPayto: String): BankAccount? {
        reconnect()
        val stmt = prepare("""
            SELECT
             bank_account_id
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
            WHERE internal_payto_uri=?
        """)
        stmt.setString(1, internalPayto)

        val rs = stmt.executeQuery()
        rs.use {
            if (!it.next()) return null
            return BankAccount(
                internalPaytoUri = internalPayto,
                balance = TalerAmount(
                    it.getLong("balance_val"),
                    it.getInt("balance_frac"),
                    getCurrency()
                ),
                lastNexusFetchRowId = it.getLong("last_nexus_fetch_row_id"),
                owningCustomerId = it.getLong("owning_customer_id"),
                hasDebt = it.getBoolean("has_debt"),
                isTalerExchange = it.getBoolean("is_taler_exchange"),
                maxDebt = TalerAmount(
                    value = it.getLong("max_debt_val"),
                    frac = it.getInt("max_debt_frac"),
                    getCurrency()
                ),
                bankAccountId = it.getLong("bank_account_id")
            )
        }
    }

    // BANK ACCOUNT TRANSACTIONS
    enum class BankTransactionResult {
        NO_CREDITOR,
        NO_DEBTOR,
        SUCCESS,
        CONFLICT // balance insufficient
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

    /**
     * Only checks if a bank transaction with the given subject
     * exists.  That's only used in the /admin/add-incoming, to
     * prevent a public key from being reused.
     *
     * Returns the row ID if found, null otherwise.
     */
    fun bankTransactionCheckExists(subject: String): Long? {
        reconnect()
        val stmt = prepare("""
            SELECT bank_transaction_id
            FROM bank_account_transactions
            WHERE subject = ?;           
        """)
        stmt.setString(1, subject)
        val res = stmt.executeQuery()
        res.use {
            if (!it.next()) return null
            return it.getLong("bank_transaction_id")
        }
    }

    // Get the bank transaction whose row ID is rowId
    fun bankTransactionGetFromInternalId(rowId: Long): BankAccountTransaction? {
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
	        WHERE bank_transaction_id=?
        """)
        stmt.setLong(1, rowId)
        val rs = stmt.executeQuery()
        rs.use {
            if (!it.next()) return null
            return BankAccountTransaction(
                creditorPaytoUri = it.getString("creditor_payto_uri"),
                creditorName = it.getString("creditor_name"),
                debtorPaytoUri = it.getString("debtor_payto_uri"),
                debtorName = it.getString("debtor_name"),
                amount = TalerAmount(
                    it.getLong("amount_val"),
                    it.getInt("amount_frac"),
                    getCurrency()
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
        }
    }

    /**
     * The following function returns the list of transactions, according
     * to the history parameters.  The parameters take at least the 'start'
     * and 'delta' values, and _optionally_ the payment direction.  At the
     * moment, only the TWG uses the direction, to provide the /incoming
     * and /outgoing endpoints.
     */
    // Helper type to collect the history parameters.
    private data class HistoryParams(
        val cmpOp: String, // < or >
        val orderBy: String // ASC or DESC
    )
    fun bankTransactionGetHistory(
        start: Long,
        delta: Long,
        bankAccountId: Long,
        withDirection: TransactionDirection? = null
    ): List<BankAccountTransaction> {
        reconnect()
        val ops = if (delta < 0)
            HistoryParams("<", "DESC") else
                HistoryParams(">", "ASC")
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
              ${if (withDirection != null) "" else ",direction"}
              ,bank_account_id
              ,bank_transaction_id
            FROM bank_account_transactions
	        WHERE bank_transaction_id ${ops.cmpOp} ? 
              AND bank_account_id=?
              ${if (withDirection != null) "AND direction=?::direction_enum" else ""}
            ORDER BY bank_transaction_id ${ops.orderBy}
            LIMIT ?
        """)
        stmt.setLong(1, start)
        stmt.setLong(2, bankAccountId)
        /**
         * The LIMIT parameter index might change, according to
         * the presence of the direction filter.
         */
        val limitParamIndex = if (withDirection != null) {
            stmt.setString(3, withDirection.name)
            4
        }
        else
            3
        stmt.setLong(limitParamIndex, abs(delta))
        val rs = stmt.executeQuery()
        rs.use {
            val ret = mutableListOf<BankAccountTransaction>()
            if (!it.next()) return ret
            do {
                val direction = if (withDirection == null) {
                    it.getString("direction").run {
                        when (this) {
                            "credit" -> TransactionDirection.credit
                            "debit" -> TransactionDirection.debit
                            else -> throw internalServerError("Wrong direction in transaction: $this")
                        }
                    }
                }
                else
                    withDirection
                ret.add(
                    BankAccountTransaction(
                        creditorPaytoUri = it.getString("creditor_payto_uri"),
                        creditorName = it.getString("creditor_name"),
                        debtorPaytoUri = it.getString("debtor_payto_uri"),
                        debtorName = it.getString("debtor_name"),
                        amount = TalerAmount(
                            it.getLong("amount_val"),
                            it.getInt("amount_frac"),
                            getCurrency()
                        ),
                        accountServicerReference = it.getString("account_servicer_reference"),
                        endToEndId = it.getString("end_to_end_id"),
                        direction = direction,
                        bankAccountId = it.getLong("bank_account_id"),
                        paymentInformationId = it.getString("payment_information_id"),
                        subject = it.getString("subject"),
                        transactionDate = it.getLong("transaction_date"),
                        dbRowId = it.getLong("bank_transaction_id")
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
                   it.getInt("amount_frac"),
                   getCurrency()
               ),
               selectionDone = it.getBoolean("selection_done"),
               selectedExchangePayto = it.getString("selected_exchange_payto"),
               walletBankAccount = it.getLong("wallet_bank_account"),
               confirmationDone = it.getBoolean("confirmation_done"),
               aborted = it.getBoolean("aborted"),
               reservePub = it.getString("reserve_pub"),
               withdrawalUuid = it.getObject("withdrawal_uuid") as UUID
            )
        }
    }

    /**
     * Aborts one Taler withdrawal, only if it wasn't previously
     * confirmed.  It returns false if the UPDATE didn't succeed.
     */
    fun talerWithdrawalAbort(opUUID: UUID): Boolean {
        reconnect()
        val stmt = prepare("""
            UPDATE taler_withdrawal_operations
            SET aborted = true
            WHERE withdrawal_uuid=? AND selection_done = false
            RETURNING taler_withdrawal_id
        """
        )
        stmt.setObject(1, opUUID)
        val res = stmt.executeQuery()
        res.use {
            if (!it.next()) return false
        }
        return true
    }

    /**
     * Associates a reserve public key and an exchange to
     * a Taler withdrawal.  Returns true on success, false
     * otherwise.
     *
     * Checking for idempotency is entirely on the Kotlin side.
     */
    fun talerWithdrawalSetDetails(
        opUuid: UUID,
        exchangePayto: String,
        reservePub: String
    ): Boolean {
        reconnect()
        val stmt = prepare("""
            UPDATE taler_withdrawal_operations
            SET selected_exchange_payto = ?, reserve_pub = ?, selection_done = true
            WHERE withdrawal_uuid=?
        """
        )
        stmt.setString(1, exchangePayto)
        stmt.setString(2, reservePub)
        stmt.setObject(3, opUuid)
        return myExecute(stmt)
    }

    /**
     *
     */
    fun talerWithdrawalConfirm(
        opUuid: UUID,
        timestamp: Long,
        accountServicerReference: String = "NOT-USED",
        endToEndId: String = "NOT-USED",
        paymentInfId: String = "NOT-USED"
    ): WithdrawalConfirmationResult {
        reconnect()
        val stmt = prepare("""
            SELECT
              out_nx_op,
              out_nx_exchange,
              out_insufficient_funds,
              out_already_confirmed_conflict
            FROM confirm_taler_withdrawal(?, ?, ?, ?, ?);
        """
        )
        stmt.setObject(1, opUuid)
        stmt.setLong(2, timestamp)
        stmt.setString(3, accountServicerReference)
        stmt.setString(4, endToEndId)
        stmt.setString(5, paymentInfId)
        val res = stmt.executeQuery()
        res.use {
            if (!res.next())
                throw internalServerError("No result from DB procedure confirm_taler_withdrawal")
            if (it.getBoolean("out_nx_op")) return WithdrawalConfirmationResult.OP_NOT_FOUND
            if (it.getBoolean("out_nx_exchange")) return WithdrawalConfirmationResult.EXCHANGE_NOT_FOUND
            if (it.getBoolean("out_insufficient_funds")) return WithdrawalConfirmationResult.BALANCE_INSUFFICIENT
            if (it.getBoolean("out_already_confirmed_conflict")) return WithdrawalConfirmationResult.CONFLICT
        }
        return WithdrawalConfirmationResult.SUCCESS
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
                    frac = it.getInt("amount_debit_frac"),
                    getCurrency()
                ),
                amountCredit = TalerAmount(
                    value = it.getLong("amount_credit_val"),
                    frac = it.getInt("amount_credit_frac"),
                    getCurrency()
                ),
                bankAccount = it.getLong("bank_account"),
                buyAtRatio = it.getInt("buy_at_ratio"),
                buyInFee = TalerAmount(
                    value = it.getLong("buy_in_fee_val"),
                    frac = it.getInt("buy_in_fee_frac"),
                    getCurrency()
                ),
                credit_payto_uri = it.getString("credit_payto_uri"),
                cashoutCurrency = it.getString("cashout_currency"),
                cashoutUuid = opUuid,
                creationTime = it.getLong("creation_time"),
                sellAtRatio = it.getInt("sell_at_ratio"),
                sellOutFee = TalerAmount(
                    value = it.getLong("sell_out_fee_val"),
                    frac = it.getInt("sell_out_fee_frac"),
                    getCurrency()
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
    data class TalerTransferFromDb(
        // Only used when this type if defined from a DB record
        val timestamp: Long,
        val debitTxRowId: Long,
        val requestUid: String,
        val amount: TalerAmount,
        val exchangeBaseUrl: String,
        val wtid: String,
        val creditAccount: String
    )
    // Gets a Taler transfer request, given its UID.
    fun talerTransferGetFromUid(requestUid: String): TalerTransferFromDb? {
        reconnect()
        val stmt = prepare("""
            SELECT
              wtid
              ,exchange_base_url
              ,(tfr.amount).val AS amount_value
              ,(tfr.amount).frac AS amount_frac
              ,tfr.credit_account_payto
              ,tfr.bank_transaction
              ,txs.transaction_date AS timestamp
              FROM taler_exchange_transfers AS tfr
                JOIN bank_account_transactions AS txs
                  ON bank_transaction=txs.bank_transaction_id
              WHERE request_uid = ?;
        """)
        stmt.setString(1, requestUid)
        val res = stmt.executeQuery()
        res.use {
            if (!it.next()) return null
            return TalerTransferFromDb(
                wtid = it.getString("wtid"),
                amount = TalerAmount(
                    value = it.getLong("amount_value"),
                    frac = it.getInt("amount_frac"),
                    getCurrency()
                ),
                creditAccount = it.getString("credit_account_payto"),
                exchangeBaseUrl = it.getString("exchange_base_url"),
                requestUid = requestUid,
                debitTxRowId = it.getLong("bank_transaction"),
                timestamp = it.getLong("timestamp")
            )
        }
    }

    data class TalerTransferCreationResult(
        val txResult: BankTransactionResult,
        // Row ID of the debit bank transaction
        // of a successful case.  Null upon errors
        val txRowId: Long? = null
    )
    /**
     * This function calls the SQL function that (1) inserts the TWG
     * requests details into the database and (2) performs the actual
     * bank transaction to pay the merchant according to the 'req' parameter.
     *
     * 'req' contains the same data that was POSTed by the exchange
     * to the TWG /transfer endpoint.  The exchangeBankAccountId parameter
     * is the row ID of the exchange's bank account.  The return type
     * is the same returned by "bank_wire_transfer()" where however
     * the NO_DEBTOR error will hardly take place.
     */
    fun talerTransferCreate(
        req: TransferRequest,
        exchangeBankAccountId: Long,
        timestamp: Long,
        acctSvcrRef: String = "not used",
        pmtInfId: String = "not used",
        endToEndId: String = "not used",
        ): TalerTransferCreationResult {
        reconnect()
        // FIXME: future versions should return the exchange's latest bank transaction ID
        val stmt = prepare("""
            SELECT
              out_exchange_balance_insufficient
              ,out_nx_creditor
              ,out_tx_row_id
              FROM
                taler_transfer (
                  ?,
                  ?,
                  (?,?)::taler_amount,
                  ?,
                  ?,
                  ?,
                  ?,
                  ?,
                  ?,
                  ?
                );
        """)
        stmt.setString(1, req.request_uid)
        stmt.setString(2, req.wtid)
        stmt.setLong(3, req.amount.value)
        stmt.setInt(4, req.amount.frac)
        stmt.setString(5, req.exchange_base_url)
        stmt.setString(6, req.credit_account)
        stmt.setLong(7, exchangeBankAccountId)
        stmt.setLong(8, timestamp)
        stmt.setString(9, acctSvcrRef)
        stmt.setString(10, pmtInfId)
        stmt.setString(11, endToEndId)

        val res = stmt.executeQuery()
        res.use {
            if (!it.next())
                throw internalServerError("SQL function taler_transfer did not return anything.")
            if (it.getBoolean("out_nx_creditor"))
                return TalerTransferCreationResult(BankTransactionResult.NO_CREDITOR)
            if (it.getBoolean("out_exchange_balance_insufficient"))
                return TalerTransferCreationResult(BankTransactionResult.CONFLICT)
            val txRowId = it.getLong("out_tx_row_id")
            return TalerTransferCreationResult(
                txResult = BankTransactionResult.SUCCESS,
                txRowId = txRowId
            )
        }
    }
}
