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
import org.junit.Test
import org.postgresql.jdbc.PgConnection
import tech.libeufin.bank.*
import tech.libeufin.util.CryptoUtil
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.random.Random
import kotlin.test.*
import kotlinx.coroutines.*

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
            }.assertStatus(HttpStatusCode.Conflict)
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
        }.assertStatus(HttpStatusCode.NotFound)

        // Reserved account
        reservedAccounts.forEach {
            client.delete("/accounts/$it") {
                basicAuth("admin", "admin-password")
                expectSuccess = false
            }.assertStatus(HttpStatusCode.Conflict)
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
        }.assertStatus(HttpStatusCode.NotFound)

        
        // fail to delete, due to a non-zero balance.
        client.post("/accounts/exchange/transactions") {
            basicAuth("exchange", "exchange-password")
            jsonBody(json {
                "payto_uri" to "payto://iban/MERCHANT-IBAN-XYZ?message=payout&amount=KUDOS:1"
            })
        }.assertOk()
        client.delete("/accounts/merchant") {
            basicAuth("admin", "admin-password")
        }.assertStatus(HttpStatusCode.PreconditionFailed)
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
        }.assertStatus(HttpStatusCode.Forbidden)
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
            basicAuth("merchanr", "merchanr-password")
        }.assertStatus(HttpStatusCode.Unauthorized)
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
        }.assertStatus(HttpStatusCode.Unauthorized)

        // Wrong password
        client.request(path) {
            this.method = method
            basicAuth("merchant", "wrong-password")
        }.assertStatus(HttpStatusCode.Unauthorized)

        // Wrong account
        client.request(path) {
            this.method = method
            basicAuth("exchange", "merchant-password")
        }.assertStatus(HttpStatusCode.Unauthorized)

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
                    assert(history.transactions.windowed(2).all { (a, b) -> a.row_id > b.row_id })
                } else {
                    // testing that the first row_id is at least the 'start' query param.
                    assert(history.transactions[0].row_id >= params.start)
                    // testing that the row_id increases.
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
        assertTime(0, 300) {
            client.get("/accounts/merchant/transactions?delta=-6&start=11&long_poll_ms=1000") {
                basicAuth("merchant", "merchant-password")
            }.assertHistory(5)
        }

        // Check polling end
        client.get("/accounts/merchant/transactions?delta=6&long_poll_ms=60") {
            basicAuth("merchant", "merchant-password")
        }.assertHistory(5)

        runBlocking {
            joinAll(
                launch {  // Check polling succeed forward
                    assertTime(200, 1000) {
                        client.get("/accounts/merchant/transactions?delta=6&long_poll_ms=1000") {
                            basicAuth("merchant", "merchant-password")
                        }.assertHistory(6)
                    }
                },
                launch {  // Check polling succeed backward
                    assertTime(200, 1000) {
                        client.get("/accounts/merchant/transactions?delta=-6&long_poll_ms=1000") {
                            basicAuth("merchant", "merchant-password")
                        }.assertHistory(6)
                    }
                },
                launch {  // Check polling timeout forward
                    assertTime(200, 400) {
                        client.get("/accounts/merchant/transactions?delta=8&long_poll_ms=300") {
                            basicAuth("merchant", "merchant-password")
                        }.assertHistory(6)
                    }
                },
                launch {  // Check polling timeout backward
                    assertTime(200, 400) {
                        client.get("/accounts/merchant/transactions?delta=-8&long_poll_ms=300") {
                            basicAuth("merchant", "merchant-password")
                        }.assertHistory(6)
                    }
                },
                launch {
                    delay(200)
                    client.post("/accounts/merchant/transactions") {
                        basicAuth("merchant", "merchant-password")
                        jsonBody(json {
                            "payto_uri" to "payto://iban/EXCHANGE-IBAN-XYZ?message=payout_poll&amount=KUDOS:4.2"
                        })
                    }.assertOk()
                }
            )
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
        }.assertStatus(HttpStatusCode.NotFound)
        // Check wrong transaction
        client.get("/accounts/merchant/transactions/2") {
            basicAuth("merchant", "merchant-password")
        }.assertStatus(HttpStatusCode.Unauthorized) // Should be NOT_FOUND ?
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
        }.assertBadRequest()
        // Surpassing the debt limit
        client.post("/accounts/merchant/transactions") {
            basicAuth("merchant", "merchant-password")
            contentType(ContentType.Application.Json)
            jsonBody(json(valid_req) {
                "amount" to "KUDOS:555"
            })
        }.assertStatus(HttpStatusCode.Conflict)
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
        }.assertStatus(HttpStatusCode.NotFound)
        // Transaction to self
        client.post("/accounts/merchant/transactions") {
            basicAuth("merchant", "merchant-password")
            contentType(ContentType.Application.Json)
            jsonBody(json(valid_req) {
                "payto_uri" to "payto://iban/MERCHANT-IBAN-XYZ?message=payout"
            })
        }.assertStatus(HttpStatusCode.Conflict)
    }
}

class LibeuFinApiTest {
    private val customerFoo = Customer(
        login = "foo",
        passwordHash = CryptoUtil.hashpw("pw"),
        name = "Foo",
        phone = "+00",
        email = "foo@b.ar",
        cashoutPayto = "payto://external-IBAN",
        cashoutCurrency = "KUDOS"
    )
    private val customerBar = Customer(
        login = "bar",
        passwordHash = CryptoUtil.hashpw("pw"),
        name = "Bar",
        phone = "+99",
        email = "bar@example.com",
        cashoutPayto = "payto://external-IBAN",
        cashoutCurrency = "KUDOS"
    )

    private fun genBankAccount(rowId: Long) = BankAccount(
        hasDebt = false,
        internalPaytoUri = IbanPayTo("payto://iban/ac${rowId}"),
        maxDebt = TalerAmount(100, 0, "KUDOS"),
        owningCustomerId = rowId
    )

    @Test
    fun getConfig() = bankSetup { _ -> 
        val r = client.get("/config") {
             expectSuccess = true
        }.assertOk()
        println(r.bodyAsText())
    }

   
    @Test
    fun tokenDeletionTest() = setup { db, ctx -> 
        assert(db.customerCreate(customerFoo) != null)
        val token = ByteArray(32)
        Random.nextBytes(token)
        assert(db.bearerTokenCreate(
            BearerToken(
                bankCustomer = 1L,
                content = token,
                creationTime = Instant.now(),
                expirationTime = Instant.now().plusSeconds(10),
                scope = TokenScope.readwrite
            )
        ))
        testApplication {
            application {
                corebankWebApp(db, ctx)
            }
            // Legitimate first attempt, should succeed
            client.delete("/accounts/foo/token") {
                expectSuccess = true
                headers["Authorization"] = "Bearer secret-token:${Base32Crockford.encode(token)}"
            }.apply {
                assert(this.status == HttpStatusCode.NoContent)
            }
            // Trying after deletion should hit 404.
            client.delete("/accounts/foo/token") {
                expectSuccess = false
                headers["Authorization"] = "Bearer secret-token:${Base32Crockford.encode(token)}"
            }.apply {
                assert(this.status == HttpStatusCode.Unauthorized)
            }
            // Checking foo can still be served by basic auth, after token deletion.
            assert(db.bankAccountCreate(
                BankAccount(
                    hasDebt = false,
                    internalPaytoUri = IbanPayTo("payto://iban/DE1234"),
                    maxDebt = TalerAmount(100, 0, "KUDOS"),
                    owningCustomerId = 1
                )
            ) != null)
            client.get("/accounts/foo") {
                expectSuccess = true
                basicAuth("foo", "pw")
            }
        }
    }

    // Creating token with "forever" duration.
    @Test
    fun tokenForeverTest() = setup { db, ctx -> 
        assert(db.customerCreate(customerFoo) != null)
        testApplication {
            application {
                corebankWebApp(db, ctx)
            }
            val newTok = client.post("/accounts/foo/token") {
                expectSuccess = true
                contentType(ContentType.Application.Json)
                basicAuth("foo", "pw")
                setBody(
                    """
                    {"duration": {"d_us": "forever"}, "scope": "readonly"}
                """.trimIndent()
                )
            }
            val newTokObj = Json.decodeFromString<TokenSuccessResponse>(newTok.bodyAsText())
            assert(newTokObj.expiration.t_s == Instant.MAX)
        }
    }

    // Testing that too big or invalid durations fail the request.
    @Test
    fun tokenInvalidDurationTest() = setup { db, ctx -> 
        assert(db.customerCreate(customerFoo) != null)
        testApplication {
            application {
                corebankWebApp(db, ctx)
            }
            var r = client.post("/accounts/foo/token") {
                expectSuccess = false
                contentType(ContentType.Application.Json)
                basicAuth("foo", "pw")
                setBody("""{
                    "duration": {"d_us": "invalid"},
                    "scope": "readonly"}""".trimIndent())
            }
            assert(r.status == HttpStatusCode.BadRequest)
            r = client.post("/accounts/foo/token") {
                expectSuccess = false
                contentType(ContentType.Application.Json)
                basicAuth("foo", "pw")
                setBody("""{
                    "duration": {"d_us": ${Long.MAX_VALUE}},
                    "scope": "readonly"}""".trimIndent())
            }
            assert(r.status == HttpStatusCode.BadRequest)
            r = client.post("/accounts/foo/token") {
                expectSuccess = false
                contentType(ContentType.Application.Json)
                basicAuth("foo", "pw")
                setBody("""{
                    "duration": {"d_us": -1},
                    "scope": "readonly"}""".trimIndent())
            }
            assert(r.status == HttpStatusCode.BadRequest)
        }
    }
    // Checking the POST /token handling.
    @Test
    fun tokenTest() = setup { db, ctx -> 
        assert(db.customerCreate(customerFoo) != null)
        testApplication {
            application {
                corebankWebApp(db, ctx)
            }
            val newTok = client.post("/accounts/foo/token") {
                expectSuccess = true
                contentType(ContentType.Application.Json)
                basicAuth("foo", "pw")
                setBody(
                    """
                    {"scope": "readonly"}
                """.trimIndent()
                )
            }
            // Checking that the token lifetime defaulted to 24 hours.
            val newTokObj = Json.decodeFromString<TokenSuccessResponse>(newTok.bodyAsText())
            val newTokDb = db.bearerTokenGet(Base32Crockford.decode(newTokObj.access_token))
            val lifeTime = Duration.between(newTokDb!!.creationTime, newTokDb.expirationTime)
            assert(lifeTime == Duration.ofDays(1))

            // foo tries to create a token on behalf of bar, expect 403.
            val r = client.post("/accounts/bar/token") {
                expectSuccess = false
                basicAuth("foo", "pw")
            }
            assert(r.status == HttpStatusCode.Unauthorized)
            // Make ad-hoc token for foo.
            val fooTok = ByteArray(32).apply { Random.nextBytes(this) }
            assert(
                db.bearerTokenCreate(
                    BearerToken(
                        content = fooTok,
                        bankCustomer = 1L, // only foo exists.
                        scope = TokenScope.readonly,
                        creationTime = Instant.now(),
                        isRefreshable = true,
                        expirationTime = Instant.now().plus(1, ChronoUnit.DAYS)
                    )
                )
            )
            // Testing the secret-token:-scheme.
            client.post("/accounts/foo/token") {
                headers.set("Authorization", "Bearer secret-token:${Base32Crockford.encode(fooTok)}")
                contentType(ContentType.Application.Json)
                setBody("{\"scope\": \"readonly\"}")
                expectSuccess = true
            }
            // Testing the 'forever' case.
            val forever = client.post("/accounts/foo/token") {
                expectSuccess = true
                contentType(ContentType.Application.Json)
                basicAuth("foo", "pw")
                setBody("""{
                    "scope": "readonly",
                    "duration": {"d_us": "forever"}
                }""".trimIndent())
            }
            val never: TokenSuccessResponse = Json.decodeFromString(forever.bodyAsText())
            assert(never.expiration.t_s == Instant.MAX)
        }
    }
}
