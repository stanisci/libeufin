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

import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.engine.*
import io.ktor.server.testing.*
import java.time.Duration
import java.time.Instant
import java.util.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonElement
import net.taler.common.errorcodes.TalerErrorCode
import net.taler.wallet.crypto.Base32Crockford
import org.junit.Test
import tech.libeufin.bank.*

class CoreBankConfigTest {
    // GET /config
    @Test
    fun config() = bankSetup { _ -> 
        client.get("/config").assertOk()
    }

    // GET /monitor
    @Test
    fun monitor() = bankSetup { _ -> 
        // Check OK
        client.get("/monitor?timeframe=hour") {
            pwAuth("admin")
        }.assertOk()

        // Check only admin
        client.get("/monitor") {
            pwAuth("exchange")
        }.assertUnauthorized()
    }
}

class CoreBankTokenApiTest {
    // POST /accounts/USERNAME/token
    @Test
    fun post() = bankSetup { db -> 
        // Wrong user
        client.post("/accounts/merchant/token") {
            pwAuth("exchange")
        }.assertUnauthorized()

        // New default token
        client.postA("/accounts/merchant/token") {
            json { "scope" to "readonly" }
        }.assertOkJson<TokenSuccessResponse> {
            // Checking that the token lifetime defaulted to 24 hours.
            val token = db.token.get(Base32Crockford.decode(it.access_token))
            val lifeTime = Duration.between(token!!.creationTime, token.expirationTime)
            assertEquals(Duration.ofDays(1), lifeTime)
        }

        // Check default duration
        client.postA("/accounts/merchant/token") {
            json { "scope" to "readonly" }
        }.assertOkJson<TokenSuccessResponse> {
            // Checking that the token lifetime defaulted to 24 hours.
            val token = db.token.get(Base32Crockford.decode(it.access_token))
            val lifeTime = Duration.between(token!!.creationTime, token.expirationTime)
            assertEquals(Duration.ofDays(1), lifeTime)
        }

        // Check refresh
        client.postA("/accounts/merchant/token") {
            json { 
                "scope" to "readonly"
                "refreshable" to true
            }
        }.assertOkJson<TokenSuccessResponse> {
            val token = it.access_token
            client.post("/accounts/merchant/token") {
                headers["Authorization"] = "Bearer secret-token:$token"
                json { "scope" to "readonly" }
            }.assertOk()
        }
        
        // Check'forever' case.
        client.postA("/accounts/merchant/token") {
            json { 
                "scope" to "readonly"
                "duration" to obj {
                    "d_us" to "forever"
                }
            }
        }.run {
            val never: TokenSuccessResponse = json()
            assertEquals(Instant.MAX, never.expiration.t_s)
        }

        // Check too big or invalid durations
        client.postA("/accounts/merchant/token") {
            json { 
                "scope" to "readonly"
                "duration" to obj {
                    "d_us" to "invalid"
                }
            }
        }.assertBadRequest()
        client.postA("/accounts/merchant/token") {
            json { 
                "scope" to "readonly"
                "duration" to obj {
                    "d_us" to Long.MAX_VALUE
                }
            }
        }.assertBadRequest()
        client.postA("/accounts/merchant/token") {
            json { 
                "scope" to "readonly"
                "duration" to obj {
                    "d_us" to -1
                }
            }
        }.assertBadRequest()
    }

    // DELETE /accounts/USERNAME/token
    @Test
    fun delete() = bankSetup { _ -> 
        // TODO test restricted
        val token = client.post("/accounts/merchant/token") {
            pwAuth("merchant")
            json { "scope" to "readonly" }
        }.assertOkJson<TokenSuccessResponse>().access_token
        // Check OK
        client.delete("/accounts/merchant/token") {
            headers["Authorization"] = "Bearer secret-token:$token"
        }.assertNoContent()
        // Check token no longer work
        client.delete("/accounts/merchant/token") {
            headers["Authorization"] = "Bearer secret-token:$token"
        }.assertUnauthorized()

        // Checking merchant can still be served by basic auth, after token deletion.
        client.get("/accounts/merchant") {
            pwAuth("merchant")
        }.assertOk()
    }
}

class CoreBankAccountsApiTest {
    // Testing the account creation and its idempotency
    @Test
    fun createAccountTest() = bankSetup { _ -> 
        val ibanPayto = genIbanPaytoUri()
        val req = obj {
            "username" to "foo"
            "password" to "password"
            "name" to "Jane"
            "is_public" to true
            "internal_payto_uri" to ibanPayto
            "is_taler_exchange" to true
        }
        // Check Ok
        client.post("/accounts") {
            json(req)
        }.assertCreated()
        // Testing idempotency
        client.post("/accounts") {
            json(req)
        }.assertCreated()

        // Test generate payto_uri
        client.post("/accounts") {
            json {
                "username" to "jor"
                "password" to "password"
                "name" to "Joe"
            }
        }.assertCreated()

        // Reserved account
        reservedAccounts.forEach {
            client.post("/accounts") {
                json {
                    "username" to it
                    "password" to "password"
                    "name" to "John Smith"
                }
            }.assertConflict(TalerErrorCode.BANK_RESERVED_USERNAME_CONFLICT)
        }

        // Testing login conflict
        client.post("/accounts") {
            json(req) {
                "name" to "Foo"
            }
        }.assertConflict(TalerErrorCode.BANK_REGISTER_USERNAME_REUSE)
        // Testing payto conflict
        client.post("/accounts") {
            json(req) {
                "username" to "bar"
            }
        }.assertConflict(TalerErrorCode.BANK_REGISTER_PAYTO_URI_REUSE)
        client.get("/accounts/bar") {
            pwAuth("admin")
        }.assertNotFound(TalerErrorCode.BANK_UNKNOWN_ACCOUNT)
    }

    // Test account created with bonus
    @Test
    fun createAccountBonusTest() = bankSetup(conf = "test_bonus.conf") { _ -> 
        val req = obj {
            "username" to "foo"
            "password" to "xyz"
            "name" to "Mallory"
        }

        // Check ok
        repeat(100) {
            client.post("/accounts") {
                pwAuth("admin")
                json(req) {
                    "username" to "foo$it"
                }
            }.assertCreated()
            assertBalance("foo$it", CreditDebitInfo.credit, "KUDOS:100")
        }
        assertBalance("admin", CreditDebitInfo.debit, "KUDOS:10000")
        
        // Check unsufficient fund
        client.post("/accounts") {
            pwAuth("admin")
            json(req) {
                "username" to "bar"
            }
        }.assertConflict(TalerErrorCode.BANK_UNALLOWED_DEBIT)
        client.get("/accounts/bar") {
            pwAuth("admin")
        }.assertNotFound(TalerErrorCode.BANK_UNKNOWN_ACCOUNT)
    }

    // Test admin-only account creation
    @Test
    fun createAccountRestrictedTest() = bankSetup(conf = "test_restrict.conf") { _ -> 
        val req = obj {
            "username" to "baz"
            "password" to "xyz"
            "name" to "Mallory"
        }

        client.post("/accounts") {
            pwAuth("merchant")
            json(req)
        }.assertUnauthorized()
        client.post("/accounts") {
            pwAuth("admin")
            json(req)
        }.assertCreated()
    }

    // DELETE /accounts/USERNAME
    @Test
    fun deleteAccount() = bankSetup { _ -> 
        // Unknown account
        client.delete("/accounts/unknown") {
            pwAuth("admin")
        }.assertNotFound(TalerErrorCode.BANK_UNKNOWN_ACCOUNT)

        // Reserved account
        reservedAccounts.forEach {
            client.delete("/accounts/$it") {
                pwAuth("admin")
            }.assertConflict(TalerErrorCode.BANK_RESERVED_USERNAME_CONFLICT)
        }
       
        // successful deletion
        client.post("/accounts") {
            json {
                "username" to "john"
                "password" to "password"
                "name" to "John Smith"
            }
        }.assertCreated()
        client.delete("/accounts/john") {
            pwAuth("admin")
        }.assertNoContent()
        // Trying again must yield 404
        client.delete("/accounts/john") {
            pwAuth("admin")
        }.assertNotFound(TalerErrorCode.BANK_UNKNOWN_ACCOUNT)

        
        // fail to delete, due to a non-zero balance.
        tx("exchange", "KUDOS:1", "merchant")
        client.delete("/accounts/merchant") {
            pwAuth("admin")
        }.assertConflict(TalerErrorCode.BANK_ACCOUNT_BALANCE_NOT_ZERO)
        tx("merchant", "KUDOS:1", "exchange")
        client.delete("/accounts/merchant") {
            pwAuth("admin")
        }.assertNoContent()
    }

    // PATCH /accounts/USERNAME
    @Test
    fun accountReconfig() = bankSetup { _ -> 
        // Successful attempt now.
        val cashout = IbanPayTo(genIbanPaytoUri())
        val req = obj {
            "cashout_payto_uri" to cashout.canonical
            "challenge_contact_data" to obj {
                "phone" to "+99"
                "email" to "foo@example.com"
            }
            "is_taler_exchange" to true 
        }
        client.patchA("/accounts/merchant") {
            json(req)
        }.assertNoContent()
        // Checking idempotence.
        client.patchA("/accounts/merchant") {
            json(req)
        }.assertNoContent()

        suspend fun checkAdminOnly(req: JsonElement, error: TalerErrorCode) {
            // Checking ordinary user doesn't get to patch
            client.patchA("/accounts/merchant") {
                json(req)
            }.assertConflict(error)
            // Finally checking that admin does get to patch
            client.patch("/accounts/merchant") {
                pwAuth("admin")
                json(req)
            }.assertNoContent()
        }

        checkAdminOnly(
            obj(req) { "name" to "Another Foo" },
            TalerErrorCode.BANK_NON_ADMIN_PATCH_LEGAL_NAME
        )
        checkAdminOnly(
            obj(req) { "debit_threshold" to "KUDOS:100" },
            TalerErrorCode.BANK_NON_ADMIN_PATCH_DEBT_LIMIT
        )

        // Check admin account cannot be exchange
        client.patchA("/accounts/admin") {
            json { "is_taler_exchange" to true }
        }.assertConflict(TalerErrorCode.BANK_PATCH_ADMIN_EXCHANGE)
        // But we can change its debt limit
        client.patchA("/accounts/admin") {
            json { "debit_threshold" to "KUDOS:100" }
        }.assertNoContent()
        
        // Check currency
        client.patch("/accounts/merchant") {
            pwAuth("admin")
            json(req) { "debit_threshold" to "EUR:100" }
        }.assertBadRequest()

        // Check patch
        client.get("/accounts/merchant") {
            pwAuth("admin")
        }.assertOkJson<AccountData> { obj ->
            assertEquals("Another Foo", obj.name)
            assertEquals(cashout.canonical, obj.cashout_payto_uri)
            assertEquals("+99", obj.contact_data?.phone)
            assertEquals("foo@example.com", obj.contact_data?.email)
            assertEquals(TalerAmount("KUDOS:100"), obj.debit_threshold)
        }
    }

    // PATCH /accounts/USERNAME/auth
    @Test
    fun passwordChangeTest() = bankSetup { _ -> 
        // Changing the password.
        client.patch("/accounts/customer/auth") {
            basicAuth("customer", "customer-password")
            json {
                "old_password" to "customer-password"
                "new_password" to "new-password"
            }
        }.assertNoContent()
        // Previous password should fail.
        client.patch("/accounts/customer/auth") {
            basicAuth("customer", "customer-password")
        }.assertUnauthorized()
        // New password should succeed.
        client.patch("/accounts/customer/auth") {
            basicAuth("customer", "new-password")
            json {
                "old_password" to "new-password"
                "new_password" to "customer-password"
            }
        }.assertNoContent()


        // Check require test old password
        client.patch("/accounts/customer/auth") {
            basicAuth("customer", "customer-password")
            json {
                "old_password" to "bad-password"
                "new_password" to "new-password"
            }
        }.assertConflict(TalerErrorCode.BANK_PATCH_BAD_OLD_PASSWORD)

        // Check require old password for user
        client.patch("/accounts/customer/auth") {
            basicAuth("customer", "customer-password")
            json {
                "new_password" to "new-password"
            }
        }.assertConflict(TalerErrorCode.BANK_NON_ADMIN_PATCH_MISSING_OLD_PASSWORD)

        // Check admin 
        client.patch("/accounts/customer/auth") {
            pwAuth("admin")
            json {
                "new_password" to "new-password"
            }
        }.assertNoContent()
    }

    // GET /public-accounts and GET /accounts
    @Test
    fun accountsListTest() = bankSetup { _ -> 
        // Remove default accounts
        listOf("merchant", "exchange", "customer").forEach {
            client.delete("/accounts/$it") {
                pwAuth("admin")
            }.assertNoContent()
        }
        // Check error when no public accounts
        client.get("/public-accounts").assertNoContent()
        client.get("/accounts") {
            pwAuth("admin")
        }.assertOk()
        
        // Gen some public and private accounts
        repeat(5) {
            client.post("/accounts") {
                json {
                    "username" to "$it"
                    "password" to "password"
                    "name" to "Mr $it"
                    "is_public" to (it%2 == 0)
                }
            }.assertCreated()
        }
        // All public
        client.get("/public-accounts").run {
            assertOk()
            val obj = json<PublicAccountsResponse>()
            assertEquals(3, obj.public_accounts.size)
            obj.public_accounts.forEach {
                assertEquals(0, it.account_name.toInt() % 2)
            }
        }
        // All accounts
        client.get("/accounts?delta=10"){
            pwAuth("admin")
        }.run {
            assertOk()
            val obj = json<ListBankAccountsResponse>()
            assertEquals(6, obj.accounts.size)
            obj.accounts.forEachIndexed { idx, it ->
                if (idx == 0) {
                    assertEquals("admin", it.username)
                } else {
                    assertEquals(idx - 1, it.username.toInt())
                }
            }
        }
        // Filtering
        client.get("/accounts?filter_name=3"){
            pwAuth("admin")
        }.run {
            assertOk()
            val obj = json<ListBankAccountsResponse>()
            assertEquals(1, obj.accounts.size)
            assertEquals("3", obj.accounts[0].username)
        }
    }

    // GET /accounts/USERNAME
    @Test
    fun getAccountTest() = bankSetup { _ -> 
        // Check ok
        client.getA("/accounts/merchant").assertOkJson<AccountData> {
            assertEquals("Merchant", it.name)
        }

        // Check admin ok
        client.get("/accounts/merchant") {
            pwAuth("admin")
        }.assertOk()

        // Check wrong user
        client.get("/accounts/exchange") {
            pwAuth("merchant")
        }.assertUnauthorized()
    }
}

class CoreBankTransactionsApiTest {
    // Test endpoint is correctly authenticated 
    suspend fun ApplicationTestBuilder.authRoutine(path: String, withAdmin: Boolean = true, method: HttpMethod = HttpMethod.Post) {
        // No body when authentication must happen before parsing the body
        
        // Unknown account
        client.request(path) {
            this.method = method
            basicAuth("unknown", "password")
        }.assertUnauthorized()

        // Wrong password
        client.request(path) {
            this.method = method
            basicAuth("merchant", "wrong-password")
        }.assertUnauthorized()

        // Wrong account
        client.request(path) {
            this.method = method
            basicAuth("exchange", "merchant-password")
        }.assertUnauthorized()

        // TODO check admin rights
    }

    // GET /transactions
    @Test
    fun testHistory() = bankSetup { _ -> 
        suspend fun HttpResponse.assertHistory(size: Int) {
            assertHistoryIds<BankAccountTransactionsResponse>(size) {
                it.transactions.map { it.row_id }
            }
        }

        authRoutine("/accounts/merchant/transactions", method = HttpMethod.Get)

        // Check error when no transactions
        client.get("/accounts/merchant/transactions") {
            pwAuth("merchant")
        }.assertNoContent()
        
        // Gen three transactions from merchant to exchange
        repeat(3) {
            tx("merchant", "KUDOS:0.$it", "exchange")
        }
        // Gen two transactions from exchange to merchant
        repeat(2) {
            tx("exchange", "KUDOS:0.$it", "merchant")
        }

        // Check no useless polling
        assertTime(0, 100) {
            client.get("/accounts/merchant/transactions?delta=-6&start=11&long_poll_ms=1000") {
                pwAuth("merchant")
            }.assertHistory(5)
        }

        // Check no polling when find transaction
        assertTime(0, 100) {
            client.getA("/accounts/merchant/transactions?delta=6&long_poll_ms=1000") 
                .assertHistory(5)
        }

        coroutineScope {
            launch { // Check polling succeed
                assertTime(100, 200) {
                    client.getA("/accounts/merchant/transactions?delta=2&start=10&long_poll_ms=1000")
                        .assertHistory(1)
                }
            }
            launch { // Check polling timeout
                assertTime(200, 300) {
                    client.getA("/accounts/merchant/transactions?delta=1&start=11&long_poll_ms=200")
                        .assertNoContent()
                }
            }
            delay(100)
            tx("merchant", "KUDOS:4.2", "exchange")
        }

        // Testing ranges. 
        repeat(30) {
            tx("merchant", "KUDOS:0.001", "exchange")
        }

        // forward range:
        client.getA("/accounts/merchant/transactions?delta=10&start=20")
            .assertHistory(10)

        // backward range:
        client.getA("/accounts/merchant/transactions?delta=-10&start=25")
            .assertHistory(10)
    }

    // GET /transactions/T_ID
    @Test
    fun testById() = bankSetup { _ -> 
        authRoutine("/accounts/merchant/transactions/1", method = HttpMethod.Get)

        // Create transaction
        tx("merchant", "KUDOS:0.3", "exchange", "tx")
        // Check OK
        client.getA("/accounts/merchant/transactions/1")
            .assertOkJson<BankAccountTransactionInfo> { tx ->
            assertEquals("tx", tx.subject)
            assertEquals(TalerAmount("KUDOS:0.3"), tx.amount)
        }
        // Check unknown transaction
        client.getA("/accounts/merchant/transactions/3")
            .assertNotFound(TalerErrorCode.BANK_TRANSACTION_NOT_FOUND)
        // Check another user's transaction
        client.getA("/accounts/merchant/transactions/2")
            .assertNotFound(TalerErrorCode.BANK_TRANSACTION_NOT_FOUND)
    }

    // POST /transactions
    @Test
    fun create() = bankSetup { _ -> 
        val valid_req = obj {
            "payto_uri" to "$exchangePayto?message=payout"
            "amount" to "KUDOS:0.3"
        }

        authRoutine("/accounts/merchant/transactions")

        // Check OK
        client.postA("/accounts/merchant/transactions") {
            json(valid_req)
        }.assertOkJson<TransactionCreateResponse> {
            client.getA("/accounts/merchant/transactions/${it.row_id}")
                .assertOkJson<BankAccountTransactionInfo> { tx ->
                assertEquals("payout", tx.subject)
                assertEquals(TalerAmount("KUDOS:0.3"), tx.amount)
            }
        }
        
        // Check amount in payto_uri
        client.postA("/accounts/merchant/transactions") {
            json {
                "payto_uri" to "$exchangePayto?message=payout2&amount=KUDOS:1.05"
            }
        }.assertOkJson <TransactionCreateResponse> {
            client.getA("/accounts/merchant/transactions/${it.row_id}")
                .assertOkJson<BankAccountTransactionInfo> { tx ->
                assertEquals("payout2", tx.subject)
                assertEquals(TalerAmount("KUDOS:1.05"), tx.amount)
            }
        }
       
        // Check amount in payto_uri precedence
        client.postA("/accounts/merchant/transactions") {
            json {
                "payto_uri" to "$exchangePayto?message=payout3&amount=KUDOS:1.05"
                "amount" to "KUDOS:10.003"
            }
        }.assertOkJson<TransactionCreateResponse> {
            client.getA("/accounts/merchant/transactions/${it.row_id}")
                .assertOkJson<BankAccountTransactionInfo> { tx ->
                assertEquals("payout3", tx.subject)
                assertEquals(TalerAmount("KUDOS:1.05"), tx.amount)
            }
        }
        // Testing the wrong currency
        client.postA("/accounts/merchant/transactions") {
            json(valid_req) {
                "amount" to "EUR:3.3"
            }
        }.assertBadRequest(TalerErrorCode.GENERIC_CURRENCY_MISMATCH)
        // Surpassing the debt limit
        client.postA("/accounts/merchant/transactions") {
            json(valid_req) {
                "amount" to "KUDOS:555"
            }
        }.assertConflict(TalerErrorCode.BANK_UNALLOWED_DEBIT)
        // Missing message
        client.postA("/accounts/merchant/transactions") {
            json(valid_req) {
                "payto_uri" to "$exchangePayto"
            }
        }.assertBadRequest()
        // Unknown creditor
        client.postA("/accounts/merchant/transactions") {
            json(valid_req) {
                "payto_uri" to "$unknownPayto?message=payout"
            }
        }.assertConflict(TalerErrorCode.BANK_UNKNOWN_CREDITOR)
        // Transaction to self
        client.postA("/accounts/merchant/transactions") {
            json(valid_req) {
                "payto_uri" to "$merchantPayto?message=payout"
            }
        }.assertConflict(TalerErrorCode.BANK_SAME_ACCOUNT)

        suspend fun checkBalance(
            merchantDebt: Boolean,
            merchantAmount: String,
            customerDebt: Boolean,
            customerAmount: String,
        ) {
            assertBalance("merchant", if (merchantDebt) CreditDebitInfo.debit else CreditDebitInfo.credit, merchantAmount)
            assertBalance("customer", if (customerDebt) CreditDebitInfo.debit else CreditDebitInfo.credit, customerAmount)
        }

        // Init state
        assertBalance("merchant", CreditDebitInfo.debit, "KUDOS:2.4")
        assertBalance("customer", CreditDebitInfo.credit, "KUDOS:0")
        // Send 2 times 3
        repeat(2) {
            tx("merchant", "KUDOS:3", "customer")
        }
        client.post("/accounts/merchant/transactions") {
            pwAuth("merchant")
            json {
                "payto_uri" to "$customerPayto?message=payout2&amount=KUDOS:3"
            }
        }.assertConflict(TalerErrorCode.BANK_UNALLOWED_DEBIT)
        assertBalance("merchant", CreditDebitInfo.debit, "KUDOS:8.4")
        assertBalance("customer", CreditDebitInfo.credit, "KUDOS:6")
        // Send throught debt
        client.post("/accounts/customer/transactions") {
            pwAuth("customer")
            json {
                "payto_uri" to "$merchantPayto?message=payout2&amount=KUDOS:10"
            }
        }.assertOk()
        assertBalance("merchant", CreditDebitInfo.credit, "KUDOS:1.6")
        assertBalance("customer", CreditDebitInfo.debit, "KUDOS:4")
    }
}

class CoreBankWithdrawalApiTest {
    // POST /accounts/USERNAME/withdrawals
    @Test
    fun create() = bankSetup { _ ->
        // Check OK
        client.postA("/accounts/merchant/withdrawals") {
            json { "amount" to "KUDOS:9.0" } 
        }.assertOk()

        // Check exchange account
        client.postA("/accounts/exchange/withdrawals") {
            json { "amount" to "KUDOS:9.0" } 
        }.assertConflict(TalerErrorCode.BANK_ACCOUNT_IS_EXCHANGE)

        // Check insufficient fund
        client.postA("/accounts/merchant/withdrawals") {
            json { "amount" to "KUDOS:90" } 
        }.assertConflict(TalerErrorCode.BANK_UNALLOWED_DEBIT)
    }

    // GET /withdrawals/withdrawal_id
    @Test
    fun get() = bankSetup { _ ->
        val amount = TalerAmount("KUDOS:9.0")
        // Check OK
        client.postA("/accounts/merchant/withdrawals") {
            json { "amount" to amount}
        }.assertOkJson<BankAccountCreateWithdrawalResponse> {
            client.get("/withdrawals/${it.withdrawal_id}") {
                pwAuth("merchant")
            }.assertOkJson<BankAccountGetWithdrawalResponse> {
                assert(!it.selection_done)
                assert(!it.aborted)
                assert(!it.confirmation_done)
                assertEquals(amount, it.amount)
                // TODO check all status
            }
        }

        // Check bad UUID
        client.get("/withdrawals/chocolate").assertBadRequest()

        // Check unknown
        client.get("/withdrawals/${UUID.randomUUID()}")
            .assertNotFound(TalerErrorCode.BANK_TRANSACTION_NOT_FOUND)
    }

    // POST /withdrawals/withdrawal_id/abort
    @Test
    fun abort() = bankSetup { _ ->
        // Check abort created
        client.postA("/accounts/merchant/withdrawals") {
            json { "amount" to "KUDOS:1" } 
        }.assertOkJson<BankAccountCreateWithdrawalResponse> {
            val uuid = it.taler_withdraw_uri.split("/").last()

            // Check OK
            client.post("/withdrawals/$uuid/abort").assertNoContent()
            // Check idempotence
            client.post("/withdrawals/$uuid/abort").assertNoContent()
        }

        // Check abort selected
        client.postA("/accounts/merchant/withdrawals") {
            json { "amount" to "KUDOS:1" } 
        }.assertOkJson<BankAccountCreateWithdrawalResponse> {
            val uuid = it.taler_withdraw_uri.split("/").last()
            withdrawalSelect(uuid)

            // Check OK
            client.post("/withdrawals/$uuid/abort").assertNoContent()
            // Check idempotence
            client.post("/withdrawals/$uuid/abort").assertNoContent()
        }

        // Check abort confirmed
        client.postA("/accounts/merchant/withdrawals") {
            json { "amount" to "KUDOS:1" } 
        }.assertOkJson<BankAccountCreateWithdrawalResponse> {
            val uuid = it.taler_withdraw_uri.split("/").last()
            withdrawalSelect(uuid)
            client.post("/withdrawals/$uuid/confirm").assertNoContent()

            // Check error
            client.post("/withdrawals/$uuid/abort")
                .assertConflict(TalerErrorCode.BANK_ABORT_CONFIRM_CONFLICT)
        }

        // Check bad UUID
        client.post("/withdrawals/chocolate/abort").assertBadRequest()

        // Check unknown
        client.post("/withdrawals/${UUID.randomUUID()}/abort")
            .assertNotFound(TalerErrorCode.BANK_TRANSACTION_NOT_FOUND)
    }

    // POST /withdrawals/withdrawal_id/confirm
    @Test
    fun confirm() = bankSetup { _ -> 
        // Check confirm created
        client.postA("/accounts/merchant/withdrawals") {
            json { "amount" to "KUDOS:1" } 
        }.assertOkJson<BankAccountCreateWithdrawalResponse> {
            val uuid = it.taler_withdraw_uri.split("/").last()

            // Check err
            client.post("/withdrawals/$uuid/confirm")
                .assertConflict(TalerErrorCode.BANK_CONFIRM_INCOMPLETE)
        }

        // Check confirm selected
        client.postA("/accounts/merchant/withdrawals") {
            json { "amount" to "KUDOS:1" } 
        }.assertOkJson<BankAccountCreateWithdrawalResponse> {
            val uuid = it.taler_withdraw_uri.split("/").last()
            withdrawalSelect(uuid)

            // Check OK
            client.post("/withdrawals/$uuid/confirm").assertNoContent()
            // Check idempotence
            client.post("/withdrawals/$uuid/confirm").assertNoContent()
        }

        // Check confirm aborted
        client.postA("/accounts/merchant/withdrawals") {
            json { "amount" to "KUDOS:1" } 
        }.assertOkJson<BankAccountCreateWithdrawalResponse> {
            val uuid = it.taler_withdraw_uri.split("/").last()
            withdrawalSelect(uuid)
            client.post("/withdrawals/$uuid/abort").assertNoContent()

            // Check error
            client.post("/withdrawals/$uuid/confirm")
                .assertConflict(TalerErrorCode.BANK_CONFIRM_ABORT_CONFLICT)
        }

        // Check balance insufficient
        client.postA("/accounts/merchant/withdrawals") {
            json { "amount" to "KUDOS:5" } 
        }.assertOkJson<BankAccountCreateWithdrawalResponse> {
            val uuid = it.taler_withdraw_uri.split("/").last()
            withdrawalSelect(uuid)

            // Send too much money
            tx("merchant", "KUDOS:5", "exchange")
            client.post("/withdrawals/$uuid/confirm")
                .assertConflict(TalerErrorCode.BANK_UNALLOWED_DEBIT)

            // Check can abort because not confirmed
            client.post("/withdrawals/$uuid/abort").assertNoContent()
        }

        // Check bad UUID
        client.post("/withdrawals/chocolate/confirm").assertBadRequest()

        // Check unknown
        client.post("/withdrawals/${UUID.randomUUID()}/confirm")
            .assertNotFound(TalerErrorCode.BANK_TRANSACTION_NOT_FOUND)
    }
}


class CoreBankCashoutApiTest {
    // POST /accounts/{USERNAME}/cashouts
    @Test
    fun create() = bankSetup { _ ->
        // TODO auth routine
        val req = obj {
            "request_uid" to randShortHashCode()
            "amount_debit" to "KUDOS:1"
            "amount_credit" to convert("KUDOS:1")
        }

        // Check missing TAN info
        client.postA("/accounts/customer/cashouts") {
            json(req) 
        }.assertConflict(TalerErrorCode.BANK_MISSING_TAN_INFO)
        client.patchA("/accounts/customer") {
            json {
                "challenge_contact_data" to obj {
                    "phone" to "+99"
                    "email" to "foo@example.com"
                }
            }
        }.assertNoContent()

        // Check email TAN error
        client.postA("/accounts/customer/cashouts") {
            json(req) {
                "tan_channel" to "email"
            }
        }.assertStatus(HttpStatusCode.BadGateway, TalerErrorCode.BANK_TAN_CHANNEL_SCRIPT_FAILED)

        // Check OK
        client.postA("/accounts/customer/cashouts") {
            json(req) 
        }.assertOkJson<CashoutPending> { first ->
            smsCode("+99")
            // Check idempotency
            client.postA("/accounts/customer/cashouts") {
                json(req) 
            }.assertOkJson<CashoutPending> { second ->
                assertEquals(first.cashout_id, second.cashout_id)
                assertNull(smsCode("+99"))     
            }
        }

        // Trigger conflict due to reused request_uid
        client.postA("/accounts/customer/cashouts") {
            json(req) {
                "amount_debit" to "KUDOS:2"
                "amount_credit" to convert("KUDOS:2")
            }
        }.assertConflict(TalerErrorCode.BANK_TRANSFER_REQUEST_UID_REUSED)

        // Check exchange account
        client.postA("/accounts/exchange/cashouts") {
            json(req) 
        }.assertConflict(TalerErrorCode.BANK_ACCOUNT_IS_EXCHANGE)

        // Check insufficient fund
        client.postA("/accounts/customer/cashouts") {
            json(req) {
                "amount_debit" to "KUDOS:75"
                "amount_credit" to convert("KUDOS:75")
            }
        }.assertConflict(TalerErrorCode.BANK_UNALLOWED_DEBIT)

        // Check wrong conversion
        client.postA("/accounts/customer/cashouts") {
            json(req) {
                "amount_credit" to convert("KUDOS:2")
            }
        }.assertConflict(TalerErrorCode.BANK_BAD_CONVERSION)

        // Check wrong currency
        client.postA("/accounts/customer/cashouts") {
            json(req) {
                "amount_debit" to "EUR:1"
            }
        }.assertBadRequest(TalerErrorCode.GENERIC_CURRENCY_MISMATCH)
        client.postA("/accounts/customer/cashouts") {
            json(req) {
                "amount_credit" to "EUR:1"
            } 
        }.assertBadRequest(TalerErrorCode.GENERIC_CURRENCY_MISMATCH)
    }

    // POST /accounts/{USERNAME}/cashouts
    @Test
    fun create_no_tan() = bankSetup("test_no_tan.conf") { _ ->
        val req = obj {
            "request_uid" to randShortHashCode()
            "amount_debit" to "KUDOS:1"
            "amount_credit" to convert("KUDOS:1")
        }

        // Check unsupported TAN channel
        client.postA("/accounts/customer/cashouts") {
            json(req) 
        }.assertStatus(HttpStatusCode.NotImplemented, TalerErrorCode.BANK_TAN_CHANNEL_NOT_SUPPORTED)
    }

    // POST /accounts/{USERNAME}/cashouts/{CASHOUT_ID}/abort
    @Test
    fun abort() = bankSetup { _ ->
        // TODO auth routine
        fillCashoutInfo("customer")
        
        val req = obj {
            "request_uid" to randShortHashCode()
            "amount_debit" to "KUDOS:1"
            "amount_credit" to convert("KUDOS:1")
        }

        // Check abort created
        client.postA("/accounts/customer/cashouts") {
            json(req) 
        }.assertOkJson<CashoutPending> {
            val id = it.cashout_id

            // Check OK
            client.postA("/accounts/customer/cashouts/$id/abort")
                .assertNoContent()
            // Check idempotence
            client.postA("/accounts/customer/cashouts/$id/abort")
                .assertNoContent()
        }

        // Check abort confirmed
        client.postA("/accounts/customer/cashouts") {
            json(req) { "request_uid" to randShortHashCode() }
        }.assertOkJson<CashoutPending> {
            val id = it.cashout_id

            client.postA("/accounts/customer/cashouts/$id/confirm") {
                json { "tan" to smsCode("+99") } 
            }.assertNoContent()

            // Check error
            client.postA("/accounts/customer/cashouts/$id/abort")
                .assertConflict(TalerErrorCode.BANK_ABORT_CONFIRM_CONFLICT)
        }

        // Check bad id
        client.postA("/accounts/customer/cashouts/chocolate/abort") {
            json { "tan" to "code" } 
        }.assertBadRequest()

        // Check unknown
        client.postA("/accounts/customer/cashouts/42/abort") {
            json { "tan" to "code" } 
        }.assertNotFound(TalerErrorCode.BANK_TRANSACTION_NOT_FOUND)

        // Check abort another user's operation
        client.postA("/accounts/customer/cashouts") {
            json(req) { "request_uid" to randShortHashCode() }
        }.assertOkJson<CashoutPending> {
            val id = it.cashout_id

            // Check error
            client.postA("/accounts/merchant/cashouts/$id/abort")
                .assertNotFound(TalerErrorCode.BANK_TRANSACTION_NOT_FOUND)
        }
    }

    // POST /accounts/{USERNAME}/cashouts/{CASHOUT_ID}/confirm
    @Test
    fun confirm() = bankSetup { db -> 
        // TODO auth routine
        client.patchA("/accounts/customer") {
            json {
                "challenge_contact_data" to obj {
                    "phone" to "+99"
                }
            }
        }.assertNoContent()

        val req = obj {
            "request_uid" to randShortHashCode()
            "amount_debit" to "KUDOS:1"
            "amount_credit" to convert("KUDOS:1")
        }

        // Check confirm
        client.postA("/accounts/customer/cashouts") {
            json(req) { "request_uid" to randShortHashCode() }
        }.assertOkJson<CashoutPending> {
            val id = it.cashout_id

            // Check missing cashout address
            client.postA("/accounts/customer/cashouts/$id/confirm") {
                json { "tan" to "code" }
            }.assertConflict(TalerErrorCode.BANK_CONFIRM_INCOMPLETE)
            fillCashoutInfo("customer")

            // Check bad TAN code
            client.postA("/accounts/customer/cashouts/$id/confirm") {
                json { "tan" to "nice-try" } 
            }.assertConflict(TalerErrorCode.BANK_TAN_CHALLENGE_FAILED)

            val code = smsCode("+99")
           
            // Check OK
            client.postA("/accounts/customer/cashouts/$id/confirm") {
                json { "tan" to code }
            }.assertNoContent()
            // Check idempotence
            client.postA("/accounts/customer/cashouts/$id/confirm") {
                json { "tan" to code }
            }.assertNoContent()
        }

        // Check confirm another user's operation
        client.postA("/accounts/customer/cashouts") {
            json(req) { 
                "request_uid" to randShortHashCode()
                "amount_credit" to convert("KUDOS:1")
            }
        }.assertOkJson<CashoutPending> {
            val id = it.cashout_id

            // Check error
            client.postA("/accounts/merchant/cashouts/$id/confirm") {
                json { "tan" to "unused" }
            }.assertNotFound(TalerErrorCode.BANK_TRANSACTION_NOT_FOUND)
        }

        // Check bad conversion
        client.postA("/accounts/customer/cashouts") {
            json(req) { "request_uid" to randShortHashCode() }
        }.assertOkJson<CashoutPending> {
            val id = it.cashout_id

            db.conversion.updateConfig(ConversionInfo(
                cashin_ratio = DecimalNumber("1"),
                cashin_fee = TalerAmount("KUDOS:0.1"),
                cashin_tiny_amount = TalerAmount("KUDOS:0.0001"),
                cashin_rounding_mode = RoundingMode.nearest,
                cashin_min_amount = TalerAmount("FIAT:0.0001"),
                cashout_ratio = DecimalNumber("1"),
                cashout_fee = TalerAmount("FIAT:0.1"),
                cashout_tiny_amount = TalerAmount("FIAT:0.0001"),
                cashout_rounding_mode = RoundingMode.nearest,
                cashout_min_amount = TalerAmount("KUDOS:0.0001"),
            ))

            client.postA("/accounts/customer/cashouts/$id/confirm"){
                json { "tan" to smsCode("+99") } 
            }.assertConflict(TalerErrorCode.BANK_BAD_CONVERSION)

            // Check can abort because not confirmed
            client.postA("/accounts/customer/cashouts/$id/abort")
                .assertNoContent()
        }

        // Check balance insufficient
        client.postA("/accounts/customer/cashouts") {
            json(req) { 
                "request_uid" to randShortHashCode()
                "amount_credit" to convert("KUDOS:1")
            }
        }.assertOkJson<CashoutPending> {
            val id = it.cashout_id
            // Send too much money
            tx("customer", "KUDOS:9", "merchant")
            client.postA("/accounts/customer/cashouts/$id/confirm"){
                json { "tan" to smsCode("+99") } 
            }.assertConflict(TalerErrorCode.BANK_UNALLOWED_DEBIT)

            // Check can abort because not confirmed
            client.postA("/accounts/customer/cashouts/$id/abort")
                .assertNoContent()
        }

        // Check bad UUID
        client.postA("/accounts/customer/cashouts/chocolate/confirm") {
            json { "tan" to "code" }
        }.assertBadRequest()

        // Check unknown
        client.postA("/accounts/customer/cashouts/42/confirm") {
            json { "tan" to "code" }
        }.assertNotFound(TalerErrorCode.BANK_TRANSACTION_NOT_FOUND)
    }

    // GET /accounts/{USERNAME}/cashouts/{CASHOUT_ID}
    @Test
    fun get() = bankSetup { _ ->
        // TODO auth routine
        fillCashoutInfo("customer")

        val amountDebit = TalerAmount("KUDOS:1.5")
        val amountCredit = convert("KUDOS:1.5")
        val req = obj {
            "amount_debit" to amountDebit
            "amount_credit" to amountCredit
        }

        // Check confirm
        client.postA("/accounts/customer/cashouts") {
            json(req) { "request_uid" to randShortHashCode() }
        }.assertOkJson<CashoutPending> {
            val id = it.cashout_id
            client.getA("/accounts/customer/cashouts/$id")
                .assertOkJson<CashoutStatusResponse> {
                assertEquals(CashoutStatus.pending, it.status)
                assertEquals(amountDebit, it.amount_debit)
                assertEquals(amountCredit, it.amount_credit)
            }

            client.postA("/accounts/customer/cashouts/$id/confirm") {
                json { "tan" to smsCode("+99") }
            }.assertNoContent()
            client.getA("/accounts/customer/cashouts/$id")
                .assertOkJson<CashoutStatusResponse> {
                assertEquals(CashoutStatus.confirmed, it.status)
            }
        }

        // Check abort
        client.postA("/accounts/customer/cashouts") {
            json(req) { "request_uid" to randShortHashCode() }
        }.assertOkJson<CashoutPending> {
            val id = it.cashout_id
            client.getA("/accounts/customer/cashouts/$id")
                .assertOkJson<CashoutStatusResponse> {
                assertEquals(CashoutStatus.pending, it.status)
            }

            client.postA("/accounts/customer/cashouts/$id/abort")
                .assertNoContent()
            client.getA("/accounts/customer/cashouts/$id")
                .assertOkJson<CashoutStatusResponse> {
                assertEquals(CashoutStatus.aborted, it.status)
            }
        }

        // Check bad UUID
        client.getA("/accounts/customer/cashouts/chocolate")
            .assertBadRequest()

        // Check unknown
        client.getA("/accounts/customer/cashouts/42")
            .assertNotFound(TalerErrorCode.BANK_TRANSACTION_NOT_FOUND)

        // Check get another user's operation
        client.postA("/accounts/customer/cashouts") {
            json(req) { "request_uid" to randShortHashCode() }
        }.assertOkJson<CashoutPending> {
            val id = it.cashout_id

            // Check error
            client.getA("/accounts/merchant/cashouts/$id")
                .assertNotFound(TalerErrorCode.BANK_TRANSACTION_NOT_FOUND)
        }
    }

    // GET /accounts/{USERNAME}/cashouts
    @Test
    fun history() = bankSetup { _ ->
        // TODO auth routine

        fillCashoutInfo("customer")

        suspend fun HttpResponse.assertHistory(size: Int) {
            assertHistoryIds<Cashouts>(size) {
                it.cashouts.map { it.cashout_id }
            }
        }

        // Empty
        client.getA("/accounts/customer/cashouts")
            .assertNoContent()

        // Testing ranges. 
        repeat(30) {
            cashout("KUDOS:0.${it+1}")
        }

        // Default
        client.getA("/accounts/customer/cashouts")
            .assertHistory(20)

        // Forward range:
        client.getA("/accounts/customer/cashouts?delta=10&start=20")
            .assertHistory(10)

        // Fackward range:
        client.getA("/accounts/customer/cashouts?delta=-10&start=25")
            .assertHistory(10)
    }

    // GET /cashouts
    @Test
    fun globalHistory() = bankSetup { _ ->
        // TODO admin auth routine

        fillCashoutInfo("customer")

        suspend fun HttpResponse.assertHistory(size: Int) {
            assertHistoryIds<GlobalCashouts>(size) {
                it.cashouts.map { it.cashout_id }
            }
        }

        // Empty
        client.get("/cashouts") {
            pwAuth("admin")
        }.assertNoContent()

        // Testing ranges. 
        repeat(30) {
            cashout("KUDOS:0.${it+1}")
        }

        // Default
        client.get("/cashouts") {
            pwAuth("admin")
        }.assertHistory(20)

        // Forward range:
        client.get("/cashouts?delta=10&start=20") {
            pwAuth("admin")
        }.assertHistory(10)

        // Fackward range:
        client.get("/cashouts?delta=-10&start=25") {
            pwAuth("admin")
        }.assertHistory(10)
    }

    @Test
    fun notImplemented() = bankSetup("test_restrict.conf") { _ ->
        client.get("/accounts/customer/cashouts")
            .assertNotImplemented()
    }
}