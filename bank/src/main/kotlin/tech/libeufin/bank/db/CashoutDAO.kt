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

import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import tech.libeufin.util.*

/** Data access logic for cashout operations */
class CashoutDAO(private val db: Database) {
    /** Result of cashout operation creation */
    sealed class CashoutCreationResult {
        /** Cashout [id] has been created or refreshed. If [tanCode] is not null, use [tanInfo] to send it via [tanChannel] then call [markSent] */
        data class Success(val id: Long, val tanInfo: String, val tanCode: String?): CashoutCreationResult()
        object BadConversion: CashoutCreationResult()
        object AccountNotFound: CashoutCreationResult()
        object AccountIsExchange: CashoutCreationResult()
        object MissingTanInfo: CashoutCreationResult()
        object BalanceInsufficient: CashoutCreationResult()
        object RequestUidReuse: CashoutCreationResult()
    }

    /** Create a new cashout operation */
    suspend fun create(
        login: String,
        requestUid: ShortHashCode,
        amountDebit: TalerAmount,
        amountCredit: TalerAmount,
        subject: String,
        tanChannel: TanChannel,
        tanCode: String,
        now: Instant,
        retryCounter: Int,
        validityPeriod: Duration
    ): CashoutCreationResult = db.serializable { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
                out_bad_conversion,
                out_account_not_found,
                out_account_is_exchange,
                out_missing_tan_info,
                out_balance_insufficient,
                out_request_uid_reuse,
                out_cashout_id,
                out_tan_info,
                out_tan_code
            FROM cashout_create(?, ?, (?,?)::taler_amount, (?,?)::taler_amount, ?, ?, ?::tan_enum, ?, ?, ?)
        """)
        stmt.setString(1, login)
        stmt.setBytes(2, requestUid.raw)
        stmt.setLong(3, amountDebit.value)
        stmt.setInt(4, amountDebit.frac)
        stmt.setLong(5, amountCredit.value)
        stmt.setInt(6, amountCredit.frac)
        stmt.setString(7, subject)
        stmt.setLong(8, now.toDbMicros() ?: throw faultyTimestampByBank())
        stmt.setString(9, tanChannel.name)
        stmt.setString(10, tanCode)
        stmt.setInt(11, retryCounter)
        stmt.setLong(12, TimeUnit.MICROSECONDS.convert(validityPeriod))
        stmt.executeQuery().use {
            when {
                !it.next() ->
                    throw internalServerError("No result from DB procedure cashout_create")
                it.getBoolean("out_bad_conversion") -> CashoutCreationResult.BadConversion
                it.getBoolean("out_account_not_found") -> CashoutCreationResult.AccountNotFound
                it.getBoolean("out_account_is_exchange") -> CashoutCreationResult.AccountIsExchange
                it.getBoolean("out_missing_tan_info") -> CashoutCreationResult.MissingTanInfo
                it.getBoolean("out_balance_insufficient") -> CashoutCreationResult.BalanceInsufficient
                it.getBoolean("out_request_uid_reuse") -> CashoutCreationResult.RequestUidReuse
                else -> CashoutCreationResult.Success(
                    id = it.getLong("out_cashout_id"),
                    tanInfo = it.getString("out_tan_info"),
                    tanCode = it.getString("out_tan_code")
                )
            }
        }
    }

    /** Mark cashout operation [id] challenge as having being successfully sent [now] and not to be retransmit until after [retransmissionPeriod] */
    suspend fun markSent(
        id: Long,
        now: Instant,
        retransmissionPeriod: Duration,
        tanChannel: TanChannel,
        tanInfo: String
    ) = db.serializable {
        it.transaction { conn ->
            conn.prepareStatement("""
                SELECT challenge_mark_sent(challenge, ?, ?)
                FROM cashout_operations
                WHERE cashout_id=?
            """).run {
                setLong(1, now.toDbMicros() ?: throw faultyTimestampByBank())
                setLong(2, TimeUnit.MICROSECONDS.convert(retransmissionPeriod))
                setLong(3, id)
                executeQueryCheck()
            }
            conn.prepareStatement("""
                UPDATE cashout_operations
                SET tan_channel = ?, tan_info = ?
                WHERE cashout_id=?
            """).run {
                setString(1, tanChannel.name)
                setString(2, tanInfo)
                setLong(3, id)
                executeUpdateCheck()
            }
        }
    }

    /** Abort cashout operation [id] owned by [login] */
    suspend fun abort(id: Long, login: String): AbortResult = db.serializable { conn ->
        val stmt = conn.prepareStatement("""
            UPDATE cashout_operations
            SET aborted = local_transaction IS NULL
            FROM bank_accounts JOIN customers ON customer_id=owning_customer_id
            WHERE cashout_id=? AND bank_account=bank_account_id AND login=?
            RETURNING local_transaction IS NOT NULL
        """)
        stmt.setLong(1, id)
        stmt.setString(2, login)
        when (stmt.oneOrNull { it.getBoolean(1) }) {
            null -> AbortResult.UnknownOperation
            true -> AbortResult.AlreadyConfirmed
            false -> AbortResult.Success
        }
    }

    /** Result status of cashout operation confirmation */
    enum class CashoutConfirmationResult {
        SUCCESS,
        BAD_CONVERSION,
        OP_NOT_FOUND,
        BAD_TAN_CODE,
        BALANCE_INSUFFICIENT,
        NO_RETRY,
        NO_CASHOUT_PAYTO,
        ABORTED
    }

    /** Confirm cashout operation [id] owned by [login] */
    suspend fun confirm(
        id: Long,
        login: String,
        tanCode: String,
        timestamp: Instant
    ): CashoutConfirmationResult = db.serializable { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
                out_no_op,
                out_bad_conversion,
                out_bad_code,
                out_balance_insufficient,
                out_aborted,
                out_no_retry,
                out_no_cashout_payto
            FROM cashout_confirm(?, ?, ?, ?);
        """)
        stmt.setLong(1, id)
        stmt.setString(2, login)
        stmt.setString(3, tanCode)
        stmt.setLong(4, timestamp.toDbMicros() ?: throw faultyTimestampByBank())
        stmt.executeQuery().use {
            when {
                !it.next() ->
                    throw internalServerError("No result from DB procedure cashout_create")
                it.getBoolean("out_no_op") -> CashoutConfirmationResult.OP_NOT_FOUND
                it.getBoolean("out_bad_code") -> CashoutConfirmationResult.BAD_TAN_CODE
                it.getBoolean("out_balance_insufficient") -> CashoutConfirmationResult.BALANCE_INSUFFICIENT
                it.getBoolean("out_aborted") -> CashoutConfirmationResult.ABORTED
                it.getBoolean("out_no_retry") -> CashoutConfirmationResult.NO_RETRY
                it.getBoolean("out_no_cashout_payto") -> CashoutConfirmationResult.NO_CASHOUT_PAYTO
                it.getBoolean("out_bad_conversion") -> CashoutConfirmationResult.BAD_CONVERSION
                else -> CashoutConfirmationResult.SUCCESS
            }
        }
    }

    /** Get status of cashout operation [id] owned by [login] */
    suspend fun get(id: Long, login: String): CashoutStatusResponse? = db.conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
                CASE 
                    WHEN aborted THEN 'aborted'
                    WHEN local_transaction IS NOT NULL THEN 'confirmed'
                    ELSE 'pending'
                END as status
                ,(amount_debit).val as amount_debit_val
                ,(amount_debit).frac as amount_debit_frac
                ,(amount_credit).val as amount_credit_val
                ,(amount_credit).frac as amount_credit_frac
                ,cashout_operations.subject
                ,creation_time
                ,transaction_date as confirmation_date
                ,tan_channel
                ,tan_info
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
                status = CashoutStatus.valueOf(it.getString("status")),
                amount_debit = it.getAmount("amount_debit", db.bankCurrency),
                amount_credit = it.getAmount("amount_credit", db.fiatCurrency!!),
                subject = it.getString("subject"),
                creation_time = it.getTalerTimestamp("creation_time"),
                confirmation_time = when (val timestamp = it.getLong("confirmation_date")) {
                    0L -> null
                    else -> TalerProtocolTimestamp(timestamp.microsToJavaInstant() ?: throw faultyTimestampByBank())
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
                ,CASE 
                    WHEN aborted THEN 'aborted'
                    WHEN local_transaction IS NOT NULL THEN 'confirmed'
                    ELSE 'pending'
                END as status
            FROM cashout_operations
                JOIN bank_accounts ON bank_account=bank_account_id
                JOIN customers ON owning_customer_id=customer_id
            WHERE
        """) {
            GlobalCashoutInfo(
                cashout_id = it.getLong("cashout_id"),
                username = it.getString("login"),
                status = CashoutStatus.valueOf(it.getString("status"))
            )
        }

    /** Get a page of all cashout operations owned by [login] */
    suspend fun pageForUser(params: PageParams, login: String): List<CashoutInfo> =
        db.page(params, "cashout_id", """
            SELECT
                cashout_id
                ,CASE 
                    WHEN aborted THEN 'aborted'
                    WHEN local_transaction IS NOT NULL THEN 'confirmed'
                    ELSE 'pending'
                END as status
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
                status = CashoutStatus.valueOf(it.getString("status"))
            )
        }
}