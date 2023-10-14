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
import org.postgresql.ds.PGSimpleDataSource
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.util.getJdbcConnectionFromPg
import tech.libeufin.util.microsToJavaInstant
import tech.libeufin.util.stripIbanPayto
import tech.libeufin.util.toDbMicros
import tech.libeufin.util.XMLUtil
import java.io.File
import java.sql.*
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import com.zaxxer.hikari.*

private const val DB_CTR_LIMIT = 1000000

fun Customer.expectRowId(): Long = this.dbRowId ?: throw internalServerError("Cutsomer '$login' had no DB row ID.")
fun BankAccount.expectBalance(): TalerAmount = this.balance ?: throw internalServerError("Bank account '${this.internalPaytoUri}' lacks balance.")
fun BankAccount.expectRowId(): Long = this.bankAccountId ?: throw internalServerError("Bank account '${this.internalPaytoUri}' lacks database row ID.")
fun BankAccountTransaction.expectRowId(): Long = this.dbRowId ?: throw internalServerError("Bank account transaction (${this.subject}) lacks database row ID.")

private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.bank.Database")

/**
 * This error occurs in case the timestamp took by the bank for some
 * event could not be converted in microseconds.  Note: timestamp are
 * taken via the Instant.now(), then converted to nanos, and then divided
 * by 1000 to obtain the micros.
 *
 * It could be that a legitimate timestamp overflows in the process of
 * being converted to micros - as described above.  In the case of a timestamp,
 * the fault lies to the bank, because legitimate timestamps must (at the
 * time of writing!) go through the conversion to micros.
 *
 * On the other hand (and for the sake of completeness), in the case of a
 * timestamp that was calculated after a client-submitted duration, the overflow
 * lies to the client, because they must have specified a gigantic amount of time
 * that overflew the conversion to micros and should simply have specified "forever".
 */
private fun faultyTimestampByBank() = internalServerError("Bank took overflowing timestamp")
private fun faultyDurationByClient() = badRequest("Overflowing duration, please specify 'forever' instead.")

private fun pgDataSource(dbConfig: String): PGSimpleDataSource {
    val jdbcConnStr = getJdbcConnectionFromPg(dbConfig)
    logger.info("connecting to database via JDBC string '$jdbcConnStr'")
    val pgSource = PGSimpleDataSource()
    pgSource.setUrl(jdbcConnStr)
    pgSource.prepareThreshold = 1
    return pgSource
}

private fun PGSimpleDataSource.pgConnection(): PgConnection {
    val conn = connection.unwrap(PgConnection::class.java)
    conn.execSQLUpdate("SET search_path TO libeufin_bank;")
    return conn
}

private fun <R> PgConnection.transaction(lambda: (PgConnection) -> R): R {
    try {
        setAutoCommit(false);
        val result = lambda(this)
        commit();
        setAutoCommit(true);
        return result
    } catch(e: Exception){
        rollback();
        setAutoCommit(true); 
        throw e;
    }
}

fun initializeDatabaseTables(cfg: DatabaseConfig) {
    logger.info("doing DB initialization, sqldir ${cfg.sqlDir}, dbConnStr ${cfg.dbConnStr}")
    pgDataSource(cfg.dbConnStr).pgConnection().use { conn ->
        val sqlVersioning = File("${cfg.sqlDir}/versioning.sql").readText()
        conn.execSQLUpdate(sqlVersioning)

        val checkStmt = conn.prepareStatement("SELECT count(*) as n FROM _v.patches where patch_name = ?")

        for (n in 1..9999) {
            val numStr = n.toString().padStart(4, '0')
            val patchName = "libeufin-bank-$numStr"

            checkStmt.setString(1, patchName)
            val patchCount = checkStmt.oneOrNull { it.getInt(1) } ?: throw Error("unable to query patches");
            if (patchCount >= 1) {
                logger.info("patch $patchName already applied")
                continue
            }

            val path = File("${cfg.sqlDir}/libeufin-bank-$numStr.sql")
            if (!path.exists()) {
                logger.info("path $path doesn't exist anymore, stopping")
                break
            }
            logger.info("applying patch $path")
            val sqlPatchText = path.readText()
            conn.execSQLUpdate(sqlPatchText)
        }
        val sqlProcedures = File("${cfg.sqlDir}/procedures.sql").readText()
        conn.execSQLUpdate(sqlProcedures)
    }
}

fun resetDatabaseTables(cfg: DatabaseConfig) {
    logger.info("reset DB, sqldir ${cfg.sqlDir}, dbConnStr ${cfg.dbConnStr}")
    pgDataSource(cfg.dbConnStr).pgConnection().use { conn ->
        val count = conn.prepareStatement("SELECT count(*) FROM information_schema.schemata WHERE schema_name='_v'").oneOrNull { 
            it.getInt(1)
        } ?: 0
        if (count == 0) {
            logger.info("versioning schema not present, not running drop sql")
            return
        }

        val sqlDrop = File("${cfg.sqlDir}/libeufin-bank-drop.sql").readText()
        conn.execSQLUpdate(sqlDrop) // TODO can fail ?
    }
}


private fun <T> PreparedStatement.oneOrNull(lambda: (ResultSet) -> T): T? {
    executeQuery().use {
        if (!it.next()) return null
        return lambda(it)
    }
}

private fun <T> PreparedStatement.all(lambda: (ResultSet) -> T): List<T> {
    executeQuery().use {
        val ret = mutableListOf<T>()
        while (it.next()) {
            ret.add(lambda(it))
        }
        return ret
    }
}

private fun PreparedStatement.executeQueryCheck(): Boolean {
    executeQuery().use {
        return it.next()
    }
}

private fun PreparedStatement.executeUpdateCheck(): Boolean {
    executeUpdate()
    return updateCount > 0
}

/**
 * Helper that returns false if the row to be inserted
 * hits a unique key constraint violation, true when it
 * succeeds.  Any other error (re)throws exception.
 */
private fun PreparedStatement.executeUpdateViolation(): Boolean {
    return try {
        executeUpdateCheck()
    } catch (e: SQLException) {
        logger.error(e.message)
        if (e.sqlState == "23505") return false // unique_violation
        throw e // rethrowing, not to hide other types of errors.
    }
}

class Database(dbConfig: String, private val bankCurrency: String): java.io.Closeable {
    private val dbPool: HikariDataSource
    private val notifWatcher: NotificationWatcher

    init {
        val pgSource = pgDataSource(dbConfig)
        val config = HikariConfig();
        config.dataSource = pgSource
        config.connectionInitSql = "SET search_path TO libeufin_bank;"
        config.validate()
        dbPool = HikariDataSource(config);
        notifWatcher = NotificationWatcher(pgSource)
    }

    override fun close() {
        dbPool.close()
    }

    private suspend fun <R> conn(lambda: suspend (PgConnection) -> R): R {
        // Use a coroutine dispatcher that we can block as JDBC API is blocking
        return withContext(Dispatchers.IO) {
            val conn = dbPool.getConnection()
            conn.use{ it -> lambda(it.unwrap(PgConnection::class.java)) }
        }
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
    suspend fun customerCreate(customer: Customer): Long? = conn { conn ->
        val stmt = conn.prepareStatement("""
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
            if (e.errorCode == 0) return@conn null // unique constraint violation.
            throw e // rethrow on other errors.
        }
        res.use {
            when {
                !it.next() -> throw internalServerError("SQL RETURNING gave no customer_id.")
                else -> it.getLong("customer_id")
            }
        }
    }

    /**
     * Deletes a customer (including its bank account row) from
     * the database.  The bank account gets deleted by the cascade.
     */
    suspend fun customerDeleteIfBalanceIsZero(login: String): CustomerDeletionResult = conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
              out_nx_customer,
              out_balance_not_zero
              FROM customer_delete(?);
        """)
        stmt.setString(1, login)
        stmt.executeQuery().use {
            when {
                !it.next() -> throw internalServerError("Deletion returned nothing.")
                it.getBoolean("out_nx_customer") -> CustomerDeletionResult.CUSTOMER_NOT_FOUND
                it.getBoolean("out_balance_not_zero") -> CustomerDeletionResult.BALANCE_NOT_ZERO
                else -> CustomerDeletionResult.SUCCESS
            }
        }
    }

    // Mostly used to get customers out of bearer tokens.
    suspend fun customerGetFromRowId(customer_id: Long): Customer? = conn { conn ->
        val stmt = conn.prepareStatement("""
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
        stmt.oneOrNull { 
            Customer(
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

    suspend fun customerChangePassword(customerName: String, passwordHash: String): Boolean = conn { conn ->
        val stmt = conn.prepareStatement("""
            UPDATE customers SET password_hash=? where login=?
        """)
        stmt.setString(1, passwordHash)
        stmt.setString(2, customerName)
        stmt.executeUpdateCheck()
    }

    suspend fun customerGetFromLogin(login: String): Customer? = conn { conn ->
        val stmt = conn.prepareStatement("""
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
        stmt.oneOrNull { 
            Customer(
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
    suspend fun bearerTokenCreate(token: BearerToken): Boolean = conn { conn ->
        val stmt = conn.prepareStatement("""
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
        stmt.setLong(2, token.creationTime.toDbMicros() ?: throw faultyTimestampByBank())
        stmt.setLong(3, token.expirationTime.toDbMicros() ?: throw faultyDurationByClient())
        stmt.setString(4, token.scope.name)
        stmt.setLong(5, token.bankCustomer)
        stmt.setBoolean(6, token.isRefreshable)
        stmt.executeUpdateViolation()
    }
    suspend fun bearerTokenGet(token: ByteArray): BearerToken? = conn { conn ->
        val stmt = conn.prepareStatement("""
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
        stmt.oneOrNull { 
            BearerToken(
                content = token,
                creationTime = it.getLong("creation_time").microsToJavaInstant() ?: throw faultyTimestampByBank(),
                expirationTime = it.getLong("expiration_time").microsToJavaInstant() ?: throw faultyDurationByClient(),
                bankCustomer = it.getLong("bank_customer"),
                scope = when (it.getString("scope")) {
                    TokenScope.readwrite.name -> TokenScope.readwrite
                    TokenScope.readonly.name -> TokenScope.readonly
                    else -> throw internalServerError("Wrong token scope found in the database: $this")
                },
                isRefreshable = it.getBoolean("is_refreshable")
            )
        }
    }
    /**
     * Deletes a bearer token from the database.  Returns true,
     * if deletion succeeds or false if the token could not be
     * deleted (= not found).
     */
    suspend fun bearerTokenDelete(token: ByteArray): Boolean = conn { conn ->
        val stmt = conn.prepareStatement("""
            DELETE FROM bearer_tokens
              WHERE content = ?
              RETURNING bearer_token_id;
        """)
        stmt.setBytes(1, token)
        stmt.executeQueryCheck()
    }

    // MIXED CUSTOMER AND BANK ACCOUNT DATA

    /**
     * Updates accounts according to the PATCH /accounts/foo endpoint.
     * The 'login' parameter decides which customer and bank account rows
     * will get the update.
     *
     * Meaning of null in the parameters: when 'name' and 'isTalerExchange'
     * are null, NOTHING gets changed.  If any of the other values are null,
     * WARNING: their value will be overridden with null.  No parameter gets
     * null as the default, as to always keep the caller aware of what gets in
     * the database.
     *
     * The return type expresses either success, or that the target rows
     * could not be found.
     */
    suspend fun accountReconfig(
        login: String,
        name: String?,
        cashoutPayto: String?,
        phoneNumber: String?,
        emailAddress: String?,
        isTalerExchange: Boolean?
    ): AccountReconfigDBResult = conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
              out_nx_customer,
              out_nx_bank_account
              FROM account_reconfig(?, ?, ?, ?, ?, ?)
        """)
        stmt.setString(1, login)
        stmt.setString(2, name)
        stmt.setString(3, phoneNumber)
        stmt.setString(4, emailAddress)
        stmt.setString(5, cashoutPayto)

        if (isTalerExchange == null)
            stmt.setNull(6, Types.NULL)
        else stmt.setBoolean(6, isTalerExchange)

        stmt.executeQuery().use {
            when {
                !it.next() -> throw internalServerError("accountReconfig() returned nothing")
                it.getBoolean("out_nx_customer") -> AccountReconfigDBResult.CUSTOMER_NOT_FOUND
                it.getBoolean("out_nx_bank_account") -> AccountReconfigDBResult.BANK_ACCOUNT_NOT_FOUND
                else -> AccountReconfigDBResult.SUCCESS
            }
        }
    }

    /**
     * Gets the list of public accounts in the system.
     * internalCurrency is the bank's currency and loginFilter is
     * an optional filter on the account's login.
     *
     * Returns an empty list, if no public account was found.
     */
    suspend fun accountsGetPublic(internalCurrency: String, loginFilter: String = "%"): List<PublicAccount> = conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
              (balance).val AS balance_val,
              (balance).frac AS balance_frac,
              has_debt,
              internal_payto_uri,
              c.login      
              FROM bank_accounts JOIN customers AS c
                ON owning_customer_id = c.customer_id
                WHERE is_public=true AND c.login LIKE ?;
        """)
        stmt.setString(1, loginFilter)
        stmt.all {
            PublicAccount(
                account_name = it.getString("login"),
                payto_uri = it.getString("internal_payto_uri"),
                balance = Balance(
                    amount = TalerAmount(
                        value = it.getLong("balance_val"),
                        frac = it.getInt("balance_frac"),
                        currency = internalCurrency
                    ),
                    credit_debit_indicator = if (it.getBoolean("has_debt")) {
                        CorebankCreditDebitInfo.debit 
                    } else {
                        CorebankCreditDebitInfo.credit
                    }
                )
            )
        }
    }

    /**
     * Gets a minimal set of account data, as outlined in the GET /accounts
     * endpoint.  The nameFilter parameter will be passed AS IS to the SQL
     * LIKE operator.  If it's null, it defaults to the "%" wildcard, meaning
     * that it returns ALL the existing accounts.
     */
    suspend fun accountsGetForAdmin(nameFilter: String = "%"): List<AccountMinimalData> = conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
              login,
              name,
              (b.balance).val AS balance_val,
              (b.balance).frac AS balance_frac,
              (b).has_debt AS balance_has_debt,
              (max_debt).val as max_debt_val,
              (max_debt).frac as max_debt_frac
              FROM customers JOIN bank_accounts AS b
                ON customer_id = b.owning_customer_id
              WHERE name LIKE ?;
        """)
        stmt.setString(1, nameFilter)
        stmt.all {
            AccountMinimalData(
                username = it.getString("login"),
                name = it.getString("name"),
                balance = Balance(
                    amount = TalerAmount(
                        value = it.getLong("balance_val"),
                        frac = it.getInt("balance_frac"),
                        currency = getCurrency()
                    ),
                    credit_debit_indicator = if (it.getBoolean("balance_has_debt")) {
                        CorebankCreditDebitInfo.debit
                    } else {
                        CorebankCreditDebitInfo.credit
                    }
                ),
                debit_threshold = TalerAmount(
                    value = it.getLong("max_debt_val"),
                    frac = it.getInt("max_debt_frac"),
                    currency = getCurrency()
                )
            )
        }
    }

    // BANK ACCOUNTS

    /**
     * Inserts a new bank account in the database, returning its
     * row ID in the successful case.  If of unique constrain violation,
     * it returns null and any other error will be thrown as 500.
     */
    suspend fun bankAccountCreate(bankAccount: BankAccount): Long? = conn { conn ->
        if (bankAccount.balance != null)
            throw internalServerError(
                "Do not pass a balance upon bank account creation, do a wire transfer instead."
            )
        val stmt = conn.prepareStatement("""
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
        stmt.setString(1, bankAccount.internalPaytoUri.stripped)
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
            if (e.errorCode == 0) return@conn null // unique constraint violation.
            throw e // rethrow on other errors.
        }
        res.use {
            when {
                !it.next() -> throw internalServerError("SQL RETURNING gave no bank_account_id.")
                else -> it.getLong("bank_account_id")
            }
        }
    }

    suspend fun bankAccountSetMaxDebt(
        owningCustomerId: Long,
        maxDebt: TalerAmount
    ): Boolean = conn { conn ->
        val stmt = conn.prepareStatement("""
           UPDATE bank_accounts
           SET max_debt=(?,?)::taler_amount
           WHERE owning_customer_id=?
        """)
        stmt.setLong(1, maxDebt.value)
        stmt.setInt(2, maxDebt.frac)
        stmt.setLong(3, owningCustomerId)
        stmt.executeUpdateViolation()
    }

    private fun getCurrency(): String {
        return bankCurrency
    }

    suspend fun bankAccountGetFromOwnerId(ownerId: Long): BankAccount? = conn { conn ->
        val stmt = conn.prepareStatement("""
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

        stmt.oneOrNull {
            BankAccount(
                internalPaytoUri = IbanPayTo(it.getString("internal_payto_uri")),
                balance = TalerAmount(
                    it.getLong("balance_val"),
                    it.getInt("balance_frac"),
                    getCurrency()
                ),
                lastNexusFetchRowId = it.getLong("last_nexus_fetch_row_id"),
                owningCustomerId = it.getLong("owning_customer_id"),
                hasDebt = it.getBoolean("has_debt"),
                isTalerExchange = it.getBoolean("is_taler_exchange"),
                isPublic = it.getBoolean("is_public"),
                maxDebt = TalerAmount(
                    value = it.getLong("max_debt_val"),
                    frac = it.getInt("max_debt_frac"),
                    getCurrency()
                ),
                bankAccountId = it.getLong("bank_account_id")
            )
        }
    }

    data class BankInfo(
        val id: Long,
        val isTalerExchange: Boolean,
        val internalPaytoUri: String
    )
    
    suspend fun bankAccountInfoFromCustomerLogin(login: String): BankInfo? = conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
                bank_account_id
                ,is_taler_exchange
                ,internal_payto_uri
            FROM bank_accounts
                JOIN customers 
                ON customer_id=owning_customer_id
            WHERE login=?
        """)
        stmt.setString(1, login)
        stmt.oneOrNull { 
            BankInfo(
                id = it.getLong(1),
                isTalerExchange = it.getBoolean(2),
                internalPaytoUri = it.getString(3),
            )
        }
    }

    suspend fun bankAccountGetFromInternalPayto(internalPayto: IbanPayTo): BankAccount? = conn { conn ->
        val stmt = conn.prepareStatement("""
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
        stmt.setString(1, internalPayto.stripped)

        stmt.oneOrNull {
            BankAccount(
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

    suspend fun bankTransactionCreate(
        tx: BankInternalTransaction
    ): BankTransactionResult = conn { conn ->
        conn.transaction {
            val stmt = conn.prepareStatement("""
                SELECT 
                    out_same_account 
                    ,out_debtor_not_found 
                    ,out_creditor_not_found 
                    ,out_balance_insufficient 
                    ,out_credit_row_id
                    ,out_debit_row_id
                    ,out_creditor_is_exchange 
                    ,out_debtor_is_exchange
                FROM bank_wire_transfer(?,?,TEXT(?),(?,?)::taler_amount,?,TEXT(?),TEXT(?),TEXT(?))
            """
            )
            stmt.setLong(1, tx.creditorAccountId)
            stmt.setLong(2, tx.debtorAccountId)
            stmt.setString(3, tx.subject)
            stmt.setLong(4, tx.amount.value)
            stmt.setInt(5, tx.amount.frac)
            stmt.setLong(6, tx.transactionDate.toDbMicros() ?: throw faultyTimestampByBank())
            stmt.setString(7, tx.accountServicerReference)
            stmt.setString(8, tx.paymentInformationId)
            stmt.setString(9, tx.endToEndId)
            stmt.executeQuery().use {
                when {
                    !it.next() -> throw internalServerError("Bank transaction didn't properly return")
                    it.getBoolean("out_debtor_not_found") -> {
                        logger.error("No debtor account found")
                        BankTransactionResult.NO_DEBTOR
                    }
                    it.getBoolean("out_creditor_not_found") -> {
                        logger.error("No creditor account found")
                        BankTransactionResult.NO_CREDITOR
                    }
                    it.getBoolean("out_same_account") ->  BankTransactionResult.SAME_ACCOUNT
                    it.getBoolean("out_balance_insufficient") -> {
                        logger.error("Balance insufficient")
                        BankTransactionResult.BALANCE_INSUFFICIENT
                    }
                    else -> {
                        val metadata = TxMetadata.parse(tx.subject)
                        if (it.getBoolean("out_creditor_is_exchange")) {
                            val rowId = it.getLong("out_credit_row_id")
                            if (metadata is IncomingTxMetadata) {
                                val stmt = conn.prepareStatement("""
                                    INSERT INTO taler_exchange_incoming 
                                        (reserve_pub, bank_transaction) 
                                    VALUES (?, ?)
                                """)
                                stmt.setBytes(1, metadata.reservePub.raw)
                                stmt.setLong(2, rowId)
                                stmt.executeUpdate()
                                conn.execSQLUpdate("NOTIFY incoming_tx, '${"${tx.creditorAccountId} $rowId"}'")
                            } else {
                                // TODO bounce
                                logger.warn("exchange account ${tx.creditorAccountId} received a transaction $rowId with malformed metadata, will bounce in future version")
                            }
                        }
                        if (it.getBoolean("out_debtor_is_exchange")) {
                            val rowId = it.getLong("out_debit_row_id")
                            if (metadata is OutgoingTxMetadata) {
                                val stmt = conn.prepareStatement("""
                                    INSERT INTO taler_exchange_outgoing 
                                        (wtid, exchange_base_url, bank_transaction) 
                                    VALUES (?, ?, ?)
                                """)
                                stmt.setBytes(1, metadata.wtid.raw)
                                stmt.setString(2, metadata.exchangeBaseUrl.url)
                                stmt.setLong(3, rowId)
                                stmt.executeUpdate()
                                conn.execSQLUpdate("NOTIFY outgoing_tx, '${"${tx.debtorAccountId} $rowId"}'")
                            } else {
                                logger.warn("exchange account ${tx.debtorAccountId} sent a transaction $rowId with malformed metadata")
                            }
                        }
                        BankTransactionResult.SUCCESS
                    }
                }
            }
        }
    }

    /**
     * Only checks if a bank transaction with the given subject
     * exists.  That's only used in the /admin/add-incoming, to
     * prevent a public key from being reused.
     *
     * Returns the row ID if found, null otherwise.
     */
    suspend fun bankTransactionCheckExists(subject: String): Long? = conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT bank_transaction_id
            FROM bank_account_transactions
            WHERE subject = ?;           
        """)
        stmt.setString(1, subject)
        stmt.oneOrNull { it.getLong("bank_transaction_id") }
    }

    // Get the bank transaction whose row ID is rowId
    suspend fun bankTransactionGetFromInternalId(rowId: Long): BankAccountTransaction? = conn { conn ->
        val stmt = conn.prepareStatement("""
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
        stmt.oneOrNull {
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
                transactionDate = it.getLong("transaction_date").microsToJavaInstant() ?: throw faultyTimestampByBank()
            )
        }
    }

    /**
    * The following function returns the list of transactions, according
    * to the history parameters and perform long polling when necessary.
    */
    private suspend fun <T> poolHistory(
        params: HistoryParams, 
        bankAccountId: Long,
        listen: suspend NotificationWatcher.(Long, suspend (Flow<Long>) -> Unit) -> Unit,
        query: String,
        map: (ResultSet) -> T
    ): List<T> {
        val backward = params.delta < 0
        val nbTx = abs(params.delta) // Number of transaction to query
        // Range of transaction ids to check
        var (min, max) = if (backward) Pair(0L, params.start) else Pair(params.start, Long.MAX_VALUE)
        val query = """
            $query
            WHERE bank_account_id=? AND
            bank_transaction_id > ? AND  bank_transaction_id < ?
            ORDER BY bank_transaction_id ${if (backward) "DESC" else "ASC"}
            LIMIT ?
        """
        
        suspend fun load(amount: Int): List<T> = conn { conn ->
            conn.prepareStatement(query).use { stmt ->
                stmt.setLong(1, bankAccountId)
                stmt.setLong(2, min)
                stmt.setLong(3, max)
                stmt.setInt(4, amount)
                stmt.all {
                    // Remember not to check this transaction again
                    min = kotlin.math.max(it.getLong("bank_transaction_id"), min)
                    map(it)
                }
            }
        }

        val shoudPoll = when {
            params.poll_ms <= 0 -> false
            backward && max == Long.MAX_VALUE -> true
            backward -> {
                val maxId = conn {
                    it.prepareStatement("SELECT MAX(bank_transaction_id) FROM bank_account_transactions")
                        .oneOrNull { it.getLong(1) } ?: 0
                };
                // Check if a new transaction could appear within the chosen interval
                max > maxId + 1
            }
            else -> true
        }

        if (shoudPoll) {
            var history = listOf<T>()
            notifWatcher.(listen)(bankAccountId) { flow ->
                // Start buffering notification before loading transactions to not miss any
                val buffered = flow.buffer()
                // Initial load
                history += load(nbTx)
                // Long polling if transactions are missing
                val missing = nbTx - history.size
                if (missing > 0) {
                    withTimeoutOrNull(params.poll_ms) {
                        buffered
                            .filter { it > min } // Skip transactions already checked
                            .take(missing).count() // Wait for missing transactions
                    }

                    if (backward) {
                        // When going backward, we could find more transactions than we need
                        history = (load(nbTx) + history).take(nbTx)
                    } else {
                        // Only load missing ones
                        history += load(missing)
                    }
                }
            }
            return history
        } else {
            return load(nbTx)
        }
    }

    suspend fun bankPoolHistory(
        params: HistoryParams, 
        bankAccountId: Long
    ): List<BankAccountTransactionInfo> {
        return poolHistory(params, bankAccountId, NotificationWatcher::listenBank,  """
            SELECT
                bank_transaction_id
                ,transaction_date
                ,(amount).val AS amount_val
                ,(amount).frac AS amount_frac
                ,debtor_payto_uri
                ,creditor_payto_uri
                ,subject
                ,direction
            FROM bank_account_transactions
        """) {
            BankAccountTransactionInfo(
                row_id = it.getLong("bank_transaction_id"),
                date = TalerProtocolTimestamp(
                    it.getLong("transaction_date").microsToJavaInstant() ?: throw faultyTimestampByBank()
                ),
                debtor_payto_uri = it.getString("debtor_payto_uri"),
                creditor_payto_uri = it.getString("creditor_payto_uri"),
                amount = TalerAmount(
                    it.getLong("amount_val"),
                    it.getInt("amount_frac"),
                    getCurrency()
                ),
                subject = it.getString("subject"),
                direction = it.getString("direction").run {
                    when(this) {
                        "credit" -> TransactionDirection.credit
                        "debit" -> TransactionDirection.debit
                        else -> throw internalServerError("Wrong direction in transaction: $this")
                    }
                }
            )
        }
    }

    suspend fun exchangeIncomingPoolHistory(
        params: HistoryParams, 
        bankAccountId: Long
    ): List<IncomingReserveTransaction> {
        return poolHistory(params, bankAccountId, NotificationWatcher::listenIncoming,  """
            SELECT
                bank_transaction_id
                ,transaction_date
                ,(amount).val AS amount_val
                ,(amount).frac AS amount_frac
                ,debtor_payto_uri
                ,reserve_pub
            FROM taler_exchange_incoming AS tfr
                JOIN bank_account_transactions AS txs
                    ON bank_transaction=txs.bank_transaction_id
        """) {
            IncomingReserveTransaction(
                row_id = it.getLong("bank_transaction_id"),
                date = TalerProtocolTimestamp(
                    it.getLong("transaction_date").microsToJavaInstant() ?: throw faultyTimestampByBank()
                ),
                amount = TalerAmount(
                    it.getLong("amount_val"),
                    it.getInt("amount_frac"),
                    getCurrency()
                ),
                debit_account = it.getString("debtor_payto_uri"),
                reserve_pub = EddsaPublicKey(it.getBytes("reserve_pub")),
            )
        }
    }

    suspend fun exchangeOutgoingPoolHistory(
        params: HistoryParams, 
        bankAccountId: Long
    ): List<OutgoingTransaction> {
        return poolHistory(params, bankAccountId, NotificationWatcher::listenOutgoing,  """
            SELECT
                bank_transaction_id
                ,transaction_date
                ,(amount).val AS amount_val
                ,(amount).frac AS amount_frac
                ,creditor_payto_uri
                ,wtid
                ,exchange_base_url
            FROM taler_exchange_outgoing AS tfr
                JOIN bank_account_transactions AS txs
                    ON bank_transaction=txs.bank_transaction_id
        """) {
            OutgoingTransaction(
                row_id = it.getLong("bank_transaction_id"),
                date = TalerProtocolTimestamp(
                    it.getLong("transaction_date").microsToJavaInstant() ?: throw faultyTimestampByBank()
                ),
                amount = TalerAmount(
                    it.getLong("amount_val"),
                    it.getInt("amount_frac"),
                    getCurrency()
                ),
                credit_account = IbanPayTo(it.getString("creditor_payto_uri")),
                wtid = ShortHashCode(it.getBytes("wtid")),
                exchange_base_url = ExchangeUrl(it.getString("exchange_base_url"))
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
    suspend fun bankTransactionGetHistory(
        start: Long,
        delta: Int,
        bankAccountId: Long
    ): List<BankAccountTransaction> = conn { conn ->
        val (cmpOp, orderBy) = if (delta < 0) Pair("<", "DESC") else Pair(">", "ASC")
        val stmt = conn.prepareStatement("""
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
              ,bank_transaction_id
            FROM bank_account_transactions
	        WHERE bank_transaction_id ${cmpOp} ? 
              AND bank_account_id=?
            ORDER BY bank_transaction_id ${orderBy}
            LIMIT ?
        """)
        stmt.setLong(1, start)
        stmt.setLong(2, bankAccountId)
        stmt.setInt(3, abs(delta))
        stmt.all {
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
                direction = when (it.getString("direction")) {
                    "credit" -> TransactionDirection.credit
                    "debit" -> TransactionDirection.debit
                    else -> throw internalServerError("Wrong direction in transaction: $this")
                },
                bankAccountId = it.getLong("bank_account_id"),
                paymentInformationId = it.getString("payment_information_id"),
                subject = it.getString("subject"),
                transactionDate = it.getLong("transaction_date").microsToJavaInstant() ?: throw faultyTimestampByBank(),
                dbRowId = it.getLong("bank_transaction_id")
            )
        }
    }

    // WITHDRAWALS
    suspend fun talerWithdrawalCreate(
        opUUID: UUID,
        walletBankAccount: Long,
        amount: TalerAmount
    ): Boolean = conn { conn ->
        val stmt = conn.prepareStatement("""
            INSERT INTO
              taler_withdrawal_operations
              (withdrawal_uuid, wallet_bank_account, amount)
            VALUES (?,?,(?,?)::taler_amount)
        """) // Take all defaults from the SQL.
        stmt.setObject(1, opUUID)
        stmt.setLong(2, walletBankAccount)
        stmt.setLong(3, amount.value)
        stmt.setInt(4, amount.frac)
        stmt.executeUpdateViolation()
    }
    suspend fun talerWithdrawalGet(opUUID: UUID): TalerWithdrawalOperation? = conn { conn ->
        val stmt = conn.prepareStatement("""
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
        stmt.oneOrNull {
            TalerWithdrawalOperation(
                amount = TalerAmount(
                    it.getLong("amount_val"),
                    it.getInt("amount_frac"),
                    getCurrency()
                ),
                selectionDone = it.getBoolean("selection_done"),
                selectedExchangePayto = it.getString("selected_exchange_payto")?.run(::IbanPayTo),
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
    suspend fun talerWithdrawalAbort(opUUID: UUID): Boolean = conn { conn ->
        val stmt = conn.prepareStatement("""
            UPDATE taler_withdrawal_operations
            SET aborted = true
            WHERE withdrawal_uuid=? AND confirmation_done = false
            RETURNING taler_withdrawal_id
        """
        )
        stmt.setObject(1, opUUID)
        stmt.executeQueryCheck()
    }

    /**
     * Associates a reserve public key and an exchange to
     * a Taler withdrawal.  Returns true on success, false
     * otherwise.
     *
     * Checking for idempotency is entirely on the Kotlin side.
     */
    suspend fun talerWithdrawalSetDetails(
        opUuid: UUID,
        exchangePayto: IbanPayTo,
        reservePub: String
    ): Boolean = conn { conn ->
        val stmt = conn.prepareStatement("""
            UPDATE taler_withdrawal_operations
            SET selected_exchange_payto = ?, reserve_pub = ?, selection_done = true
            WHERE withdrawal_uuid=?
        """
        )
        stmt.setString(1, exchangePayto.stripped)
        stmt.setString(2, reservePub)
        stmt.setObject(3, opUuid)
        stmt.executeUpdateViolation()
    }

    /**
     * Confirms a Taler withdrawal: flags the operation as
     * confirmed and performs the related wire transfer.
     */
    suspend fun talerWithdrawalConfirm(
        opUuid: UUID,
        timestamp: Instant,
        accountServicerReference: String = "NOT-USED",
        endToEndId: String = "NOT-USED",
        paymentInfId: String = "NOT-USED"
    ): WithdrawalConfirmationResult = conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
              out_no_op,
              out_exchange_not_found,
              out_balance_insufficient,
              out_already_confirmed_conflict
            FROM confirm_taler_withdrawal(?, ?, ?, ?, ?);
        """
        )
        stmt.setObject(1, opUuid)
        stmt.setLong(2, timestamp.toDbMicros() ?: throw faultyTimestampByBank())
        stmt.setString(3, accountServicerReference)
        stmt.setString(4, endToEndId)
        stmt.setString(5, paymentInfId)
        stmt.executeQuery().use {
            when {
                !it.next() ->
                    throw internalServerError("No result from DB procedure confirm_taler_withdrawal")
                it.getBoolean("out_no_op") -> WithdrawalConfirmationResult.OP_NOT_FOUND
                it.getBoolean("out_exchange_not_found") -> WithdrawalConfirmationResult.EXCHANGE_NOT_FOUND
                it.getBoolean("out_balance_insufficient") -> WithdrawalConfirmationResult.BALANCE_INSUFFICIENT
                it.getBoolean("out_already_confirmed_conflict") -> WithdrawalConfirmationResult.CONFLICT
                else -> WithdrawalConfirmationResult.SUCCESS
            }
        }
    }

    /**
     * Creates a cashout operation in the database.
     */
    suspend fun cashoutCreate(op: Cashout): Boolean = conn { conn ->
        val stmt = conn.prepareStatement("""
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
        stmt.setLong(13, op.creationTime.toDbMicros() ?: throw faultyTimestampByBank())
        stmt.setString(14, op.tanChannel.name)
        stmt.setString(15, op.tanCode)
        stmt.setLong(16, op.bankAccount)
        stmt.setString(17, op.credit_payto_uri)
        stmt.setString(18, op.cashoutCurrency)
        stmt.executeUpdateViolation()
    }

    /**
     * Flags one cashout operation as confirmed.  The backing
     * payment should already have taken place, before calling
     * this function.
     */
    suspend fun cashoutConfirm(
        opUuid: UUID,
        tanConfirmationTimestamp: Long,
        bankTransaction: Long // regional payment backing the operation
    ): Boolean = conn { conn ->
        val stmt = conn.prepareStatement("""
            UPDATE cashout_operations
              SET tan_confirmation_time = ?, local_transaction = ?
              WHERE cashout_uuid=?;
        """)
        stmt.setLong(1, tanConfirmationTimestamp)
        stmt.setLong(2, bankTransaction)
        stmt.setObject(3, opUuid)
        stmt.executeUpdateViolation()
    }

    /**
     * This type is used by the cashout /abort handler.
     */
    enum class CashoutDeleteResult {
        SUCCESS,
        CONFLICT_ALREADY_CONFIRMED
    }

    /**
     * Deletes a cashout operation from the database.
     */
    suspend fun cashoutDelete(opUuid: UUID): CashoutDeleteResult = conn { conn ->
        val stmt = conn.prepareStatement("""
           SELECT out_already_confirmed
             FROM cashout_delete(?)
        """)
        stmt.setObject(1, opUuid)
        stmt.executeQuery().use {
            when {
                !it.next() -> throw internalServerError("Cashout deletion gave no result")
                it.getBoolean("out_already_confirmed") -> CashoutDeleteResult.CONFLICT_ALREADY_CONFIRMED
                else -> CashoutDeleteResult.SUCCESS
            }   
        }
    }

    /**
     * Gets a cashout operation from the database, according
     * to its uuid.
     */
    suspend fun cashoutGetFromUuid(opUuid: UUID): Cashout? = conn { conn ->
        val stmt = conn.prepareStatement("""
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
        stmt.oneOrNull {
            Cashout(
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
                creationTime = it.getLong("creation_time").microsToJavaInstant() ?: throw faultyTimestampByBank(),
                sellAtRatio = it.getInt("sell_at_ratio"),
                sellOutFee = TalerAmount(
                    value = it.getLong("sell_out_fee_val"),
                    frac = it.getInt("sell_out_fee_frac"),
                    getCurrency()
                ),
                subject = it.getString("subject"),
                tanChannel = when(it.getString("tan_channel")) {
                    "sms" -> TanChannel.sms
                    "email" -> TanChannel.email
                    "file" -> TanChannel.file
                    else -> throw internalServerError("TAN channel $this unsupported")
                },
                tanCode = it.getString("tan_code"),
                localTransaction = it.getLong("local_transaction"),
                tanConfirmationTime = when (val timestamp = it.getLong("tan_confirmation_time")) {
                    0L -> null
                    else -> timestamp.microsToJavaInstant() ?: throw faultyTimestampByBank()
                }
            )
        }
    }

    /**
     * Represents the database row related to one payment
     * that was requested by the Taler exchange.
     */
    data class TalerTransferFromDb(
        val timestamp: Long,
        val debitTxRowId: Long,
        val requestUid: HashCode,
        val amount: TalerAmount,
        val exchangeBaseUrl: String,
        val wtid: ShortHashCode,
        val creditAccount: String
    )

    /**
     * Holds the result of inserting a Taler transfer request
     * into the database.
     */
    data class TalerTransferCreationResult(
        val txResult: TalerTransferResult,
        /**
         * bank transaction that backs this Taler transfer request.
         * This is the debit transactions associated to the exchange
         * bank account.
         */
        val txRowId: Long? = null,
        val timestamp: TalerProtocolTimestamp? = null
    )
    /** TODO better doc
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
    suspend fun talerTransferCreate(
        req: TransferRequest,
        username: String,
        timestamp: Instant,
        acctSvcrRef: String = "not used",
        pmtInfId: String = "not used",
        endToEndId: String = "not used",
        ): TalerTransferCreationResult = conn { conn ->
        val subject = OutgoingTxMetadata(req.wtid, req.exchange_base_url).encode()
        val stmt = conn.prepareStatement("""
            SELECT
                out_debtor_not_found
                ,out_debtor_not_exchange
                ,out_creditor_not_found
                ,out_same_account
                ,out_both_exchanges
                ,out_request_uid_reuse
                ,out_exchange_balance_insufficient
                ,out_tx_row_id
                ,out_timestamp
              FROM
              taler_transfer (
                  ?, ?, ?,
                  (?,?)::taler_amount,
                  ?, ?, ?, ?, ?, ?, ?
                );
        """)

        stmt.setBytes(1, req.request_uid.raw)
        stmt.setBytes(2, req.wtid.raw)
        stmt.setString(3, subject)
        stmt.setLong(4, req.amount.value)
        stmt.setInt(5, req.amount.frac)
        stmt.setString(6, req.exchange_base_url.url)
        stmt.setString(7, req.credit_account.stripped)
        stmt.setString(8, username)
        stmt.setLong(9, timestamp.toDbMicros() ?: throw faultyTimestampByBank())
        stmt.setString(10, acctSvcrRef)
        stmt.setString(11, pmtInfId)
        stmt.setString(12, endToEndId)

        stmt.executeQuery().use {
            when {
                !it.next() ->
                    throw internalServerError("SQL function taler_transfer did not return anything.")
                it.getBoolean("out_debtor_not_found") ->
                    TalerTransferCreationResult(TalerTransferResult.NO_DEBITOR)
                it.getBoolean("out_debtor_not_exchange") ->
                    TalerTransferCreationResult(TalerTransferResult.NOT_EXCHANGE)
                it.getBoolean("out_creditor_not_found") ->
                    TalerTransferCreationResult(TalerTransferResult.NO_CREDITOR)
                it.getBoolean("out_same_account") ->
                    TalerTransferCreationResult(TalerTransferResult.SAME_ACCOUNT)
                it.getBoolean("out_both_exchanges") ->
                    TalerTransferCreationResult(TalerTransferResult.BOTH_EXCHANGE)
                it.getBoolean("out_exchange_balance_insufficient") ->
                    TalerTransferCreationResult(TalerTransferResult.BALANCE_INSUFFICIENT)
                it.getBoolean("out_request_uid_reuse") ->
                    TalerTransferCreationResult(TalerTransferResult.REQUEST_UID_REUSE)
                else -> {
                    TalerTransferCreationResult(
                        txResult = TalerTransferResult.SUCCESS,
                        txRowId = it.getLong("out_tx_row_id"),
                        timestamp = TalerProtocolTimestamp(
                            it.getLong("out_timestamp").microsToJavaInstant() ?: throw faultyTimestampByBank()
                        )
                    )
                }
            }
        }
    }

    data class TalerAddIncomingCreationResult(
        val txResult: TalerAddIncomingResult,
        val txRowId: Long? = null
    )

     /** TODO better doc
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
    suspend fun talerAddIncomingCreate(
        req: AddIncomingRequest,
        username: String,
        timestamp: Instant,
        acctSvcrRef: String = "not used",
        pmtInfId: String = "not used",
        endToEndId: String = "not used",
        ): TalerAddIncomingCreationResult = conn { conn ->
        val subject = IncomingTxMetadata(req.reserve_pub).encode()
        val stmt = conn.prepareStatement("""
            SELECT
                out_creditor_not_found
                ,out_creditor_not_exchange
                ,out_debtor_not_found
                ,out_same_account
                ,out_both_exchanges
                ,out_reserve_pub_reuse
                ,out_debitor_balance_insufficient
                ,out_tx_row_id
            FROM
            taler_add_incoming (
                ?, ?,
                (?,?)::taler_amount,
                ?, ?, ?, ?, ?, ?
                );
        """)

        stmt.setBytes(1, req.reserve_pub.raw)
        stmt.setString(2, subject)
        stmt.setLong(3, req.amount.value)
        stmt.setInt(4, req.amount.frac)
        stmt.setString(5, req.debit_account.stripped)
        stmt.setString(6, username)
        stmt.setLong(7, timestamp.toDbMicros() ?: throw faultyTimestampByBank())
        stmt.setString(8, acctSvcrRef)
        stmt.setString(9, pmtInfId)
        stmt.setString(10, endToEndId)

        stmt.executeQuery().use {
            when {
                !it.next() ->
                    throw internalServerError("SQL function taler_add_incoming did not return anything.")
                it.getBoolean("out_creditor_not_found") ->
                    TalerAddIncomingCreationResult(TalerAddIncomingResult.NO_CREDITOR)
                it.getBoolean("out_creditor_not_exchange") ->
                    TalerAddIncomingCreationResult(TalerAddIncomingResult.NOT_EXCHANGE)
                it.getBoolean("out_debtor_not_found") ->
                    TalerAddIncomingCreationResult(TalerAddIncomingResult.NO_DEBITOR)
                it.getBoolean("out_same_account") ->
                    TalerAddIncomingCreationResult(TalerAddIncomingResult.SAME_ACCOUNT)
                it.getBoolean("out_both_exchanges") ->
                    TalerAddIncomingCreationResult(TalerAddIncomingResult.BOTH_EXCHANGE)
                it.getBoolean("out_debitor_balance_insufficient") ->
                    TalerAddIncomingCreationResult(TalerAddIncomingResult.BALANCE_INSUFFICIENT)
                it.getBoolean("out_reserve_pub_reuse") ->
                    TalerAddIncomingCreationResult(TalerAddIncomingResult.RESERVE_PUB_REUSE)
                else -> {
                    TalerAddIncomingCreationResult(
                        txResult = TalerAddIncomingResult.SUCCESS,
                        txRowId = it.getLong("out_tx_row_id")
                    )
                }
            }
        }
    }
}

/** Result status of customer account deletion */
enum class CustomerDeletionResult {
    SUCCESS,
    CUSTOMER_NOT_FOUND,
    BALANCE_NOT_ZERO
}

/** Result status of bank transaction creation .*/
enum class BankTransactionResult {
    NO_CREDITOR,
    NO_DEBTOR,
    SAME_ACCOUNT,
    BALANCE_INSUFFICIENT,
    SUCCESS,
}

/** Result status of taler transfer transaction */
enum class TalerTransferResult {
    NO_DEBITOR,
    NOT_EXCHANGE,
    NO_CREDITOR,
    SAME_ACCOUNT,
    BOTH_EXCHANGE,
    REQUEST_UID_REUSE,
    BALANCE_INSUFFICIENT,
    SUCCESS
}

/** Result status of taler add incoming transaction */
enum class TalerAddIncomingResult {
    NO_DEBITOR,
    NOT_EXCHANGE,
    NO_CREDITOR,
    SAME_ACCOUNT,
    BOTH_EXCHANGE,
    RESERVE_PUB_REUSE,
    BALANCE_INSUFFICIENT,
    SUCCESS
}

/**
 * This type communicates the result of a database operation
 * to confirm one withdrawal operation.
 */
enum class WithdrawalConfirmationResult {
    SUCCESS,
    OP_NOT_FOUND,
    EXCHANGE_NOT_FOUND,
    BALANCE_INSUFFICIENT,

    /**
     * This state indicates that the withdrawal was already
     * confirmed BUT Kotlin did not detect it and still invoked
     * the SQL procedure to confirm the withdrawal.  This is
     * conflictual because only Kotlin is responsible to check
     * for idempotency, and this state witnesses a failure in
     * this regard.
     */
    CONFLICT
}

private class NotificationWatcher(private val pgSource: PGSimpleDataSource) {
    private class CountedSharedFlow(val flow: MutableSharedFlow<Long>, var count: Int)

    private val bankTxFlows = ConcurrentHashMap<Long, CountedSharedFlow>()
    private val outgoingTxFlows = ConcurrentHashMap<Long, CountedSharedFlow>()
    private val incomingTxFlows = ConcurrentHashMap<Long, CountedSharedFlow>()

    init {
        kotlin.concurrent.thread(isDaemon = true) { 
            runBlocking {
                while (true) {
                    try {
                        val conn = pgSource.pgConnection()
                        conn.execSQLUpdate("LISTEN bank_tx")
                        conn.execSQLUpdate("LISTEN outgoing_tx")
                        conn.execSQLUpdate("LISTEN incoming_tx")

                        while (true) {
                            conn.getNotifications(0) // Block until we receive at least one notification
                                .forEach {
                                if (it.name == "bank_tx") {
                                    val info = it.parameter.split(' ', limit = 4).map { it.toLong() }
                                    val debtorAccount = info[0];
                                    val creditorAccount = info[1];
                                    val debitRow = info[2];
                                    val creditRow = info[3];
                                    
                                    bankTxFlows[debtorAccount]?.run {
                                        flow.emit(debitRow)
                                        flow.emit(creditRow)
                                    }
                                    bankTxFlows[creditorAccount]?.run {
                                        flow.emit(debitRow)
                                        flow.emit(creditRow)
                                    }
                                } else {
                                    val info = it.parameter.split(' ', limit = 2).map { it.toLong() }
                                    val account = info[0];
                                    val row = info[1];
                                    if (it.name == "outgoing_tx") {
                                        outgoingTxFlows[account]?.run {
                                            flow.emit(row)
                                        }
                                    } else {
                                        incomingTxFlows[account]?.run {
                                            flow.emit(row)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logger.warn("notification_watcher failed: $e")
                    }
                }
            }
        }
    }

    private suspend fun listen(map: ConcurrentHashMap<Long, CountedSharedFlow>, account: Long, lambda: suspend (Flow<Long>) -> Unit) {
        // Register listener
        val flow = map.compute(account) { _, v ->
            val tmp = v ?: CountedSharedFlow(MutableSharedFlow(), 0);
            tmp.count++;
            tmp
        }!!.flow;

        try {
            lambda(flow)
        } finally {
            // Unregister listener
            map.compute(account) { _, v ->
                v!!;
                v.count--;
                if (v.count > 0) v else null
            }
        }
    } 

    suspend fun listenBank(account: Long, lambda: suspend (Flow<Long>) -> Unit) {
        listen(bankTxFlows, account, lambda)
    }

    suspend fun listenOutgoing(account: Long, lambda: suspend (Flow<Long>) -> Unit) {
        listen(outgoingTxFlows, account, lambda)
    }

    suspend fun listenIncoming(account: Long, lambda: suspend (Flow<Long>) -> Unit) {
        listen(incomingTxFlows, account, lambda)
    }
}