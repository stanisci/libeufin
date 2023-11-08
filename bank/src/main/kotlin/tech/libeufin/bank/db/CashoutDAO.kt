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

import java.time.Instant
import java.time.Duration
import java.util.concurrent.TimeUnit
import tech.libeufin.util.*

/** Result status of cashout operation creation */
enum class CashoutCreationResult {
    SUCCESS,
    BAD_CONVERSION,
    ACCOUNT_NOT_FOUND,
    ACCOUNT_IS_EXCHANGE,
    MISSING_TAN_INFO,
    BALANCE_INSUFFICIENT,
    REQUEST_UID_REUSE
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

class CashoutDAO(private val db: Database) {
    
    data class CashoutCreation(
        val status: CashoutCreationResult,
        val id: Long?,
        val tanInfo: String?,
        val tanCode: String?
    )

    suspend fun create(
        accountUsername: String,
        requestUid: ShortHashCode,
        amountDebit: TalerAmount,
        amountCredit: TalerAmount,
        subject: String,
        tanChannel: TanChannel,
        tanCode: String,
        now: Instant,
        retryCounter: Int,
        validityPeriod: Duration
    ): CashoutCreation = db.conn { conn ->
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
        stmt.setString(1, accountUsername)
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
            var id: Long? = null
            var info: String? = null;
            var code: String? = null;
            val status = when {
                !it.next() ->
                    throw internalServerError("No result from DB procedure cashout_create")
                it.getBoolean("out_bad_conversion") -> CashoutCreationResult.BAD_CONVERSION
                it.getBoolean("out_account_not_found") -> CashoutCreationResult.ACCOUNT_NOT_FOUND
                it.getBoolean("out_account_is_exchange") -> CashoutCreationResult.ACCOUNT_IS_EXCHANGE
                it.getBoolean("out_missing_tan_info") -> CashoutCreationResult.MISSING_TAN_INFO
                it.getBoolean("out_balance_insufficient") -> CashoutCreationResult.BALANCE_INSUFFICIENT
                it.getBoolean("out_request_uid_reuse") -> CashoutCreationResult.REQUEST_UID_REUSE
                else -> {
                    id = it.getLong("out_cashout_id")
                    info = it.getString("out_tan_info")
                    code = it.getString("out_tan_code")
                    CashoutCreationResult.SUCCESS
                }
            }
            CashoutCreation(status, id, info, code)
        }
    }

    suspend fun markSent(
        id: Long,
        now: Instant,
        retransmissionPeriod: Duration
    ) = db.conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT challenge_mark_sent(challenge, ?, ?)
            FROM cashout_operations
            WHERE cashout_id=?
        """)
        stmt.setLong(1, now.toDbMicros() ?: throw faultyTimestampByBank())
        stmt.setLong(2, TimeUnit.MICROSECONDS.convert(retransmissionPeriod))
        stmt.setLong(3, id)
        stmt.executeQueryCheck()
    }

    suspend fun abort(id: Long): AbortResult = db.conn { conn ->
        val stmt = conn.prepareStatement("""
            UPDATE cashout_operations
            SET aborted = local_transaction IS NULL
            WHERE cashout_id=?
            RETURNING local_transaction IS NOT NULL
        """)
        stmt.setLong(1, id)
        when (stmt.oneOrNull { it.getBoolean(1) }) {
            null -> AbortResult.NOT_FOUND
            true -> AbortResult.CONFIRMED
            false -> AbortResult.SUCCESS
        }
    }

    suspend fun confirm(
        id: Long,
        tanCode: String,
        timestamp: Instant
    ): CashoutConfirmationResult = db.conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
                out_no_op,
                out_bad_conversion,
                out_bad_code,
                out_balance_insufficient,
                out_aborted,
                out_no_retry,
                out_no_cashout_payto
            FROM cashout_confirm(?, ?, ?);
        """)
        stmt.setLong(1, id)
        stmt.setString(2, tanCode)
        stmt.setLong(3, timestamp.toDbMicros() ?: throw faultyTimestampByBank())
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

    enum class CashoutDeleteResult {
        SUCCESS,
        CONFLICT_ALREADY_CONFIRMED
    }

    suspend fun delete(id: Long): CashoutDeleteResult = db.conn { conn ->
        val stmt = conn.prepareStatement("""
           SELECT out_already_confirmed
             FROM cashout_delete(?)
        """)
        stmt.setLong(1, id)
        stmt.executeQuery().use {
            when {
                !it.next() -> throw internalServerError("Cashout deletion gave no result")
                it.getBoolean("out_already_confirmed") -> CashoutDeleteResult.CONFLICT_ALREADY_CONFIRMED
                else -> CashoutDeleteResult.SUCCESS
            }   
        }
    }

    suspend fun get(id: Long): CashoutStatusResponse? = db.conn { conn ->
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
            FROM cashout_operations
                LEFT JOIN bank_account_transactions ON local_transaction=bank_transaction_id
            WHERE cashout_id=?
        """)
        stmt.setLong(1, id)
        stmt.oneOrNull {
            CashoutStatusResponse(
                status = CashoutStatus.valueOf(it.getString("status")),
                amount_debit = TalerAmount(
                    value = it.getLong("amount_debit_val"),
                    frac = it.getInt("amount_debit_frac"),
                    db.bankCurrency
                ),
                amount_credit = TalerAmount(
                    value = it.getLong("amount_credit_val"),
                    frac = it.getInt("amount_credit_frac"),
                    db.fiatCurrency!!
                ),
                subject = it.getString("subject"),
                creation_time = TalerProtocolTimestamp(
                    it.getLong("creation_time").microsToJavaInstant() ?: throw faultyTimestampByBank()
                ),
                confirmation_time = when (val timestamp = it.getLong("confirmation_date")) {
                    0L -> null
                    else -> TalerProtocolTimestamp(timestamp.microsToJavaInstant() ?: throw faultyTimestampByBank())
                }
            )
        }
    }

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