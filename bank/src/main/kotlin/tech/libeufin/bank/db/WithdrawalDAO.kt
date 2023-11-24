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

/** Data access logic for withdrawal operations */
class WithdrawalDAO(private val db: Database) {
    /** Result status of withdrawal operation creation */
    enum class WithdrawalCreationResult {
        Success,
        UnknownAccount,
        AccountIsExchange,
        BalanceInsufficient
    }

    /** Create a new withdrawal operation */
    suspend fun create(
        login: String,
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
        stmt.setString(1, login)
        stmt.setObject(2, uuid)
        stmt.setLong(3, amount.value)
        stmt.setInt(4, amount.frac)
        stmt.executeQuery().use {
            when {
                !it.next() ->
                    throw internalServerError("No result from DB procedure create_taler_withdrawal")
                it.getBoolean("out_account_not_found") -> WithdrawalCreationResult.UnknownAccount
                it.getBoolean("out_account_is_exchange") -> WithdrawalCreationResult.AccountIsExchange
                it.getBoolean("out_balance_insufficient") -> WithdrawalCreationResult.BalanceInsufficient
                else -> WithdrawalCreationResult.Success
            }
        }
    }

    /** Abort withdrawal operation [uuid] */
    suspend fun abort(uuid: UUID): AbortResult = db.serializable { conn ->
        // TODO login check
        val stmt = conn.prepareStatement("""
            SELECT
                out_no_op,
                out_already_confirmed
            FROM abort_taler_withdrawal(?)
        """)
        stmt.setObject(1, uuid)
        stmt.executeQuery().use {
            when {
                !it.next() ->
                    throw internalServerError("No result from DB procedure abort_taler_withdrawal")
                it.getBoolean("out_no_op") -> AbortResult.UnknownOperation
                it.getBoolean("out_already_confirmed") -> AbortResult.AlreadyConfirmed
                else -> AbortResult.Success
            }
        }
    }

    /** Result withdrawal operation selection */
    sealed class WithdrawalSelectionResult {
        data class Success(val status: WithdrawalStatus): WithdrawalSelectionResult()
        object UnknownOperation: WithdrawalSelectionResult()
        object AlreadySelected: WithdrawalSelectionResult()
        object RequestPubReuse: WithdrawalSelectionResult()
        object UnknownAccount: WithdrawalSelectionResult()
        object AccountIsNotExchange: WithdrawalSelectionResult()
    }

    /** Set details ([exchangePayto] & [reservePub]) for withdrawal operation [uuid] */
    suspend fun setDetails(
        uuid: UUID,
        exchangePayto: IbanPayTo,
        reservePub: EddsaPublicKey
    ): WithdrawalSelectionResult = db.serializable { conn ->
        val subject = IncomingTxMetadata(reservePub).encode()
        val stmt = conn.prepareStatement("""
            SELECT
                out_no_op,
                out_already_selected,
                out_reserve_pub_reuse,
                out_account_not_found,
                out_account_is_not_exchange,
                out_status
            FROM select_taler_withdrawal(?, ?, ?, ?);
        """
        )
        stmt.setObject(1, uuid)
        stmt.setBytes(2, reservePub.raw)
        stmt.setString(3, subject)
        stmt.setString(4, exchangePayto.canonical)
        stmt.executeQuery().use {
            when {
                !it.next() ->
                    throw internalServerError("No result from DB procedure select_taler_withdrawal")
                it.getBoolean("out_no_op") -> WithdrawalSelectionResult.UnknownOperation
                it.getBoolean("out_already_selected") -> WithdrawalSelectionResult.AlreadySelected
                it.getBoolean("out_reserve_pub_reuse") -> WithdrawalSelectionResult.RequestPubReuse
                it.getBoolean("out_account_not_found") -> WithdrawalSelectionResult.UnknownAccount
                it.getBoolean("out_account_is_not_exchange") -> WithdrawalSelectionResult.AccountIsNotExchange
                else -> WithdrawalSelectionResult.Success(WithdrawalStatus.valueOf(it.getString("out_status")))
            }
        }
    }

    /** Result status of withdrawal operation confirmation */
    enum class WithdrawalConfirmationResult {
        Success,
        UnknownOperation,
        UnknownExchange,
        BalanceInsufficient,
        NotSelected,
        AlreadyAborted
    }

    /** Confirm withdrawal operation [uuid] */
    suspend fun confirm(
        uuid: UUID,
        now: Instant
    ): WithdrawalConfirmationResult = db.serializable { conn ->
         // TODO login check
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
                it.getBoolean("out_no_op") -> WithdrawalConfirmationResult.UnknownOperation
                it.getBoolean("out_exchange_not_found") -> WithdrawalConfirmationResult.UnknownExchange
                it.getBoolean("out_balance_insufficient") -> WithdrawalConfirmationResult.BalanceInsufficient
                it.getBoolean("out_not_selected") -> WithdrawalConfirmationResult.NotSelected
                it.getBoolean("out_aborted") -> WithdrawalConfirmationResult.AlreadyAborted
                else -> WithdrawalConfirmationResult.Success
            }
        }
    }

    /** Get withdrawal operation [uuid] linked account username */
    suspend fun getUsername(uuid: UUID): String? = db.conn { conn -> 
        val stmt = conn.prepareStatement("""
            SELECT username
            FROM taler_withdrawal_operations
                JOIN bank_accounts ON wallet_bank_account=bank_account_id
                JOIN customers ON customer_id=owning_customer_id
            WHERE withdrawal_uuid=?
        """)
        stmt.setObject(1, uuid)
        stmt.oneOrNull { it.getString(1) }
    }

    /** Get withdrawal operation [uuid] */
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
              ,username
            FROM taler_withdrawal_operations
                JOIN bank_accounts ON wallet_bank_account=bank_account_id
                JOIN customers ON customer_id=owning_customer_id
            WHERE withdrawal_uuid=?
        """)
        stmt.setObject(1, uuid)
        stmt.oneOrNull {
            BankAccountGetWithdrawalResponse(
                amount = it.getAmount("amount", db.bankCurrency),
                selection_done = it.getBoolean("selection_done"),
                confirmation_done = it.getBoolean("confirmation_done"),
                aborted = it.getBoolean("aborted"),
                selected_exchange_account = it.getString("selected_exchange_payto"),
                selected_reserve_pub = it.getBytes("reserve_pub")?.run(::EddsaPublicKey),
                username = it.getString("username")
            )
        }
    }

    /** Pool public status of operation [uuid] */
    suspend fun pollStatus(uuid: UUID, params: StatusParams): BankWithdrawalOperationStatus? {
        suspend fun load(): BankWithdrawalOperationStatus? = db.conn { conn ->
            val stmt = conn.prepareStatement("""
                SELECT
                  CASE 
                    WHEN confirmation_done THEN 'confirmed'
                    WHEN aborted THEN 'aborted'
                    WHEN selection_done THEN 'selected'
                    ELSE 'pending'
                  END as status
                  ,(amount).val as amount_val
                  ,(amount).frac as amount_frac
                  ,selection_done     
                  ,aborted     
                  ,confirmation_done      
                  ,internal_payto_uri
                  ,reserve_pub
                  ,selected_exchange_payto 
                FROM taler_withdrawal_operations
                    JOIN bank_accounts ON (wallet_bank_account=bank_account_id)
                WHERE withdrawal_uuid=?
            """)
            stmt.setObject(1, uuid)
            stmt.oneOrNull {
                BankWithdrawalOperationStatus(
                    status = WithdrawalStatus.valueOf(it.getString("status")),
                    amount = it.getAmount("amount", db.bankCurrency),
                    selection_done = it.getBoolean("selection_done"),
                    transfer_done = it.getBoolean("confirmation_done"),
                    aborted = it.getBoolean("aborted"),
                    sender_wire = it.getString("internal_payto_uri"),
                    confirm_transfer_url = null,
                    suggested_exchange = null,
                    selected_exchange_account = it.getString("selected_exchange_payto"),
                    selected_reserve_pub = it.getBytes("reserve_pub")?.run(::EddsaPublicKey),
                )
            }
        }
            

        return if (params.polling.poll_ms > 0) {
            db.notifWatcher.listenWithdrawals(uuid) { flow ->
                coroutineScope {
                    // Start buffering notification before loading transactions to not miss any
                    val polling = launch {
                        withTimeoutOrNull(params.polling.poll_ms) {
                            flow.first { it != params.old_state }
                        }
                    }    
                    // Initial loading
                    val init = load()
                    // Long polling if there is no operation or its not confirmed
                    if (init?.run { status == params.old_state } ?: true) {
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
}