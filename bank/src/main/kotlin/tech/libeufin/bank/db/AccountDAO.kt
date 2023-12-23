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

import tech.libeufin.util.*
import java.time.*
import java.sql.Types

/** Data access logic for accounts */
class AccountDAO(private val db: Database) {
    /** Result status of account creation */
    enum class AccountCreationResult {
        Success,
        LoginReuse,
        PayToReuse,
        BonusBalanceInsufficient,
    }

    /** Create new account */
    suspend fun create(
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
        bonus: TalerAmount,
        // Whether to check [internalPaytoUri] for idempotency
        checkPaytoIdempotent: Boolean
    ): AccountCreationResult = db.serializable { it ->
        val now = Instant.now().toDbMicros() ?: throw faultyTimestampByBank();
        it.transaction { conn ->
            val idempotent = conn.prepareStatement("""
                SELECT password_hash, name=?
                    AND email IS NOT DISTINCT FROM ?
                    AND phone IS NOT DISTINCT FROM ?
                    AND cashout_payto IS NOT DISTINCT FROM ?
                    AND (NOT ? OR internal_payto_uri=?)
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
                setBoolean(5, checkPaytoIdempotent)
                setString(6, internalPaytoUri.canonical)
                setBoolean(7, isPublic)
                setBoolean(8, isTalerExchange)
                setString(9, login)
                oneOrNull { 
                    CryptoUtil.checkpw(password, it.getString(1)) && it.getBoolean(2)
                } 
            }
            if (idempotent != null) {
                if (idempotent) {
                    AccountCreationResult.Success
                } else {
                    AccountCreationResult.LoginReuse
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
                        ,tan_channel
                    ) VALUES (?, ?, ?, ?, ?, ?, NULL)
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
                    INSERT INTO iban_history(
                        iban
                        ,creation_time
                    ) VALUES (?, ?)
                """).run {
                    setString(1, internalPaytoUri.iban)
                    setLong(2, now)
                    if (!executeUpdateViolation()) {
                        conn.rollback()
                        return@transaction AccountCreationResult.PayToReuse
                    }
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
                        return@transaction AccountCreationResult.PayToReuse
                    }
                }
                
                if (bonus.value != 0L || bonus.frac != 0) {
                    conn.prepareStatement("""
                        SELECT out_balance_insufficient
                        FROM bank_transaction(?,'admin','bonus',(?,?)::taler_amount,?)
                    """).run {
                        setString(1, internalPaytoUri.canonical)
                        setLong(2, bonus.value)
                        setInt(3, bonus.frac)
                        setLong(4, now)
                        executeQuery().use {
                            when {
                                !it.next() -> throw internalServerError("Bank transaction didn't properly return")
                                it.getBoolean("out_balance_insufficient") -> {
                                    conn.rollback()
                                    AccountCreationResult.BonusBalanceInsufficient
                                }
                                else -> AccountCreationResult.Success
                            }
                        }
                    }
                } else {
                    AccountCreationResult.Success
                }
            }
        }
    }

    /** Result status of account deletion */
    enum class AccountDeletionResult {
        Success,
        UnknownAccount,
        BalanceNotZero
    }

    /** Delete account [login] */
    suspend fun delete(login: String): AccountDeletionResult = db.serializable { conn ->
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
                it.getBoolean("out_nx_customer") -> AccountDeletionResult.UnknownAccount
                it.getBoolean("out_balance_not_zero") -> AccountDeletionResult.BalanceNotZero
                else -> AccountDeletionResult.Success
            }
        }
    }

    /** Result status of customer account patch */
    enum class AccountPatchResult {
        UnknownAccount,
        NonAdminName,
        NonAdminCashout,
        NonAdminDebtLimit,
        NonAdminContact,
        MissingTanInfo,
        TanRequired,
        Success
    }

    /** Change account [login] informations */
    suspend fun reconfig(
        login: String,
        name: String?,
        cashoutPayto: Option<IbanPayTo?>,
        phone: Option<String?>,
        email: Option<String?>,
        tan_channel: Option<TanChannel?>,
        isPublic: Boolean?,
        debtLimit: TalerAmount?,
        isAdmin: Boolean,
        is2fa: Boolean,
        allowEditName: Boolean,
        allowEditCashout: Boolean,
    ): AccountPatchResult = db.serializable { it.transaction { conn ->
        val checkName = !isAdmin && !allowEditName && name != null
        val checkCashout = !isAdmin && !allowEditCashout && cashoutPayto.isSome()
        val checkDebtLimit = !isAdmin && debtLimit != null
        val checkPhone = !isAdmin && phone.isSome()
        val checkEmail = !isAdmin && email.isSome()

        // Get user ID and check reconfig rights
        val customer_id = conn.prepareStatement("""
            SELECT
                customer_id
                ,(${ if (checkName) "name != ?" else "false" }) as name_change
                ,(${ if (checkCashout) "cashout_payto IS DISTINCT FROM ?" else "false" }) as cashout_change
                ,(${ if (checkDebtLimit) "max_debt != (?, ?)::taler_amount" else "false" }) as debt_limit_change
                ,(${ if (checkPhone) "phone IS DISTINCT FROM ?" else "false" }) as phone_change
                ,(${ if (checkEmail) "email IS DISTINCT FROM ?" else "false" }) as email_change
                ,(${ when (tan_channel.get()) {
                    null -> "false"
                    TanChannel.sms -> if (phone.get() != null) "false" else "phone IS NULL"
                    TanChannel.email -> if (email.get() != null) "false" else "email IS NULL"
                }}) as missing_tan_info
                ,(tan_channel IS NOT NULL) as tan_required
            FROM customers
                JOIN bank_accounts 
                ON customer_id=owning_customer_id
            WHERE login=?
        """).run {
            var idx = 1;
            if (checkName) {
                setString(idx, name); idx++
            }
            if (checkCashout) {
                setString(idx, cashoutPayto.get()?.canonical); idx++
            }
            if (checkDebtLimit) {
                setLong(idx, debtLimit!!.value); idx++
                setInt(idx, debtLimit.frac); idx++
            }
            if (checkPhone) {
                setString(idx, phone.get()); idx++
            }
            if (checkEmail) {
                setString(idx, email.get()); idx++
            }
            setString(idx, login)
            executeQuery().use {
                when {
                    !it.next() -> return@transaction AccountPatchResult.UnknownAccount
                    it.getBoolean("name_change") -> return@transaction AccountPatchResult.NonAdminName
                    it.getBoolean("cashout_change") -> return@transaction AccountPatchResult.NonAdminCashout
                    it.getBoolean("debt_limit_change") -> return@transaction AccountPatchResult.NonAdminDebtLimit
                    it.getBoolean("phone_change") -> return@transaction AccountPatchResult.NonAdminContact
                    it.getBoolean("email_change") -> return@transaction AccountPatchResult.NonAdminContact
                    it.getBoolean("missing_tan_info") -> return@transaction AccountPatchResult.MissingTanInfo
                    it.getBoolean("tan_required") && !is2fa -> return@transaction AccountPatchResult.TanRequired
                    else -> it.getLong("customer_id")
                }
            }
        }

        // Update bank info
        conn.dynamicUpdate(
            "bank_accounts",
            sequence {
                if (isPublic != null) yield("is_public=?")
                if (debtLimit != null) yield("max_debt=(?, ?)::taler_amount")
            },
            "WHERE owning_customer_id = ?",
            sequence {
                isPublic?.let { yield(it) }
                debtLimit?.let { yield(it.value); yield(it.frac) }
                yield(customer_id)
            }
        )

        // Update customer info
        conn.dynamicUpdate(
            "customers",
            sequence {
                cashoutPayto.some { yield("cashout_payto=?") }
                phone.some { yield("phone=?") }
                email.some { yield("email=?") }
                tan_channel.some { yield("tan_channel=?::tan_enum") }
                name?.let { yield("name=?") }
            },
            "WHERE customer_id = ?",
            sequence {
                cashoutPayto.some { yield(it?.canonical) }
                phone.some { yield(it) }
                email.some { yield(it) }
                tan_channel.some { yield(it?.name) }
                name?.let { yield(it) }
                yield(customer_id)
            }
        )

        AccountPatchResult.Success
    }}


    /** Result status of customer account auth patch */
    enum class AccountPatchAuthResult {
        UnknownAccount,
        OldPasswordMismatch,
        Success
    }

    /** Change account [login] password to [newPw] if current match [oldPw] */
    suspend fun reconfigPassword(login: String, newPw: String, oldPw: String?): AccountPatchAuthResult = db.serializable {
        it.transaction { conn ->
            val currentPwh = conn.prepareStatement("""
                SELECT password_hash FROM customers WHERE login=?
            """).run {
                setString(1, login)
                oneOrNull { it.getString(1) }
            }
            if (currentPwh == null) {
                AccountPatchAuthResult.UnknownAccount
            } else if (oldPw != null && !CryptoUtil.checkpw(oldPw, currentPwh)) {
                AccountPatchAuthResult.OldPasswordMismatch
            } else {
                val stmt = conn.prepareStatement("""
                    UPDATE customers SET password_hash=? where login=?
                """)
                stmt.setString(1, CryptoUtil.hashpw(newPw))
                stmt.setString(2, login)
                stmt.executeUpdateCheck()
                AccountPatchAuthResult.Success
            }
        }
    }

    /** Get password hash of account [login] */
    suspend fun passwordHash(login: String): String? = db.conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT password_hash FROM customers WHERE login=?
        """)
        stmt.setString(1, login)
        stmt.oneOrNull { 
            it.getString(1)
        }
    }

    /** Get login of account [id] */
    suspend fun login(id: Long): String? = db.conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT login FROM customers WHERE customer_id=?
        """)
        stmt.setLong(1, id)
        stmt.oneOrNull { 
            it.getString(1)
        }
    }

    /** Get bank info of account [login] */
    suspend fun bankInfo(login: String): BankInfo? = db.conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
             bank_account_id
             ,internal_payto_uri
             ,is_taler_exchange
            FROM bank_accounts
                JOIN customers 
                ON customer_id=owning_customer_id
                WHERE login=?
        """)
        stmt.setString(1, login)
        stmt.oneOrNull {
            BankInfo(
                internalPaytoUri = it.getString("internal_payto_uri"),
                isTalerExchange = it.getBoolean("is_taler_exchange"),
                bankAccountId = it.getLong("bank_account_id")
            )
        }
    }

    /** Get data of account [login] */
    suspend fun get(login: String): AccountData? = db.conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
                name
                ,email
                ,phone
                ,tan_channel
                ,cashout_payto
                ,internal_payto_uri
                ,(balance).val AS balance_val
                ,(balance).frac AS balance_frac
                ,has_debt
                ,(max_debt).val AS max_debt_val
                ,(max_debt).frac AS max_debt_frac
                ,is_public
                ,is_taler_exchange
            FROM customers 
                JOIN bank_accounts
                    ON customer_id=owning_customer_id
            WHERE login=?
        """)
        stmt.setString(1, login)
        stmt.oneOrNull {
            AccountData(
                name = it.getString("name"),
                contact_data = ChallengeContactData(
                    email = Option.Some(it.getString("email")),
                    phone = Option.Some(it.getString("phone"))
                ),
                tan_channel = it.getString("tan_channel")?.run { TanChannel.valueOf(this) },
                cashout_payto_uri = it.getString("cashout_payto"),
                payto_uri = it.getString("internal_payto_uri"),
                balance = Balance(
                    amount = it.getAmount("balance", db.bankCurrency),
                    credit_debit_indicator =
                        if (it.getBoolean("has_debt")) {
                            CreditDebitInfo.debit
                        } else {
                            CreditDebitInfo.credit
                        }
                ),
                debit_threshold = it.getAmount("max_debt", db.bankCurrency),
                is_public = it.getBoolean("is_public"),
                is_taler_exchange = it.getBoolean("is_taler_exchange")
            )
        }
    }

    /** Get a page of all public accounts */
    suspend fun pagePublic(params: AccountParams): List<PublicAccount>
        = db.page(
            params.page,
            "bank_account_id",
            """
            SELECT
              (balance).val AS balance_val,
              (balance).frac AS balance_frac,
              has_debt,
              internal_payto_uri,
              c.login
              ,is_taler_exchange
              FROM bank_accounts JOIN customers AS c
                ON owning_customer_id = c.customer_id
                WHERE is_public=true AND c.login LIKE ? AND
            """,
            {
                setString(1, params.loginFilter)
                1
            }
        ) {
            PublicAccount(
                username = it.getString("login"),
                account_name = it.getString("login"),
                payto_uri = it.getString("internal_payto_uri"),
                balance = Balance(
                    amount = it.getAmount("balance", db.bankCurrency),
                    credit_debit_indicator = if (it.getBoolean("has_debt")) {
                        CreditDebitInfo.debit 
                    } else {
                        CreditDebitInfo.credit
                    }
                ),
                is_taler_exchange = it.getBoolean("is_taler_exchange")
            )
        }

    /** Get a page of accounts */
    suspend fun pageAdmin(params: AccountParams): List<AccountMinimalData>
        = db.page(
            params.page,
            "bank_account_id",
            """
            SELECT
            login,
            name,
            (b.balance).val AS balance_val,
            (b.balance).frac AS balance_frac,
            (b).has_debt AS balance_has_debt,
            (max_debt).val as max_debt_val,
            (max_debt).frac as max_debt_frac
            ,is_public
            ,is_taler_exchange
            ,internal_payto_uri
            FROM customers JOIN bank_accounts AS b
              ON customer_id = b.owning_customer_id
            WHERE name LIKE ? AND
            """,
            {
                setString(1, params.loginFilter)
                1
            }
        ) {
            AccountMinimalData(
                username = it.getString("login"),
                name = it.getString("name"),
                balance = Balance(
                    amount = it.getAmount("balance", db.bankCurrency),
                    credit_debit_indicator = if (it.getBoolean("balance_has_debt")) {
                        CreditDebitInfo.debit
                    } else {
                        CreditDebitInfo.credit
                    }
                ),
                debit_threshold = it.getAmount("max_debt", db.bankCurrency),
                is_public = it.getBoolean("is_public"),
                is_taler_exchange = it.getBoolean("is_taler_exchange"),
                payto_uri = it.getString("internal_payto_uri"),
            )
        }
}