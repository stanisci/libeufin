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

import java.util.UUID
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
    OP_NOT_FOUND,
    BAD_TAN_CODE,
    BALANCE_INSUFFICIENT,
    NO_RETRY,
    ABORTED
}

class CashoutDAO(private val db: Database) {
    
    data class CashoutCreation(
        val status: CashoutCreationResult,
        val id: UUID?,
        val tanInfo: String?,
        val tanCode: String?
    )

    suspend fun create(
        accountUsername: String,
        requestUid: ShortHashCode,
        cashoutUuid: UUID,
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
                out_cashout_uuid,
                out_tan_info,
                out_tan_code
            FROM cashout_create(?, ?, ?, (?,?)::taler_amount, (?,?)::taler_amount, ?, ?, ?::tan_enum, ?, ?, ?)
        """)
        stmt.setString(1, accountUsername)
        stmt.setBytes(2, requestUid.raw)
        stmt.setObject(3, cashoutUuid)
        stmt.setLong(4, amountDebit.value)
        stmt.setInt(5, amountDebit.frac)
        stmt.setLong(6, amountCredit.value)
        stmt.setInt(7, amountCredit.frac)
        stmt.setString(8, subject)
        stmt.setLong(9, now.toDbMicros() ?: throw faultyTimestampByBank())
        stmt.setString(10, tanChannel.name)
        stmt.setString(11, tanCode)
        stmt.setInt(12, retryCounter)
        stmt.setLong(13, TimeUnit.MICROSECONDS.convert(validityPeriod))
        stmt.executeQuery().use {
            var id: UUID? = null
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
                    id = it.getObject("out_cashout_uuid") as UUID
                    info = it.getString("out_tan_info")
                    code = it.getString("out_tan_code")
                    CashoutCreationResult.SUCCESS
                }
            }
            CashoutCreation(status, id, info, code)
        }
    }

    suspend fun markSent(
        uuid: UUID,
        now: Instant,
        retransmissionPeriod: Duration
    ) = db.conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT challenge_mark_sent(challenge, ?, ?)
            FROM cashout_operations
            WHERE cashout_uuid=?
        """)
        stmt.setLong(1, now.toDbMicros() ?: throw faultyTimestampByBank())
        stmt.setLong(2, TimeUnit.MICROSECONDS.convert(retransmissionPeriod))
        stmt.setObject(3, uuid)
        stmt.executeQueryCheck()
    }

    suspend fun abort(opUUID: UUID): AbortResult = db.conn { conn ->
        val stmt = conn.prepareStatement("""
            UPDATE cashout_operations
            SET aborted = local_transaction IS NULL
            WHERE cashout_uuid=?
            RETURNING local_transaction IS NOT NULL
        """)
        stmt.setObject(1, opUUID)
        when (stmt.oneOrNull { it.getBoolean(1) }) {
            null -> AbortResult.NOT_FOUND
            true -> AbortResult.CONFIRMED
            false -> AbortResult.SUCCESS
        }
    }

    suspend fun confirm(
        opUuid: UUID,
        tanCode: String,
        timestamp: Instant
    ): CashoutConfirmationResult = db.conn { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
                out_no_op,
                out_bad_code,
                out_balance_insufficient,
                out_aborted,
                out_no_retry
            FROM cashout_confirm(?, ?, ?);
        """)
        stmt.setObject(1, opUuid)
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
                else -> CashoutConfirmationResult.SUCCESS
            }
        }
    }

    enum class CashoutDeleteResult {
        SUCCESS,
        CONFLICT_ALREADY_CONFIRMED
    }

    suspend fun delete(opUuid: UUID): CashoutDeleteResult = db.conn { conn ->
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

    suspend fun getFromUuid(opUuid: UUID): Cashout? = db.conn { conn ->
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
                    db.bankCurrency
                ),
                amountCredit = TalerAmount(
                    value = it.getLong("amount_credit_val"),
                    frac = it.getInt("amount_credit_frac"),
                    db.bankCurrency
                ),
                bankAccount = it.getLong("bank_account"),
                buyAtRatio = it.getInt("buy_at_ratio"),
                buyInFee = TalerAmount(
                    value = it.getLong("buy_in_fee_val"),
                    frac = it.getInt("buy_in_fee_frac"),
                    db.bankCurrency
                ),
                credit_payto_uri = it.getString("credit_payto_uri"),
                cashoutCurrency = it.getString("cashout_currency"),
                cashoutUuid = opUuid,
                creationTime = it.getLong("creation_time").microsToJavaInstant() ?: throw faultyTimestampByBank(),
                sellAtRatio = it.getInt("sell_at_ratio"),
                sellOutFee = TalerAmount(
                    value = it.getLong("sell_out_fee_val"),
                    frac = it.getInt("sell_out_fee_frac"),
                    db.bankCurrency
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
}