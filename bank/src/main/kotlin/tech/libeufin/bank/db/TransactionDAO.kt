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

/** Data access logic for transactions */
class TransactionDAO(private val db: Database) {
    /** Result status of bank transaction creation .*/
    sealed class BankTransactionResult {
        data class Success(val id: Long): BankTransactionResult()
        object UnknownCreditor: BankTransactionResult()
        object UnknownDebtor: BankTransactionResult()
        object BothPartySame: BankTransactionResult()
        object BalanceInsufficient: BankTransactionResult()
        
    }

    /** Create a new transaction */
    suspend fun create(
        creditAccountPayto: IbanPayTo,
        debitAccountUsername: String,
        subject: String,
        amount: TalerAmount,
        timestamp: Instant,
    ): BankTransactionResult = db.serializable { conn ->
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
                    it.getBoolean("out_creditor_not_found") -> BankTransactionResult.UnknownCreditor
                    it.getBoolean("out_debtor_not_found") -> BankTransactionResult.UnknownDebtor
                    it.getBoolean("out_same_account") -> BankTransactionResult.BothPartySame
                    it.getBoolean("out_balance_insufficient") -> BankTransactionResult.BalanceInsufficient
                    else -> {
                        val creditAccountId = it.getLong("out_credit_bank_account_id")
                        val creditRowId = it.getLong("out_credit_row_id")
                        val debitAccountId = it.getLong("out_debit_bank_account_id")
                        val debitRowId = it.getLong("out_debit_row_id")
                        val metadata = TxMetadata.parse(subject)
                        if (it.getBoolean("out_creditor_is_exchange")) {
                            if (metadata is IncomingTxMetadata) {
                                conn.prepareStatement("CALL register_incoming(?, ?)").run {
                                    setBytes(1, metadata.reservePub.raw)
                                    setLong(2, creditRowId)
                                    executeUpdate() // TODO check reserve pub reuse
                                }
                            } else {
                                // TODO bounce
                                logger.warn("exchange account $creditAccountId received a transaction $creditRowId with malformed metadata, will bounce in future version")
                            }
                        }
                        if (it.getBoolean("out_debtor_is_exchange")) {
                            if (metadata is OutgoingTxMetadata) {
                                conn.prepareStatement("CALL register_outgoing(NULL, ?, ?, ?, ?, ?, ?)").run {
                                    setBytes(1, metadata.wtid.raw)
                                    setString(2, metadata.exchangeBaseUrl.url)
                                    setLong(3, debitAccountId)
                                    setLong(4, creditAccountId)
                                    setLong(5, debitRowId)
                                    setLong(6, creditRowId)
                                    executeUpdate() // TODO check wtid reuse
                                }
                            } else {
                                logger.warn("exchange account $debitAccountId sent a transaction $debitRowId with malformed metadata")
                            }
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
              ,debtor_payto_uri
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
                creditor_payto_uri = it.getString("creditor_payto_uri"),
                debtor_payto_uri = it.getString("debtor_payto_uri"),
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
                ,creditor_payto_uri
                ,subject
                ,direction
            FROM bank_account_transactions
        """) {
            BankAccountTransactionInfo(
                row_id = it.getLong("bank_transaction_id"),
                date = it.getTalerTimestamp("transaction_date"),
                debtor_payto_uri = it.getString("debtor_payto_uri"),
                creditor_payto_uri = it.getString("creditor_payto_uri"),
                amount = it.getAmount("amount", db.bankCurrency),
                subject = it.getString("subject"),
                direction = TransactionDirection.valueOf(it.getString("direction"))
            )
        }
    }
}