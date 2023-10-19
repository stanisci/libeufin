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
import kotlin.random.Random
import kotlin.test.*
import kotlinx.coroutines.*

class CoreBankConfigTest {
    @Test
    fun getConfig() = bankSetup { _ -> 
        client.get("/config").assertOk()
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
        // Testing idempotency.
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
            }.assertForbidden().assertErr(TalerErrorCode.TALER_EC_BANK_RESERVED_USERNAME_CONFLICT)
        }
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
        }.assertNotFound().assertErr(TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT)

        // Reserved account
        reservedAccounts.forEach {
            client.delete("/accounts/$it") {
                basicAuth("admin", "admin-password")
            }.assertForbidden().assertErr(TalerErrorCode.TALER_EC_BANK_RESERVED_USERNAME_CONFLICT)
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
        }.assertNotFound().assertErr(TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT)

        
        // fail to delete, due to a non-zero balance.
        client.post("/accounts/exchange/transactions") {
            basicAuth("exchange", "exchange-password")
            jsonBody(json {
                "payto_uri" to "payto://iban/MERCHANT-IBAN-XYZ?message=payout&amount=KUDOS:1"
            })
        }.assertOk()
        client.delete("/accounts/merchant") {
            basicAuth("admin", "admin-password")
        }.assertConflict()
        client.post("/accounts/merchant/transactions") {
            basicAuth("merchant", "merchant-password")
            jsonBody(json {
                "payto_uri" to "payto://iban/EXCHANGE-IBAN-XYZ?message=payout&amount=KUDOS:1"
            })
        }.assertOk()
        client.delete("/accounts/merchant") {
            basicAuth("admin", "admin-password")
        }.assertNoContent()
    }

    // PATCH /accounts/USERNAME
    @Test
    fun accountReconfig() = bankSetup { db -> 
        // Successful attempt now.
        val req = json {
            "cashout_address" to "payto://new-cashout-address"
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

        val nameReq = json {
            "name" to "Another Foo"
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

        val fooFromDb = db.customerGetFromLogin("merchant")
        assertEquals("Another Foo", fooFromDb?.name)
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
        listOf("merchant", "exchange").forEach {
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
            val txt = this.bodyAsText()
            val history = Json.decodeFromString<BankAccountTransactionsResponse>(txt)
            val params = getHistoryParams(this.call.request.url.parameters)
       
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
            }.assertOk()
        }
        // Gen two transactions from exchange to merchant
        repeat(2) {
            client.post("/accounts/exchange/transactions") {
                basicAuth("exchange", "exchange-password")
                jsonBody(json {
                    "payto_uri" to "payto://iban/MERCHANT-IBAN-XYZ?message=payout$it&amount=KUDOS:0.$it"
                })
            }.assertOk()
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
            }.assertOk()
        }

        // Testing ranges. 
        repeat(30) {
            client.post("/accounts/merchant/transactions") {
                basicAuth("merchant", "merchant-password")
                jsonBody(json {
                    "payto_uri" to "payto://iban/EXCHANGE-IBAN-XYZ?message=payout_range&amount=KUDOS:0.001"
                })
            }.assertOk()
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
        }.assertOk()
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
        }.assertNotFound().assertErr(TalerErrorCode.TALER_EC_BANK_TRANSACTION_NOT_FOUND)
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
        }.assertOk()
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
        }.assertOk()
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
        }.assertOk()
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
        }.assertBadRequest().assertErr(TalerErrorCode.TALER_EC_GENERIC_CURRENCY_MISMATCH)
        // Surpassing the debt limit
        client.post("/accounts/merchant/transactions") {
            basicAuth("merchant", "merchant-password")
            contentType(ContentType.Application.Json)
            jsonBody(json(valid_req) {
                "amount" to "KUDOS:555"
            })
        }.assertConflict().assertErr(TalerErrorCode.TALER_EC_BANK_UNALLOWED_DEBIT)
        // Missing message
        client.post("/accounts/merchant/transactions") {
            basicAuth("merchant", "merchant-password")
            contentType(ContentType.Application.Json)
            jsonBody(json(valid_req) {
                "payto_uri" to "payto://iban/EXCHANGE-IBAN-XYZ"
            })
        }.assertBadRequest()
        // Unknown account
        client.post("/accounts/merchant/transactions") {
            basicAuth("merchant", "merchant-password")
            contentType(ContentType.Application.Json)
            jsonBody(json(valid_req) {
                "payto_uri" to "payto://iban/UNKNOWN-IBAN-XYZ?message=payout"
            })
        }.assertNotFound().assertErr(TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT)
        // Transaction to self
        client.post("/accounts/merchant/transactions") {
            basicAuth("merchant", "merchant-password")
            contentType(ContentType.Application.Json)
            jsonBody(json(valid_req) {
                "payto_uri" to "payto://iban/MERCHANT-IBAN-XYZ?message=payout"
            })
        }.assertConflict().assertErr(TalerErrorCode.TALER_EC_BANK_SAME_ACCOUNT)
    }
}

class CoreBankWithdrawalApiTest {
    // POST /accounts/USERNAME/withdrawals
    @Test
    fun create() = bankSetup { _ ->
        client.post("/accounts/merchant/withdrawals") {
            basicAuth("merchant", "merchant-password")
            jsonBody(json { "amount" to "KUDOS:9.0" }) 
        }.assertOk()
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
            client.post("/withdrawals/$uuid/abort").assertOk()
            // Check idempotence
            client.post("/withdrawals/$uuid/abort").assertOk()
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
            client.post("/withdrawals/$uuid/abort").assertOk()
            // Check idempotence
            client.post("/withdrawals/$uuid/abort").assertOk()
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
            client.post("/withdrawals/$uuid/confirm").assertOk()

            // Check error
            client.post("/withdrawals/$uuid/abort").assertConflict()
        }

        // Check bad UUID
        client.post("/withdrawals/chocolate/abort").assertBadRequest()

        // Check unknown
        client.post("/withdrawals/${UUID.randomUUID()}/abort").assertNotFound()
    }

    // POST /withdrawals/withdrawal_id/confirm
    @Test
    fun confirm() = bankSetup { db -> 
        // Check confirm created
        client.post("/accounts/merchant/withdrawals") {
            basicAuth("merchant", "merchant-password")
            jsonBody(json { "amount" to "KUDOS:1" }) 
        }.assertOk().run {
            val resp = Json.decodeFromString<BankAccountCreateWithdrawalResponse>(bodyAsText())
            val uuid = resp.taler_withdraw_uri.split("/").last()

            // Check err
            client.post("/withdrawals/$uuid/confirm").assertStatus(HttpStatusCode.UnprocessableEntity)
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
            client.post("/withdrawals/$uuid/confirm").assertOk()
            // Check idempotence
            client.post("/withdrawals/$uuid/confirm").assertOk()
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
            client.post("/withdrawals/$uuid/abort").assertOk()

            // Check error
            client.post("/withdrawals/$uuid/confirm").assertConflict()
                .assertErr(TalerErrorCode.TALER_EC_BANK_CONFIRM_ABORT_CONFLICT)
        }

        // Check bad UUID
        client.post("/withdrawals/chocolate/confirm").assertBadRequest()

        // Check unknown
        client.post("/withdrawals/${UUID.randomUUID()}/confirm").assertNotFound()
    }
}