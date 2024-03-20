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

/** Data access logic for cashout operations */
class CashoutDAO(private val db: Database) {
    /** Result of cashout operation creation */
    sealed interface CashoutCreationResult {
        data class Success(val id: Long): CashoutCreationResult
        data object BadConversion: CashoutCreationResult
        data object AccountNotFound: CashoutCreationResult
        data object AccountIsExchange: CashoutCreationResult
        data object BalanceInsufficient: CashoutCreationResult
        data object RequestUidReuse: CashoutCreationResult
        data object NoCashoutPayto: CashoutCreationResult
        data object TanRequired: CashoutCreationResult
    }

    /** Create a new cashout operation */
    suspend fun create(
        login: String,
        requestUid: ShortHashCode,
        amountDebit: TalerAmount,
        amountCredit: TalerAmount,
        subject: String,
        now: Instant,
        is2fa: Boolean
    ): CashoutCreationResult = db.serializable { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
                out_bad_conversion,
                out_account_not_found,
                out_account_is_exchange,
                out_balance_insufficient,
                out_request_uid_reuse,
                out_no_cashout_payto,
                out_tan_required,
                out_cashout_id
            FROM cashout_create(?,?,(?,?)::taler_amount,(?,?)::taler_amount,?,?,?)
        """)
        stmt.setString(1, login)
        stmt.setBytes(2, requestUid.raw)
        stmt.setLong(3, amountDebit.value)
        stmt.setInt(4, amountDebit.frac)
        stmt.setLong(5, amountCredit.value)
        stmt.setInt(6, amountCredit.frac)
        stmt.setString(7, subject)
        stmt.setLong(8, now.micros())
        stmt.setBoolean(9, is2fa)
        stmt.executeQuery().use {
            when {
                !it.next() ->
                    throw internalServerError("No result from DB procedure cashout_create")
                it.getBoolean("out_bad_conversion") -> CashoutCreationResult.BadConversion
                it.getBoolean("out_account_not_found") -> CashoutCreationResult.AccountNotFound
                it.getBoolean("out_account_is_exchange") -> CashoutCreationResult.AccountIsExchange
                it.getBoolean("out_balance_insufficient") -> CashoutCreationResult.BalanceInsufficient
                it.getBoolean("out_request_uid_reuse") -> CashoutCreationResult.RequestUidReuse
                it.getBoolean("out_no_cashout_payto") -> CashoutCreationResult.NoCashoutPayto
                it.getBoolean("out_tan_required") -> CashoutCreationResult.TanRequired
                else -> CashoutCreationResult.Success(it.getLong("out_cashout_id"))
            }
        }
    }

    /** Get status of cashout operation [id] owned by [login] */
    suspend fun get(id: Long, login: String): CashoutStatusResponse? = db.conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
                (amount_debit).val as amount_debit_val
                ,(amount_debit).frac as amount_debit_frac
                ,(amount_credit).val as amount_credit_val
                ,(amount_credit).frac as amount_credit_frac
                ,cashout_operations.subject
                ,creation_time
                ,transaction_date as confirmation_date
                ,tan_channel
                ,CASE tan_channel
                    WHEN 'sms'   THEN phone
                    WHEN 'email' THEN email
                END as tan_info
            FROM cashout_operations
                JOIN bank_accounts ON bank_account=bank_account_id
                JOIN customers ON owning_customer_id=customer_id
                LEFT JOIN bank_account_transactions ON local_transaction=bank_transaction_id
            WHERE cashout_id=? AND login=?
        """)
        stmt.setLong(1, id)
        stmt.setString(2, login)
        stmt.oneOrNull {
            CashoutStatusResponse(
                status = CashoutStatus.confirmed,
                amount_debit = it.getAmount("amount_debit", db.bankCurrency),
                amount_credit = it.getAmount("amount_credit", db.fiatCurrency!!),
                subject = it.getString("subject"),
                creation_time = it.getTalerTimestamp("creation_time"),
                confirmation_time = when (val timestamp = it.getLong("confirmation_date")) {
                    0L -> null
                    else -> TalerProtocolTimestamp(timestamp.asInstant())
                },
                tan_channel = it.getString("tan_channel")?.run { TanChannel.valueOf(this) },
                tan_info = it.getString("tan_info"),
            )
        }
    }

    /** Get a page of all cashout operations */
    suspend fun pageAll(params: PageParams): List<GlobalCashoutInfo> =
        db.page(params, "cashout_id", """
            SELECT
                cashout_id
                ,login
            FROM cashout_operations
                JOIN bank_accounts ON bank_account=bank_account_id
                JOIN customers ON owning_customer_id=customer_id
            WHERE
        """) {
            GlobalCashoutInfo(
                cashout_id = it.getLong("cashout_id"),
                username = it.getString("login"),
                status = CashoutStatus.confirmed
            )
        }

    /** Get a page of all cashout operations owned by [login] */
    suspend fun pageForUser(params: PageParams, login: String): List<CashoutInfo> =
        db.page(params, "cashout_id", """
            SELECT cashout_id
            FROM cashout_operations
                JOIN bank_accounts ON bank_account=bank_account_id
                JOIN customers ON owning_customer_id=customer_id
            WHERE login = ? AND
        """, 
            bind = { 
                setString(1, login)
                1
            }
        ) {
            CashoutInfo(
                cashout_id = it.getLong("cashout_id"),
                status = CashoutStatus.confirmed
            )
        }
}