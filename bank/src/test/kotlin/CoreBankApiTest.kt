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
import tech.libeufin.util.*

class CoreBankConfigTest {
    // GET /config
    @Test
    fun config() = bankSetup { _ -> 
        client.get("/config").assertOk()
    }

    // GET /monitor
    @Test
    fun monitor() = bankSetup { _ -> 
        authRoutine(HttpMethod.Get, "/monitor", requireAdmin = true)
        // Check OK
        client.get("/monitor?timeframe=day&wich=25") {
            pwAuth("admin")
        }.assertOk()
        client.get("/monitor?timeframe=day=wich=25") {
            pwAuth("admin")
        }.assertBadRequest()
    }
}

class CoreBankTokenApiTest {
    // POST /accounts/USERNAME/token
    @Test
    fun post() = bankSetup { db -> 
        authRoutine(HttpMethod.Post, "/accounts/merchant/token")

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
    fun create() = bankSetup { _ -> 
        // Check generated payto
        obj {
            "username" to "john"
            "password" to "password"
            "name" to "John"
        }.let { req ->
            // Check Ok
            val payto = client.post("/accounts") {
                json(req)
            }.assertOkJson<RegisterAccountResponse>().internal_payto_uri
            // Check idempotency
            client.post("/accounts") {
                json(req)
            }.assertOk()
            // Check idempotency with payto
            client.post("/accounts") {
                json(req) {
                    "internal_payto_uri" to payto
                }
            }.assertOk()
            // Check payto conflict
            client.post("/accounts") {
                json(req) {
                    "internal_payto_uri" to genIbanPaytoUri()
                }
            }.assertConflict(TalerErrorCode.BANK_REGISTER_USERNAME_REUSE)
        }

        // Check given payto
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
        }.assertOkJson<RegisterAccountResponse> {
            assertEquals(ibanPayto, it.internal_payto_uri)
        }
        // Testing idempotency
        client.post("/accounts") {
            json(req)
        }.assertOk()

        // Check debit_threshold
        obj {
            "username" to "bat"
            "password" to "password"
            "name" to "Bat"
            "debit_threshold" to "KUDOS:42"
        }.let { req ->
            client.post("/accounts") {
                json(req)
            }.assertErr(TalerErrorCode.BANK_NON_ADMIN_PATCH_DEBT_LIMIT)
            client.post("/accounts") {
                json(req)
                pwAuth("admin")
            }.assertOk()
        }
        

        // Reserved account
        RESERVED_ACCOUNTS.forEach {
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
    fun createBonus() = bankSetup(conf = "test_bonus.conf") { _ ->
        val req = obj {
            "username" to "foo"
            "password" to "xyz"
            "name" to "Mallory"
        }

        setMaxDebt("admin", "KUDOS:10000")

        // Check ok
        repeat(100) {
            client.post("/accounts") {
                pwAuth("admin")
                json(req) {
                    "username" to "foo$it"
                }
            }.assertOk()
            assertBalance("foo$it", "+KUDOS:100")
        }
        assertBalance("admin", "-KUDOS:10000")
        
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
    fun createRestricted() = bankSetup(conf = "test_restrict.conf") { _ -> 
        authRoutine(HttpMethod.Post, "/accounts", requireAdmin = true)
        client.post("/accounts") {
            pwAuth("admin")
            json {
                "username" to "baz"
                "password" to "xyz"
                "name" to "Mallory"
            }
        }.assertOk()
    }

    // DELETE /accounts/USERNAME
    @Test
    fun delete() = bankSetup { _ -> 
        // Unknown account
        client.delete("/accounts/unknown") {
            pwAuth("admin")
        }.assertNotFound(TalerErrorCode.BANK_UNKNOWN_ACCOUNT)

        // Reserved account
        RESERVED_ACCOUNTS.forEach {
            client.delete("/accounts/$it") {
                pwAuth("admin")
            }.assertConflict(TalerErrorCode.BANK_RESERVED_USERNAME_CONFLICT)
        }
        client.deleteA("/accounts/exchange")
            .assertConflict(TalerErrorCode.BANK_RESERVED_USERNAME_CONFLICT)
       
        // successful deletion
        client.post("/accounts") {
            json {
                "username" to "john"
                "password" to "password"
                "name" to "John Smith"
            }
        }.assertOk()
        client.delete("/accounts/john") {
            pwAuth("admin")
        }.assertNoContent()
        // Trying again must yield 404
        client.delete("/accounts/john") {
            pwAuth("admin")
        }.assertNotFound(TalerErrorCode.BANK_UNKNOWN_ACCOUNT)

        
        // fail to delete, due to a non-zero balance.
        tx("customer", "KUDOS:1", "merchant")
        client.deleteA("/accounts/merchant")
            .assertConflict(TalerErrorCode.BANK_ACCOUNT_BALANCE_NOT_ZERO)
        tx("merchant", "KUDOS:1", "customer")
        client.deleteA("/accounts/merchant")
            .assertNoContent()
    }

    // Test admin-only account deletion
    @Test
    fun deleteRestricted() = bankSetup(conf = "test_restrict.conf") { _ -> 
        authRoutine(HttpMethod.Post, "/accounts", requireAdmin = true)
        // Exchange is still restricted
        client.delete("/accounts/exchange") {
            pwAuth("admin")
        }.assertConflict(TalerErrorCode.BANK_RESERVED_USERNAME_CONFLICT)
    }

    // Test delete exchange account
    @Test
    fun deleteNoConversion() = bankSetup(conf = "test_no_conversion.conf") { _ -> 
        // Exchange is no longer restricted
        client.deleteA("/accounts/exchange").assertNoContent()
    }

    suspend fun ApplicationTestBuilder.checkAdminOnly(req: JsonElement, error: TalerErrorCode) {
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

    // PATCH /accounts/USERNAME
    @Test
    fun reconfig() = bankSetup { _ -> 
        authRoutine(HttpMethod.Patch, "/accounts/merchant", withAdmin = true)

        // Successful attempt now.
        val cashout = IbanPayTo(genIbanPaytoUri())
        val req = obj {
            "cashout_payto_uri" to cashout.canonical
            "contact_data" to obj {
                "phone" to "+99"
                "email" to "foo@example.com"
            }
            "name" to "Roger"
            "is_public" to true 
        }
        client.patchA("/accounts/merchant") {
            json(req)
        }.assertNoContent()
        // Checking idempotence.
        client.patchA("/accounts/merchant") {
            json(req)
        }.assertNoContent()
        
        checkAdminOnly(
            obj(req) { "debit_threshold" to "KUDOS:100" },
            TalerErrorCode.BANK_NON_ADMIN_PATCH_DEBT_LIMIT
        )
        
        // Check currency
        client.patch("/accounts/merchant") {
            pwAuth("admin")
            json(req) { "debit_threshold" to "EUR:100" }
        }.assertBadRequest()

        // Check patch
        client.getA("/accounts/merchant").assertOkJson<AccountData> { obj ->
            assertEquals("Roger", obj.name)
            assertEquals(cashout.canonical, obj.cashout_payto_uri)
            assertEquals("+99", obj.contact_data?.phone?.get())
            assertEquals("foo@example.com", obj.contact_data?.email?.get())
            assertEquals(TalerAmount("KUDOS:100"), obj.debit_threshold)
            assert(obj.is_public)
            assert(!obj.is_taler_exchange)
        }

        // Check keep values when there is no changes
        client.patchA("/accounts/merchant") {
            json { }
        }.assertNoContent()
        client.getA("/accounts/merchant").assertOkJson<AccountData> { obj ->
            assertEquals("Roger", obj.name)
            assertEquals(cashout.canonical, obj.cashout_payto_uri)
            assertEquals("+99", obj.contact_data?.phone?.get())
            assertEquals("foo@example.com", obj.contact_data?.email?.get())
            assertEquals(TalerAmount("KUDOS:100"), obj.debit_threshold)
            assert(obj.is_public)
            assert(!obj.is_taler_exchange)
        }
    }

    // Test admin-only account patch
    @Test
    fun patchRestricted() = bankSetup(conf = "test_restrict.conf") { _ -> 
        // Check restricted
        checkAdminOnly(
            obj { "name" to "Another Foo" },
            TalerErrorCode.BANK_NON_ADMIN_PATCH_LEGAL_NAME
        )
        checkAdminOnly(
            obj { "cashout_payto_uri" to genIbanPaytoUri() },
            TalerErrorCode.BANK_NON_ADMIN_PATCH_CASHOUT
        )
        // Check idempotent
        client.getA("/accounts/merchant").assertOkJson<AccountData> { obj ->
            client.patchA("/accounts/merchant") {
                json {
                    "name" to obj.name
                    "cashout_payto_uri" to obj.cashout_payto_uri
                    "debit_threshold" to obj.debit_threshold
                }
            }.assertNoContent()
        }
    }

    // PATCH /accounts/USERNAME/auth
    @Test
    fun passwordChange() = bankSetup { _ -> 
        authRoutine(HttpMethod.Patch, "/accounts/merchant/auth", withAdmin = true)

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
    fun list() = bankSetup(conf = "test_no_conversion.conf") { _ -> 
        authRoutine(HttpMethod.Get, "/accounts", requireAdmin = true)
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
            }.assertOk()
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
    fun get() = bankSetup { _ -> 
        authRoutine(HttpMethod.Get, "/accounts/merchant", withAdmin = true)
        // Check ok
        client.getA("/accounts/merchant").assertOkJson<AccountData> {
            assertEquals("Merchant", it.name)
        }
    }
}

class CoreBankTransactionsApiTest {
    // GET /transactions
    @Test
    fun history() = bankSetup { _ -> 
        authRoutine(HttpMethod.Get, "/accounts/merchant/transactions")
        historyRoutine<BankAccountTransactionsResponse>(
            url = "/accounts/customer/transactions",
            ids = { it.transactions.map { it.row_id } },
            registered = listOf(
                { 
                    // Transactions from merchant to exchange
                    tx("merchant", "KUDOS:0.1", "customer")
                },
                { 
                    // Transactions from exchange to merchant
                    tx("customer", "KUDOS:0.1", "merchant")
                },
                { 
                    // Transactions from merchant to exchange
                    tx("merchant", "KUDOS:0.1", "customer")
                },
                { 
                    // Cashout from merchant
                    cashout("KUDOS:0.1")
                }
            ),
            ignored = listOf(
                {
                    // Ignore transactions of other accounts
                    tx("merchant", "KUDOS:0.1", "exchange")
                },
                {
                    // Ignore transactions of other accounts
                    tx("exchange", "KUDOS:0.1", "merchant",)
                }
            )
        )
    }

    // GET /transactions/T_ID
    @Test
    fun testById() = bankSetup { _ -> 
        authRoutine(HttpMethod.Get, "/accounts/merchant/transactions/42")

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
        authRoutine(HttpMethod.Post, "/accounts/merchant/transactions")

        val valid_req = obj {
            "payto_uri" to "$exchangePayto?message=payout"
            "amount" to "KUDOS:0.3"
        }

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

        // Init state
        assertBalance("merchant", "+KUDOS:0")
        assertBalance("customer", "+KUDOS:0")
        // Send 2 times 3
        repeat(2) {
            tx("merchant", "KUDOS:3", "customer")
        }
        client.post("/accounts/merchant/transactions") {
            pwAuth("merchant")
            json {
                "payto_uri" to "$customerPayto?message=payout2&amount=KUDOS:5"
            }
        }.assertConflict(TalerErrorCode.BANK_UNALLOWED_DEBIT)
        assertBalance("merchant", "-KUDOS:6")
        assertBalance("customer", "+KUDOS:6")
        // Send throught debt
        tx("customer", "KUDOS:10", "merchant")
        assertBalance("merchant", "+KUDOS:4")
        assertBalance("customer", "-KUDOS:4")
        tx("merchant", "KUDOS:4", "customer")

        // Check bounce
        assertBalance("merchant", "+KUDOS:0")
        assertBalance("exchange", "+KUDOS:0")
        tx("merchant", "KUDOS:1", "exchange", "") // Bounce common to transaction
        tx("merchant", "KUDOS:1", "exchange", "Malformed") // Bounce malformed transaction
        val reserve_pub = randEddsaPublicKey();
        tx("merchant", "KUDOS:1", "exchange", randIncomingSubject(reserve_pub)) // Accept incoming
        tx("merchant", "KUDOS:1", "exchange", randIncomingSubject(reserve_pub)) // Bounce reserve_pub reuse
        assertBalance("merchant", "-KUDOS:1")
        assertBalance("exchange", "+KUDOS:1")
        
        // Check warn
        assertBalance("merchant", "-KUDOS:1")
        assertBalance("exchange", "+KUDOS:1")
        tx("exchange", "KUDOS:1", "merchant", "") // Warn common to transaction
        tx("exchange", "KUDOS:1", "merchant", "Malformed") // Warn malformed transaction
        val wtid = randShortHashCode()
        val exchange = ExchangeUrl("http://exchange.example.com/")
        tx("exchange", "KUDOS:1", "merchant", randOutgoingSubject(wtid, exchange)) // Accept outgoing
        tx("exchange", "KUDOS:1", "merchant", randOutgoingSubject(wtid, exchange)) // Warn wtid reuse
        assertBalance("merchant", "+KUDOS:3")
        assertBalance("exchange", "-KUDOS:3")
    }
}

class CoreBankWithdrawalApiTest {
    // POST /accounts/USERNAME/withdrawals
    @Test
    fun create() = bankSetup { _ ->
        authRoutine(HttpMethod.Post, "/accounts/merchant/withdrawals")
        
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
            }.assertOkJson<WithdrawalPublicInfo> {
                assert(!it.selection_done)
                assert(!it.aborted)
                assert(!it.confirmation_done)
                assertEquals(amount, it.amount)
                // TODO check all status
            }
        }

        // Check polling
        statusRoutine<WithdrawalPublicInfo>("/withdrawals") { it.status }

        // Check bad UUID
        client.get("/withdrawals/chocolate").assertBadRequest()

        // Check unknown
        client.get("/withdrawals/${UUID.randomUUID()}")
            .assertNotFound(TalerErrorCode.BANK_TRANSACTION_NOT_FOUND)
    }

    // POST /accounts/USERNAME/withdrawals/withdrawal_id/abort
    @Test
    fun abort() = bankSetup { _ ->
        // TODO auth routine
        // Check abort created
        client.postA("/accounts/merchant/withdrawals") {
            json { "amount" to "KUDOS:1" } 
        }.assertOkJson<BankAccountCreateWithdrawalResponse> {
            val uuid = it.taler_withdraw_uri.split("/").last()

            // Check OK
            client.postA("/accounts/merchant/withdrawals/$uuid/abort").assertNoContent()
            // Check idempotence
            client.postA("/accounts/merchant/withdrawals/$uuid/abort").assertNoContent()
        }

        // Check abort selected
        client.postA("/accounts/merchant/withdrawals") {
            json { "amount" to "KUDOS:1" } 
        }.assertOkJson<BankAccountCreateWithdrawalResponse> {
            val uuid = it.taler_withdraw_uri.split("/").last()
            withdrawalSelect(uuid)

            // Check OK
            client.postA("/accounts/merchant/withdrawals/$uuid/abort").assertNoContent()
            // Check idempotence
            client.postA("/accounts/merchant/withdrawals/$uuid/abort").assertNoContent()
        }

        // Check abort confirmed
        client.postA("/accounts/merchant/withdrawals") {
            json { "amount" to "KUDOS:1" } 
        }.assertOkJson<BankAccountCreateWithdrawalResponse> {
            val uuid = it.taler_withdraw_uri.split("/").last()
            withdrawalSelect(uuid)
            client.postA("/accounts/merchant/withdrawals/$uuid/confirm").assertNoContent()

            // Check error
            client.postA("/accounts/merchant/withdrawals/$uuid/abort")
                .assertConflict(TalerErrorCode.BANK_ABORT_CONFIRM_CONFLICT)
        }

        // Check bad UUID
        client.postA("/accounts/merchant/withdrawals/chocolate/abort").assertBadRequest()

        // Check unknown
        client.postA("/accounts/merchant/withdrawals/${UUID.randomUUID()}/abort")
            .assertNotFound(TalerErrorCode.BANK_TRANSACTION_NOT_FOUND)
    }

    // POST /accounts/USERNAME/withdrawals/withdrawal_id/confirm
    @Test
    fun confirm() = bankSetup { _ -> 
        // TODO auth routine
        // Check confirm created
        client.postA("/accounts/merchant/withdrawals") {
            json { "amount" to "KUDOS:1" } 
        }.assertOkJson<BankAccountCreateWithdrawalResponse> {
            val uuid = it.taler_withdraw_uri.split("/").last()

            // Check err
            client.postA("/accounts/merchant/withdrawals/$uuid/confirm")
                .assertConflict(TalerErrorCode.BANK_CONFIRM_INCOMPLETE)
        }

        // Check confirm selected
        client.postA("/accounts/merchant/withdrawals") {
            json { "amount" to "KUDOS:1" } 
        }.assertOkJson<BankAccountCreateWithdrawalResponse> {
            val uuid = it.taler_withdraw_uri.split("/").last()
            withdrawalSelect(uuid)

            // Check OK
            client.postA("/accounts/merchant/withdrawals/$uuid/confirm").assertNoContent()
            // Check idempotence
            client.postA("/accounts/merchant/withdrawals/$uuid/confirm").assertNoContent()
        }

        // Check confirm aborted
        client.postA("/accounts/merchant/withdrawals") {
            json { "amount" to "KUDOS:1" } 
        }.assertOkJson<BankAccountCreateWithdrawalResponse> {
            val uuid = it.taler_withdraw_uri.split("/").last()
            withdrawalSelect(uuid)
            client.postA("/accounts/merchant/withdrawals/$uuid/abort").assertNoContent()

            // Check error
            client.postA("/accounts/merchant/withdrawals/$uuid/confirm")
                .assertConflict(TalerErrorCode.BANK_CONFIRM_ABORT_CONFLICT)
        }

        // Check balance insufficient
        client.postA("/accounts/merchant/withdrawals") {
            json { "amount" to "KUDOS:5" } 
        }.assertOkJson<BankAccountCreateWithdrawalResponse> {
            val uuid = it.taler_withdraw_uri.split("/").last()
            withdrawalSelect(uuid)

            // Send too much money
            tx("merchant", "KUDOS:5", "customer")
            client.postA("/accounts/merchant/withdrawals/$uuid/confirm")
                .assertConflict(TalerErrorCode.BANK_UNALLOWED_DEBIT)

            // Check can abort because not confirmed
            client.postA("/accounts/merchant/withdrawals/$uuid/abort").assertNoContent()
        }

        // Check bad UUID
        client.postA("/accounts/merchant/withdrawals/chocolate/confirm").assertBadRequest()

        // Check unknown
        client.postA("/accounts/merchant/withdrawals/${UUID.randomUUID()}/confirm")
            .assertNotFound(TalerErrorCode.BANK_TRANSACTION_NOT_FOUND)
    }
}


class CoreBankCashoutApiTest {
    // POST /accounts/{USERNAME}/cashouts
    @Test
    fun create() = bankSetup { _ ->
        authRoutine(HttpMethod.Post, "/accounts/merchant/cashouts")

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
                "contact_data" to obj {
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
                "amount_credit" to "KUDOS:1"
            } 
        }.assertBadRequest(TalerErrorCode.GENERIC_CURRENCY_MISMATCH)
    }

    // POST /accounts/{USERNAME}/cashouts
    @Test
    fun createNoTan() = bankSetup("test_no_tan.conf") { _ ->
        val req = obj {
            "request_uid" to randShortHashCode()
            "amount_debit" to "KUDOS:1"
            "amount_credit" to convert("KUDOS:1")
        }

        fillCashoutInfo("customer")

        // Check unsupported TAN channel
        client.postA("/accounts/customer/cashouts") {
            json(req) 
        }.assertStatus(HttpStatusCode.NotImplemented, TalerErrorCode.BANK_TAN_CHANNEL_NOT_SUPPORTED)
    }

    // POST /accounts/{USERNAME}/cashouts/{CASHOUT_ID}/abort
    @Test
    fun abort() = bankSetup { _ ->
        authRoutine(HttpMethod.Post, "/accounts/merchant/cashouts/42/abort")

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
    fun confirm() = bankSetup { _ -> 
        authRoutine(HttpMethod.Post, "/accounts/merchant/cashouts/42/confirm")

        client.patchA("/accounts/customer") {
            json {
                "contact_data" to obj {
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
            client.post("/conversion-info/conversion-rate") {
                pwAuth("admin")
                json {
                    "cashin_ratio" to "1"
                    "cashin_fee" to "KUDOS:0.1"
                    "cashin_tiny_amount" to "KUDOS:0.0001"
                    "cashin_rounding_mode" to "nearest"
                    "cashin_min_amount" to "EUR:0.0001"
                    "cashout_ratio" to "1"
                    "cashout_fee" to "EUR:0.1"
                    "cashout_tiny_amount" to "EUR:0.0001"
                    "cashout_rounding_mode" to "nearest"
                    "cashout_min_amount" to "KUDOS:0.0001"
                }
            }.assertNoContent()

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
        authRoutine(HttpMethod.Get, "/accounts/merchant/cashouts/42")
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
                assertEquals(TanChannel.sms, it.tan_channel)
                assertEquals("+99", it.tan_info)
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
        authRoutine(HttpMethod.Get, "/accounts/merchant/cashouts")
        historyRoutine<Cashouts>(
            url = "/accounts/customer/cashouts",
            ids = { it.cashouts.map { it.cashout_id } },
            registered = listOf({ cashout("KUDOS:0.1") }),
            polling = false
        )
    }

    // GET /cashouts
    @Test
    fun globalHistory() = bankSetup { _ ->
        authRoutine(HttpMethod.Get, "/cashouts", requireAdmin = true)
        historyRoutine<GlobalCashouts>(
            url = "/cashouts",
            ids = { it.cashouts.map { it.cashout_id } },
            registered = listOf({ cashout("KUDOS:0.1") }),
            polling = false,
            auth = "admin"
        )
    }

    @Test
    fun notImplemented() = bankSetup("test_no_conversion.conf") { _ ->
        client.get("/accounts/customer/cashouts")
            .assertNotImplemented()
    }
}