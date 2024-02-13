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

package tech.libeufin.bank.db

import tech.libeufin.bank.*
import tech.libeufin.common.*
import java.time.Instant

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
        email: String?,
        phone: String?,
        cashoutPayto: IbanPayto?,
        internalPayto: Payto,
        isPublic: Boolean,
        isTalerExchange: Boolean,
        maxDebt: TalerAmount,
        bonus: TalerAmount,
        tanChannel: TanChannel?,
        // Whether to check [internalPaytoUri] for idempotency
        checkPaytoIdempotent: Boolean
    ): AccountCreationResult = db.serializable { it ->
        val now = Instant.now().toDbMicros() ?: throw faultyTimestampByBank()
        it.transaction { conn ->
            val idempotent = conn.prepareStatement("""
                SELECT password_hash, name=?
                    AND email IS NOT DISTINCT FROM ?
                    AND phone IS NOT DISTINCT FROM ?
                    AND cashout_payto IS NOT DISTINCT FROM ?
                    AND (NOT ? OR internal_payto_uri=?)
                    AND is_public=?
                    AND is_taler_exchange=?
                    AND tan_channel IS NOT DISTINCT FROM ?::tan_enum
                FROM customers 
                    JOIN bank_accounts
                        ON customer_id=owning_customer_id
                WHERE login=?
            """).run {
                // TODO check max debt
                setString(1, name)
                setString(2, email)
                setString(3, phone)
                setString(4, cashoutPayto?.full(name))
                setBoolean(5, checkPaytoIdempotent)
                setString(6, internalPayto.canonical)
                setBoolean(7, isPublic)
                setBoolean(8, isTalerExchange)
                setString(9, tanChannel?.name)
                setString(10, login)
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
                if (internalPayto is IbanPayto)
                    conn.prepareStatement("""
                        INSERT INTO iban_history(
                            iban
                            ,creation_time
                        ) VALUES (?, ?)
                    """).run {
                        setString(1, internalPayto.iban.value)
                        setLong(2, now)
                        if (!executeUpdateViolation()) {
                            conn.rollback()
                            return@transaction AccountCreationResult.PayToReuse
                        }
                    }

                val customerId = conn.prepareStatement("""
                    INSERT INTO customers (
                        login
                        ,password_hash
                        ,name
                        ,email
                        ,phone
                        ,cashout_payto
                        ,tan_channel
                    ) VALUES (?, ?, ?, ?, ?, ?, ?::tan_enum)
                        RETURNING customer_id
                """
                ).run {
                    setString(1, login)
                    setString(2, CryptoUtil.hashpw(password))
                    setString(3, name)
                    setString(4, email)
                    setString(5, phone)
                    setString(6, cashoutPayto?.full(name))
                    setString(7, tanChannel?.name)
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
                    setString(1, internalPayto.canonical)
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
                        FROM bank_transaction(?,'admin','bonus',(?,?)::taler_amount,?,true)
                    """).run {
                        setString(1, internalPayto.canonical)
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
        BalanceNotZero,
        TanRequired
    }

    /** Delete account [login] */
    suspend fun delete(
        login: String, 
        is2fa: Boolean
    ): AccountDeletionResult = db.serializable { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
              out_not_found,
              out_balance_not_zero,
              out_tan_required
              FROM account_delete(?,?);
        """)
        stmt.setString(1, login)
        stmt.setBoolean(2, is2fa)
        stmt.executeQuery().use {
            when {
                !it.next() -> throw internalServerError("Deletion returned nothing.")
                it.getBoolean("out_not_found") -> AccountDeletionResult.UnknownAccount
                it.getBoolean("out_balance_not_zero") -> AccountDeletionResult.BalanceNotZero
                it.getBoolean("out_tan_required") -> AccountDeletionResult.TanRequired
                else -> AccountDeletionResult.Success
            }
        }
    }

    /** Result status of customer account patch */
    sealed interface AccountPatchResult {
        data object UnknownAccount: AccountPatchResult
        data object NonAdminName: AccountPatchResult
        data object NonAdminCashout: AccountPatchResult
        data object NonAdminDebtLimit: AccountPatchResult
        data object MissingTanInfo: AccountPatchResult
        data class TanRequired(val channel: TanChannel?, val info: String?): AccountPatchResult
        data object Success: AccountPatchResult
    }

    /** Change account [login] informations */
    suspend fun reconfig(
        login: String,
        name: String?,
        cashoutPayto: Option<IbanPayto?>,
        phone: Option<String?>,
        email: Option<String?>,
        tan_channel: Option<TanChannel?>,
        isPublic: Boolean?,
        debtLimit: TalerAmount?,
        isAdmin: Boolean,
        is2fa: Boolean,
        faChannel: TanChannel?,
        faInfo: String?,
        allowEditName: Boolean,
        allowEditCashout: Boolean,
    ): AccountPatchResult = db.serializable { it.transaction { conn ->
        val checkName = !isAdmin && !allowEditName && name != null
        val checkCashout = !isAdmin && !allowEditCashout && cashoutPayto.isSome()
        val checkDebtLimit = !isAdmin && debtLimit != null

        data class CurrentAccount(
            val id: Long,
            val channel: TanChannel?,
            val email: String?,
            val phone: String?,
            val name: String,
            val cashoutPayTo: String?,
            val debtLimit: TalerAmount,
        )

        // Get user ID and current data
        val curr = conn.prepareStatement("""
            SELECT 
                customer_id, tan_channel, phone, email, name, cashout_payto
                ,(max_debt).val AS max_debt_val
                ,(max_debt).frac AS max_debt_frac
            FROM customers
                JOIN bank_accounts 
                ON customer_id=owning_customer_id
            WHERE login=?
        """).run {
            setString(1, login)
            oneOrNull {
                CurrentAccount(
                    id = it.getLong("customer_id"),
                    channel = it.getString("tan_channel")?.run { TanChannel.valueOf(this) },
                    phone = it.getString("phone"),
                    email = it.getString("email"),
                    name = it.getString("name"),
                    cashoutPayTo = it.getString("cashout_payto"),
                    debtLimit = it.getAmount("max_debt", db.bankCurrency),
                )
            } ?: return@transaction AccountPatchResult.UnknownAccount
        }

        // Patched TAN channel
        val patchChannel = tan_channel.get()
        // TAN channel after the PATCH
        val newChannel = patchChannel ?: curr.channel
        // Patched TAN info
        val patchInfo = when (newChannel) {
            TanChannel.sms -> phone.get()
            TanChannel.email -> email.get()
            null -> null
        }
        // TAN info after the PATCH
        val newInfo = patchInfo ?: when (newChannel) {
            TanChannel.sms -> curr.phone
            TanChannel.email -> curr.email
            null -> null
        }
        // Cashout payto with a receiver-name using if receiver-name is missing the new named if present or the current one 
        val fullCashoutPayto = cashoutPayto.get()?.full(name ?: curr.name)

        // Check reconfig rights
        if (checkName && name != curr.name) 
            return@transaction AccountPatchResult.NonAdminName
        if (checkCashout && fullCashoutPayto != curr.cashoutPayTo) 
            return@transaction AccountPatchResult.NonAdminCashout
        if (checkDebtLimit && debtLimit != curr.debtLimit)
            return@transaction AccountPatchResult.NonAdminDebtLimit
        if (patchChannel != null && newInfo == null)
            return@transaction AccountPatchResult.MissingTanInfo


        // Tan channel verification
        if (!isAdmin) {
            // Check performed 2fa check
            if (curr.channel != null && !is2fa) {
                // Perform challenge with current settings
                return@transaction AccountPatchResult.TanRequired(channel = null, info = null)
            }
            // If channel or info changed and the 2fa challenge is performed with old settings perform a new challenge with new settings
            if ((patchChannel != null && patchChannel != faChannel) || (patchInfo != null && patchInfo != faInfo)) {
                return@transaction AccountPatchResult.TanRequired(channel = newChannel, info = newInfo)
            }
        }

        // Invalidate current challenges
        if (patchChannel != null || patchInfo != null) {
            val stmt = conn.prepareStatement("UPDATE tan_challenges SET expiration_date=0 WHERE customer=?")
            stmt.setLong(1, curr.id)
            stmt.execute()
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
                yield(curr.id)
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
                cashoutPayto.some { yield(fullCashoutPayto) }
                phone.some { yield(it) }
                email.some { yield(it) }
                tan_channel.some { yield(it?.name) }
                name?.let { yield(it) }
                yield(curr.id)
            }
        )

        AccountPatchResult.Success
    }}


    /** Result status of customer account auth patch */
    enum class AccountPatchAuthResult {
        UnknownAccount,
        OldPasswordMismatch,
        TanRequired,
        Success
    }

    /** Change account [login] password to [newPw] if current match [oldPw] */
    suspend fun reconfigPassword(
        login: String, 
        newPw: String, 
        oldPw: String?,
        is2fa: Boolean
    ): AccountPatchAuthResult = db.serializable {
        it.transaction { conn ->
            val (currentPwh, tanRequired) = conn.prepareStatement("""
                SELECT password_hash, (NOT ? AND tan_channel IS NOT NULL) FROM customers WHERE login=?
            """).run {
                setBoolean(1, is2fa)
                setString(2, login)
                oneOrNull { 
                    Pair(it.getString(1), it.getBoolean(2))
                } ?: return@transaction AccountPatchAuthResult.UnknownAccount
            }
            if (tanRequired) {
                AccountPatchAuthResult.TanRequired
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

    /** Get bank info of account [login] */
    suspend fun bankInfo(login: String, ctx: BankPaytoCtx): BankInfo? = db.conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
             bank_account_id
             ,internal_payto_uri
             ,name
             ,is_taler_exchange
            FROM bank_accounts
                JOIN customers 
                ON customer_id=owning_customer_id
                WHERE login=?
        """)
        stmt.setString(1, login)
        stmt.oneOrNull {
            BankInfo(
                payto = it.getBankPayto("internal_payto_uri", "name", ctx),
                isTalerExchange = it.getBoolean("is_taler_exchange"),
                bankAccountId = it.getLong("bank_account_id")
            )
        }
    }

    /** Get data of account [login] */
    suspend fun get(login: String, ctx: BankPaytoCtx): AccountData? = db.conn { conn ->
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
                payto_uri = it.getBankPayto("internal_payto_uri", "name", ctx),
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
    suspend fun pagePublic(params: AccountParams, ctx: BankPaytoCtx): List<PublicAccount>
        = db.page(
            params.page,
            "bank_account_id",
            """
            SELECT
              (balance).val AS balance_val,
              (balance).frac AS balance_frac,
              has_debt,
              internal_payto_uri,
              c.login,
              is_taler_exchange,
              name
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
                payto_uri = it.getBankPayto("internal_payto_uri", "name", ctx),
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
    suspend fun pageAdmin(params: AccountParams, ctx: BankPaytoCtx): List<AccountMinimalData>
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
                payto_uri = it.getBankPayto("internal_payto_uri", "name", ctx),
            )
        }
}