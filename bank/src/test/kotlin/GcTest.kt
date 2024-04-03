/*
 * This file is part of LibEuFin.
 * Copyright (C) 2024 Taler Systems S.A.

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

import org.junit.Test
import tech.libeufin.bank.DecimalNumber
import tech.libeufin.bank.db.TransactionDAO.BankTransactionResult
import tech.libeufin.bank.db.WithdrawalDAO.*
import tech.libeufin.bank.db.TransactionDAO.*
import tech.libeufin.bank.db.CashoutDAO.CashoutCreationResult
import tech.libeufin.bank.db.ExchangeDAO.TransferResult
import tech.libeufin.common.*
import tech.libeufin.common.db.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import tech.libeufin.bank.*
import java.time.*
import java.util.*
import kotlin.test.*

class GcTest {
    @Test
    fun gc() = bankSetup { db -> db.conn { conn ->
        suspend fun assertNb(nb: Int, stmt: String) {
            assertEquals(nb, conn.prepareStatement(stmt).one { it.getInt(1) })
        }
        suspend fun assertNbAccount(nb: Int) = assertNb(nb, "SELECT count(*) from bank_accounts")
        suspend fun assertNbTokens(nb: Int) = assertNb(nb, "SELECT count(*) from bearer_tokens")
        suspend fun assertNbTan(nb: Int) = assertNb(nb, "SELECT count(*) from tan_challenges")
        suspend fun assertNbCashout(nb: Int) = assertNb(nb, "SELECT count(*) from cashout_operations")
        suspend fun assertNbWithdrawal(nb: Int) = assertNb(nb, "SELECT count(*) from taler_withdrawal_operations")
        suspend fun assertNbBankTx(nb: Int) = assertNb(nb, "SELECT count(*) from bank_transaction_operations")
        suspend fun assertNbTx(nb: Int) = assertNb(nb, "SELECT count(*) from bank_account_transactions")
        suspend fun assertNbIncoming(nb: Int) = assertNb(nb, "SELECT count(*) from taler_exchange_incoming")
        suspend fun assertNbOutgoing(nb: Int) = assertNb(nb, "SELECT count(*) from taler_exchange_outgoing")

        // Time calculation
        val abortAfter = Duration.ofMinutes(15)
        val cleanAfter = Duration.ofDays(14)
        val deleteAfter = Duration.ofDays(350)
        val now = Instant.now()
        val abort = now.minus(abortAfter)
        val clean = now.minus(cleanAfter)
        val delete = now.minus(deleteAfter)

        // Create test accounts
        val payto = IbanPayto.rand()
        val oldPayto = client.post("/accounts") {
            json {
                "username" to "old_account"
                "password" to "old_account-password"
                "name" to "Old Account"
                "cashout_payto_uri" to payto
            }
        }.assertOkJson<RegisterAccountResponse>().internal_payto_uri
        val recentPayto = client.post("/accounts") {
            json {
                "username" to "recent_account"
                "password" to "recent_account-password"
                "name" to "Recent Account"
                "cashout_payto_uri" to payto
            }

        }.assertOkJson<RegisterAccountResponse>().internal_payto_uri
        assertNbAccount(6)

        // Create test tokens
        for (time in listOf(now, clean)) {
            for (account in listOf("old_account", "recent_account")) {
                assert(db.token.create(account, ByteArray(32).rand(), time, time, TokenScope.readonly, false))
                db.tan.new(account, Operation.cashout, "", "", time, 0, Duration.ZERO, null, null)
            }
        }
        assertNbTokens(4)
        assertNbTan(4)

        // Create test operations
        val from = TalerAmount("KUDOS:1")
        val to = convert("KUDOS:1")
        for ((account, times) in listOf(
            Pair("old_account", listOf(delete)),
            Pair("recent_account", listOf(now, abort, clean, delete))
        )) {
            for (time in times) {
                val uuid = UUID.randomUUID()
                assertEquals(
                    db.withdrawal.create(account, uuid, from, time),
                    WithdrawalCreationResult.Success
                )
                assertIs<WithdrawalSelectionResult.Success>(
                    db.withdrawal.setDetails(uuid, exchangePayto, EddsaPublicKey.rand())
                )
                assertEquals(
                    db.withdrawal.confirm(account, uuid, time, false),
                    WithdrawalConfirmationResult.Success
                )
                assertIs<CashoutCreationResult.Success>(
                    db.cashout.create(account, ShortHashCode.rand(), from, to, "", time, false),
                )
                assertIs<BankTransactionResult.Success>(
                    db.transaction.create(customerPayto, account, "", from, time, false, ShortHashCode.rand()),
                )
            }
            for (time in listOf(now, abort, clean, delete)) {
                assertEquals(
                    db.withdrawal.create(account, UUID.randomUUID(), from, time),
                    WithdrawalCreationResult.Success
                )
            }
        }
        for (time in listOf(now, abort, clean, delete)) {
            assertIs<TransferResult.Success>(
                db.exchange.transfer(
                    TransferRequest(HashCode.rand(), from, ExchangeUrl("http://localhost"), ShortHashCode.rand(), customerPayto),
                    "exchange", time
                )
            )
        }
        assertNbTx(38)
        assertNbCashout(5)
        assertNbBankTx(5)
        assertNbWithdrawal(13)
        assertNbIncoming(5)
        assertNbOutgoing(4)

        // Check soft delete
        conn.execSQLUpdate("UPDATE bank_accounts SET balance = (0, 0)::taler_amount")
        for (account in listOf("old_account", "recent_account")) {
            client.deleteA("/accounts/$account").assertNoContent()
        }
        assertNbAccount(6)

        db.gc.collect(
            Instant.now(),
            abortAfter,
            cleanAfter,
            deleteAfter
        )
        // Check hard delete
        assertNbAccount(5)
        assertNbTokens(1)
        assertNbTan(1)
        assertNbTx(24)
        assertNbCashout(3)
        assertNbBankTx(3)
        assertNbWithdrawal(4)
        assertNbIncoming(3)
        assertNbOutgoing(3)
    }}
}