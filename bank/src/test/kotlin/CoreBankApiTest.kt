import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.HttpClient
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.engine.*
import io.ktor.server.testing.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
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
            jsonBody(json { "scope" to "readonly"})
        }.assertOk().run {
            // Checking that the token lifetime defaulted to 24 hours.
            val resp = Json.decodeFromString<TokenSuccessResponse>(bodyAsText())
            val token = db.bearerTokenGet(Base32Crockford.decode(resp.access_token))
            val lifeTime = Duration.between(token!!.creationTime, token.expirationTime)
            assertEquals(Duration.ofDays(1), lifeTime)
        }

        // Check default duration
        client.post("/accounts/merchant/token") {
            basicAuth("merchant", "merchant-password")
            jsonBody(json { "scope" to "readonly" })
        }.assertOk().run {
            // Checking that the token lifetime defaulted to 24 hours.
            val resp = Json.decodeFromString<TokenSuccessResponse>(bodyAsText())
            val token = db.bearerTokenGet(Base32Crockford.decode(resp.access_token))
            val lifeTime = Duration.between(token!!.creationTime, token.expirationTime)
            assertEquals(Duration.ofDays(1), lifeTime)
        }

        // Check refresh
        client.post("/accounts/merchant/token") {
            basicAuth("merchant", "merchant-password")
            jsonBody(json { 
                "scope" to "readonly"
                "refreshable" to true
            })
        }.assertOk().run {
            val token = Json.decodeFromString<TokenSuccessResponse>(bodyAsText()).access_token
            client.post("/accounts/merchant/token") {
                headers["Authorization"] = "Bearer secret-token:$token"
                jsonBody(json { "scope" to "readonly" })
            }.assertOk()
        }
        
        // Check'forever' case.
        client.post("/accounts/merchant/token") {
            basicAuth("merchant", "merchant-password")
            jsonBody(json { 
                "scope" to "readonly"
                "duration" to json {
                    "d_us" to "forever"
                }
            })
        }.run {
            val never: TokenSuccessResponse = Json.decodeFromString(bodyAsText())
            assertEquals(Instant.MAX, never.expiration.t_s)
        }

        // Check too big or invalid durations
        client.post("/accounts/merchant/token") {
            basicAuth("merchant", "merchant-password")
            jsonBody(json { 
                "scope" to "readonly"
                "duration" to json {
                    "d_us" to "invalid"
                }
            })
        }.assertBadRequest()
        client.post("/accounts/merchant/token") {
            basicAuth("merchant", "merchant-password")
            jsonBody(json { 
                "scope" to "readonly"
                "duration" to json {
                    "d_us" to Long.MAX_VALUE
                }
            })
        }.assertBadRequest()
        client.post("/accounts/merchant/token") {
            basicAuth("merchant", "merchant-password")
            jsonBody(json { 
                "scope" to "readonly"
                "duration" to json {
                    "d_us" to -1
                }
            })
        }.assertBadRequest()
    }

    // DELETE /accounts/USERNAME/token
    @Test
    fun delete() = bankSetup { _ -> 
        // TODO test restricted
        val token = client.post("/accounts/merchant/token") {
            basicAuth("merchant", "merchant-password")
            jsonBody(json { "scope" to "readonly" })
        }.assertOk().run {
            Json.decodeFromString<TokenSuccessResponse>(bodyAsText()).access_token
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

class CoreBankAccountsMgmtApiTest {
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
            jsonBody(json {
                "username" to "jor"
                "password" to "password"
                "name" to "Joe"
            })
        }.assertCreated()

        // Reserved account
        reservedAccounts.forEach {
            client.post("/accounts") {
                jsonBody(json {
                    "username" to it
                    "password" to "password"
                    "name" to "John Smith"
                })
            }.assertForbidden().assertErr(TalerErrorCode.BANK_RESERVED_USERNAME_CONFLICT)
        }

        // Testing login conflict
        client.post("/accounts") {
            jsonBody(json(req) {
                "name" to "Foo"
            })
        }.assertConflict().assertErr(TalerErrorCode.BANK_REGISTER_USERNAME_REUSE)
        // Testing payto conflict
        client.post("/accounts") {
            jsonBody(json(req) {
                "username" to "bar"
            })
        }.assertConflict().assertErr(TalerErrorCode.BANK_REGISTER_PAYTO_URI_REUSE)
        client.get("/accounts/bar") {
            basicAuth("admin", "admin-password")
        }.assertNotFound().assertErr(TalerErrorCode.BANK_UNKNOWN_ACCOUNT)
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
                jsonBody(json(req) {
                    "username" to "foo$it"
                })
            }.assertCreated()
            client.get("/accounts/foo$it") {
                basicAuth("admin", "admin-password")
            }.assertOk().run {
                val obj: AccountData = Json.decodeFromString(bodyAsText())
                assertEquals(TalerAmount("KUDOS:100"), obj.balance.amount)
                assertEquals(CorebankCreditDebitInfo.credit, obj.balance.credit_debit_indicator)
            }
        }
        client.get("/accounts/admin") {
            basicAuth("admin", "admin-password")
        }.assertOk().run {
            val obj: AccountData = Json.decodeFromString(bodyAsText())
            assertEquals(TalerAmount("KUDOS:10000"), obj.balance.amount)
            assertEquals(CorebankCreditDebitInfo.debit, obj.balance.credit_debit_indicator)
        }
        
        // Check unsufficient fund
        client.post("/accounts") {
            basicAuth("admin", "admin-password")
            jsonBody(json(req) {
                "username" to "bar"
            })
        }.assertConflict().assertErr(TalerErrorCode.BANK_UNALLOWED_DEBIT)
        client.get("/accounts/bar") {
            basicAuth("admin", "admin-password")
        }.assertNotFound().assertErr(TalerErrorCode.BANK_UNKNOWN_ACCOUNT)
    }

    // Test admin-only account creation
    @Test
    fun createAccountRestrictedTest() = bankSetup(conf = "test_restrict.conf") { _ -> 
        val req = json {
            "username" to "baz"
            "password" to "xyz"
            "name" to "Mallory"
        }

        // Ordinary user
        client.post("/accounts") {
            basicAuth("merchant", "merchant-password")
            jsonBody(req)
        }.assertUnauthorized()
        // Administrator
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
        }.assertNotFound().assertErr(TalerErrorCode.BANK_UNKNOWN_ACCOUNT)

        // Reserved account
        reservedAccounts.forEach {
            client.delete("/accounts/$it") {
                basicAuth("admin", "admin-password")
            }.assertForbidden().assertErr(TalerErrorCode.BANK_RESERVED_USERNAME_CONFLICT)
        }
       
        // successful deletion
        client.post("/accounts") {
            jsonBody(json {
                "username" to "john"
                "password" to "password"
                "name" to "John Smith"
            })
        }.assertCreated()
        client.delete("/accounts/john") {
            basicAuth("admin", "admin-password")
        }.assertNoContent()
        // Trying again must yield 404
        client.delete("/accounts/john") {
            basicAuth("admin", "admin-password")
        }.assertNotFound().assertErr(TalerErrorCode.BANK_UNKNOWN_ACCOUNT)

        
        // fail to delete, due to a non-zero balance.
        client.post("/accounts/exchange/transactions") {
            basicAuth("exchange", "exchange-password")
            jsonBody(json {
                "payto_uri" to "payto://iban/MERCHANT-IBAN-XYZ?message=payout&amount=KUDOS:1"
            })
        }.assertNoContent()
        client.delete("/accounts/merchant") {
            basicAuth("admin", "admin-password")
        }.assertConflict().assertErr(TalerErrorCode.BANK_ACCOUNT_BALANCE_NOT_ZERO)
        client.post("/accounts/merchant/transactions") {
            basicAuth("merchant", "merchant-password")
            jsonBody(json {
                "payto_uri" to "payto://iban/EXCHANGE-IBAN-XYZ?message=payout&amount=KUDOS:1"
            })
        }.assertNoContent()
        client.delete("/accounts/merchant") {
            basicAuth("admin", "admin-password")
        }.assertNoContent()
    }

    // PATCH /accounts/USERNAME
    @Test
    fun accountReconfig() = bankSetup { _ -> 
        // Successful attempt now.
        val req = json {
            "cashout_address" to IbanPayTo(genIbanPaytoUri()).canonical
            "challenge_contact_data" to json {
                "email" to "new@example.com"
                "phone" to "+987"
            }
            "is_exchange" to true
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

        val cashout = IbanPayTo(genIbanPaytoUri())
        val nameReq = json {
            "login" to "foo"
            "name" to "Another Foo"
            "cashout_address" to cashout.canonical
            "challenge_contact_data" to json {
                "phone" to "+99"
                "email" to "foo@example.com"
            }
        }
        // Checking ordinary user doesn't get to patch their name.
        client.patch("/accounts/merchant") {
            basicAuth("merchant", "merchant-password")
            jsonBody(nameReq)
        }.assertForbidden()
        // Finally checking that admin does get to patch foo's name.
        client.patch("/accounts/merchant") {
            basicAuth("admin", "admin-password")
            jsonBody(nameReq)
        }.assertNoContent()

        // Check patch
        client.get("/accounts/merchant") {
            basicAuth("admin", "admin-password")
        }.assertOk().run {
            val obj: AccountData = Json.decodeFromString(bodyAsText())
            assertEquals("Another Foo", obj.name)
            assertEquals(cashout.canonical, obj.cashout_payto_uri?.canonical)
            assertEquals("+99", obj.contact_data?.phone)
            assertEquals("foo@example.com", obj.contact_data?.email)
        }
    }

    // PATCH /accounts/USERNAME/auth
    @Test
    fun passwordChangeTest() = bankSetup { _ -> 
        // Changing the password.
        client.patch("/accounts/merchant/auth") {
            basicAuth("merchant", "merchant-password")
            jsonBody(json {
                "new_password" to "new-password"
            })
        }.assertNoContent()
        // Previous password should fail.
        client.patch("/accounts/merchant/auth") {
            basicAuth("merchant", "merchant-password")
        }.assertUnauthorized()
        // New password should succeed.
        client.patch("/accounts/merchant/auth") {
            basicAuth("merchant", "new-password")
            jsonBody(json {
                "new_password" to "merchant-password"
            })
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
                jsonBody(json {
                    "username" to "$it"
                    "password" to "password"
                    "name" to "Mr $it"
                    "is_public" to (it%2 == 0)
                })
            }.assertCreated()
        }
        // All public
        client.get("/public-accounts").run {
            assertOk()
            val obj = Json.decodeFromString<PublicAccountsResponse>(bodyAsText())
            assertEquals(3, obj.public_accounts.size)
            obj.public_accounts.forEach {
                assertEquals(0, it.account_name.toInt() % 2)
            }
        }
        // All accounts
        client.get("/accounts"){
            basicAuth("admin", "admin-password")
        }.run {
            assertOk()
            val obj = Json.decodeFromString<ListBankAccountsResponse>(bodyAsText())
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
            val obj = Json.decodeFromString<ListBankAccountsResponse>(bodyAsText())
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
            val obj: AccountData = Json.decodeFromString(bodyAsText())
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
            assertOk()
            val txt = bodyAsText()
            val history = Json.decodeFromString<BankAccountTransactionsResponse>(txt)
            val params = HistoryParams.extract(call.request.url.parameters)
       
            // testing the size is like expected.
            assert(history.transactions.size == size) {
                println("transactions has wrong size: ${history.transactions.size}")
                println("Response was: ${txt}")
            }
            if (size > 0) {
                if (params.delta < 0) {
                    // testing that the first row_id is at most the 'start' query param.
                    assert(history.transactions[0].row_id <= params.start)
                    // testing that the row_id decreases.
                    if (history.transactions.size > 1)
                        assert(history.transactions.windowed(2).all { (a, b) -> a.row_id > b.row_id })
                } else {
                    // testing that the first row_id is at least the 'start' query param.
                    assert(history.transactions[0].row_id >= params.start)
                    // testing that the row_id increases.
                    if (history.transactions.size > 1)
                        assert(history.transactions.windowed(2).all { (a, b) -> a.row_id < b.row_id })
                }
            }
        }

        authRoutine("/accounts/merchant/transactions?delta=7", method = HttpMethod.Get)

        // Check empty lisy when no transactions
        client.get("/accounts/merchant/transactions?delta=7") {
            basicAuth("merchant", "merchant-password")
        }.assertHistory(0)
        
        // Gen three transactions from merchant to exchange
        repeat(3) {
            client.post("/accounts/merchant/transactions") {
                basicAuth("merchant", "merchant-password")
                jsonBody(json {
                    "payto_uri" to "payto://iban/EXCHANGE-IBAN-XYZ?message=payout$it&amount=KUDOS:0.$it"
                })
            }.assertNoContent()
        }
        // Gen two transactions from exchange to merchant
        repeat(2) {
            client.post("/accounts/exchange/transactions") {
                basicAuth("exchange", "exchange-password")
                jsonBody(json {
                    "payto_uri" to "payto://iban/MERCHANT-IBAN-XYZ?message=payout$it&amount=KUDOS:0.$it"
                })
            }.assertNoContent()
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
                    }.assertHistory(0)
                }
            }
            delay(200)
            client.post("/accounts/merchant/transactions") {
                basicAuth("merchant", "merchant-password")
                jsonBody(json {
                    "payto_uri" to "payto://iban/EXCHANGE-IBAN-XYZ?message=payout_poll&amount=KUDOS:4.2"
                })
            }.assertNoContent()
        }

        // Testing ranges. 
        repeat(30) {
            client.post("/accounts/merchant/transactions") {
                basicAuth("merchant", "merchant-password")
                jsonBody(json {
                    "payto_uri" to "payto://iban/EXCHANGE-IBAN-XYZ?message=payout_range&amount=KUDOS:0.001"
                })
            }.assertNoContent()
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
        client.post("/accounts/merchant/transactions") {
            basicAuth("merchant", "merchant-password")
            jsonBody(json {
                "payto_uri" to "payto://iban/EXCHANGE-IBAN-XYZ?message=payout"
                "amount" to "KUDOS:0.3"
            })
        }.assertNoContent()
        // Check OK
        client.get("/accounts/merchant/transactions/1") {
            basicAuth("merchant", "merchant-password")
        }.assertOk().run {
            val tx: BankAccountTransactionInfo = Json.decodeFromString(bodyAsText())
            assertEquals("payout", tx.subject)
            assertEquals(TalerAmount("KUDOS:0.3"), tx.amount)
        }
        // Check unknown transaction
        client.get("/accounts/merchant/transactions/3") {
            basicAuth("merchant", "merchant-password")
        }.assertNotFound().assertErr(TalerErrorCode.BANK_TRANSACTION_NOT_FOUND)
        // Check wrong transaction
        client.get("/accounts/merchant/transactions/2") {
            basicAuth("merchant", "merchant-password")
        }.assertUnauthorized() // Should be NOT_FOUND ?
    }

    // POST /transactions
    @Test
    fun testCreate() = bankSetup { _ -> 
        val valid_req = json {
            "payto_uri" to "payto://iban/EXCHANGE-IBAN-XYZ?message=payout"
            "amount" to "KUDOS:0.3"
        }

        authRoutine("/accounts/merchant/transactions")

        // Check OK
        client.post("/accounts/merchant/transactions") {
            basicAuth("merchant", "merchant-password")
            jsonBody(valid_req)
        }.assertNoContent()
        client.get("/accounts/merchant/transactions/1") {
            basicAuth("merchant", "merchant-password")
        }.assertOk().run {
            val tx: BankAccountTransactionInfo = Json.decodeFromString(bodyAsText())
            assertEquals("payout", tx.subject)
            assertEquals(TalerAmount("KUDOS:0.3"), tx.amount)
        }
        // Check amount in payto_uri
        client.post("/accounts/merchant/transactions") {
            basicAuth("merchant", "merchant-password")
            jsonBody(json {
                "payto_uri" to "payto://iban/EXCHANGE-IBAN-XYZ?message=payout2&amount=KUDOS:1.05"
            })
        }.assertNoContent()
        client.get("/accounts/merchant/transactions/3") {
            basicAuth("merchant", "merchant-password")
        }.assertOk().run {
            val tx: BankAccountTransactionInfo = Json.decodeFromString(bodyAsText())
            assertEquals("payout2", tx.subject)
            assertEquals(TalerAmount("KUDOS:1.05"), tx.amount)
        }
        // Check amount in payto_uri precedence
        client.post("/accounts/merchant/transactions") {
            basicAuth("merchant", "merchant-password")
            jsonBody(json {
                "payto_uri" to "payto://iban/EXCHANGE-IBAN-XYZ?message=payout3&amount=KUDOS:1.05"
                "amount" to "KUDOS:10.003"
            })
        }.assertNoContent()
        client.get("/accounts/merchant/transactions/5") {
            basicAuth("merchant", "merchant-password")
        }.assertOk().run {
            val tx: BankAccountTransactionInfo = Json.decodeFromString(bodyAsText())
            assertEquals("payout3", tx.subject)
            assertEquals(TalerAmount("KUDOS:1.05"), tx.amount)
        }
        // Testing the wrong currency
        client.post("/accounts/merchant/transactions") {
            basicAuth("merchant", "merchant-password")
            jsonBody(json(valid_req) {
                "amount" to "EUR:3.3"
            })
        }.assertBadRequest().assertErr(TalerErrorCode.GENERIC_CURRENCY_MISMATCH)
        // Surpassing the debt limit
        client.post("/accounts/merchant/transactions") {
            basicAuth("merchant", "merchant-password")
            contentType(ContentType.Application.Json)
            jsonBody(json(valid_req) {
                "amount" to "KUDOS:555"
            })
        }.assertConflict().assertErr(TalerErrorCode.BANK_UNALLOWED_DEBIT)
        // Missing message
        client.post("/accounts/merchant/transactions") {
            basicAuth("merchant", "merchant-password")
            contentType(ContentType.Application.Json)
            jsonBody(json(valid_req) {
                "payto_uri" to "payto://iban/EXCHANGE-IBAN-XYZ"
            })
        }.assertBadRequest()
        // Unknown creditor
        client.post("/accounts/merchant/transactions") {
            basicAuth("merchant", "merchant-password")
            contentType(ContentType.Application.Json)
            jsonBody(json(valid_req) {
                "payto_uri" to "payto://iban/UNKNOWN-IBAN-XYZ?message=payout"
            })
        }.assertConflict().assertErr(TalerErrorCode.BANK_UNKNOWN_CREDITOR)
        // Transaction to self
        client.post("/accounts/merchant/transactions") {
            basicAuth("merchant", "merchant-password")
            contentType(ContentType.Application.Json)
            jsonBody(json(valid_req) {
                "payto_uri" to "payto://iban/MERCHANT-IBAN-XYZ?message=payout"
            })
        }.assertConflict().assertErr(TalerErrorCode.BANK_SAME_ACCOUNT)

        suspend fun checkBalance(
            merchantDebt: Boolean,
            merchantAmount: String,
            customerDebt: Boolean,
            customerAmount: String,
        ) {
            client.get("/accounts/merchant") {
                basicAuth("admin", "admin-password")
            }.assertOk().run {
                val obj: AccountData = Json.decodeFromString(bodyAsText())
                assertEquals(
                    if (merchantDebt) CorebankCreditDebitInfo.debit else CorebankCreditDebitInfo.credit, 
                    obj.balance.credit_debit_indicator)
                assertEquals(TalerAmount(merchantAmount), obj.balance.amount)
            }
            client.get("/accounts/customer") {
                basicAuth("admin", "admin-password")
            }.assertOk().run {
                val obj: AccountData = Json.decodeFromString(bodyAsText())
                assertEquals(
                    if (customerDebt) CorebankCreditDebitInfo.debit else CorebankCreditDebitInfo.credit, 
                    obj.balance.credit_debit_indicator)
                assertEquals(TalerAmount(customerAmount), obj.balance.amount)
            }
        }

        // Init state
        checkBalance(true, "KUDOS:2.4", false, "KUDOS:0")
        // Send 2 times 3
        repeat(2) {
            client.post("/accounts/merchant/transactions") {
                basicAuth("merchant", "merchant-password")
                jsonBody(json {
                    "payto_uri" to "payto://iban/CUSTOMER-IBAN-XYZ?message=payout2&amount=KUDOS:3"
                })
            }.assertNoContent()
        }
        client.post("/accounts/merchant/transactions") {
            basicAuth("merchant", "merchant-password")
            jsonBody(json {
                "payto_uri" to "payto://iban/CUSTOMER-IBAN-XYZ?message=payout2&amount=KUDOS:3"
            })
        }.assertConflict().assertErr(TalerErrorCode.BANK_UNALLOWED_DEBIT)
        checkBalance(true, "KUDOS:8.4", false, "KUDOS:6")
        // Send throught debt
        client.post("/accounts/customer/transactions") {
            basicAuth("customer", "customer-password")
            jsonBody(json {
                "payto_uri" to "payto://iban/MERCHANT-IBAN-XYZ?message=payout2&amount=KUDOS:10"
            })
        }.assertNoContent()
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
            jsonBody(json { "amount" to "KUDOS:9.0" }) 
        }.assertOk()

        // Check exchange account
        client.post("/accounts/exchange/withdrawals") {
            basicAuth("exchange", "exchange-password")
            jsonBody(json { "amount" to "KUDOS:9.0" }) 
        }.assertConflict().assertErr(TalerErrorCode.BANK_ACCOUNT_IS_EXCHANGE)

        // Check insufficient fund
        client.post("/accounts/merchant/withdrawals") {
            basicAuth("merchant", "merchant-password")
            jsonBody(json { "amount" to "KUDOS:90" }) 
        }.assertConflict().assertErr(TalerErrorCode.BANK_UNALLOWED_DEBIT)
    }

    // GET /withdrawals/withdrawal_id
    @Test
    fun get() = bankSetup { _ ->
        // Check OK
        client.post("/accounts/merchant/withdrawals") {
            basicAuth("merchant", "merchant-password")
            jsonBody(json { "amount" to "KUDOS:9.0" } ) 
        }.assertOk().run {
            val opId = Json.decodeFromString<BankAccountCreateWithdrawalResponse>(bodyAsText())
            client.get("/withdrawals/${opId.withdrawal_id}") {
                basicAuth("merchant", "merchant-password")
            }.assertOk()
        }

        // Check bad UUID
        client.get("/withdrawals/chocolate").assertBadRequest()

        // Check unknown
        client.get("/withdrawals/${UUID.randomUUID()}").assertNotFound()
    }

    // POST /withdrawals/withdrawal_id/abort
    @Test
    fun abort() = bankSetup { _ ->
        // Check abort created
        client.post("/accounts/merchant/withdrawals") {
            basicAuth("merchant", "merchant-password")
            jsonBody(json { "amount" to "KUDOS:1" }) 
        }.assertOk().run {
            val resp = Json.decodeFromString<BankAccountCreateWithdrawalResponse>(bodyAsText())
            val uuid = resp.taler_withdraw_uri.split("/").last()

            // Check OK
            client.post("/withdrawals/$uuid/abort").assertNoContent()
            // Check idempotence
            client.post("/withdrawals/$uuid/abort").assertNoContent()
        }

        // Check abort selected
        client.post("/accounts/merchant/withdrawals") {
            basicAuth("merchant", "merchant-password")
            jsonBody(json { "amount" to "KUDOS:1" }) 
        }.assertOk().run {
            val resp = Json.decodeFromString<BankAccountCreateWithdrawalResponse>(bodyAsText())
            val uuid = resp.taler_withdraw_uri.split("/").last()
            client.post("/taler-integration/withdrawal-operation/$uuid") {
                jsonBody(json {
                    "reserve_pub" to randEddsaPublicKey()
                    "selected_exchange" to IbanPayTo("payto://iban/EXCHANGE-IBAN-XYZ")
                })
            }.assertOk()

            // Check OK
            client.post("/withdrawals/$uuid/abort").assertNoContent()
            // Check idempotence
            client.post("/withdrawals/$uuid/abort").assertNoContent()
        }

        // Check abort confirmed
        client.post("/accounts/merchant/withdrawals") {
            basicAuth("merchant", "merchant-password")
            jsonBody(json { "amount" to "KUDOS:1" }) 
        }.assertOk().run {
            val resp = Json.decodeFromString<BankAccountCreateWithdrawalResponse>(bodyAsText())
            val uuid = resp.taler_withdraw_uri.split("/").last()
            client.post("/taler-integration/withdrawal-operation/$uuid") {
                jsonBody(json {
                    "reserve_pub" to randEddsaPublicKey()
                    "selected_exchange" to IbanPayTo("payto://iban/EXCHANGE-IBAN-XYZ")
                })
            }.assertOk()
            client.post("/withdrawals/$uuid/confirm").assertNoContent()

            // Check error
            client.post("/withdrawals/$uuid/abort").assertConflict()
                .assertErr(TalerErrorCode.BANK_ABORT_CONFIRM_CONFLICT)
        }

        // Check bad UUID
        client.post("/withdrawals/chocolate/abort").assertBadRequest()

        // Check unknown
        client.post("/withdrawals/${UUID.randomUUID()}/abort").assertNotFound()
            .assertErr(TalerErrorCode.BANK_TRANSACTION_NOT_FOUND)
    }

    // POST /withdrawals/withdrawal_id/confirm
    @Test
    fun confirm() = bankSetup { _ -> 
        // Check confirm created
        client.post("/accounts/merchant/withdrawals") {
            basicAuth("merchant", "merchant-password")
            jsonBody(json { "amount" to "KUDOS:1" }) 
        }.assertOk().run {
            val resp = Json.decodeFromString<BankAccountCreateWithdrawalResponse>(bodyAsText())
            val uuid = resp.taler_withdraw_uri.split("/").last()

            // Check err
            client.post("/withdrawals/$uuid/confirm").assertConflict()
            .assertErr(TalerErrorCode.BANK_CONFIRM_INCOMPLETE)
        }

        // Check confirm selected
        client.post("/accounts/merchant/withdrawals") {
            basicAuth("merchant", "merchant-password")
            jsonBody(json { "amount" to "KUDOS:1" }) 
        }.assertOk().run {
            val resp = Json.decodeFromString<BankAccountCreateWithdrawalResponse>(bodyAsText())
            val uuid = resp.taler_withdraw_uri.split("/").last()
            client.post("/taler-integration/withdrawal-operation/$uuid") {
                jsonBody(json {
                    "reserve_pub" to randEddsaPublicKey()
                    "selected_exchange" to IbanPayTo("payto://iban/EXCHANGE-IBAN-XYZ")
                })
            }.assertOk()

            // Check OK
            client.post("/withdrawals/$uuid/confirm").assertNoContent()
            // Check idempotence
            client.post("/withdrawals/$uuid/confirm").assertNoContent()
        }

        // Check confirm aborted
        client.post("/accounts/merchant/withdrawals") {
            basicAuth("merchant", "merchant-password")
            jsonBody(json { "amount" to "KUDOS:1" }) 
        }.assertOk().run {
            val resp = Json.decodeFromString<BankAccountCreateWithdrawalResponse>(bodyAsText())
            val uuid = resp.taler_withdraw_uri.split("/").last()
            client.post("/taler-integration/withdrawal-operation/$uuid") {
                jsonBody(json {
                    "reserve_pub" to randEddsaPublicKey()
                    "selected_exchange" to IbanPayTo("payto://iban/EXCHANGE-IBAN-XYZ")
                })
            }.assertOk()
            client.post("/withdrawals/$uuid/abort").assertNoContent()

            // Check error
            client.post("/withdrawals/$uuid/confirm").assertConflict()
            .assertErr(TalerErrorCode.BANK_CONFIRM_ABORT_CONFLICT)
        }

        // Check balance insufficient
        client.post("/accounts/merchant/withdrawals") {
            basicAuth("merchant", "merchant-password")
            jsonBody(json { "amount" to "KUDOS:5" }) 
        }.assertOk().run {
            val resp = Json.decodeFromString<BankAccountCreateWithdrawalResponse>(bodyAsText())
            val uuid = resp.taler_withdraw_uri.split("/").last()
            client.post("/taler-integration/withdrawal-operation/$uuid") {
                jsonBody(json {
                    "reserve_pub" to randEddsaPublicKey()
                    "selected_exchange" to IbanPayTo("payto://iban/EXCHANGE-IBAN-XYZ")
                })
            }.assertOk()

            // Send too much money
            client.post("/accounts/merchant/transactions") {
                basicAuth("merchant", "merchant-password")
                jsonBody(json {
                    "payto_uri" to "payto://iban/EXCHANGE-IBAN-XYZ?message=payout&amount=KUDOS:5"
                })
            }.assertNoContent()

            client.post("/withdrawals/$uuid/confirm").assertConflict()
            .assertErr(TalerErrorCode.BANK_UNALLOWED_DEBIT)

            // Check can abort because not confirmed
            client.post("/withdrawals/$uuid/abort").assertNoContent()
        }

        // Check bad UUID
        client.post("/withdrawals/chocolate/confirm").assertBadRequest()

        // Check unknown
        client.post("/withdrawals/${UUID.randomUUID()}/confirm").assertNotFound()
            .assertErr(TalerErrorCode.BANK_TRANSACTION_NOT_FOUND)
    }
}


class CoreBankCashoutApiTest {
    fun tanCode(): String = File("/tmp/cashout-tan.txt").readText()

    private suspend fun ApplicationTestBuilder.convert(amount: String): TalerAmount {
        // Check conversion
        client.get("/cashout-rate?amount_debit=$amount").assertOk().run {
            val resp = Json.decodeFromString<ConversionResponse>(bodyAsText())
            return resp.amount_credit
        }
    }

    // POST /accounts/{USERNAME}/cashouts
    @Test
    fun create() = bankSetup { _ ->
        // TODO auth routine
        val req = json {
            "amount_debit" to "KUDOS:1"
            "amount_credit" to convert("KUDOS:1")
            "tan_channel" to "file"
        }

        // Check OK
        client.post("/accounts/customer/cashouts") {
            basicAuth("customer", "customer-password")
            jsonBody(req) 
        }.assertOk()

        // Check exchange account
        client.post("/accounts/exchange/cashouts") {
            basicAuth("exchange", "exchange-password")
            jsonBody(req) 
        }.assertConflict().assertErr(TalerErrorCode.BANK_ACCOUNT_IS_EXCHANGE)

        // Check insufficient fund
        client.post("/accounts/customer/cashouts") {
            basicAuth("customer", "customer-password")
            jsonBody(json(req) {
                "amount_debit" to "KUDOS:75"
                "amount_credit" to convert("KUDOS:75")
            })
        }.assertConflict().assertErr(TalerErrorCode.BANK_UNALLOWED_DEBIT)

        // Check wrong conversion
        client.post("/accounts/customer/cashouts") {
            basicAuth("customer", "customer-password")
            jsonBody(json(req) {
                "amount_credit" to convert("KUDOS:2")
            }) 
        }.assertConflict().assertErr(TalerErrorCode.BANK_BAD_CONVERSION)

        // Check wrong currency
        client.post("/accounts/customer/cashouts") {
            basicAuth("customer", "customer-password")
            jsonBody(json(req) {
                "amount_debit" to "EUR:1"
            }) 
        }.assertBadRequest().assertErr(TalerErrorCode.GENERIC_CURRENCY_MISMATCH)
        client.post("/accounts/customer/cashouts") {
            basicAuth("customer", "customer-password")
            jsonBody(json(req) {
                "amount_credit" to "EUR:1"
            }) 
        }.assertBadRequest().assertErr(TalerErrorCode.GENERIC_CURRENCY_MISMATCH)

        // Check missing TAN info
        client.post("/accounts/customer/cashouts") {
            basicAuth("customer", "customer-password")
            jsonBody(json(req) {
                "tan_channel" to "sms"
            }) 
        }.assertConflict().assertErr(TalerErrorCode.BANK_MISSING_TAN_INFO)
    }

    // POST /accounts/{USERNAME}/cashouts/{CASHOUT_ID}/abort
    @Test
    fun abort() = bankSetup { _ ->
        // TODO auth routine
        val req = json {
            "amount_debit" to "KUDOS:1"
            "amount_credit" to convert("KUDOS:1")
            "tan_channel" to "file"
        }

        // Check abort created
        client.post("/accounts/customer/cashouts") {
            basicAuth("customer", "customer-password")
            jsonBody(req) 
        }.assertOk().run {
            val uuid = Json.decodeFromString<CashoutPending>(bodyAsText()).cashout_id

            // Check OK
            client.post("/accounts/customer/cashouts/$uuid/abort") {
                basicAuth("customer", "customer-password")
            }.assertNoContent()
            // Check idempotence
            client.post("/accounts/customer/cashouts/$uuid/abort") {
                basicAuth("customer", "customer-password")
            }.assertNoContent()
        }

        // Check abort confirmed
        client.post("/accounts/customer/cashouts") {
            basicAuth("customer", "customer-password")
            jsonBody(req) 
        }.assertOk().run {
            val uuid = Json.decodeFromString<CashoutPending>(bodyAsText()).cashout_id

            client.post("/accounts/customer/cashouts/$uuid/confirm") {
                basicAuth("customer", "customer-password")
                jsonBody(json { "tan" to tanCode() }) 
            }.assertNoContent()

            // Check error
            client.post("/accounts/customer/cashouts/$uuid/abort") {
                basicAuth("customer", "customer-password")
            }.assertConflict().assertErr(TalerErrorCode.BANK_ABORT_CONFIRM_CONFLICT)
        }

        // Check bad UUID
        client.post("/accounts/customer/cashouts/chocolate/abort") {
            basicAuth("customer", "customer-password")
            jsonBody(json { "tan" to tanCode() }) 
        }.assertBadRequest()

        // Check unknown
        client.post("/accounts/customer/cashouts/${UUID.randomUUID()}/abort") {
            basicAuth("customer", "customer-password")
            jsonBody(json { "tan" to tanCode() }) 
        }.assertNotFound().assertErr(TalerErrorCode.BANK_TRANSACTION_NOT_FOUND)
    }

    // POST /accounts/{USERNAME}/cashouts/{CASHOUT_ID}/confirm
    @Test
    fun confirm() = bankSetup { _ -> 
        // TODO auth routine
        val req = json {
            "amount_debit" to "KUDOS:1"
            "amount_credit" to convert("KUDOS:1")
            "tan_channel" to "file"
        }

        // TODO check sms and mail TAN channel
        // Check confirm
        client.post("/accounts/customer/cashouts") {
            basicAuth("customer", "customer-password")
            jsonBody(req) 
        }.assertOk().run {
            val uuid = Json.decodeFromString<CashoutPending>(bodyAsText()).cashout_id

            // Check bad TAN code
            client.post("/accounts/customer/cashouts/$uuid/confirm") {
                basicAuth("customer", "customer-password")
                jsonBody(json { "tan" to "nice-try" }) 
            }.assertForbidden()

            // Check OK
            client.post("/accounts/customer/cashouts/$uuid/confirm") {
                basicAuth("customer", "customer-password")
                jsonBody(json { "tan" to tanCode() }) 
            }.assertNoContent()
            // Check idempotence
            client.post("/accounts/customer/cashouts/$uuid/confirm") {
                basicAuth("customer", "customer-password")
                jsonBody(json { "tan" to tanCode() }) 
            }.assertNoContent()
        }

        // Check confirm aborted TODO
        client.post("/accounts/customer/cashouts") {
            basicAuth("customer", "customer-password")
            jsonBody(req) 
        }.assertOk().run {
            val uuid = Json.decodeFromString<CashoutPending>(bodyAsText()).cashout_id
            client.post("/accounts/customer/cashouts/$uuid/abort") {
                basicAuth("customer", "customer-password")
            }.assertNoContent()

            // Check error
            client.post("/accounts/customer/cashouts/$uuid/confirm") {
                basicAuth("customer", "customer-password")
                jsonBody(json { "tan" to tanCode() }) 
            }.assertConflict().assertErr(TalerErrorCode.BANK_CONFIRM_ABORT_CONFLICT)
        }

        // Check balance insufficient
        client.post("/accounts/customer/cashouts") {
            basicAuth("customer", "customer-password")
            jsonBody(req) 
        }.assertOk().run {
            val uuid = Json.decodeFromString<CashoutPending>(bodyAsText()).cashout_id
            // Send too much money
            client.post("/accounts/customer/transactions") {
                basicAuth("customer", "customer-password")
                jsonBody(json {
                    "payto_uri" to "payto://iban/merchant-IBAN-XYZ?message=payout&amount=KUDOS:8"
                })
            }.assertNoContent()

            client.post("/accounts/customer/cashouts/$uuid/confirm"){
                basicAuth("customer", "customer-password")
                jsonBody(json { "tan" to tanCode() }) 
            }.assertConflict().assertErr(TalerErrorCode.BANK_UNALLOWED_DEBIT)

            // Check can abort because not confirmed
            client.post("/accounts/customer/cashouts/$uuid/abort") {
                basicAuth("customer", "customer-password")
            }.assertNoContent()
        }

        // Check bad UUID
        client.post("/accounts/customer/cashouts/chocolate/confirm") {
            basicAuth("customer", "customer-password")
            jsonBody(json { "tan" to tanCode() }) 
        }.assertBadRequest()

        // Check unknown
        client.post("/accounts/customer/cashouts/${UUID.randomUUID()}/confirm") {
            basicAuth("customer", "customer-password")
            jsonBody(json { "tan" to tanCode() }) 
        }.assertNotFound().assertErr(TalerErrorCode.BANK_TRANSACTION_NOT_FOUND)
    }

    // GET /cashout-rate
    @Test
    fun cashoutRate() = bankSetup { _ ->
        // Check conversion
        client.get("/cashout-rate?amount_debit=KUDOS:1").assertOk().run {
            val resp = Json.decodeFromString<ConversionResponse>(bodyAsText())
            assertEquals(TalerAmount("FIAT:1.247"), resp.amount_credit)
        }

        // No amount
        client.get("/cashout-rate").assertBadRequest()
        // Wrong currency
        client.get("/cashout-rate?amount_debit=FIAT:1").assertBadRequest()
            .assertBadRequest().assertErr(TalerErrorCode.GENERIC_CURRENCY_MISMATCH)
        client.get("/cashout-rate?amount_credit=KUDOS:1").assertBadRequest()
            .assertBadRequest().assertErr(TalerErrorCode.GENERIC_CURRENCY_MISMATCH)
    }

    // GET /cashin-rate
    @Test
    fun cashinRate() = bankSetup { _ ->
        // Check conversion
        for ((amount, converted) in listOf(
            Pair(0.75, 0.58), Pair(0.33, 0.24), Pair(0.66, 0.51)
        )) {
            client.get("/cashin-rate?amount_debit=FIAT:$amount").assertOk().run {
                val resp = Json.decodeFromString<ConversionResponse>(bodyAsText())
                assertEquals(TalerAmount("KUDOS:$converted"), resp.amount_credit)
            }
        }

        // No amount
        client.get("/cashin-rate").assertBadRequest()
        // Wrong currency
        client.get("/cashin-rate?amount_debit=KUDOS:1").assertBadRequest()
            .assertBadRequest().assertErr(TalerErrorCode.GENERIC_CURRENCY_MISMATCH)
        client.get("/cashin-rate?amount_credit=FIAT:1").assertBadRequest()
            .assertBadRequest().assertErr(TalerErrorCode.GENERIC_CURRENCY_MISMATCH)
    }
}