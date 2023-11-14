import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.HttpClient
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.engine.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import net.taler.wallet.crypto.Base32Crockford
import net.taler.common.errorcodes.TalerErrorCode
import org.junit.Test
import org.postgresql.jdbc.PgConnection
import tech.libeufin.bank.*
import tech.libeufin.util.CryptoUtil
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import java.io.File
import kotlin.random.Random
import kotlin.test.*
import kotlinx.coroutines.*

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
            basicAuth("admin", "admin-password")
        }.assertOk()

        // Check only admin
        client.get("/monitor") {
            basicAuth("exchange", "exchange-password")
        }.assertUnauthorized()
    }
}

class CoreBankTokenApiTest {
    // POST /accounts/USERNAME/token
    @Test
    fun post() = bankSetup { db -> 
        // Wrong user
        client.post("/accounts/merchant/token") {
            basicAuth("exchange", "exchange-password")
        }.assertUnauthorized()

        // New default token
        client.post("/accounts/merchant/token") {
            basicAuth("merchant", "merchant-password")
            jsonBody { "scope" to "readonly" }
        }.assertOk().run {
            // Checking that the token lifetime defaulted to 24 hours.
            val resp = json<TokenSuccessResponse>()
            val token = db.bearerTokenGet(Base32Crockford.decode(resp.access_token))
            val lifeTime = Duration.between(token!!.creationTime, token.expirationTime)
            assertEquals(Duration.ofDays(1), lifeTime)
        }

        // Check default duration
        client.post("/accounts/merchant/token") {
            basicAuth("merchant", "merchant-password")
            jsonBody { "scope" to "readonly" }
        }.assertOk().run {
            // Checking that the token lifetime defaulted to 24 hours.
            val resp = json<TokenSuccessResponse>()
            val token = db.bearerTokenGet(Base32Crockford.decode(resp.access_token))
            val lifeTime = Duration.between(token!!.creationTime, token.expirationTime)
            assertEquals(Duration.ofDays(1), lifeTime)
        }

        // Check refresh
        client.post("/accounts/merchant/token") {
            basicAuth("merchant", "merchant-password")
            jsonBody { 
                "scope" to "readonly"
                "refreshable" to true
            }
        }.assertOk().run {
            val token = json<TokenSuccessResponse>().access_token
            client.post("/accounts/merchant/token") {
                headers["Authorization"] = "Bearer secret-token:$token"
                jsonBody { "scope" to "readonly" }
            }.assertOk()
        }
        
        // Check'forever' case.
        client.post("/accounts/merchant/token") {
            basicAuth("merchant", "merchant-password")
            jsonBody { 
                "scope" to "readonly"
                "duration" to json {
                    "d_us" to "forever"
                }
            }
        }.run {
            val never: TokenSuccessResponse = json()
            assertEquals(Instant.MAX, never.expiration.t_s)
        }

        // Check too big or invalid durations
        client.post("/accounts/merchant/token") {
            basicAuth("merchant", "merchant-password")
            jsonBody { 
                "scope" to "readonly"
                "duration" to json {
                    "d_us" to "invalid"
                }
            }
        }.assertBadRequest()
        client.post("/accounts/merchant/token") {
            basicAuth("merchant", "merchant-password")
            jsonBody { 
                "scope" to "readonly"
                "duration" to json {
                    "d_us" to Long.MAX_VALUE
                }
            }
        }.assertBadRequest()
        client.post("/accounts/merchant/token") {
            basicAuth("merchant", "merchant-password")
            jsonBody { 
                "scope" to "readonly"
                "duration" to json {
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
            basicAuth("merchant", "merchant-password")
            jsonBody { "scope" to "readonly" }
        }.assertOk().run {
            json<TokenSuccessResponse>().access_token
        }
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
            basicAuth("merchant", "merchant-password")
        }.assertOk()
    }
}

class CoreBankAccountsApiTest {
    // Testing the account creation and its idempotency
    @Test
    fun createAccountTest() = bankSetup { _ -> 
        val ibanPayto = genIbanPaytoUri()
        val req = json {
            "username" to "foo"
            "password" to "password"
            "name" to "Jane"
            "is_public" to true
            "internal_payto_uri" to ibanPayto
            "is_taler_exchange" to true
        }
        // Check Ok
        client.post("/accounts") {
            jsonBody(req)
        }.assertCreated()
        // Testing idempotency
        client.post("/accounts") {
            jsonBody(req)
        }.assertCreated()

        // Test generate payto_uri
        client.post("/accounts") {
            jsonBody {
                "username" to "jor"
                "password" to "password"
                "name" to "Joe"
            }
        }.assertCreated()

        // Reserved account
        reservedAccounts.forEach {
            client.post("/accounts") {
                jsonBody {
                    "username" to it
                    "password" to "password"
                    "name" to "John Smith"
                }
            }.assertConflict(TalerErrorCode.BANK_RESERVED_USERNAME_CONFLICT)
        }

        // Testing login conflict
        client.post("/accounts") {
            jsonBody(req) {
                "name" to "Foo"
            }
        }.assertConflict(TalerErrorCode.BANK_REGISTER_USERNAME_REUSE)
        // Testing payto conflict
        client.post("/accounts") {
            jsonBody(req) {
                "username" to "bar"
            }
        }.assertConflict(TalerErrorCode.BANK_REGISTER_PAYTO_URI_REUSE)
        client.get("/accounts/bar") {
            basicAuth("admin", "admin-password")
        }.assertNotFound(TalerErrorCode.BANK_UNKNOWN_ACCOUNT)
    }

    // Test account created with bonus
    @Test
    fun createAccountBonusTest() = bankSetup(conf = "test_bonus.conf") { _ -> 
        val req = json {
            "username" to "foo"
            "password" to "xyz"
            "name" to "Mallory"
        }

        // Check ok
        repeat(100) {
            client.post("/accounts") {
                basicAuth("admin", "admin-password")
                jsonBody(req) {
                    "username" to "foo$it"
                }
            }.assertCreated()
            assertBalance("foo$it", CreditDebitInfo.credit, "KUDOS:100")
        }
        assertBalance("admin", CreditDebitInfo.debit, "KUDOS:10000")
        
        // Check unsufficient fund
        client.post("/accounts") {
            basicAuth("admin", "admin-password")
            jsonBody(req) {
                "username" to "bar"
            }
        }.assertConflict(TalerErrorCode.BANK_UNALLOWED_DEBIT)
        client.get("/accounts/bar") {
            basicAuth("admin", "admin-password")
        }.assertNotFound(TalerErrorCode.BANK_UNKNOWN_ACCOUNT)
    }

    // Test admin-only account creation
    @Test
    fun createAccountRestrictedTest() = bankSetup(conf = "test_restrict.conf") { _ -> 
        val req = json {
            "username" to "baz"
            "password" to "xyz"
            "name" to "Mallory"
        }

        client.post("/accounts") {
            basicAuth("merchant", "merchant-password")
            jsonBody(req)
        }.assertUnauthorized()
        client.post("/accounts") {
            basicAuth("admin", "admin-password")
            jsonBody(req)
        }.assertCreated()
    }

    // DELETE /accounts/USERNAME
    @Test
    fun deleteAccount() = bankSetup { _ -> 
        // Unknown account
        client.delete("/accounts/unknown") {
            basicAuth("admin", "admin-password")
        }.assertNotFound(TalerErrorCode.BANK_UNKNOWN_ACCOUNT)

        // Reserved account
        reservedAccounts.forEach {
            client.delete("/accounts/$it") {
                basicAuth("admin", "admin-password")
            }.assertConflict(TalerErrorCode.BANK_RESERVED_USERNAME_CONFLICT)
        }
       
        // successful deletion
        client.post("/accounts") {
            jsonBody {
                "username" to "john"
                "password" to "password"
                "name" to "John Smith"
            }
        }.assertCreated()
        client.delete("/accounts/john") {
            basicAuth("admin", "admin-password")
        }.assertNoContent()
        // Trying again must yield 404
        client.delete("/accounts/john") {
            basicAuth("admin", "admin-password")
        }.assertNotFound(TalerErrorCode.BANK_UNKNOWN_ACCOUNT)

        
        // fail to delete, due to a non-zero balance.
        tx("exchange", "KUDOS:1", "merchant")
        client.delete("/accounts/merchant") {
            basicAuth("admin", "admin-password")
        }.assertConflict(TalerErrorCode.BANK_ACCOUNT_BALANCE_NOT_ZERO)
        tx("merchant", "KUDOS:1", "exchange")
        client.delete("/accounts/merchant") {
            basicAuth("admin", "admin-password")
        }.assertNoContent()
    }

    // PATCH /accounts/USERNAME
    @Test
    fun accountReconfig() = bankSetup { _ -> 
        // Successful attempt now.
        val cashout = IbanPayTo(genIbanPaytoUri())
        val req = json {
            "cashout_payto_uri" to cashout.canonical
            "challenge_contact_data" to json {
                "phone" to "+99"
                "email" to "foo@example.com"
            }
            "is_taler_exchange" to true 
        }
        client.patch("/accounts/merchant") {
            basicAuth("merchant", "merchant-password")
            jsonBody(req)
        }.assertNoContent()
        // Checking idempotence.
        client.patch("/accounts/merchant") {
            basicAuth("merchant", "merchant-password")
            jsonBody(req)
        }.assertNoContent()

        suspend fun checkAdminOnly(req: JsonElement, error: TalerErrorCode) {
            // Checking ordinary user doesn't get to patch
            client.patch("/accounts/merchant") {
                basicAuth("merchant", "merchant-password")
                jsonBody(req)
            }.assertConflict(error)
            // Finally checking that admin does get to patch
            client.patch("/accounts/merchant") {
                basicAuth("admin", "admin-password")
                jsonBody(req)
            }.assertNoContent()
        }

        checkAdminOnly(
            json(req) { "name" to "Another Foo" },
            TalerErrorCode.BANK_NON_ADMIN_PATCH_LEGAL_NAME
        )
        checkAdminOnly(
            json(req) { "debit_threshold" to "KUDOS:100" },
            TalerErrorCode.BANK_NON_ADMIN_PATCH_DEBT_LIMIT
        )

        // Check admin account cannot be exchange
        client.patch("/accounts/admin") {
            basicAuth("admin", "admin-password")
            jsonBody { "is_taler_exchange" to true }
        }.assertConflict(TalerErrorCode.BANK_PATCH_ADMIN_EXCHANGE)
        // But we can change its debt limit
        client.patch("/accounts/admin") {
            basicAuth("admin", "admin-password")
            jsonBody { "debit_threshold" to "KUDOS:100" }
        }.assertNoContent()
        
        // Check currency
        client.patch("/accounts/merchant") {
            basicAuth("admin", "admin-password")
            jsonBody(req) { "debit_threshold" to "EUR:100" }
        }.assertBadRequest()

        // Check patch
        client.get("/accounts/merchant") {
            basicAuth("admin", "admin-password")
        }.assertOk().run { 
            val obj: AccountData = json()
            assertEquals("Another Foo", obj.name)
            assertEquals(cashout.canonical, obj.cashout_payto_uri?.canonical)
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
            jsonBody {
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
            jsonBody {
                "old_password" to "new-password"
                "new_password" to "customer-password"
            }
        }.assertNoContent()


        // Check require test old password
        client.patch("/accounts/customer/auth") {
            basicAuth("customer", "customer-password")
            jsonBody {
                "old_password" to "bad-password"
                "new_password" to "new-password"
            }
        }.assertConflict(TalerErrorCode.BANK_PATCH_BAD_OLD_PASSWORD)

        // Check require old password for user
        client.patch("/accounts/customer/auth") {
            basicAuth("customer", "customer-password")
            jsonBody {
                "new_password" to "new-password"
            }
        }.assertConflict(TalerErrorCode.BANK_NON_ADMIN_PATCH_MISSING_OLD_PASSWORD)

        // Check admin 
        client.patch("/accounts/customer/auth") {
            basicAuth("admin", "admin-password")
            jsonBody {
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
                basicAuth("admin", "admin-password")
            }.assertNoContent()
        }
        // Check error when no public accounts
        client.get("/public-accounts").assertNoContent()
        client.get("/accounts") {
            basicAuth("admin", "admin-password")
        }.assertOk()
        
        // Gen some public and private accounts
        repeat(5) {
            client.post("/accounts") {
                jsonBody {
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
            basicAuth("admin", "admin-password")
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
            basicAuth("admin", "admin-password")
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
        client.get("/accounts/merchant") {
            basicAuth("merchant", "merchant-password")
        }.assertOk().run {
            val obj: AccountData = json()
            assertEquals("Merchant", obj.name)
        }

        // Check admin ok
        client.get("/accounts/merchant") {
            basicAuth("admin", "admin-password")
        }.assertOk()

        // Check wrong user
        client.get("/accounts/exchange") {
            basicAuth("merchant", "merchant-password")
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
            basicAuth("merchant", "merchant-password")
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
        assertTime(0, 200) {
            client.get("/accounts/merchant/transactions?delta=-6&start=11&long_poll_ms=1000") {
                basicAuth("merchant", "merchant-password")
            }.assertHistory(5)
        }

        // Check no polling when find transaction
        assertTime(0, 200) {
            client.get("/accounts/merchant/transactions?delta=6&long_poll_ms=1000") {
                basicAuth("merchant", "merchant-password")
            }.assertHistory(5)
        }

        coroutineScope {
            launch { // Check polling succeed
                assertTime(200, 1000) {
                    client.get("/accounts/merchant/transactions?delta=2&start=10&long_poll_ms=1000") {
                        basicAuth("merchant", "merchant-password")
                    }.assertHistory(1)
                }
            }
            launch { // Check polling timeout
                assertTime(200, 400) {
                    client.get("/accounts/merchant/transactions?delta=1&start=11&long_poll_ms=300") {
                        basicAuth("merchant", "merchant-password")
                    }.assertNoContent()
                }
            }
            delay(200)
            tx("merchant", "KUDOS:4.2", "exchange")
        }

        // Testing ranges. 
        repeat(30) {
            tx("merchant", "KUDOS:0.001", "exchange")
        }

        // forward range:
        client.get("/accounts/merchant/transactions?delta=10&start=20") {
            basicAuth("merchant", "merchant-password")
        }.assertHistory(10)

        // backward range:
        client.get("/accounts/merchant/transactions?delta=-10&start=25") {
            basicAuth("merchant", "merchant-password")
        }.assertHistory(10)
    }

    // GET /transactions/T_ID
    @Test
    fun testById() = bankSetup { _ -> 
        authRoutine("/accounts/merchant/transactions/1", method = HttpMethod.Get)

        // Create transaction
        tx("merchant", "KUDOS:0.3", "exchange", "tx")
        // Check OK
        client.get("/accounts/merchant/transactions/1") {
            basicAuth("merchant", "merchant-password")
        }.assertOk().run {
            val tx: BankAccountTransactionInfo = json()
            assertEquals("tx", tx.subject)
            assertEquals(TalerAmount("KUDOS:0.3"), tx.amount)
        }
        // Check unknown transaction
        client.get("/accounts/merchant/transactions/3") {
            basicAuth("merchant", "merchant-password")
        }.assertNotFound(TalerErrorCode.BANK_TRANSACTION_NOT_FOUND)
        // Check wrong transaction
        client.get("/accounts/merchant/transactions/2") {
            basicAuth("merchant", "merchant-password")
        }.assertUnauthorized() // Should be NOT_FOUND ?
    }

    // POST /transactions
    @Test
    fun create() = bankSetup { _ -> 
        val valid_req = json {
            "payto_uri" to "$exchangePayto?message=payout"
            "amount" to "KUDOS:0.3"
        }

        authRoutine("/accounts/merchant/transactions")

        // Check OK
        client.post("/accounts/merchant/transactions") {
            basicAuth("merchant", "merchant-password")
            jsonBody(valid_req)
        }.assertOk().run {
            val id = json<TransactionCreateResponse>().row_id
            client.get("/accounts/merchant/transactions/$id") {
                basicAuth("merchant", "merchant-password")
            }.assertOk().run {
                val tx: BankAccountTransactionInfo = json()
                assertEquals("payout", tx.subject)
                assertEquals(TalerAmount("KUDOS:0.3"), tx.amount)
            }
        }
        
        // Check amount in payto_uri
        client.post("/accounts/merchant/transactions") {
            basicAuth("merchant", "merchant-password")
            jsonBody {
                "payto_uri" to "$exchangePayto?message=payout2&amount=KUDOS:1.05"
            }
        }.assertOk().run {
            val id = json<TransactionCreateResponse>().row_id
            client.get("/accounts/merchant/transactions/$id") {
                basicAuth("merchant", "merchant-password")
            }.assertOk().run {
                val tx: BankAccountTransactionInfo = json()
                assertEquals("payout2", tx.subject)
                assertEquals(TalerAmount("KUDOS:1.05"), tx.amount)
            }
        }
       
        // Check amount in payto_uri precedence
        client.post("/accounts/merchant/transactions") {
            basicAuth("merchant", "merchant-password")
            jsonBody {
                "payto_uri" to "$exchangePayto?message=payout3&amount=KUDOS:1.05"
                "amount" to "KUDOS:10.003"
            }
        }.assertOk().run {
            val id = json<TransactionCreateResponse>().row_id
            client.get("/accounts/merchant/transactions/$id") {
                basicAuth("merchant", "merchant-password")
            }.assertOk().run {
                val tx: BankAccountTransactionInfo = json()
                assertEquals("payout3", tx.subject)
                assertEquals(TalerAmount("KUDOS:1.05"), tx.amount)
            }
        }
        // Testing the wrong currency
        client.post("/accounts/merchant/transactions") {
            basicAuth("merchant", "merchant-password")
            jsonBody(valid_req) {
                "amount" to "EUR:3.3"
            }
        }.assertBadRequest(TalerErrorCode.GENERIC_CURRENCY_MISMATCH)
        // Surpassing the debt limit
        client.post("/accounts/merchant/transactions") {
            basicAuth("merchant", "merchant-password")
            contentType(ContentType.Application.Json)
            jsonBody(valid_req) {
                "amount" to "KUDOS:555"
            }
        }.assertConflict(TalerErrorCode.BANK_UNALLOWED_DEBIT)
        // Missing message
        client.post("/accounts/merchant/transactions") {
            basicAuth("merchant", "merchant-password")
            contentType(ContentType.Application.Json)
            jsonBody(valid_req) {
                "payto_uri" to "$exchangePayto"
            }
        }.assertBadRequest()
        // Unknown creditor
        client.post("/accounts/merchant/transactions") {
            basicAuth("merchant", "merchant-password")
            contentType(ContentType.Application.Json)
            jsonBody(valid_req) {
                "payto_uri" to "$unknownPayto?message=payout"
            }
        }.assertConflict(TalerErrorCode.BANK_UNKNOWN_CREDITOR)
        // Transaction to self
        client.post("/accounts/merchant/transactions") {
            basicAuth("merchant", "merchant-password")
            contentType(ContentType.Application.Json)
            jsonBody(valid_req) {
                "payto_uri" to "$merchantPayto?message=payout"
            }
        }.assertConflict(TalerErrorCode.BANK_SAME_ACCOUNT)

        suspend fun checkBalance(
            merchantDebt: Boolean,
            merchantAmount: String,
            customerDebt: Boolean,
            customerAmount: String,
        ) {
            client.get("/accounts/merchant") {
                basicAuth("admin", "admin-password")
            }.assertOk().run {
                val obj: AccountData = json()
                assertEquals(
                    if (merchantDebt) CreditDebitInfo.debit else CreditDebitInfo.credit, 
                    obj.balance.credit_debit_indicator)
                assertEquals(TalerAmount(merchantAmount), obj.balance.amount)
            }
            client.get("/accounts/customer") {
                basicAuth("admin", "admin-password")
            }.assertOk().run {
                val obj: AccountData = json()
                assertEquals(
                    if (customerDebt) CreditDebitInfo.debit else CreditDebitInfo.credit, 
                    obj.balance.credit_debit_indicator)
                assertEquals(TalerAmount(customerAmount), obj.balance.amount)
            }
        }

        // Init state
        checkBalance(true, "KUDOS:2.4", false, "KUDOS:0")
        // Send 2 times 3
        repeat(2) {
            tx("merchant", "KUDOS:3", "customer")
        }
        client.post("/accounts/merchant/transactions") {
            basicAuth("merchant", "merchant-password")
            jsonBody {
                "payto_uri" to "$customerPayto?message=payout2&amount=KUDOS:3"
            }
        }.assertConflict(TalerErrorCode.BANK_UNALLOWED_DEBIT)
        checkBalance(true, "KUDOS:8.4", false, "KUDOS:6")
        // Send throught debt
        client.post("/accounts/customer/transactions") {
            basicAuth("customer", "customer-password")
            jsonBody {
                "payto_uri" to "$merchantPayto?message=payout2&amount=KUDOS:10"
            }
        }.assertOk()
        checkBalance(false, "KUDOS:1.6", true, "KUDOS:4")
    }
}

class CoreBankWithdrawalApiTest {
    // POST /accounts/USERNAME/withdrawals
    @Test
    fun create() = bankSetup { _ ->
        // Check OK
        client.post("/accounts/merchant/withdrawals") {
            basicAuth("merchant", "merchant-password")
            jsonBody { "amount" to "KUDOS:9.0" } 
        }.assertOk()

        // Check exchange account
        client.post("/accounts/exchange/withdrawals") {
            basicAuth("exchange", "exchange-password")
            jsonBody { "amount" to "KUDOS:9.0" } 
        }.assertConflict(TalerErrorCode.BANK_ACCOUNT_IS_EXCHANGE)

        // Check insufficient fund
        client.post("/accounts/merchant/withdrawals") {
            basicAuth("merchant", "merchant-password")
            jsonBody { "amount" to "KUDOS:90" } 
        }.assertConflict(TalerErrorCode.BANK_UNALLOWED_DEBIT)
    }

    // GET /withdrawals/withdrawal_id
    @Test
    fun get() = bankSetup { _ ->
        // Check OK
        client.post("/accounts/merchant/withdrawals") {
            basicAuth("merchant", "merchant-password")
            jsonBody { "amount" to "KUDOS:9.0" }
        }.assertOk().run {
            val opId = json<BankAccountCreateWithdrawalResponse>()
            client.get("/withdrawals/${opId.withdrawal_id}") {
                basicAuth("merchant", "merchant-password")
            }.assertOk()
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
        client.post("/accounts/merchant/withdrawals") {
            basicAuth("merchant", "merchant-password")
            jsonBody { "amount" to "KUDOS:1" } 
        }.assertOk().run {
            val resp = json<BankAccountCreateWithdrawalResponse>()
            val uuid = resp.taler_withdraw_uri.split("/").last()

            // Check OK
            client.post("/withdrawals/$uuid/abort").assertNoContent()
            // Check idempotence
            client.post("/withdrawals/$uuid/abort").assertNoContent()
        }

        // Check abort selected
        client.post("/accounts/merchant/withdrawals") {
            basicAuth("merchant", "merchant-password")
            jsonBody { "amount" to "KUDOS:1" } 
        }.assertOk().run {
            val resp = json<BankAccountCreateWithdrawalResponse>()
            val uuid = resp.taler_withdraw_uri.split("/").last()
            client.post("/taler-integration/withdrawal-operation/$uuid") {
                jsonBody {
                    "reserve_pub" to randEddsaPublicKey()
                    "selected_exchange" to exchangePayto
                }
            }.assertOk()

            // Check OK
            client.post("/withdrawals/$uuid/abort").assertNoContent()
            // Check idempotence
            client.post("/withdrawals/$uuid/abort").assertNoContent()
        }

        // Check abort confirmed
        client.post("/accounts/merchant/withdrawals") {
            basicAuth("merchant", "merchant-password")
            jsonBody { "amount" to "KUDOS:1" } 
        }.assertOk().run {
            val resp = json<BankAccountCreateWithdrawalResponse>()
            val uuid = resp.taler_withdraw_uri.split("/").last()
            client.post("/taler-integration/withdrawal-operation/$uuid") {
                jsonBody {
                    "reserve_pub" to randEddsaPublicKey()
                    "selected_exchange" to exchangePayto
                }
            }.assertOk()
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
        client.post("/accounts/merchant/withdrawals") {
            basicAuth("merchant", "merchant-password")
            jsonBody { "amount" to "KUDOS:1" } 
        }.assertOk().run {
            val resp = json<BankAccountCreateWithdrawalResponse>()
            val uuid = resp.taler_withdraw_uri.split("/").last()

            // Check err
            client.post("/withdrawals/$uuid/confirm")
                .assertConflict(TalerErrorCode.BANK_CONFIRM_INCOMPLETE)
        }

        // Check confirm selected
        client.post("/accounts/merchant/withdrawals") {
            basicAuth("merchant", "merchant-password")
            jsonBody { "amount" to "KUDOS:1" } 
        }.assertOk().run {
            val resp = json<BankAccountCreateWithdrawalResponse>()
            val uuid = resp.taler_withdraw_uri.split("/").last()
            client.post("/taler-integration/withdrawal-operation/$uuid") {
                jsonBody {
                    "reserve_pub" to randEddsaPublicKey()
                    "selected_exchange" to exchangePayto
                }
            }.assertOk()

            // Check OK
            client.post("/withdrawals/$uuid/confirm").assertNoContent()
            // Check idempotence
            client.post("/withdrawals/$uuid/confirm").assertNoContent()
        }

        // Check confirm aborted
        client.post("/accounts/merchant/withdrawals") {
            basicAuth("merchant", "merchant-password")
            jsonBody { "amount" to "KUDOS:1" } 
        }.assertOk().run {
            val resp = json<BankAccountCreateWithdrawalResponse>()
            val uuid = resp.taler_withdraw_uri.split("/").last()
            client.post("/taler-integration/withdrawal-operation/$uuid") {
                jsonBody {
                    "reserve_pub" to randEddsaPublicKey()
                    "selected_exchange" to exchangePayto
                }
            }.assertOk()
            client.post("/withdrawals/$uuid/abort").assertNoContent()

            // Check error
            client.post("/withdrawals/$uuid/confirm")
                .assertConflict(TalerErrorCode.BANK_CONFIRM_ABORT_CONFLICT)
        }

        // Check balance insufficient
        client.post("/accounts/merchant/withdrawals") {
            basicAuth("merchant", "merchant-password")
            jsonBody { "amount" to "KUDOS:5" } 
        }.assertOk().run {
            val resp = json<BankAccountCreateWithdrawalResponse>()
            val uuid = resp.taler_withdraw_uri.split("/").last()
            client.post("/taler-integration/withdrawal-operation/$uuid") {
                jsonBody {
                    "reserve_pub" to randEddsaPublicKey()
                    "selected_exchange" to exchangePayto
                }
            }.assertOk()

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
        val req = json {
            "request_uid" to randShortHashCode()
            "amount_debit" to "KUDOS:1"
            "amount_credit" to convert("KUDOS:1")
        }

        // Check missing TAN info
        client.post("/accounts/customer/cashouts") {
            basicAuth("customer", "customer-password")
            jsonBody(req) 
        }.assertConflict(TalerErrorCode.BANK_MISSING_TAN_INFO)
        client.patch("/accounts/customer") {
            basicAuth("customer", "customer-password")
            jsonBody(json {
                "challenge_contact_data" to json {
                    "phone" to "+99"
                    "email" to "foo@example.com"
                }
            })
        }.assertNoContent()

        // Check email TAN error
        client.post("/accounts/customer/cashouts") {
            basicAuth("customer", "customer-password")
            jsonBody(req) {
                "tan_channel" to "email"
            }
        }.assertStatus(HttpStatusCode.BadGateway, TalerErrorCode.BANK_TAN_CHANNEL_SCRIPT_FAILED)

        // Check OK
        client.post("/accounts/customer/cashouts") {
            basicAuth("customer", "customer-password")
            jsonBody(req) 
        }.assertOk().run {
            val id = json<CashoutPending>().cashout_id
            smsCode("+99")
            // Check idempotency
            client.post("/accounts/customer/cashouts") {
                basicAuth("customer", "customer-password")
                jsonBody(req) 
            }.assertOk().run { 
                assertEquals(id, json<CashoutPending>().cashout_id)
                assertNull(smsCode("+99"))     
            }
        }

        // Trigger conflict due to reused request_uid
        client.post("/accounts/customer/cashouts") {
            basicAuth("customer", "customer-password")
            jsonBody(req) {
                "amount_debit" to "KUDOS:2"
                "amount_credit" to convert("KUDOS:2")
            }
        }.assertConflict(TalerErrorCode.BANK_TRANSFER_REQUEST_UID_REUSED)

        // Check exchange account
        client.post("/accounts/exchange/cashouts") {
            basicAuth("exchange", "exchange-password")
            jsonBody(req) 
        }.assertConflict(TalerErrorCode.BANK_ACCOUNT_IS_EXCHANGE)

        // Check insufficient fund
        client.post("/accounts/customer/cashouts") {
            basicAuth("customer", "customer-password")
            jsonBody(req) {
                "amount_debit" to "KUDOS:75"
                "amount_credit" to convert("KUDOS:75")
            }
        }.assertConflict(TalerErrorCode.BANK_UNALLOWED_DEBIT)

        // Check wrong conversion
        client.post("/accounts/customer/cashouts") {
            basicAuth("customer", "customer-password")
            jsonBody(req) {
                "amount_credit" to convert("KUDOS:2")
            }
        }.assertConflict(TalerErrorCode.BANK_BAD_CONVERSION)

        // Check wrong currency
        client.post("/accounts/customer/cashouts") {
            basicAuth("customer", "customer-password")
            jsonBody(req) {
                "amount_debit" to "EUR:1"
            }
        }.assertBadRequest(TalerErrorCode.GENERIC_CURRENCY_MISMATCH)
        client.post("/accounts/customer/cashouts") {
            basicAuth("customer", "customer-password")
            jsonBody(req) {
                "amount_credit" to "EUR:1"
            } 
        }.assertBadRequest(TalerErrorCode.GENERIC_CURRENCY_MISMATCH)
    }

    // POST /accounts/{USERNAME}/cashouts
    @Test
    fun create_no_tan() = bankSetup("test_no_tan.conf") { _ ->
        val req = json {
            "request_uid" to randShortHashCode()
            "amount_debit" to "KUDOS:1"
            "amount_credit" to convert("KUDOS:1")
        }

        // Check unsupported TAN channel
        client.post("/accounts/customer/cashouts") {
            basicAuth("customer", "customer-password")
            jsonBody(req) 
        }.assertStatus(HttpStatusCode.NotImplemented, TalerErrorCode.BANK_TAN_CHANNEL_NOT_SUPPORTED)
    }

    // POST /accounts/{USERNAME}/cashouts/{CASHOUT_ID}/abort
    @Test
    fun abort() = bankSetup { _ ->
        // TODO auth routine
        client.patch("/accounts/customer") {
            basicAuth("customer", "customer-password")
            jsonBody(json {
                "cashout_payto_uri" to IbanPayTo(genIbanPaytoUri())
                "challenge_contact_data" to json {
                    "phone" to "+99"
                }
            })
        }.assertNoContent()
        
        val req = json {
            "request_uid" to randShortHashCode()
            "amount_debit" to "KUDOS:1"
            "amount_credit" to convert("KUDOS:1")
        }

        // Check abort created
        client.post("/accounts/customer/cashouts") {
            basicAuth("customer", "customer-password")
            jsonBody(req) 
        }.assertOk().run {
            val id = json<CashoutPending>().cashout_id

            // Check OK
            client.post("/accounts/customer/cashouts/$id/abort") {
                basicAuth("customer", "customer-password")
            }.assertNoContent()
            // Check idempotence
            client.post("/accounts/customer/cashouts/$id/abort") {
                basicAuth("customer", "customer-password")
            }.assertNoContent()
        }

        // Check abort confirmed
        client.post("/accounts/customer/cashouts") {
            basicAuth("customer", "customer-password")
            jsonBody(json(req) { "request_uid" to randShortHashCode() }) 
        }.assertOk().run {
            val id = json<CashoutPending>().cashout_id

            client.post("/accounts/customer/cashouts/$id/confirm") {
                basicAuth("customer", "customer-password")
                jsonBody { "tan" to smsCode("+99") } 
            }.assertNoContent()

            // Check error
            client.post("/accounts/customer/cashouts/$id/abort") {
                basicAuth("customer", "customer-password")
            }.assertConflict(TalerErrorCode.BANK_ABORT_CONFIRM_CONFLICT)
        }

        // Check bad id
        client.post("/accounts/customer/cashouts/chocolate/abort") {
            basicAuth("customer", "customer-password")
            jsonBody { "tan" to "code" } 
        }.assertBadRequest()

        // Check unknown
        client.post("/accounts/customer/cashouts/42/abort") {
            basicAuth("customer", "customer-password")
            jsonBody { "tan" to "code" } 
        }.assertNotFound(TalerErrorCode.BANK_TRANSACTION_NOT_FOUND)
    }

    // POST /accounts/{USERNAME}/cashouts/{CASHOUT_ID}/confirm
    @Test
    fun confirm() = bankSetup { db -> 
        // TODO auth routine
        client.patch("/accounts/customer") {
            basicAuth("customer", "customer-password")
            jsonBody(json {
                "challenge_contact_data" to json {
                    "phone" to "+99"
                }
            })
        }.assertNoContent()

        val req = json {
            "request_uid" to randShortHashCode()
            "amount_debit" to "KUDOS:1"
            "amount_credit" to convert("KUDOS:1")
        }

        // Check confirm
        client.post("/accounts/customer/cashouts") {
            basicAuth("customer", "customer-password")
            jsonBody(req) { "request_uid" to randShortHashCode() }
        }.assertOk().run {
            val id = json<CashoutPending>().cashout_id

            // Check missing cashout address
            client.post("/accounts/customer/cashouts/$id/confirm") {
                basicAuth("customer", "customer-password")
                jsonBody { "tan" to "code" }
            }.assertConflict(TalerErrorCode.BANK_CONFIRM_INCOMPLETE)
            client.patch("/accounts/customer") {
                basicAuth("customer", "customer-password")
                jsonBody(json {
                    "cashout_payto_uri" to IbanPayTo(genIbanPaytoUri())
                    "challenge_contact_data" to json {
                        "phone" to "+99"
                    }
                })
            }.assertNoContent()

            // Check bad TAN code
            client.post("/accounts/customer/cashouts/$id/confirm") {
                basicAuth("customer", "customer-password")
                jsonBody { "tan" to "nice-try" } 
            }.assertConflict(TalerErrorCode.BANK_TAN_CHALLENGE_FAILED)

            val code = smsCode("+99")
           
            // Check OK
            client.post("/accounts/customer/cashouts/$id/confirm") {
                basicAuth("customer", "customer-password")
                jsonBody { "tan" to code }
            }.assertNoContent()
            // Check idempotence
            client.post("/accounts/customer/cashouts/$id/confirm") {
                basicAuth("customer", "customer-password")
                jsonBody { "tan" to code }
            }.assertNoContent()
        }

        // Check bad conversion
        client.post("/accounts/customer/cashouts") {
            basicAuth("customer", "customer-password")
            jsonBody(json(req) { "request_uid" to randShortHashCode() }) 
        }.assertOk().run {
            val id = json<CashoutPending>().cashout_id

            db.conversion.updateConfig(ConversionInfo(
                buy_ratio = DecimalNumber("1"),
                buy_fee = DecimalNumber("1"),
                buy_tiny_amount = TalerAmount("KUDOS:0.0001"),
                buy_rounding_mode = RoundingMode.nearest,
                buy_min_amount = TalerAmount("FIAT:0.0001"),
                sell_ratio = DecimalNumber("1"),
                sell_fee = DecimalNumber("1"),
                sell_tiny_amount = TalerAmount("FIAT:0.0001"),
                sell_rounding_mode = RoundingMode.nearest,
                sell_min_amount = TalerAmount("KUDOS:0.0001"),
            ))

            client.post("/accounts/customer/cashouts/$id/confirm"){
                basicAuth("customer", "customer-password")
                jsonBody { "tan" to smsCode("+99") } 
            }.assertConflict(TalerErrorCode.BANK_BAD_CONVERSION)

            // Check can abort because not confirmed
            client.post("/accounts/customer/cashouts/$id/abort") {
                basicAuth("customer", "customer-password")
            }.assertNoContent()
        }

        // Check balance insufficient
        client.post("/accounts/customer/cashouts") {
            basicAuth("customer", "customer-password")
            jsonBody(json(req) { 
                "request_uid" to randShortHashCode()
                "amount_credit" to convert("KUDOS:1")
            }) 
        }.assertOk().run {
            val id = json<CashoutPending>().cashout_id
            // Send too much money
            tx("customer", "KUDOS:9", "merchant")
            client.post("/accounts/customer/cashouts/$id/confirm"){
                basicAuth("customer", "customer-password")
                jsonBody { "tan" to smsCode("+99") } 
            }.assertConflict(TalerErrorCode.BANK_UNALLOWED_DEBIT)

            // Check can abort because not confirmed
            client.post("/accounts/customer/cashouts/$id/abort") {
                basicAuth("customer", "customer-password")
            }.assertNoContent()
        }

        // Check bad UUID
        client.post("/accounts/customer/cashouts/chocolate/confirm") {
            basicAuth("customer", "customer-password")
            jsonBody { "tan" to "code" }
        }.assertBadRequest()

        // Check unknown
        client.post("/accounts/customer/cashouts/42/confirm") {
            basicAuth("customer", "customer-password")
            jsonBody { "tan" to "code" }
        }.assertNotFound(TalerErrorCode.BANK_TRANSACTION_NOT_FOUND)
    }

    // GET /accounts/{USERNAME}/cashouts/{CASHOUT_ID}
    @Test
    fun get() = bankSetup { _ ->
        // TODO auth routine

        client.patch("/accounts/customer") {
            basicAuth("customer", "customer-password")
            jsonBody(json {
                "cashout_payto_uri" to IbanPayTo(genIbanPaytoUri())
                "challenge_contact_data" to json {
                    "phone" to "+99"
                }
            })
        }.assertNoContent()

        val amountDebit = TalerAmount("KUDOS:1.5")
        val amountCredit = convert("KUDOS:1.5")
        val req = json {
            "amount_debit" to amountDebit
            "amount_credit" to amountCredit
        }

        // Check confirm
        client.post("/accounts/customer/cashouts") {
            basicAuth("customer", "customer-password")
            jsonBody(req) { "request_uid" to randShortHashCode() }
        }.assertOk().run {
            val id = json<CashoutPending>().cashout_id
            client.get("/accounts/customer/cashouts/$id") {
                basicAuth("customer", "customer-password")
            }.assertOk().run {
                val res = json<CashoutStatusResponse>()
                assertEquals(CashoutStatus.pending, res.status)
                assertEquals(amountDebit, res.amount_debit)
                assertEquals(amountCredit, res.amount_credit)
            }

            client.post("/accounts/customer/cashouts/$id/confirm") {
                basicAuth("customer", "customer-password")
                jsonBody { "tan" to smsCode("+99") }
            }.assertNoContent()
            client.get("/accounts/customer/cashouts/$id") {
                basicAuth("customer", "customer-password")
            }.assertOk().run {
                assertEquals(CashoutStatus.confirmed, json<CashoutStatusResponse>().status)
            }
        }

        // Check abort
        client.post("/accounts/customer/cashouts") {
            basicAuth("customer", "customer-password")
            jsonBody(req) { "request_uid" to randShortHashCode() }
        }.assertOk().run {
            val id = json<CashoutPending>().cashout_id
            client.get("/accounts/customer/cashouts/$id") {
                basicAuth("customer", "customer-password")
            }.assertOk().run {
                assertEquals(CashoutStatus.pending, json<CashoutStatusResponse>().status)
            }

            client.post("/accounts/customer/cashouts/$id/abort") {
                basicAuth("customer", "customer-password")
            }.assertNoContent()
            client.get("/accounts/customer/cashouts/$id") {
                basicAuth("customer", "customer-password")
            }.assertOk().run {
                assertEquals(CashoutStatus.aborted, json<CashoutStatusResponse>().status)
            }
        }

        // Check bad UUID
        client.get("/accounts/customer/cashouts/chocolate") {
            basicAuth("customer", "customer-password")
        }.assertBadRequest()

        // Check unknown
        client.get("/accounts/customer/cashouts/42") {
            basicAuth("customer", "customer-password")
        }.assertNotFound(TalerErrorCode.BANK_TRANSACTION_NOT_FOUND)
    }

    // GET /accounts/{USERNAME}/cashouts
    @Test
    fun history() = bankSetup { _ ->
        // TODO auth routine

        client.patch("/accounts/customer") {
            basicAuth("customer", "customer-password")
            jsonBody(json {
                "cashout_payto_uri" to IbanPayTo(genIbanPaytoUri())
                "challenge_contact_data" to json {
                    "phone" to "+99"
                }
            })
        }.assertNoContent()

        suspend fun HttpResponse.assertHistory(size: Int) {
            assertHistoryIds<Cashouts>(size) {
                it.cashouts.map { it.cashout_id }
            }
        }

        // Empty
        client.get("/accounts/customer/cashouts") {
            basicAuth("customer", "customer-password")
        }.assertNoContent()

        // Testing ranges. 
        repeat(30) {
            cashout("KUDOS:0.${it+1}")
        }

        // Default
        client.get("/accounts/customer/cashouts") {
            basicAuth("customer", "customer-password")
        }.assertHistory(20)

        // Forward range:
        client.get("/accounts/customer/cashouts?delta=10&start=20") {
            basicAuth("customer", "customer-password")
        }.assertHistory(10)

        // Fackward range:
        client.get("/accounts/customer/cashouts?delta=-10&start=25") {
            basicAuth("customer", "customer-password")
        }.assertHistory(10)
    }

    // GET /cashouts
    @Test
    fun globalHistory() = bankSetup { _ ->
        // TODO admin auth routine

        client.patch("/accounts/customer") {
            basicAuth("customer", "customer-password")
            jsonBody(json {
                "cashout_payto_uri" to IbanPayTo(genIbanPaytoUri())
                "challenge_contact_data" to json {
                    "phone" to "+99"
                }
            })
        }.assertNoContent()

        suspend fun HttpResponse.assertHistory(size: Int) {
            assertHistoryIds<GlobalCashouts>(size) {
                it.cashouts.map { it.cashout_id }
            }
        }

        // Empty
        client.get("/cashouts") {
            basicAuth("admin", "admin-password")
        }.assertNoContent()

        // Testing ranges. 
        repeat(30) {
            cashout("KUDOS:0.${it+1}")
        }

        // Default
        client.get("/cashouts") {
            basicAuth("admin", "admin-password")
        }.assertHistory(20)

        // Forward range:
        client.get("/cashouts?delta=10&start=20") {
            basicAuth("admin", "admin-password")
        }.assertHistory(10)

        // Fackward range:
        client.get("/cashouts?delta=-10&start=25") {
            basicAuth("admin", "admin-password")
        }.assertHistory(10)
    }

    // GET /cashout-rate
    @Test
    fun cashoutRate() = bankSetup { _ ->
        // Check conversion
        client.get("/cashout-rate?amount_debit=KUDOS:1").assertOk().run {
            val resp = json<ConversionResponse>()
            assertEquals(TalerAmount("FIAT:1.247"), resp.amount_credit)
        }
        // Not implemented (yet)
        client.get("/cashout-rate?amount_credit=FIAT:1")
            .assertNotImplemented()

        // Too small
        client.get("/cashout-rate?amount_debit=KUDOS:0.08")
            .assertConflict(TalerErrorCode.BANK_BAD_CONVERSION)
        // No amount
        client.get("/cashout-rate")
            .assertBadRequest(TalerErrorCode.GENERIC_PARAMETER_MISSING)
        // Both amount
        client.get("/cashout-rate?amount_debit=FIAT:1&amount_credit=KUDOS:1")
            .assertBadRequest(TalerErrorCode.GENERIC_PARAMETER_MALFORMED)
        // Wrong format
        client.get("/cashout-rate?amount_debit=1")
            .assertBadRequest(TalerErrorCode.GENERIC_PARAMETER_MALFORMED)
        client.get("/cashout-rate?amount_credit=1")
            .assertBadRequest(TalerErrorCode.GENERIC_PARAMETER_MALFORMED)
        // Wrong currency
        client.get("/cashout-rate?amount_debit=FIAT:1")
            .assertBadRequest(TalerErrorCode.GENERIC_CURRENCY_MISMATCH)
        client.get("/cashout-rate?amount_credit=KUDOS:1")
            .assertBadRequest(TalerErrorCode.GENERIC_CURRENCY_MISMATCH)
    }

    // GET /cashin-rate
    @Test
    fun cashinRate() = bankSetup { _ ->
        // Check conversion
        for ((amount, converted) in listOf(
            Pair(0.75, 0.58), Pair(0.33, 0.24), Pair(0.66, 0.51)
        )) {
            client.get("/cashin-rate?amount_debit=FIAT:$amount").assertOk().run {
                val resp = json<ConversionResponse>()
                assertEquals(TalerAmount("KUDOS:$converted"), resp.amount_credit)
            }
        }
        // Not implemented (yet)
        client.get("/cashin-rate?amount_credit=KUDOS:1")
            .assertNotImplemented()

        // No amount
        client.get("/cashin-rate")
            .assertBadRequest(TalerErrorCode.GENERIC_PARAMETER_MISSING)
        // Both amount
        client.get("/cashin-rate?amount_debit=KUDOS:1&amount_credit=FIAT:1")
            .assertBadRequest(TalerErrorCode.GENERIC_PARAMETER_MALFORMED)
        // Wrong format
        client.get("/cashin-rate?amount_debit=1")
            .assertBadRequest(TalerErrorCode.GENERIC_PARAMETER_MALFORMED)
        client.get("/cashin-rate?amount_credit=1")
            .assertBadRequest(TalerErrorCode.GENERIC_PARAMETER_MALFORMED)
        // Wrong currency
        client.get("/cashin-rate?amount_debit=KUDOS:1")
            .assertBadRequest(TalerErrorCode.GENERIC_CURRENCY_MISMATCH)
        client.get("/cashin-rate?amount_credit=FIAT:1")
            .assertBadRequest(TalerErrorCode.GENERIC_CURRENCY_MISMATCH)
    }

    @Test
    fun notImplemented() = bankSetup("test_restrict.conf") { _ ->
        client.get("/cashin-rate")
            .assertNotImplemented()
        client.get("/cashout-rate")
            .assertNotImplemented()
        client.get("/accounts/customer/cashouts")
            .assertNotImplemented()
    }
}