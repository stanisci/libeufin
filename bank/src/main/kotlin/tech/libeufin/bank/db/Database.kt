/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023 Taler Systems S.A.

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
import java.time.*
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import com.zaxxer.hikari.*
import tech.libeufin.util.*

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
internal fun faultyTimestampByBank() = internalServerError("Bank took overflowing timestamp")
internal fun faultyDurationByClient() = badRequest("Overflowing duration, please specify 'forever' instead.")

class Database(dbConfig: String, internal val bankCurrency: String, internal val fiatCurrency: String?): java.io.Closeable {
    val dbPool: HikariDataSource
    internal val notifWatcher: NotificationWatcher

    init {
        val pgSource = pgDataSource(dbConfig)
        val config = HikariConfig();
        config.dataSource = pgSource
        config.connectionInitSql = "SET search_path TO libeufin_bank;"
        config.validate()
        dbPool = HikariDataSource(config);
        notifWatcher = NotificationWatcher(pgSource)
    }

    val cashout = CashoutDAO(this)
    val withdrawal = WithdrawalDAO(this)
    val exchange = ExchangeDAO(this)
    val conversion = ConversionDAO(this)

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
                        FROM bank_transaction(?,'admin','bonus',(?,?)::taler_amount,?)
                    """).run {
                        setString(1, internalPaytoUri.canonical)
                        setLong(2, bonus.value)
                        setInt(3, bonus.frac)
                        setLong(4, Instant.now().toDbMicros() ?: throw faultyTimestampByBank())
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
                            CreditDebitInfo.debit
                        } else {
                            CreditDebitInfo.credit
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
                        CreditDebitInfo.debit 
                    } else {
                        CreditDebitInfo.credit
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
                        CreditDebitInfo.debit
                    } else {
                        CreditDebitInfo.credit
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
                FROM bank_transaction(?,?,?,(?,?)::taler_amount,?)
            """
            )
            stmt.setString(1, creditAccountPayto.canonical)
            stmt.setString(2, debitAccountUsername)
            stmt.setString(3, subject)
            stmt.setLong(4, amount.value)
            stmt.setInt(5, amount.frac)
            stmt.setLong(6, timestamp.toDbMicros() ?: throw faultyTimestampByBank())
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
                direction = TransactionDirection.valueOf(it.getString("direction")),
                bankAccountId = it.getLong("bank_account_id"),
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
    internal suspend fun <T> poolHistory(
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

/** Result status of withdrawal or cashout operation abortion */
enum class AbortResult {
    SUCCESS,
    NOT_FOUND,
    CONFIRMED
}