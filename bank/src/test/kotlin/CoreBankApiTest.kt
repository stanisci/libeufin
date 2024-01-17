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
import tech.libeufin.bank.db.*
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
                    "payto_uri" to payto
                }
            }.assertOk()
            // Check payto conflict
            client.post("/accounts") {
                json(req) {
                    "payto_uri" to genIbanPaytoUri()
                }
            }.assertConflict(TalerErrorCode.BANK_REGISTER_USERNAME_REUSE)
        }

        // Check given payto
        val ibanPayto = IbanPayTo(genIbanPaytoUri())
        val req = obj {
            "username" to "foo"
            "password" to "password"
            "name" to "Jane"
            "is_public" to true
            "payto_uri" to ibanPayto
            "is_taler_exchange" to true
        }
        // Check Ok
        client.post("/accounts") {
            json(req)
        }.assertOkJson<RegisterAccountResponse> {
            assertEquals(ibanPayto.canonical, it.internal_payto_uri)
        }
        // Testing idempotency
        client.post("/accounts") {
            json(req)
        }.assertOk()

        // Check admin only debit_threshold
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

        // Check admin only tan_channel
        obj {
            "username" to "bat2"
            "password" to "password"
            "name" to "Bat"
            "contact_data" to obj {
                "phone" to "+456"
            }
            "tan_channel" to "sms"
        }.let { req ->
            client.post("/accounts") {
                json(req)
            }.assertErr(TalerErrorCode.BANK_NON_ADMIN_SET_TAN_CHANNEL)
            client.post("/accounts") {
                json(req)
                pwAuth("admin")
            }.assertOk()
        }

        // Check tan info
        for (channel in listOf("sms", "email")) {
            client.post("/accounts") {
                pwAuth("admin")
                json { 
                    "username" to "bat2"
                    "password" to "password"
                    "name" to "Bat"
                    "tan_channel" to channel
                }
            }.assertErr(TalerErrorCode.BANK_MISSING_TAN_INFO)
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

        // Non exchange account
        client.post("/accounts") {
            json {
                "username" to "exchange"
                "password" to "password"
                "name" to "Exchange"
            }
        }.assertConflict(TalerErrorCode.END)

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

        // Check cashout payto receiver name logic
        client.post("/accounts") {
            json {
                "username" to "cashout_guess"
                "password" to "cashout_guess-password"
                "name" to "Mr Guess My Name"
                "cashout_payto_uri" to ibanPayto
            }
        }.assertOk()
        client.getA("/accounts/cashout_guess").assertOkJson<AccountData> {
            assertEquals(ibanPayto.fullOptName("Mr Guess My Name"), it.cashout_payto_uri)
        }
        val full = ibanPayto.fullOptName("Santa Claus")
        client.post("/accounts") {
            json {
                "username" to "cashout_keep"
                "password" to "cashout_keep-password"
                "name" to "Mr Keep My Name"
                "cashout_payto_uri" to full
            }
        }.assertOk()
        client.getA("/accounts/cashout_keep").assertOkJson<AccountData> {
            assertEquals(full, it.cashout_payto_uri)
        }
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

    // Test admin-only account creation
    @Test
    fun createTanErr() = bankSetup(conf = "test_tan_err.conf") { _ -> 
        client.post("/accounts") {
            pwAuth("admin")
            json {
                "username" to "baz"
                "password" to "xyz"
                "name" to "Mallory"
                "tan_channel" to "email"
            }
        }.assertConflict(TalerErrorCode.BANK_TAN_CHANNEL_NOT_SUPPORTED)
    }

    // DELETE /accounts/USERNAME
    @Test
    fun delete() = bankSetup { db -> 
        authRoutine(HttpMethod.Delete, "/accounts/merchant", allowAdmin = true)

        // Reserved account
        RESERVED_ACCOUNTS.forEach {
            client.delete("/accounts/$it") {
                pwAuth("admin")
            }.assertConflict(TalerErrorCode.BANK_RESERVED_USERNAME_CONFLICT)
        }
        client.deleteA("/accounts/exchange")
            .assertConflict(TalerErrorCode.BANK_RESERVED_USERNAME_CONFLICT)

        client.post("/accounts") {
            json {
                "username" to "john"
                "password" to "john-password"
                "name" to "John"
                "payto_uri" to genTmpPayTo()
            }
        }.assertOk()
        fillTanInfo("john")
        // Fail to delete, due to a non-zero balance.
        tx("customer", "KUDOS:1", "john")
        client.deleteA("/accounts/john")
            .assertConflict(TalerErrorCode.BANK_ACCOUNT_BALANCE_NOT_ZERO)
        // Sucessful deletion
        tx("john", "KUDOS:1", "customer")
        // TODO remove with gc
        db.conn { conn ->
            val id = conn.prepareStatement("SELECT bank_account_id FROM bank_accounts JOIN customers ON customer_id=owning_customer_id WHERE login = ?").run {
                setString(1, "john")
                oneOrNull {
                    it.getLong(1)
                }!!
            }
            conn.prepareStatement("DELETE FROM bank_account_transactions WHERE bank_account_id=?").run {
                setLong(1, id)
                execute()
            }
        }
        client.deleteA("/accounts/john")
            .assertChallenge()
            .assertNoContent()
        // Account no longer exists
        client.delete("/accounts/john") {
            pwAuth("admin")
        }.assertNotFound(TalerErrorCode.BANK_UNKNOWN_ACCOUNT)
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

    suspend fun ApplicationTestBuilder.checkAdminOnly(
        req: JsonElement,
        error: TalerErrorCode
    ) {
        // Check restricted
        client.patchA("/accounts/merchant") {
            json(req)
        }.assertConflict(error)
        // Check admin always can
        client.patch("/accounts/merchant") {
            pwAuth("admin")
            json(req)
        }.assertNoContent()
        // Check idempotent
        client.patchA("/accounts/merchant") {
            json(req)
        }.assertNoContent()
    }

    // PATCH /accounts/USERNAME
    @Test
    fun reconfig() = bankSetup { _ -> 
        authRoutine(HttpMethod.Patch, "/accounts/merchant", allowAdmin = true)

        // Check tan info
        for (channel in listOf("sms", "email")) {
            client.patchA("/accounts/merchant") {
                json { "tan_channel" to channel }
            }.assertErr(TalerErrorCode.BANK_MISSING_TAN_INFO)
        }

        // Successful attempt now
        val cashout = IbanPayTo(genIbanPaytoUri())
        val req = obj {
            "cashout_payto_uri" to cashout
            "name" to "Roger"
            "is_public" to true
            "contact_data" to obj {
                "phone" to "+99"
                "email" to "foo@example.com"
            }
        }
        client.patchA("/accounts/merchant") {
            json(req)
        }.assertNoContent()
        // Checking idempotence
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
            assertEquals(cashout.fullOptName(obj.name), obj.cashout_payto_uri)
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
            assertEquals(cashout.fullOptName(obj.name), obj.cashout_payto_uri)
            assertEquals("+99", obj.contact_data?.phone?.get())
            assertEquals("foo@example.com", obj.contact_data?.email?.get())
            assertEquals(TalerAmount("KUDOS:100"), obj.debit_threshold)
            assert(obj.is_public)
            assert(!obj.is_taler_exchange)
        }

        // Admin cannot be public
        client.patchA("/accounts/admin") {
            json {
                "is_public" to true
            }
        }.assertConflict(TalerErrorCode.END)

        // Check cashout payto receiver name logic
        client.post("/accounts") {
            json {
                "username" to "cashout"
                "password" to "cashout-password"
                "name" to "Mr Cashout Cashout"
            }
        }.assertOk()
        for ((cashout, name, expect) in listOf(
            Triple(cashout.canonical, null, cashout.fullOptName("Mr Cashout Cashout")),
            Triple(cashout.canonical, "New name", cashout.fullOptName("New name")),
            Triple(cashout.fullOptName("Full name"), null, cashout.fullOptName("Full name")),
            Triple(cashout.fullOptName("Full second name"), "Another name", cashout.fullOptName("Full second name"))
        )) {
            client.patch("/accounts/cashout") {
                pwAuth("admin")
                json {
                    "cashout_payto_uri" to cashout
                    if (name != null) "name" to name
                }
            }.assertNoContent()
            client.getA("/accounts/cashout").assertOkJson<AccountData> { obj ->
                assertEquals(expect, obj.cashout_payto_uri)
            }
        }

        // Check 2FA
        fillTanInfo("merchant")
        client.patchA("/accounts/merchant") {
            json { "is_public" to false }
        }.assertChallenge { _, _ ->
            client.getA("/accounts/merchant").assertOkJson<AccountData> { obj ->
                assert(obj.is_public)
            }
        }.assertNoContent();
        client.getA("/accounts/merchant").assertOkJson<AccountData> { obj ->
            assert(!obj.is_public)
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

    // Test TAN check account patch
    @Test
    fun patchTanErr() = bankSetup(conf = "test_tan_err.conf") { _ -> 
        // Check unsupported TAN channel
        client.patchA("/accounts/customer") {
            json {
                "tan_channel" to "email"
            }
        }.assertConflict(TalerErrorCode.BANK_TAN_CHANNEL_NOT_SUPPORTED)
    }

    // PATCH /accounts/USERNAME/auth
    @Test
    fun passwordChange() = bankSetup { _ -> 
        authRoutine(HttpMethod.Patch, "/accounts/merchant/auth", allowAdmin = true)

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
                "new_password" to "customer-password"
            }
        }.assertNoContent()

        // Check 2FA
        fillTanInfo("customer")
        client.patchA("/accounts/customer/auth") {
            json {
                "old_password" to "customer-password"
                "new_password" to "it-password"
            }
        }.assertChallenge().assertNoContent()
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
                assertEquals(0, it.username.toInt() % 2)
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
        authRoutine(HttpMethod.Get, "/accounts/merchant", allowAdmin = true)
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
    fun create() = bankSetup { db -> 
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
        // Transaction to admin
        val adminPayto = db.account.bankInfo("admin")!!.internalPaytoUri
        client.postA("/accounts/merchant/transactions") {
            json(valid_req) {
                "payto_uri" to "$adminPayto?message=payout"
            }
        }.assertConflict(TalerErrorCode.BANK_ADMIN_CREDITOR)

        // Init state
        assertBalance("merchant", "+KUDOS:0")
        assertBalance("customer", "+KUDOS:0")
        // Send 2 times 3
        repeat(2) {
            tx("merchant", "KUDOS:3", "customer")
        }
        client.postA("/accounts/merchant/transactions") {
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
        val reservePub = randEddsaPublicKey();
        tx("merchant", "KUDOS:1", "exchange", randIncomingSubject(reservePub)) // Accept incoming
        tx("merchant", "KUDOS:1", "exchange", randIncomingSubject(reservePub)) // Bounce reserve_pub reuse
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

        // Check 2fa
        fillTanInfo("merchant")
        assertBalance("merchant", "+KUDOS:3")
        assertBalance("customer", "+KUDOS:0")
        client.postA("/accounts/merchant/transactions") {
            json {
                "payto_uri" to "$customerPayto?message=tan+check&amount=KUDOS:1"
            }
        }.assertChallenge { _,_->
            assertBalance("merchant", "+KUDOS:3")
            assertBalance("customer", "+KUDOS:0")
        }.assertOkJson <TransactionCreateResponse> { 
            assertBalance("merchant", "+KUDOS:2")
            assertBalance("customer", "+KUDOS:1")
        }
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
                assertEquals(amount, it.amount)
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

    // POST /accounts/USERNAME/withdrawals/withdrawal_id/confirm
    @Test
    fun confirm() = bankSetup { _ -> 
        authRoutine(HttpMethod.Post, "/accounts/merchant/withdrawals/42/confirm")
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

        // Check confirm another user's operation
        client.postA("/accounts/customer/withdrawals") {
            json { "amount" to "KUDOS:1" } 
        }.assertOkJson<BankAccountCreateWithdrawalResponse> {
            val uuid = it.taler_withdraw_uri.split("/").last()
            withdrawalSelect(uuid)

            // Check error
            client.postA("/accounts/merchant/withdrawals/$uuid/confirm")
                .assertNotFound(TalerErrorCode.BANK_TRANSACTION_NOT_FOUND)
        }

        // Check bad UUID
        client.postA("/accounts/merchant/withdrawals/chocolate/confirm").assertBadRequest()

        // Check unknown
        client.postA("/accounts/merchant/withdrawals/${UUID.randomUUID()}/confirm")
            .assertNotFound(TalerErrorCode.BANK_TRANSACTION_NOT_FOUND)

        // Check 2fa
        fillTanInfo("merchant")
        assertBalance("merchant", "-KUDOS:6")
        client.postA("/accounts/merchant/withdrawals") {
            json { "amount" to "KUDOS:1" } 
        }.assertOkJson<BankAccountCreateWithdrawalResponse> {
            val uuid = it.taler_withdraw_uri.split("/").last()
            withdrawalSelect(uuid)

            client.postA("/accounts/merchant/withdrawals/$uuid/confirm")
            .assertChallenge { _,_->
                assertBalance("merchant", "-KUDOS:6")
            }.assertNoContent()
        }
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

        // Missing info
        client.postA("/accounts/customer/cashouts") {
            json(req) 
        }.assertConflict(TalerErrorCode.BANK_CONFIRM_INCOMPLETE)

        fillCashoutInfo("customer")

        // Check OK
        client.postA("/accounts/customer/cashouts") {
            json(req) 
        }.assertOkJson<CashoutResponse>()

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
                "request_uid" to randShortHashCode()
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

        // Check 2fa
        fillTanInfo("customer")
        assertBalance("customer", "-KUDOS:1")
        client.postA("/accounts/customer/cashouts") {
            json(req) {
                "request_uid" to randShortHashCode()
            }
        }.assertChallenge { _,_->
            assertBalance("customer", "-KUDOS:1")
        }.assertOkJson<CashoutResponse> {
            assertBalance("customer", "-KUDOS:2")
        }
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
        }.assertOkJson<CashoutResponse> {
            val id = it.cashout_id
            client.getA("/accounts/customer/cashouts/$id")
                .assertOkJson<CashoutStatusResponse> {
                assertEquals(CashoutStatus.confirmed, it.status)
                assertEquals(amountDebit, it.amount_debit)
                assertEquals(amountCredit, it.amount_credit)
                assertNull(it.tan_channel)
                assertNull(it.tan_info)
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
        }.assertOkJson<CashoutResponse> {
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

class CoreBankTanApiTest {
    // POST /accounts/{USERNAME}/challenge/{challenge_id}
    @Test
    fun send() = bankSetup { _ ->
        authRoutine(HttpMethod.Post, "/accounts/merchant/challenge/42")

        suspend fun HttpResponse.expectChallenge(channel: TanChannel, info: String): HttpResponse {
            return assertChallenge { tanChannel, tanInfo ->
                assertEquals(channel, tanChannel)
                assertEquals(info, tanInfo)
            }
        }

        suspend fun HttpResponse.expectTransmission(channel: TanChannel, info: String) {
            this.assertOkJson<TanTransmission> {
                assertEquals(it.tan_channel, channel)
                assertEquals(it.tan_info, info)
            }
        }

        // Set up 2fa 
        client.patchA("/accounts/merchant") {
            json { 
                "contact_data" to obj {
                    "phone" to "+99"
                    "email" to "email@example.com"
                }
                "tan_channel" to "sms"
            }
        }.expectChallenge(TanChannel.sms, "+99")
            .assertNoContent()
        
        // Update 2fa settings - first 2FA challenge then new tan channel check
        client.patchA("/accounts/merchant") {
            json { // Info change
                "contact_data" to obj { "phone" to "+98" }
            }
        }.expectChallenge(TanChannel.sms, "+99")
            .expectChallenge(TanChannel.sms, "+98")
            .assertNoContent()
        client.patchA("/accounts/merchant") {
            json { // Channel change
                "tan_channel" to "email"
            }
        }.expectChallenge(TanChannel.sms, "+98")
            .expectChallenge(TanChannel.email, "email@example.com")
            .assertNoContent()
        client.patchA("/accounts/merchant") {
            json { // Both change
                "contact_data" to obj { "phone" to "+97" }
                "tan_channel" to "sms"
            }
        }.expectChallenge(TanChannel.email, "email@example.com")
            .expectChallenge(TanChannel.sms, "+97")
            .assertNoContent()

        // Disable 2fa
        client.patchA("/accounts/merchant") {
            json { "tan_channel" to null as String? }
        }.expectChallenge(TanChannel.sms, "+97")
            .assertNoContent()

        // Admin has no 2FA
        client.patch("/accounts/merchant") {
            pwAuth("admin")
            json { 
                "contact_data" to obj { "phone" to "+99" }
                "tan_channel" to "sms"
            }
        }.assertNoContent()
        client.patch("/accounts/merchant") {
            pwAuth("admin")
            json { "tan_channel" to "email" }
        }.assertNoContent()
        client.patch("/accounts/merchant") {
            pwAuth("admin")
            json { "tan_channel" to null as String? }
        }.assertNoContent()

        // Check retry and invalidate
        client.patchA("/accounts/merchant") {
            json { 
                "contact_data" to obj { "phone" to "+88" }
                "tan_channel" to "sms"
            }
        }.assertChallenge().assertNoContent()
        client.patchA("/accounts/merchant") {
            json { "is_public" to false }
        }.assertAcceptedJson<TanChallenge> { 
            // Check ok
            client.postA("/accounts/merchant/challenge/${it.challenge_id}")
                .expectTransmission(TanChannel.sms, "+88")
            assertNotNull(tanCode("+88"))
            // Check retry
            client.postA("/accounts/merchant/challenge/${it.challenge_id}")
                .expectTransmission(TanChannel.sms, "+88")
            assertNull(tanCode("+88"))
            // Idempotent patch does nothing
            client.patchA("/accounts/merchant") {
                json { 
                    "contact_data" to obj { "phone" to "+88" }
                    "tan_channel" to "sms"
                }
            }
            client.postA("/accounts/merchant/challenge/${it.challenge_id}")
                .expectTransmission(TanChannel.sms, "+88")
            assertNull(tanCode("+88"))
            // Change 2fa settings
            client.patchA("/accounts/merchant") {
                json { 
                    "tan_channel" to "email"
                }
            }.expectChallenge(TanChannel.sms, "+88")
                .expectChallenge(TanChannel.email, "email@example.com")
                .assertNoContent()
            // Check invalidated
            client.postA("/accounts/merchant/challenge/${it.challenge_id}")
                .expectTransmission(TanChannel.email, "email@example.com")
            assertNotNull(tanCode("email@example.com"))
        }

        // Unknown challenge
        client.postA("/accounts/merchant/challenge/42")
            .assertNotFound(TalerErrorCode.BANK_TRANSACTION_NOT_FOUND)
    }

    // POST /accounts/{USERNAME}/challenge/{challenge_id}
    @Test
    fun sendTanErr() = bankSetup("test_tan_err.conf") { _ ->
        // Check fail
        client.patch("/accounts/merchant") {
            pwAuth("admin")
            json { 
                "contact_data" to obj { "phone" to "+1234" }
                "tan_channel" to "sms"
            }
        }.assertNoContent()
        client.patchA("/accounts/merchant") {
            json { "is_public" to false }
        }.assertAcceptedJson<TanChallenge> { 
            client.postA("/accounts/merchant/challenge/${it.challenge_id}")
                .assertStatus(HttpStatusCode.BadGateway, TalerErrorCode.BANK_TAN_CHANNEL_SCRIPT_FAILED)
        }
    }

    // POST /accounts/{USERNAME}/challenge/{challenge_id}/confirm
    @Test
    fun confirm() = bankSetup { _ ->
        authRoutine(HttpMethod.Post, "/accounts/merchant/challenge/42/confirm")

        fillTanInfo("merchant")

        // Check simple case
        client.patchA("/accounts/merchant") {
            json { "is_public" to false }
        }.assertAcceptedJson<TanChallenge> {
            val id = it.challenge_id
            val info = client.postA("/accounts/merchant/challenge/$id")
                .assertOkJson<TanTransmission>().tan_info
            val code = tanCode(info)

            // Check bad TAN code
            client.postA("/accounts/merchant/challenge/$id/confirm") {
                json { "tan" to "nice-try" } 
            }.assertConflict(TalerErrorCode.BANK_TAN_CHALLENGE_FAILED)

            // Check wrong account
            client.postA("/accounts/customer/challenge/$id/confirm") {
                json { "tan" to "nice-try" } 
            }.assertNotFound(TalerErrorCode.BANK_CHALLENGE_NOT_FOUND)
        
            // Check OK
            client.postA("/accounts/merchant/challenge/$id/confirm") {
                json { "tan" to code }
            }.assertNoContent()
            // Check idempotence
            client.postA("/accounts/merchant/challenge/$id/confirm") {
                json { "tan" to code }
            }.assertNoContent()

            // Unknown challenge
            client.postA("/accounts/merchant/challenge/42/confirm") {
                json { "tan" to code }
            }.assertNotFound(TalerErrorCode.BANK_CHALLENGE_NOT_FOUND)
        }
        
        // Check invalidation
        client.patchA("/accounts/merchant") {
            json { "is_public" to true }
        }.assertAcceptedJson<TanChallenge> {
            val id = it.challenge_id
            val info = client.postA("/accounts/merchant/challenge/$id")
                .assertOkJson<TanTransmission>().tan_info
             
            // Check invalidated
            fillTanInfo("merchant")
            client.postA("/accounts/merchant/challenge/$id/confirm") {
                json { "tan" to tanCode(info) }
            }.assertConflict(TalerErrorCode.BANK_TAN_CHALLENGE_EXPIRED)

            val new = client.postA("/accounts/merchant/challenge/$id")
                .assertOkJson<TanTransmission>().tan_info
            val code = tanCode(new)
            // Idempotent patch does nothing
            client.patchA("/accounts/merchant") {
                json { 
                    "contact_data" to obj { "phone" to "+88" }
                    "tan_channel" to "sms"
                }
            }
            client.postA("/accounts/merchant/challenge/$id/confirm") {
                json { "tan" to code }
            }.assertNoContent()
            
            // Solved challenge remain solved
            fillTanInfo("merchant")
            client.postA("/accounts/merchant/challenge/$id/confirm") {
                json { "tan" to code }
            }.assertNoContent()
        }
    }
}