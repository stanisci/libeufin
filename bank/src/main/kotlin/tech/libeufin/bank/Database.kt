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
import java.io.File
import java.sql.*
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import com.zaxxer.hikari.*
import tech.libeufin.util.*

private const val DB_CTR_LIMIT = 1000000

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

fun <T> PreparedStatement.all(lambda: (ResultSet) -> T): List<T> {
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

class Database(dbConfig: String, private val bankCurrency: String, private val fiatCurrency: String?): java.io.Closeable {
    val dbPool: HikariDataSource
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

    suspend fun <R> conn(lambda: suspend (PgConnection) -> R): R {
        // Use a coroutine dispatcher that we can block as JDBC API is blocking
        return withContext(Dispatchers.IO) {
            val conn = dbPool.getConnection()
            conn.use{ it -> lambda(it.unwrap(PgConnection::class.java)) }
        }
    }

    // CUSTOMERS

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

    suspend fun customerChangePassword(customerName: String, passwordHash: String): Boolean = conn { conn ->
        val stmt = conn.prepareStatement("""
            UPDATE customers SET password_hash=? where login=?
        """)
        stmt.setString(1, passwordHash)
        stmt.setString(2, customerName)
        stmt.executeUpdateCheck()
    }

    suspend fun customerPasswordHashFromLogin(login: String): String? = conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT password_hash FROM customers WHERE login=?
        """)
        stmt.setString(1, login)
        stmt.oneOrNull { 
            it.getString(1)
        }
    }

    suspend fun customerLoginFromId(id: Long): String? = conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT login FROM customers WHERE customer_id=?
        """)
        stmt.setLong(1, id)
        stmt.oneOrNull { 
            it.getString(1)
        }
    }

    // BEARER TOKEN
    suspend fun bearerTokenCreate(
        login: String,
        content: ByteArray,
        creationTime: Instant,
        expirationTime: Instant,
        scope: TokenScope,
        isRefreshable: Boolean
    ): Boolean = conn { conn ->
        val bankCustomer = conn.prepareStatement("""
            SELECT customer_id FROM customers WHERE login=?
        """).run {
            setString(1, login)
            oneOrNull { it.getLong(1) }!!
        }
        val stmt = conn.prepareStatement("""
            INSERT INTO bearer_tokens (
                content,
                creation_time,
                expiration_time,
                scope,
                bank_customer,
                is_refreshable
            ) VALUES (?, ?, ?, ?::token_scope_enum, ?, ?)
        """)
        stmt.setBytes(1, content)
        stmt.setLong(2, creationTime.toDbMicros() ?: throw faultyTimestampByBank())
        stmt.setLong(3, expirationTime.toDbMicros() ?: throw faultyDurationByClient())
        stmt.setString(4, scope.name)
        stmt.setLong(5, bankCustomer)
        stmt.setBoolean(6, isRefreshable)
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
                scope = TokenScope.valueOf(it.getString("scope")),
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

    suspend fun accountCreate(
        login: String,
        password: String,
        name: String,
        email: String? = null,
        phone: String? = null,
        cashoutPayto: IbanPayTo? = null,
        internalPaytoUri: IbanPayTo,
        isPublic: Boolean,
        isTalerExchange: Boolean,
        maxDebt: TalerAmount,
        bonus: TalerAmount?
    ): CustomerCreationResult = conn { it ->
        it.transaction { conn ->
            val idempotent = conn.prepareStatement("""
                SELECT password_hash, name=?
                    AND email IS NOT DISTINCT FROM ?
                    AND phone IS NOT DISTINCT FROM ?
                    AND cashout_payto IS NOT DISTINCT FROM ?
                    AND internal_payto_uri=?
                    AND is_public=?
                    AND is_taler_exchange=?
                FROM customers 
                    JOIN bank_accounts
                        ON customer_id=owning_customer_id
                WHERE login=?
            """).run {
                setString(1, name)
                setString(2, email)
                setString(3, phone)
                setString(4, cashoutPayto?.canonical)
                setString(5, internalPaytoUri.canonical)
                setBoolean(6, isPublic)
                setBoolean(7, isTalerExchange)
                setString(8, login)
                oneOrNull { 
                    CryptoUtil.checkpw(password, it.getString(1)) && it.getBoolean(2)
                } 
            }
            if (idempotent != null) {
                if (idempotent) {
                    CustomerCreationResult.SUCCESS
                } else {
                    CustomerCreationResult.CONFLICT_LOGIN
                }
            } else {
                val customerId = conn.prepareStatement("""
                    INSERT INTO customers (
                        login
                        ,password_hash
                        ,name
                        ,email
                        ,phone
                        ,cashout_payto
                    ) VALUES (?, ?, ?, ?, ?, ?)
                        RETURNING customer_id
                """
                ).run {
                    setString(1, login)
                    setString(2, CryptoUtil.hashpw(password))
                    setString(3, name)
                    setString(4, email)
                    setString(5, phone)
                    setString(6, cashoutPayto?.canonical)
                    oneOrNull { it.getLong("customer_id") }!!
                }
            
                conn.prepareStatement("""
                    INSERT INTO bank_accounts(
                        internal_payto_uri
                        ,owning_customer_id
                        ,is_public
                        ,is_taler_exchange
                        ,max_debt
                    ) VALUES (?, ?, ?, ?, (?, ?)::taler_amount)
                """).run {
                    setString(1, internalPaytoUri.canonical)
                    setLong(2, customerId)
                    setBoolean(3, isPublic)
                    setBoolean(4, isTalerExchange)
                    setLong(5, maxDebt.value)
                    setInt(6, maxDebt.frac)
                    if (!executeUpdateViolation()) {
                        conn.rollback()
                        return@transaction CustomerCreationResult.CONFLICT_PAY_TO
                    }
                }
               
                if (bonus != null) {
                    conn.prepareStatement("""
                        SELECT out_balance_insufficient
                        FROM bank_transaction(?,'admin','bonus',(?,?)::taler_amount,?,?,?,?)
                    """).run {
                        setString(1, internalPaytoUri.canonical)
                        setLong(2, bonus.value)
                        setInt(3, bonus.frac)
                        setLong(4, Instant.now().toDbMicros() ?: throw faultyTimestampByBank())
                        setString(5, "not used") // ISO20022
                        setString(6, "not used") // ISO20022
                        setString(7, "not used") // ISO20022
                        executeQuery().use {
                            when {
                                !it.next() -> throw internalServerError("Bank transaction didn't properly return")
                                it.getBoolean("out_balance_insufficient") -> {
                                    conn.rollback()
                                    CustomerCreationResult.BALANCE_INSUFFICIENT
                                }
                                else -> CustomerCreationResult.SUCCESS
                            }
                        }
                    }
                } else {
                    CustomerCreationResult.SUCCESS
                }
            }
        }
    }

    suspend fun accountDataFromLogin(
        login: String
    ): AccountData? = conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
                name
                ,email
                ,phone
                ,cashout_payto
                ,internal_payto_uri
                ,(balance).val AS balance_val
                ,(balance).frac AS balance_frac
                ,has_debt
                ,(max_debt).val AS max_debt_val
                ,(max_debt).frac AS max_debt_frac
            FROM customers 
                JOIN  bank_accounts
                    ON customer_id=owning_customer_id
            WHERE login=?
        """)
        stmt.setString(1, login)
        stmt.oneOrNull {
            AccountData(
                name = it.getString("name"),
                contact_data = ChallengeContactData(
                    email = it.getString("email"),
                    phone = it.getString("phone")
                ),
                cashout_payto_uri = it.getString("cashout_payto")?.run(::IbanPayTo),
                payto_uri = IbanPayTo(it.getString("internal_payto_uri")),
                balance = Balance(
                    amount = TalerAmount(
                        it.getLong("balance_val"),
                        it.getInt("balance_frac"),
                        bankCurrency
                    ),
                    credit_debit_indicator =
                        if (it.getBoolean("has_debt")) {
                            CorebankCreditDebitInfo.debit
                        } else {
                            CorebankCreditDebitInfo.credit
                        }
                ),
                debit_threshold = TalerAmount(
                    value = it.getLong("max_debt_val"),
                    frac = it.getInt("max_debt_frac"),
                    bankCurrency
                )
            )
        }
    }

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
        cashoutPayto: IbanPayTo?,
        phoneNumber: String?,
        emailAddress: String?,
        isTalerExchange: Boolean?,
        debtLimit: TalerAmount?,
        isAdmin: Boolean
    ): CustomerPatchResult = conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
                out_not_found,
                out_legal_name_change,
                out_debt_limit_change
            FROM account_reconfig(?, ?, ?, ?, ?, ?, (?, ?)::taler_amount, ?)
        """)
        stmt.setString(1, login)
        stmt.setString(2, name)
        stmt.setString(3, phoneNumber)
        stmt.setString(4, emailAddress)
        stmt.setString(5, cashoutPayto?.canonical)
        if (isTalerExchange == null)
            stmt.setNull(6, Types.NULL)
        else stmt.setBoolean(6, isTalerExchange)
        if (debtLimit == null) {
            stmt.setNull(7, Types.NULL)
            stmt.setNull(8, Types.NULL)
        } else {
            stmt.setLong(7, debtLimit.value)
            stmt.setInt(8, debtLimit.frac)
        }
        stmt.setBoolean(9, isAdmin)
        stmt.executeQuery().use {
            when {
                !it.next() -> throw internalServerError("accountReconfig() returned nothing")
                it.getBoolean("out_not_found") -> CustomerPatchResult.ACCOUNT_NOT_FOUND
                it.getBoolean("out_legal_name_change") -> CustomerPatchResult.CONFLICT_LEGAL_NAME
                it.getBoolean("out_debt_limit_change") -> CustomerPatchResult.CONFLICT_DEBT_LIMIT
                else -> CustomerPatchResult.SUCCESS
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
                        currency = bankCurrency
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
                    currency = bankCurrency
                )
            )
        }
    }

    // BANK ACCOUNTS

    suspend fun bankAccountGetFromOwnerId(ownerId: Long): BankAccount? = conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
            internal_payto_uri
             ,owning_customer_id
             ,is_public
             ,is_taler_exchange
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
                    bankCurrency
                ),
                owningCustomerId = it.getLong("owning_customer_id"),
                hasDebt = it.getBoolean("has_debt"),
                isTalerExchange = it.getBoolean("is_taler_exchange"),
                isPublic = it.getBoolean("is_public"),
                maxDebt = TalerAmount(
                    value = it.getLong("max_debt_val"),
                    frac = it.getInt("max_debt_frac"),
                    bankCurrency
                ),
                bankAccountId = it.getLong("bank_account_id")
            )
        }
    }

    suspend fun bankAccountGetFromCustomerLogin(login: String): BankAccount? = conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
             bank_account_id
             ,owning_customer_id
             ,internal_payto_uri
             ,is_public
             ,is_taler_exchange
             ,(balance).val AS balance_val
             ,(balance).frac AS balance_frac
             ,has_debt
             ,(max_debt).val AS max_debt_val
             ,(max_debt).frac AS max_debt_frac
            FROM bank_accounts
                JOIN customers 
                ON customer_id=owning_customer_id
                WHERE login=?
        """)
        stmt.setString(1, login)

        stmt.oneOrNull {
            BankAccount(
                internalPaytoUri = IbanPayTo(it.getString("internal_payto_uri")),
                balance = TalerAmount(
                    it.getLong("balance_val"),
                    it.getInt("balance_frac"),
                    bankCurrency
                ),
                owningCustomerId = it.getLong("owning_customer_id"),
                hasDebt = it.getBoolean("has_debt"),
                isTalerExchange = it.getBoolean("is_taler_exchange"),
                maxDebt = TalerAmount(
                    value = it.getLong("max_debt_val"),
                    frac = it.getInt("max_debt_frac"),
                    bankCurrency
                ),
                isPublic = it.getBoolean("is_public"),
                bankAccountId = it.getLong("bank_account_id")
            )
        }
    }

    // BANK ACCOUNT TRANSACTIONS

    private fun handleExchangeTx(
        conn: PgConnection,
        subject: String,
        creditorAccountId: Long,
        debtorAccountId: Long,
        it: ResultSet
    ) {
        val metadata = TxMetadata.parse(subject)
        if (it.getBoolean("out_creditor_is_exchange")) {
            val rowId = it.getLong("out_credit_row_id")
            if (metadata is IncomingTxMetadata) {
                val stmt = conn.prepareStatement("CALL register_incoming(?, ?, ?)")
                stmt.setBytes(1, metadata.reservePub.raw)
                stmt.setLong(2, rowId)
                stmt.setLong(3, creditorAccountId)
                stmt.executeUpdate()
            } else {
                // TODO bounce
                logger.warn("exchange account $creditorAccountId received a transaction $rowId with malformed metadata, will bounce in future version")
            }
        }
        if (it.getBoolean("out_debtor_is_exchange")) {
            val rowId = it.getLong("out_debit_row_id")
            if (metadata is OutgoingTxMetadata) {
                val stmt = conn.prepareStatement("CALL register_outgoing(NULL, ?, ?, ?)")
                stmt.setBytes(1, metadata.wtid.raw)
                stmt.setString(2, metadata.exchangeBaseUrl.url)
                stmt.setLong(3, rowId)
                stmt.executeUpdate()
            } else {
                logger.warn("exchange account $debtorAccountId sent a transaction $rowId with malformed metadata")
            }
        }
    }

    suspend fun bankTransaction(
        creditAccountPayto: IbanPayTo,
        debitAccountUsername: String,
        subject: String,
        amount: TalerAmount,
        timestamp: Instant,
        accountServicerReference: String = "not used", // ISO20022
        endToEndId: String = "not used", // ISO20022
        paymentInformationId: String = "not used" // ISO20022
    ): BankTransactionResult = conn { conn ->
        conn.transaction {
            val stmt = conn.prepareStatement("""
                SELECT 
                    out_creditor_not_found 
                    ,out_debtor_not_found
                    ,out_same_account
                    ,out_balance_insufficient
                    ,out_credit_bank_account_id
                    ,out_debit_bank_account_id
                    ,out_credit_row_id
                    ,out_debit_row_id
                    ,out_creditor_is_exchange 
                    ,out_debtor_is_exchange
                FROM bank_transaction(?,?,?,(?,?)::taler_amount,?,?,?,?)
            """
            )
            stmt.setString(1, creditAccountPayto.canonical)
            stmt.setString(2, debitAccountUsername)
            stmt.setString(3, subject)
            stmt.setLong(4, amount.value)
            stmt.setInt(5, amount.frac)
            stmt.setLong(6, timestamp.toDbMicros() ?: throw faultyTimestampByBank())
            stmt.setString(7, accountServicerReference)
            stmt.setString(8, paymentInformationId)
            stmt.setString(9, endToEndId)
            stmt.executeQuery().use {
                when {
                    !it.next() -> throw internalServerError("Bank transaction didn't properly return")
                    it.getBoolean("out_creditor_not_found") -> BankTransactionResult.NO_CREDITOR
                    it.getBoolean("out_debtor_not_found") -> BankTransactionResult.NO_DEBTOR
                    it.getBoolean("out_same_account") -> BankTransactionResult.SAME_ACCOUNT
                    it.getBoolean("out_balance_insufficient") -> BankTransactionResult.BALANCE_INSUFFICIENT
                    else -> {
                        handleExchangeTx(conn, subject, it.getLong("out_credit_bank_account_id"), it.getLong("out_debit_bank_account_id"), it)
                        BankTransactionResult.SUCCESS
                    }
                }
            }
        }
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
              ,bank_transaction_id
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
                    bankCurrency
                ),
                accountServicerReference = it.getString("account_servicer_reference"),
                endToEndId = it.getString("end_to_end_id"),
                direction = TransactionDirection.valueOf(it.getString("direction")),
                bankAccountId = it.getLong("bank_account_id"),
                paymentInformationId = it.getString("payment_information_id"),
                subject = it.getString("subject"),
                transactionDate = it.getLong("transaction_date").microsToJavaInstant() ?: throw faultyTimestampByBank(),
                dbRowId = it.getLong("bank_transaction_id")
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
        val query = """
            $query
            WHERE bank_account_id=? AND
            bank_transaction_id ${if (backward) '<' else '>'} ?
            ORDER BY bank_transaction_id ${if (backward) "DESC" else "ASC"}
            LIMIT ?
        """
      
        suspend fun load(amount: Int): List<T> = conn { conn ->
            conn.prepareStatement(query).use { stmt ->
                stmt.setLong(1, bankAccountId)
                stmt.setLong(2, params.start)
                stmt.setInt(3, amount)
                stmt.all { map(it) }
            }
        }

        // TODO do we want to handle polling when going backward and there is no transactions yet ?
        // When going backward there is always at least one transaction or none
        if (!backward && params.poll_ms > 0) {
            var history = listOf<T>()
            notifWatcher.(listen)(bankAccountId) { flow ->
                coroutineScope {
                    // Start buffering notification before loading transactions to not miss any
                    val polling = launch {
                        withTimeoutOrNull(params.poll_ms) {
                            flow.first { it > params.start } // Always forward so >
                        }
                    }    
                    // Initial loading
                    history = load(nbTx)
                    // Long polling if we found no transactions
                    if (history.isEmpty()) {
                        polling.join()
                        history = load(nbTx)
                    } else {
                        polling.cancel()
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
                    bankCurrency
                ),
                subject = it.getString("subject"),
                direction = TransactionDirection.valueOf(it.getString("direction"))
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
                    bankCurrency
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
                    bankCurrency
                ),
                credit_account = IbanPayTo(it.getString("creditor_payto_uri")),
                wtid = ShortHashCode(it.getBytes("wtid")),
                exchange_base_url = ExchangeUrl(it.getString("exchange_base_url"))
            )
        }
    }

    // WITHDRAWALS
    suspend fun talerWithdrawalCreate(
        walletAccountUsername: String,
        opUUID: UUID,
        amount: TalerAmount
    ): WithdrawalCreationResult = conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
                out_account_not_found,
                out_account_is_exchange,
                out_balance_insufficient
            FROM create_taler_withdrawal(?, ?, (?,?)::taler_amount);
        """)
        stmt.setString(1, walletAccountUsername)
        stmt.setObject(2, opUUID)
        stmt.setLong(3, amount.value)
        stmt.setInt(4, amount.frac)
        stmt.executeQuery().use {
            when {
                !it.next() ->
                    throw internalServerError("No result from DB procedure create_taler_withdrawal")
                it.getBoolean("out_account_not_found") -> WithdrawalCreationResult.ACCOUNT_NOT_FOUND
                it.getBoolean("out_account_is_exchange") -> WithdrawalCreationResult.ACCOUNT_IS_EXCHANGE
                it.getBoolean("out_balance_insufficient") -> WithdrawalCreationResult.BALANCE_INSUFFICIENT
                else -> WithdrawalCreationResult.SUCCESS
            }
        }
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
                    bankCurrency
                ),
                selectionDone = it.getBoolean("selection_done"),
                selectedExchangePayto = it.getString("selected_exchange_payto")?.run(::IbanPayTo),
                walletBankAccount = it.getLong("wallet_bank_account"),
                confirmationDone = it.getBoolean("confirmation_done"),
                aborted = it.getBoolean("aborted"),
                reservePub = it.getBytes("reserve_pub")?.run(::EddsaPublicKey),
                withdrawalUuid = it.getObject("withdrawal_uuid") as UUID
            )
        }
    }

    /**
     * Aborts one Taler withdrawal, only if it wasn't previously
     * confirmed.  It returns false if the UPDATE didn't succeed.
     */
    suspend fun talerWithdrawalAbort(opUUID: UUID): AbortResult = conn { conn ->
        val stmt = conn.prepareStatement("""
            UPDATE taler_withdrawal_operations
            SET aborted = NOT confirmation_done
            WHERE withdrawal_uuid=?
            RETURNING confirmation_done
        """
        )
        stmt.setObject(1, opUUID)
        when (stmt.oneOrNull { it.getBoolean(1) }) {
            null -> AbortResult.NOT_FOUND
            true -> AbortResult.CONFIRMED
            false -> AbortResult.SUCCESS
        }
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
        reservePub: EddsaPublicKey
    ): Pair<WithdrawalSelectionResult, Boolean> = conn { conn ->
        val subject = IncomingTxMetadata(reservePub).encode()
        val stmt = conn.prepareStatement("""
            SELECT
                out_no_op,
                out_already_selected,
                out_reserve_pub_reuse,
                out_account_not_found,
                out_account_is_not_exchange,
                out_confirmation_done
            FROM select_taler_withdrawal(?, ?, ?, ?);
        """
        )
        stmt.setObject(1, opUuid)
        stmt.setBytes(2, reservePub.raw)
        stmt.setString(3, subject)
        stmt.setString(4, exchangePayto.canonical)
        stmt.executeQuery().use {
            val status = when {
                !it.next() ->
                    throw internalServerError("No result from DB procedure select_taler_withdrawal")
                it.getBoolean("out_no_op") -> WithdrawalSelectionResult.OP_NOT_FOUND
                it.getBoolean("out_already_selected") -> WithdrawalSelectionResult.ALREADY_SELECTED
                it.getBoolean("out_reserve_pub_reuse") -> WithdrawalSelectionResult.RESERVE_PUB_REUSE
                it.getBoolean("out_account_not_found") -> WithdrawalSelectionResult.ACCOUNT_NOT_FOUND
                it.getBoolean("out_account_is_not_exchange") -> WithdrawalSelectionResult.ACCOUNT_IS_NOT_EXCHANGE
                else -> WithdrawalSelectionResult.SUCCESS
            }
            Pair(status, it.getBoolean("out_confirmation_done"))
        }
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
              out_not_selected,
              out_aborted
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
                it.getBoolean("out_not_selected") -> WithdrawalConfirmationResult.NOT_SELECTED
                it.getBoolean("out_aborted") -> WithdrawalConfirmationResult.ABORTED
                else -> WithdrawalConfirmationResult.SUCCESS
            }
        }
    }

    /**
     * Creates a cashout operation in the database.
     */
    suspend fun cashoutCreate(
        accountUsername: String,
        cashoutUuid: UUID,
        amountDebit: TalerAmount,
        amountCredit: TalerAmount,
        subject: String,
        creationTime: Instant,
        tanChannel: TanChannel,
        tanCode: String,
    ): Pair<CashoutCreationResult, String?> = conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
                out_bad_conversion,
                out_account_not_found,
                out_account_is_exchange,
                out_missing_tan_info,
                out_balance_insufficient,
                out_tan_info
            FROM cashout_create(?, ?, (?,?)::taler_amount, (?,?)::taler_amount, ?, ?, ?::tan_enum, ?);
        """)
        stmt.setString(1, accountUsername)
        stmt.setObject(2, cashoutUuid)
        stmt.setLong(3, amountDebit.value)
        stmt.setInt(4, amountDebit.frac)
        stmt.setLong(5, amountCredit.value)
        stmt.setInt(6, amountCredit.frac)
        stmt.setString(7, subject)
        stmt.setLong(8, creationTime.toDbMicros() ?: throw faultyTimestampByBank())
        stmt.setString(9, tanChannel.name)
        stmt.setString(10, tanCode)
        stmt.executeQuery().use {
            var info: String? = null;
            val status = when {
                !it.next() ->
                    throw internalServerError("No result from DB procedure cashout_create")
                it.getBoolean("out_bad_conversion") -> CashoutCreationResult.BAD_CONVERSION
                it.getBoolean("out_account_not_found") -> CashoutCreationResult.ACCOUNT_NOT_FOUND
                it.getBoolean("out_account_is_exchange") -> CashoutCreationResult.ACCOUNT_IS_EXCHANGE
                it.getBoolean("out_missing_tan_info") -> CashoutCreationResult.MISSING_TAN_INFO
                it.getBoolean("out_balance_insufficient") -> CashoutCreationResult.BALANCE_INSUFFICIENT
                else -> {
                    info = it.getString("out_tan_info")
                    CashoutCreationResult.SUCCESS
                }
            }
            Pair(status, info)
        }
    }

    suspend fun cashoutAbort(opUUID: UUID): AbortResult = conn { conn ->
        val stmt = conn.prepareStatement("""
            UPDATE cashout_operations
            SET aborted = tan_confirmation_time IS NULL
            WHERE cashout_uuid=?
            RETURNING tan_confirmation_time IS NOT NULL
        """)
        stmt.setObject(1, opUUID)
        when (stmt.oneOrNull { it.getBoolean(1) }) {
            null -> AbortResult.NOT_FOUND
            true -> AbortResult.CONFIRMED
            false -> AbortResult.SUCCESS
        }
    }

    suspend fun cashoutConfirm(
        opUuid: UUID,
        tanCode: String,
        timestamp: Instant,
        accountServicerReference: String = "NOT-USED",
        endToEndId: String = "NOT-USED",
        paymentInfId: String = "NOT-USED"
    ): CashoutConfirmationResult = conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
                out_no_op,
                out_bad_code,
                out_balance_insufficient,
                out_aborted
            FROM cashout_confirm(?, ?, ?, ?, ?, ?);
        """)
        stmt.setObject(1, opUuid)
        stmt.setString(2, tanCode)
        stmt.setLong(3, timestamp.toDbMicros() ?: throw faultyTimestampByBank())
        stmt.setString(4, accountServicerReference)
        stmt.setString(5, endToEndId)
        stmt.setString(6, paymentInfId)
        stmt.executeQuery().use {
            when {
                !it.next() ->
                    throw internalServerError("No result from DB procedure cashout_create")
                it.getBoolean("out_no_op") -> CashoutConfirmationResult.OP_NOT_FOUND
                it.getBoolean("out_bad_code") -> CashoutConfirmationResult.BAD_TAN_CODE
                it.getBoolean("out_balance_insufficient") -> CashoutConfirmationResult.BALANCE_INSUFFICIENT
                it.getBoolean("out_aborted") -> CashoutConfirmationResult.ABORTED
                else -> CashoutConfirmationResult.SUCCESS
            }
        }
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
                    bankCurrency
                ),
                amountCredit = TalerAmount(
                    value = it.getLong("amount_credit_val"),
                    frac = it.getInt("amount_credit_frac"),
                    bankCurrency
                ),
                bankAccount = it.getLong("bank_account"),
                buyAtRatio = it.getInt("buy_at_ratio"),
                buyInFee = TalerAmount(
                    value = it.getLong("buy_in_fee_val"),
                    frac = it.getInt("buy_in_fee_frac"),
                    bankCurrency
                ),
                credit_payto_uri = it.getString("credit_payto_uri"),
                cashoutCurrency = it.getString("cashout_currency"),
                cashoutUuid = opUuid,
                creationTime = it.getLong("creation_time").microsToJavaInstant() ?: throw faultyTimestampByBank(),
                sellAtRatio = it.getInt("sell_at_ratio"),
                sellOutFee = TalerAmount(
                    value = it.getLong("sell_out_fee_val"),
                    frac = it.getInt("sell_out_fee_frac"),
                    bankCurrency
                ),
                subject = it.getString("subject"),
                tanChannel = TanChannel.valueOf(it.getString("tan_channel")),
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
        stmt.setString(7, req.credit_account.canonical)
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
        stmt.setString(5, req.debit_account.canonical)
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

    suspend fun monitor(
        params: MonitorParams
    ): MonitorResponse = conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
                cashin_count
                ,(cashin_volume_in_fiat).val as cashin_volume_in_fiat_val
                ,(cashin_volume_in_fiat).frac as cashin_volume_in_fiat_frac
                ,cashout_count
                ,(cashout_volume_in_fiat).val as cashout_volume_in_fiat_val
                ,(cashout_volume_in_fiat).frac as cashout_volume_in_fiat_frac
                ,internal_taler_payments_count
                ,(internal_taler_payments_volume).val as internal_taler_payments_volume_val
                ,(internal_taler_payments_volume).frac as internal_taler_payments_volume_frac
            FROM stats_get_frame(now()::timestamp, ?::stat_timeframe_enum, ?)
        """)
        stmt.setString(1, params.timeframe.name)
        if (params.which != null) {
            stmt.setInt(2, params.which)
        } else {
            stmt.setNull(2, java.sql.Types.INTEGER)
        }
        stmt.oneOrNull {
            fiatCurrency?.run {
                MonitorWithCashout(
                    cashinCount = it.getLong("cashin_count"),
                    cashinExternalVolume = TalerAmount(
                        value = it.getLong("cashin_volume_in_fiat_val"),
                        frac = it.getInt("cashin_volume_in_fiat_frac"),
                        currency = this
                    ),
                    cashoutCount = it.getLong("cashout_count"),
                    cashoutExternalVolume = TalerAmount(
                        value = it.getLong("cashout_volume_in_fiat_val"),
                        frac = it.getInt("cashout_volume_in_fiat_frac"),
                        currency = this
                    ),
                    talerPayoutCount = it.getLong("internal_taler_payments_count"),
                    talerPayoutInternalVolume = TalerAmount(
                        value = it.getLong("internal_taler_payments_volume_val"),
                        frac = it.getInt("internal_taler_payments_volume_frac"),
                        currency = bankCurrency
                    )
                )
            } ?:  MonitorJustPayouts(
                talerPayoutCount = it.getLong("internal_taler_payments_count"),
                talerPayoutInternalVolume = TalerAmount(
                    value = it.getLong("internal_taler_payments_volume_val"),
                    frac = it.getInt("internal_taler_payments_volume_frac"),
                    currency = bankCurrency
                )
            )
           
        } ?: throw internalServerError("No result from DB procedure stats_get_frame")
    }

    suspend fun conversionUpdateConfig(cfg: ConversionInfo) = conn {
        it.transaction { conn -> 
            var stmt = conn.prepareStatement("CALL config_set_amount(?, (?, ?)::taler_amount)")
            for ((name, amount) in listOf(
                Pair("buy_ratio", cfg.buy_ratio),
                Pair("buy_fee", cfg.buy_fee),
                Pair("sell_ratio", cfg.sell_ratio),
                Pair("sell_fee", cfg.sell_fee),
            )) {
                stmt.setString(1, name)
                stmt.setLong(2, amount.value)
                stmt.setInt(3, amount.frac)
                stmt.executeUpdate()
            }
            for ((name, amount) in listOf(
                Pair("buy_tiny_amount", cfg.buy_tiny_amount),
                Pair("buy_min_amount", cfg.buy_min_amount),
                Pair("sell_tiny_amount", cfg.sell_tiny_amount),
                Pair("sell_min_amount", cfg.sell_min_amount),
            )) {
                stmt.setString(1, name)
                stmt.setLong(2, amount.value)
                stmt.setInt(3, amount.frac)
                stmt.executeUpdate()
            }
            stmt = conn.prepareStatement("CALL config_set_rounding_mode(?, ?::rounding_mode)")
            for ((name, value) in listOf(
                Pair("buy_rounding_mode", cfg.buy_rounding_mode),
                Pair("sell_rounding_mode", cfg.sell_rounding_mode)
            )) {
                stmt.setString(1, name)
                stmt.setString(2, value.name)
                stmt.executeUpdate()
            }
        }
    }

    private suspend fun conversionTo(amount: TalerAmount, name: String, currency: String): TalerAmount? = conn { conn ->
        val stmt = conn.prepareStatement("SELECT too_small, (to_amount).val AS amount_val, (to_amount).frac AS amount_frac FROM conversion_to((?, ?)::taler_amount, ?)")
        stmt.setLong(1, amount.value)
        stmt.setInt(2, amount.frac)
        stmt.setString(3, name)
        stmt.executeQuery().use {
            it.next()
            if (!it.getBoolean("too_small")) {
                TalerAmount(
                    value = it.getLong("amount_val"),
                    frac = it.getInt("amount_frac"),
                    currency = currency
                )
            } else {
                null
            }
        }
    }

    suspend fun conversionInternalToFiat(amount: TalerAmount): TalerAmount? = conversionTo(amount, "sell", fiatCurrency!!)
    suspend fun conversionFiatToInternal(amount: TalerAmount): TalerAmount? = conversionTo(amount, "buy", bankCurrency)

}

/** Result status of customer account creation */
enum class CustomerCreationResult {
    SUCCESS,
    CONFLICT_LOGIN,
    CONFLICT_PAY_TO,
    BALANCE_INSUFFICIENT,
}

/** Result status of customer account patch */
enum class CustomerPatchResult {
    ACCOUNT_NOT_FOUND,
    CONFLICT_LEGAL_NAME,
    CONFLICT_DEBT_LIMIT,
    SUCCESS
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
    BOTH_EXCHANGE,
    RESERVE_PUB_REUSE,
    BALANCE_INSUFFICIENT,
    SUCCESS
}

/** Result status of withdrawal operation creation */
enum class WithdrawalCreationResult {
    SUCCESS,
    ACCOUNT_NOT_FOUND,
    ACCOUNT_IS_EXCHANGE,
    BALANCE_INSUFFICIENT
}

/** Result status of withdrawal operation selection */
enum class WithdrawalSelectionResult {
    SUCCESS,
    OP_NOT_FOUND,
    ALREADY_SELECTED,
    RESERVE_PUB_REUSE,
    ACCOUNT_NOT_FOUND,
    ACCOUNT_IS_NOT_EXCHANGE
}

/** Result status of withdrawal operation confirmation */
enum class WithdrawalConfirmationResult {
    SUCCESS,
    OP_NOT_FOUND,
    EXCHANGE_NOT_FOUND,
    BALANCE_INSUFFICIENT,
    NOT_SELECTED,
    ABORTED
}

/** Result status of cashout operation creation */
enum class CashoutCreationResult {
    SUCCESS,
    BAD_CONVERSION,
    ACCOUNT_NOT_FOUND,
    ACCOUNT_IS_EXCHANGE,
    MISSING_TAN_INFO,
    BALANCE_INSUFFICIENT
}

/** Result status of cashout operation confirmation */
enum class CashoutConfirmationResult {
    SUCCESS,
    OP_NOT_FOUND,
    BAD_TAN_CODE,
    BALANCE_INSUFFICIENT,
    ABORTED
}

/** Result status of withdrawal or cashout operation abortion */
enum class AbortResult {
    SUCCESS,
    NOT_FOUND,
    CONFIRMED
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