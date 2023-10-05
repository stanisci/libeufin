import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
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
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

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
        internalPaytoUri = "payto://iban/ac${rowId}",
        maxDebt = TalerAmount(100, 0, "KUDOS"),
        owningCustomerId = rowId
    )

    @Test
    fun getConfig() {
        val db = initDb()
        val ctx = getTestContext()
        testApplication {
            application { corebankWebApp(db, ctx) }
            val r = client.get("/config") {
                expectSuccess = true
            }
            println(r.bodyAsText())
        }
    }

    /**
     * Testing GET /transactions.  This test checks that the sign
     * of delta gets honored by the HTTP handler, namely that the
     * records appear in ASC or DESC order, according to the sign
     * of delta.
     */
    @Test
    fun testHistory() {
        val db = initDb()
        val ctx = getTestContext()
        val fooId = db.customerCreate(customerFoo); assert(fooId != null)
        assert(db.bankAccountCreate(genBankAccount(fooId!!)) != null)
        val barId = db.customerCreate(customerBar); assert(barId != null)
        assert(db.bankAccountCreate(genBankAccount(barId!!)) != null)
        for (i in 1..10) {
            db.bankTransactionCreate(genTx("test-$i"))
        }
        testApplication {
            application {
                corebankWebApp(db, ctx)
            }
            val asc = client.get("/accounts/foo/transactions?delta=2") {
                basicAuth("foo", "pw")
                expectSuccess = true
            }
            var obj = Json.decodeFromString<BankAccountTransactionsResponse>(asc.bodyAsText())
            assert(obj.transactions.size == 2)
            assert(obj.transactions[0].row_id < obj.transactions[1].row_id)
            val desc = client.get("/accounts/foo/transactions?delta=-2") {
                basicAuth("foo", "pw")
                expectSuccess = true
            }
            obj = Json.decodeFromString(desc.bodyAsText())
            assert(obj.transactions.size == 2)
            assert(obj.transactions[0].row_id > obj.transactions[1].row_id)
        }
    }

    // Testing the creation of bank transactions.
    @Test
    fun postTransactionsTest() {
        val db = initDb()
        val ctx = getTestContext()
        // foo account
        val fooId = db.customerCreate(customerFoo);
        assert(fooId != null)
        assert(db.bankAccountCreate(genBankAccount(fooId!!)) != null)
        // bar account
        val barId = db.customerCreate(customerBar);
        assert(barId != null)
        assert(db.bankAccountCreate(genBankAccount(barId!!)) != null)
        // accounts exist, now create one transaction.
        testApplication {
            application {
                corebankWebApp(db, ctx)
            }
            client.post("/accounts/foo/transactions") {
                expectSuccess = true
                basicAuth("foo", "pw")
                contentType(ContentType.Application.Json)
                // expectSuccess = true
                setBody(
                    """{
                    "payto_uri": "payto://iban/AC${barId}?message=payout", 
                    "amount": "KUDOS:3.3"
                }
                """.trimIndent()
                )
            }
            // Getting the only tx that exists in the DB, hence has ID == 1.
            val r = client.get("/accounts/foo/transactions/1") {
                basicAuth("foo", "pw")
                expectSuccess = true
            }
            val obj: BankAccountTransactionInfo = Json.decodeFromString(r.bodyAsText())
            assert(obj.subject == "payout")
            // Testing the wrong currency.
            val wrongCurrencyResp = client.post("/accounts/foo/transactions") {
                expectSuccess = false
                basicAuth("foo", "pw")
                contentType(ContentType.Application.Json)
                // expectSuccess = true
                setBody(
                    """{
                    "payto_uri": "payto://iban/AC${barId}?message=payout", 
                    "amount": "EUR:3.3"
                }
                """.trimIndent()
                )
            }
            assert(wrongCurrencyResp.status == HttpStatusCode.BadRequest)
            // Surpassing the debt limit.
            val unallowedDebtResp = client.post("/accounts/foo/transactions") {
                expectSuccess = false
                basicAuth("foo", "pw")
                contentType(ContentType.Application.Json)
                // expectSuccess = true
                setBody(
                    """{
                    "payto_uri": "payto://iban/AC${barId}?message=payout", 
                    "amount": "KUDOS:555"
                }
                """.trimIndent()
                )
            }
            assert(unallowedDebtResp.status == HttpStatusCode.Conflict)
            val bigAmount = client.post("/accounts/foo/transactions") {
                expectSuccess = false
                basicAuth("foo", "pw")
                contentType(ContentType.Application.Json)
                // expectSuccess = true
                setBody(
                    """{
                    "payto_uri": "payto://iban/AC${barId}?message=payout", 
                    "amount": "KUDOS:${"5".repeat(200)}"
                }
                """.trimIndent()
                )
            }
            assert(bigAmount.status == HttpStatusCode.BadRequest)
        }
    }

    @Test
    fun passwordChangeTest() {
        val db = initDb()
        val ctx = getTestContext()
        assert(db.customerCreate(customerFoo) != null)
        testApplication {
            application {
                corebankWebApp(db, ctx)
            }
            // Changing the password.
            client.patch("/accounts/foo/auth") {
                expectSuccess = true
                contentType(ContentType.Application.Json)
                basicAuth("foo", "pw")
                setBody("""{"new_password": "bar"}""")
            }
            // Previous password should fail.
            client.patch("/accounts/foo/auth") {
                expectSuccess = false
                contentType(ContentType.Application.Json)
                basicAuth("foo", "pw")
                setBody("""{"new_password": "not-even-parsed"}""")
            }.apply {
                assert(this.status == HttpStatusCode.Unauthorized)
            }
            // New password should succeed.
            client.patch("/accounts/foo/auth") {
                expectSuccess = true
                contentType(ContentType.Application.Json)
                basicAuth("foo", "bar")
                setBody("""{"new_password": "not-used"}""")
            }
        }
    }
    @Test
    fun tokenDeletionTest() {
        val db = initDb()
        val ctx = getTestContext()
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
                    internalPaytoUri = "payto://iban/DE1234",
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

    @Test
    fun publicAccountsTest() {
        val db = initDb()
        val ctx = getTestContext()
        testApplication {
            application {
                corebankWebApp(db, ctx)
            }
            client.get("/public-accounts").apply {
                assert(this.status == HttpStatusCode.NoContent)
            }
            // Make one public account.
            db.customerCreate(customerBar).apply {
                assert(this != null)
                assert(
                    db.bankAccountCreate(
                        BankAccount(
                            isPublic = true,
                            internalPaytoUri = "payto://iban/non-used",
                            lastNexusFetchRowId = 1L,
                            owningCustomerId = this!!,
                            hasDebt = false,
                            maxDebt = TalerAmount(10, 1, "KUDOS")
                        )
                    ) != null
                )
            }
            client.get("/public-accounts").apply {
                assert(this.status == HttpStatusCode.OK)
                val obj = Json.decodeFromString<PublicAccountsResponse>(this.bodyAsText())
                assert(obj.public_accounts.size == 1)
                assert(obj.public_accounts[0].account_name == "bar")
            }
        }
    }
    // Creating token with "forever" duration.
    @Test
    fun tokenForeverTest() {
        val db = initDb()
        val ctx = getTestContext()
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
    fun tokenInvalidDurationTest() {
        val db = initDb()
        val ctx = getTestContext()
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
    fun tokenTest() {
        val db = initDb()
        val ctx = getTestContext()
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
            assert(r.status == HttpStatusCode.Forbidden)
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

    /**
     * Testing the retrieval of account information.
     * The tested logic is the one usually needed by SPAs
     * to show customers their status.
     */
    @Test
    fun getAccountTest() {
        // Artificially insert a customer and bank account in the database.
        val db = initDb()
        val ctx = getTestContext()
        val customerRowId = db.customerCreate(
            Customer(
                "foo",
                CryptoUtil.hashpw("pw"),
                "Foo"
            )
        )
        assert(customerRowId != null)
        assert(
            db.bankAccountCreate(
                BankAccount(
                    hasDebt = false,
                    internalPaytoUri = "payto://iban/DE1234",
                    maxDebt = TalerAmount(100, 0, "KUDOS"),
                    owningCustomerId = customerRowId!!
                )
            ) != null
        )
        testApplication {
            application {
                corebankWebApp(db, ctx)
            }
            val r = client.get("/accounts/foo") {
                expectSuccess = true
                basicAuth("foo", "pw")
            }
            val obj: AccountData = Json.decodeFromString(r.bodyAsText())
            assert(obj.name == "Foo")
            // Checking admin can.
            val adminRowId = db.customerCreate(
                Customer(
                    "admin",
                    CryptoUtil.hashpw("admin"),
                    "Admin"
                )
            )
            assert(adminRowId != null)
            assert(
                db.bankAccountCreate(
                    BankAccount(
                        hasDebt = false,
                        internalPaytoUri = "payto://iban/SANDBOXX/ADMIN-IBAN",
                        maxDebt = TalerAmount(100, 0, "KUDOS"),
                        owningCustomerId = adminRowId!!
                    )
                ) != null
            )
            client.get("/accounts/foo") {
                expectSuccess = true
                basicAuth("admin", "admin")
            }
            val shouldNot = client.get("/accounts/foo") {
                basicAuth("not", "not")
                expectSuccess = false
            }
            assert(shouldNot.status == HttpStatusCode.Unauthorized)
        }
    }

    /**
     * Testing the account creation and its idempotency
     */
    @Test
    fun createAccountTest() {
        testApplication {
            val db = initDb()
            val ctx = getTestContext()
            val ibanPayto = genIbanPaytoUri()
            application {
                corebankWebApp(db, ctx)
            }
            var resp = client.post("/accounts") {
                expectSuccess = false
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "username": "foo",
                    "password": "bar",
                    "name": "Jane",
                    "is_public": true,
                    "internal_payto_uri": "$ibanPayto"
                }""".trimIndent()
                )
            }
            assert(resp.status == HttpStatusCode.Created)
            // Testing idempotency.
            resp = client.post("/accounts") {
                expectSuccess = false
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "username": "foo",
                    "password": "bar",
                    "name": "Jane",
                    "is_public": true,
                    "internal_payto_uri": "$ibanPayto"
                }""".trimIndent()
                )
            }
            assert(resp.status == HttpStatusCode.Created)
        }
    }

    /**
     * Testing the account creation and its idempotency
     */
    @Test
    fun createTwoAccountsTest() {
        testApplication {
            val db = initDb()
            val ctx = getTestContext()
            application {
                corebankWebApp(db, ctx)
            }
            var resp = client.post("/accounts") {
                expectSuccess = false
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "username": "foo",
                    "password": "bar",
                    "name": "Jane"
                }""".trimIndent()
                )
            }
            assert(resp.status == HttpStatusCode.Created)
            // Test creating another account.
            resp = client.post("/accounts") {
                expectSuccess = false
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "username": "joe",
                    "password": "bar",
                    "name": "Joe"
                }""".trimIndent()
                )
            }
            assert(resp.status == HttpStatusCode.Created)
        }
    }

    /**
     * Test admin-only account creation
     */
    @Test
    fun createAccountRestrictedTest() {
        testApplication {
            val db = initDb()
            // For this test, we restrict registrations
            val ctx = getTestContext(restrictRegistration = true)

            application {
                corebankWebApp(db, ctx)
            }

            // Ordinary user tries, should fail.
            var resp = client.post("/accounts") {
                expectSuccess = false
                basicAuth("foo", "bar")
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "username": "baz",
                    "password": "xyz",
                    "name": "Mallory"
                }""".trimIndent()
                )
            }
            assert(resp.status == HttpStatusCode.Unauthorized)
            // Creating the administrator.
            assert(
                db.customerCreate(
                    Customer(
                        "admin",
                        CryptoUtil.hashpw("pass"),
                        "CFO"
                    )
                ) != null
            )
            // customer exists, this makes only the bank account:
            assert(maybeCreateAdminAccount(db, ctx))
            resp = client.post("/accounts") {
                expectSuccess = false
                basicAuth("admin", "pass")
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "username": "baz",
                    "password": "xyz",
                    "name": "Mallory"
                }""".trimIndent()
                )
            }
            assert(resp.status == HttpStatusCode.Created)
        }
    }

    /**
     * Tests DELETE /accounts/foo
     */
    @Test
    fun deleteAccount() {
        val db = initDb()
        val ctx = getTestContext()
        val adminCustomer = Customer(
            "admin",
            CryptoUtil.hashpw("pass"),
            "CFO"
        )
        db.customerCreate(adminCustomer)
        testApplication {
            application {
                corebankWebApp(db, ctx)
            }
            // account to delete doesn't exist.
            client.delete("/accounts/foo") {
                basicAuth("admin", "pass")
                expectSuccess = false
            }.apply {
                assert(this.status == HttpStatusCode.NotFound)
            }
            // account to delete is reserved.
            client.delete("/accounts/admin") {
                basicAuth("admin", "pass")
                expectSuccess = false
            }.apply {
                assert(this.status == HttpStatusCode.Forbidden)
            }
            // successful deletion
            db.customerCreate(customerFoo).apply {
                assert(this != null)
                assert(db.bankAccountCreate(genBankAccount(this!!)) != null)
            }
            client.delete("/accounts/foo") {
                basicAuth("admin", "pass")
                expectSuccess = true
            }.apply {
                assert(this.status == HttpStatusCode.NoContent)
            }
            // Trying again must yield 404
            client.delete("/accounts/foo") {
                basicAuth("admin", "pass")
                expectSuccess = false
            }.apply {
                assert(this.status == HttpStatusCode.NotFound)
            }
            // fail to delete, due to a non-zero balance.
            db.customerCreate(customerBar).apply {
                assert(this != null)
                db.bankAccountCreate(genBankAccount(this!!)).apply {
                    assert(this != null)
                    val conn = DriverManager.getConnection("jdbc:postgresql:///libeufincheck").unwrap(PgConnection::class.java)
                    conn.execSQLUpdate("UPDATE libeufin_bank.bank_accounts SET balance.val = 1 WHERE bank_account_id = $this")
                }
            }
            client.delete("/accounts/bar") {
                basicAuth("admin", "pass")
                expectSuccess = false
            }.apply {
                assert(this.status == HttpStatusCode.PreconditionFailed)
            }
        }
    }

    /**
     * Tests reconfiguration of account data.
     */
    @Test
    fun accountReconfig() {
        val db = initDb()
        val ctx = getTestContext()
        testApplication {
            application {
                corebankWebApp(db, ctx)
            }
            assertNotNull(db.customerCreate(customerFoo))
            // First call expects 500, because foo lacks a bank account
            client.patch("/accounts/foo") {
                basicAuth("foo", "pw")
                jsonBody(json {
                    "is_exchange" to true
                })
            }.assertStatus(HttpStatusCode.InternalServerError)
            // Creating foo's bank account.
            assertNotNull(db.bankAccountCreate(genBankAccount(1L)))
            // Successful attempt now.
            val validReq = AccountReconfiguration(
                cashout_address = "payto://new-cashout-address",
                challenge_contact_data = ChallengeContactData(
                    email = "new@example.com",
                    phone = "+987"
                ),
                is_exchange = true,
                name = null
            )
            client.patch("/accounts/foo") {
                basicAuth("foo", "pw")
                jsonBody(validReq)
            }.assertStatus(HttpStatusCode.NoContent)
            // Checking idempotence.
            client.patch("/accounts/foo") {
                basicAuth("foo", "pw")
                jsonBody(validReq)
            }.assertStatus(HttpStatusCode.NoContent)
            // Checking ordinary user doesn't get to patch their name.
            client.patch("/accounts/foo") {
                basicAuth("foo", "pw")
                jsonBody(json {
                    "name" to "Another Foo"
                })
            }.assertStatus(HttpStatusCode.Forbidden)
            // Finally checking that admin does get to patch foo's name.
            assertNotNull(db.customerCreate(Customer(
                login = "admin",
                passwordHash = CryptoUtil.hashpw("secret"),
                name = "CFO"
            )))
            client.patch("/accounts/foo") {
                basicAuth("admin", "secret")
                jsonBody(json {
                    "name" to "Another Foo"
                })
            }.assertStatus(HttpStatusCode.NoContent)
            val fooFromDb = db.customerGetFromLogin("foo")
            assertNotNull(fooFromDb)
            assertEquals("Another Foo", fooFromDb.name)
        }
    }

    /**
     * Tests the GET /accounts endpoint.
     */
    @Test
    fun getAccountsList() {
        val db = initDb()
        val ctx = getTestContext()
        val adminCustomer = Customer(
            "admin",
            CryptoUtil.hashpw("pass"),
            "CFO"
        )
        assert(db.customerCreate(adminCustomer) != null)
        testApplication {
            application {
                corebankWebApp(db, ctx)
            }
            // No users registered, expect no data.
            client.get("/accounts") {
                basicAuth("admin", "pass")
                expectSuccess = true
            }.apply {
                assert(this.status == HttpStatusCode.NoContent)
            }
            // foo account
            db.customerCreate(customerFoo).apply {
                assert(this != null)
                assert(db.bankAccountCreate(genBankAccount(this!!)) != null)
            }
            // bar account
            db.customerCreate(customerBar).apply {
                assert(this != null)
                assert(db.bankAccountCreate(genBankAccount(this!!)) != null)
            }
            // Two users registered, requesting all of them.
            client.get("/accounts") {
                basicAuth("admin", "pass")
                expectSuccess = true
            }.apply {
                println(this.bodyAsText())
                assert(this.status == HttpStatusCode.OK)
                val obj = Json.decodeFromString<ListBankAccountsResponse>(this.bodyAsText())
                assert(obj.accounts.size == 2)
                // Order unreliable, just checking they're different.
                assert(obj.accounts[0].username != obj.accounts[1].username)
            }
            // Filtering on bar.
            client.get("/accounts?filter_name=ar") {
                basicAuth("admin", "pass")
                expectSuccess = true
            }.apply {
                assert(this.status == HttpStatusCode.OK)
                val obj = Json.decodeFromString<ListBankAccountsResponse>(this.bodyAsText())
                assert(obj.accounts.size == 1) {
                    println("Wrong size of filtered query: ${obj.accounts.size}")
                }
                assert(obj.accounts[0].username == "bar")
            }
        }
    }

}
