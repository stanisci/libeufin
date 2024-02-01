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

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.common.*
import java.time.*
import java.sql.Types
import tech.libeufin.bank.*

private val logger: Logger = LoggerFactory.getLogger("libeufin-bank-tx-dao")

/** Data access logic for transactions */
class TransactionDAO(private val db: Database) {
    /** Result status of bank transaction creation .*/
    sealed interface BankTransactionResult {
        data class Success(val id: Long): BankTransactionResult
        data object UnknownCreditor: BankTransactionResult
        data object AdminCreditor: BankTransactionResult
        data object UnknownDebtor: BankTransactionResult
        data object BothPartySame: BankTransactionResult
        data object BalanceInsufficient: BankTransactionResult
        data object TanRequired: BankTransactionResult
    }

    /** Create a new transaction */
    suspend fun create(
        creditAccountPayto: IbanPayto,
        debitAccountUsername: String,
        subject: String,
        amount: TalerAmount,
        timestamp: Instant,
        is2fa: Boolean
    ): BankTransactionResult = db.serializable { conn ->
        val now = timestamp.toDbMicros() ?: throw faultyTimestampByBank();
        conn.transaction {
            val stmt = conn.prepareStatement("""
                SELECT 
                    out_creditor_not_found 
                    ,out_debtor_not_found
                    ,out_same_account
                    ,out_balance_insufficient
                    ,out_tan_required
                    ,out_credit_bank_account_id
                    ,out_debit_bank_account_id
                    ,out_credit_row_id
                    ,out_debit_row_id
                    ,out_creditor_is_exchange 
                    ,out_debtor_is_exchange
                    ,out_creditor_admin
                FROM bank_transaction(?,?,?,(?,?)::taler_amount,?,?)
            """
            )
            stmt.setString(1, creditAccountPayto.canonical)
            stmt.setString(2, debitAccountUsername)
            stmt.setString(3, subject)
            stmt.setLong(4, amount.value)
            stmt.setInt(5, amount.frac)
            stmt.setLong(6, now)
            stmt.setBoolean(7, is2fa)
            stmt.executeQuery().use {
                when {
                    !it.next() -> throw internalServerError("Bank transaction didn't properly return")
                    it.getBoolean("out_creditor_not_found") -> BankTransactionResult.UnknownCreditor
                    it.getBoolean("out_debtor_not_found") -> BankTransactionResult.UnknownDebtor
                    it.getBoolean("out_same_account") -> BankTransactionResult.BothPartySame
                    it.getBoolean("out_balance_insufficient") -> BankTransactionResult.BalanceInsufficient
                    it.getBoolean("out_creditor_admin") -> BankTransactionResult.AdminCreditor
                    it.getBoolean("out_tan_required") -> BankTransactionResult.TanRequired
                    else -> {
                        val creditAccountId = it.getLong("out_credit_bank_account_id")
                        val creditRowId = it.getLong("out_credit_row_id")
                        val debitAccountId = it.getLong("out_debit_bank_account_id")
                        val debitRowId = it.getLong("out_debit_row_id")
                        val exchangeCreditor = it.getBoolean("out_creditor_is_exchange")
                        val exchangeDebtor = it.getBoolean("out_debtor_is_exchange")
                        if (exchangeCreditor && exchangeDebtor) {
                            logger.warn("exchange account $exchangeDebtor sent a manual transaction to exchange account $exchangeCreditor, this should never happens and is not bounced to prevent bouncing loop, may fail in the future")
                        } else if (exchangeCreditor) {
                            val reservePub = parseIncomingTxMetadata(subject)
                            val bounceCause = if (reservePub != null) {
                                val registered = conn.prepareStatement("CALL register_incoming(?, ?)").run {
                                    setBytes(1, reservePub.raw)
                                    setLong(2, creditRowId)
                                    executeProcedureViolation()
                                }
                                if (!registered) {
                                    logger.warn("exchange account $creditAccountId received an incoming taler transaction $creditRowId with an already used reserve public key")
                                    "reserve public key reuse"
                                } else {
                                    null
                                }
                            } else {
                                logger.warn("exchange account $creditAccountId received a manual transaction $creditRowId with malformed metadata")
                                "malformed metadata"
                            }
                            if (bounceCause != null) {
                                // No error can happens because an opposite transaction already took place in the same transaction
                                conn.prepareStatement("""
                                    SELECT bank_wire_transfer(
                                        ?, ?, ?, (?, ?)::taler_amount, ?,
                                        NULL, NULL, NULL
                                    );
                                """
                                ).run {
                                    setLong(1, debitAccountId)
                                    setLong(2, creditAccountId)
                                    setString(3, "Bounce $creditRowId: $bounceCause")
                                    setLong(4, amount.value)
                                    setInt(5, amount.frac)
                                    setLong(6, now)
                                    executeQuery()
                                }
                            }
                        } else if (exchangeDebtor) {
                            logger.warn("exchange account $debitAccountId sent a manual transaction $debitRowId which will not be recorderd as a taler outgoing transaction, use the API instead")
                        }
                        BankTransactionResult.Success(debitRowId)
                    }
                }
            }
        }
    }
    
    /** Get transaction [rowId] owned by [login] */
    suspend fun get(rowId: Long, login: String): BankAccountTransactionInfo? = db.conn { conn ->
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
              ,bank_transaction_id
            FROM bank_account_transactions
                JOIN bank_accounts ON bank_account_transactions.bank_account_id=bank_accounts.bank_account_id
                JOIN customers ON customer_id=owning_customer_id 
	        WHERE bank_transaction_id=? AND login=?
        """)
        stmt.setLong(1, rowId)
        stmt.setString(2, login)
        stmt.oneOrNull {
            BankAccountTransactionInfo(
                creditor_payto_uri = it.getFullPayto("creditor_payto_uri", "creditor_name"),
                debtor_payto_uri = it.getFullPayto("debtor_payto_uri", "debtor_name"),
                amount = it.getAmount("amount", db.bankCurrency),
                direction = TransactionDirection.valueOf(it.getString("direction")),
                subject = it.getString("subject"),
                date = it.getTalerTimestamp("transaction_date"),
                row_id = it.getLong("bank_transaction_id")
            )
        }
    }

    /** Pool [accountId] transactions history */
    suspend fun pollHistory(
        params: HistoryParams, 
        accountId: Long
    ): List<BankAccountTransactionInfo> {
        return db.poolHistory(params, accountId, NotificationWatcher::listenBank,  """
            SELECT
                bank_transaction_id
                ,transaction_date
                ,(amount).val AS amount_val
                ,(amount).frac AS amount_frac
                ,debtor_payto_uri
                ,debtor_name
                ,creditor_payto_uri
                ,creditor_name
                ,subject
                ,direction
            FROM bank_account_transactions WHERE
        """) {
            BankAccountTransactionInfo(
                row_id = it.getLong("bank_transaction_id"),
                date = it.getTalerTimestamp("transaction_date"),
                creditor_payto_uri = it.getFullPayto("creditor_payto_uri", "creditor_name"),
                debtor_payto_uri = it.getFullPayto("debtor_payto_uri", "debtor_name"),
                amount = it.getAmount("amount", db.bankCurrency),
                subject = it.getString("subject"),
                direction = TransactionDirection.valueOf(it.getString("direction"))
            )
        }
    }

    /** Query [accountId] history of incoming transactions to its account */
    suspend fun revenueHistory(
        params: HistoryParams, 
        accountId: Long
    ): List<RevenueIncomingBankTransaction> 
        = db.poolHistory(params, accountId, NotificationWatcher::listenRevenue, """
            SELECT
                bank_transaction_id
                ,transaction_date
                ,(amount).val AS amount_val
                ,(amount).frac AS amount_frac
                ,debtor_payto_uri
                ,debtor_name
                ,subject
            FROM bank_account_transactions WHERE direction='credit' AND
        """) {
            RevenueIncomingBankTransaction(
                row_id = it.getLong("bank_transaction_id"),
                date = it.getTalerTimestamp("transaction_date"),
                amount = it.getAmount("amount", db.bankCurrency),
                debit_account = it.getFullPayto("debtor_payto_uri", "debtor_name"),
                subject = it.getString("subject")
            )
        }
}