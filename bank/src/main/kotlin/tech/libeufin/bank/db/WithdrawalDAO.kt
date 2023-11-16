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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*

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

class WithdrawalDAO(private val db: Database) {
    suspend fun create(
        walletAccountUsername: String,
        uuid: UUID,
        amount: TalerAmount
    ): WithdrawalCreationResult = db.serializable { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
                out_account_not_found,
                out_account_is_exchange,
                out_balance_insufficient
            FROM create_taler_withdrawal(?, ?, (?,?)::taler_amount);
        """)
        stmt.setString(1, walletAccountUsername)
        stmt.setObject(2, uuid)
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

    suspend fun get(uuid: UUID): BankAccountGetWithdrawalResponse? = db.conn { conn -> 
        val stmt = conn.prepareStatement("""
            SELECT
              (amount).val as amount_val
              ,(amount).frac as amount_frac
              ,selection_done     
              ,aborted     
              ,confirmation_done     
              ,reserve_pub
              ,selected_exchange_payto 
            FROM taler_withdrawal_operations
            WHERE withdrawal_uuid=?
        """)
        stmt.setObject(1, uuid)
        stmt.oneOrNull {
            BankAccountGetWithdrawalResponse(
                amount = TalerAmount(
                    it.getLong("amount_val"),
                    it.getInt("amount_frac"),
                    db.bankCurrency
                ),
                selection_done = it.getBoolean("selection_done"),
                confirmation_done = it.getBoolean("confirmation_done"),
                aborted = it.getBoolean("aborted"),
                selected_exchange_account = it.getString("selected_exchange_payto"),
                selected_reserve_pub = it.getBytes("reserve_pub")?.run(::EddsaPublicKey),
            )
        }
    }

    suspend fun pollStatus(uuid: UUID, params: PollingParams): BankWithdrawalOperationStatus? {
        suspend fun load(): BankWithdrawalOperationStatus? = db.conn { conn ->
            val stmt = conn.prepareStatement("""
                SELECT
                  (amount).val as amount_val
                  ,(amount).frac as amount_frac
                  ,selection_done     
                  ,aborted     
                  ,confirmation_done      
                  ,internal_payto_uri
                FROM taler_withdrawal_operations
                    JOIN bank_accounts ON (wallet_bank_account=bank_account_id)
                WHERE withdrawal_uuid=?
            """)
            stmt.setObject(1, uuid)
            stmt.oneOrNull {
                BankWithdrawalOperationStatus(
                    amount = TalerAmount(
                        it.getLong("amount_val"),
                        it.getInt("amount_frac"),
                        db.bankCurrency
                    ),
                    selection_done = it.getBoolean("selection_done"),
                    transfer_done = it.getBoolean("confirmation_done"),
                    aborted = it.getBoolean("aborted"),
                    sender_wire = it.getString("internal_payto_uri"),
                    confirm_transfer_url = null,
                    suggested_exchange = null
                )
            }
        }
            

        return if (params.poll_ms > 0) {
            db.notifWatcher.listenWithdrawals { flow ->
                coroutineScope {
                    // Start buffering notification before loading transactions to not miss any
                    val polling = launch {
                        withTimeoutOrNull(params.poll_ms) {
                            flow.first { it == uuid }
                        }
                    }    
                    // Initial loading
                    val init = load()
                    // Long polling if there is no operation or its not confirmed
                    if (init?.run { transfer_done == false } ?: true) {
                        polling.join()
                        load()
                    } else {
                        polling.cancel()
                        init
                    }
                }
            }
        } else {
            load()
        }
    }

    /**
     * Aborts one Taler withdrawal, only if it wasn't previously
     * confirmed.  It returns false if the UPDATE didn't succeed.
     */
    suspend fun abort(uuid: UUID): AbortResult = db.serializable { conn ->
        val stmt = conn.prepareStatement("""
            UPDATE taler_withdrawal_operations
            SET aborted = NOT confirmation_done
            WHERE withdrawal_uuid=?
            RETURNING confirmation_done
        """
        )
        stmt.setObject(1, uuid)
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
    suspend fun setDetails(
        uuid: UUID,
        exchangePayto: IbanPayTo,
        reservePub: EddsaPublicKey
    ): Pair<WithdrawalSelectionResult, Boolean> = db.serializable { conn ->
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
        stmt.setObject(1, uuid)
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
    suspend fun confirm(
        uuid: UUID,
        now: Instant
    ): WithdrawalConfirmationResult = db.serializable { conn ->
        val stmt = conn.prepareStatement("""
            SELECT
              out_no_op,
              out_exchange_not_found,
              out_balance_insufficient,
              out_not_selected,
              out_aborted
            FROM confirm_taler_withdrawal(?, ?);
        """
        )
        stmt.setObject(1, uuid)
        stmt.setLong(2, now.toDbMicros() ?: throw faultyTimestampByBank())
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
}