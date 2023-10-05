import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.HttpClient
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import net.taler.wallet.crypto.Base32Crockford
import org.junit.Test
import tech.libeufin.bank.*
import tech.libeufin.util.CryptoUtil
import tech.libeufin.util.stripIbanPayto
import java.util.*
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import randHashCode

class TalerApiTest {
    private val customerFoo = Customer(
        login = "foo",
        passwordHash = CryptoUtil.hashpw("pw"),
        name = "Foo",
        phone = "+00",
        email = "foo@b.ar",
        cashoutPayto = "payto://external-IBAN",
        cashoutCurrency = "KUDOS"
    )
    private val bankAccountFoo = BankAccount(
        internalPaytoUri = "payto://iban/FOO-IBAN-XYZ".lowercase(),
        lastNexusFetchRowId = 1L,
        owningCustomerId = 1L,
        hasDebt = false,
        maxDebt = TalerAmount(10, 1, "KUDOS")
    )
    val bankAccountBar = BankAccount(
        internalPaytoUri = stripIbanPayto("payto://iban/BAR-IBAN-ABC")!!,
        lastNexusFetchRowId = 1L,
        owningCustomerId = 2L,
        hasDebt = false,
        maxDebt = TalerAmount(10, 1, "KUDOS"),
        isTalerExchange = true
    )
    val customerBar = Customer(
        login = "bar",
        passwordHash = CryptoUtil.hashpw("secret"),
        name = "Bar",
        phone = "+00",
        email = "foo@b.ar",
        cashoutPayto = "payto://external-IBAN",
        cashoutCurrency = "KUDOS"
    )

    suspend fun transfer(db: Database, from: Long, to: BankAccount) {
        db.talerTransferCreate(
            req = TransferRequest(
                request_uid = randHashCode(),
                amount = TalerAmount(10, 0, "Kudos"),
                exchange_base_url = "http://exchange.example.com/",
                wtid = randShortHashCode(),
                credit_account ="${stripIbanPayto(to.internalPaytoUri)}"
            ),
            exchangeBankAccountId = from,
            timestamp = Instant.now()
        )
    }

    fun commonSetup(): Pair<Database, BankApplicationContext> {
        val db = initDb()
        val ctx = getTestContext()
        // Creating the exchange and merchant accounts first.
        assertNotNull(db.customerCreate(customerFoo))
        assertNotNull(db.bankAccountCreate(bankAccountFoo))
        assertNotNull(db.customerCreate(customerBar))
        assertNotNull(db.bankAccountCreate(bankAccountBar))
        return Pair(db, ctx)
    }

    // Test endpoint is correctly authenticated 
    suspend fun authRoutine(client: HttpClient, path: String, method: HttpMethod = HttpMethod.Post) {
        // No body because authentication must happen before parsing the body
        
        // Unknown account
        client.request(path) {
            this.method = method
            basicAuth("unknown", "password")
        }.assertStatus(HttpStatusCode.Unauthorized)

        // Wrong password
        client.request(path) {
            this.method = method
            basicAuth("foo", "wrong_password")
        }.assertStatus(HttpStatusCode.Unauthorized)

        // Wrong account
        client.request(path) {
            this.method = method
            basicAuth("bar", "secret")
        }.assertStatus(HttpStatusCode.Forbidden)
    }

    // Testing the POST /transfer call from the TWG API.
    @Test
    fun transfer() {
        val (db, ctx) = commonSetup()
        // Do POST /transfer.
        testApplication {
            application {
                corebankWebApp(db, ctx)
            }

            authRoutine(client, "/accounts/foo/taler-wire-gateway/transfer")

            val valid_req = json {
                "request_uid" to randHashCode()
                "amount" to "KUDOS:55"
                "exchange_base_url" to "http://exchange.example.com/"
                "wtid" to randShortHashCode()
                "credit_account" to "${stripIbanPayto(bankAccountBar.internalPaytoUri)}"
            };

            // Checking exchange debt constraint.
            client.post("/accounts/foo/taler-wire-gateway/transfer") {
                basicAuth("foo", "pw")
                jsonBody(valid_req)
            }.assertStatus(HttpStatusCode.Conflict)

            // Giving debt allowance and checking the OK case.
            assert(db.bankAccountSetMaxDebt(
                1L,
                TalerAmount(1000, 0, "KUDOS")
            ))
            client.post("/accounts/foo/taler-wire-gateway/transfer") {
                basicAuth("foo", "pw")
                jsonBody(valid_req)
            }.assertOk()

            // check idempotency
            client.post("/accounts/foo/taler-wire-gateway/transfer") {
                basicAuth("foo", "pw")
                jsonBody(valid_req)
            }.assertOk()

            // Trigger conflict due to reused request_uid
            client.post("/accounts/foo/taler-wire-gateway/transfer") {
                basicAuth("foo", "pw")
                jsonBody(
                    json(valid_req) { 
                        "wtid" to randShortHashCode()
                        "exchange_base_url" to "http://different-exchange.example.com/"
                    }
                )
            }.assertStatus(HttpStatusCode.Conflict)

            // Triggering currency mismatch
            client.post("/accounts/foo/taler-wire-gateway/transfer") {
                basicAuth("foo", "pw")
                jsonBody(
                    json(valid_req) { 
                        "request_uid" to randHashCode()
                        "wtid" to randShortHashCode()
                        "amount" to "EUR:33"
                    }
                )
            }.assertBadRequest()

            // Bad BASE32 wtid
            client.post("/accounts/foo/taler-wire-gateway/transfer") {
                basicAuth("foo", "pw")
                jsonBody(
                    json(valid_req) { 
                        "wtid" to "I love chocolate"
                    }
                )
            }.assertBadRequest()
            
            // Bad BASE32 len wtid
            client.post("/accounts/foo/taler-wire-gateway/transfer") {
                basicAuth("foo", "pw")
                jsonBody(
                    json(valid_req) { 
                        "wtid" to randBase32Crockford(31)
                    }
                )
            }.assertBadRequest()

            // Bad BASE32 request_uid
            client.post("/accounts/foo/taler-wire-gateway/transfer") {
                basicAuth("foo", "pw")
                jsonBody(
                    json(valid_req) { 
                        "request_uid" to "I love chocolate"
                    }
                )
            }.assertBadRequest()

            // Bad BASE32 len wtid
            client.post("/accounts/foo/taler-wire-gateway/transfer") {
                basicAuth("foo", "pw")
                jsonBody(
                    json(valid_req) { 
                        "request_uid" to randBase32Crockford(65)
                    }
                )
            }.assertBadRequest()
        }
    }

    /**
     * FIXME: outlines loop bug in the /history/incoming handler.
     */
    @Test
    fun historyLoop() {
        val (db, ctx) = commonSetup()
        // Give Foo reasonable debt allowance:
        assert(
            db.bankAccountSetMaxDebt(
                1L,
                TalerAmount(1000000, 0, "KUDOS")
            )
        )
        db.bankTransactionCreate(genTx("bogus foobar")).assertSuccess()
        testApplication {
            application {
                corebankWebApp(db, ctx)
            }
            client.get("/accounts/bar/taler-wire-gateway/history/incoming?delta=1") {
                basicAuth("bar", "secret")
            }.apply { println(this.bodyAsText()) }
        }
    }
    /**
     * Testing the /history/incoming call from the TWG API.
     */
    @Test
    fun historyIncoming() {
        val (db, ctx) = commonSetup()
        // Give Foo reasonable debt allowance:
        assert(
            db.bankAccountSetMaxDebt(
                1L,
                TalerAmount(1000000, 0, "KUDOS")
            )
        )

        testApplication {
            application {
                corebankWebApp(db, ctx)
            }

            authRoutine(client, "/accounts/foo/taler-wire-gateway/history/incoming", HttpMethod.Get)

            // Check error when no transactions
            client.get("/accounts/bar/taler-wire-gateway/history/incoming?delta=7") {
                basicAuth("bar", "secret")
            }.assertStatus(HttpStatusCode.NoContent)

            // Foo pays Bar (the exchange) three time
            repeat(3) {
                db.bankTransactionCreate(genTx(randShortHashCode().encoded)).assertSuccess()
            }
            // Should not show up in the taler wire gateway API history
            db.bankTransactionCreate(genTx("bogus foobar")).assertSuccess()
            // Bar pays Foo once, but that should not appear in the result.
            db.bankTransactionCreate(genTx("payout", creditorId = 1, debtorId = 2)).assertSuccess()
            // Foo pays Bar (the exchange) twice, we should see five valid transactions
            repeat(2) {
                db.bankTransactionCreate(genTx(randShortHashCode().encoded)).assertSuccess()
            }

            // Check ignore bogus subject
            client.get("/accounts/bar/taler-wire-gateway/history/incoming?delta=7") {
                basicAuth("bar", "secret")
            }.assertOk().run {
                val j: IncomingHistory = Json.decodeFromString(this.bodyAsText())
                assertEquals(5, j.incoming_transactions.size)
            }
           
            // Check skip bogus subject
            client.get("/accounts/bar/taler-wire-gateway/history/incoming?delta=5") {
                basicAuth("bar", "secret")
            }.assertOk().run {
                val j: IncomingHistory = Json.decodeFromString(this.bodyAsText())
                assertEquals(5, j.incoming_transactions.size)
            }

            // Testing ranges.
            val mockReservePub = randShortHashCode().encoded
            for (i in 1..400)
                db.bankTransactionCreate(genTx(mockReservePub)).assertSuccess()

            // forward range:
            client.get("/accounts/bar/taler-wire-gateway/history/incoming?delta=10&start=30") {
                basicAuth("bar", "secret")
            }.assertOk().run {
                val txt = this.bodyAsText()
                val history = Json.decodeFromString<IncomingHistory>(txt)
                // testing the size is like expected.
                assert(history.incoming_transactions.size == 10) {
                    println("incoming_transaction has wrong size: ${history.incoming_transactions.size}")
                    println("Response was: ${txt}")
                }
                // testing that the first row_id is at least the 'start' query param.
                assert(history.incoming_transactions[0].row_id >= 30)
                // testing that the row_id increases.
                assert(history.incoming_transactions.windowed(2).all { (a, b) -> a.row_id < b.row_id })
            }

            // backward range:
            client.get("/accounts/bar/taler-wire-gateway/history/incoming?delta=-10&start=300") {
                basicAuth("bar", "secret")
            }.assertOk().run {
                val txt = this.bodyAsText()
                val history = Json.decodeFromString<IncomingHistory>(txt)
                // testing the size is like expected.
                assert(history.incoming_transactions.size == 10) {
                    println("incoming_transaction has wrong size: ${history.incoming_transactions.size}")
                    println("Response was: ${txt}")
                }
                // testing that the first row_id is at most the 'start' query param.
                assert(history.incoming_transactions[0].row_id <= 300)
                // testing that the row_id decreases.
                assert(history.incoming_transactions.windowed(2).all { (a, b) -> a.row_id > b.row_id })
            } 
        }
    }

    
    /**
     * Testing the /history/outgoing call from the TWG API.
     */
    @Test
    fun historyOutgoing() {
        val (db, ctx) = commonSetup()
        // Give Bar reasonable debt allowance:
        assert(
            db.bankAccountSetMaxDebt(
                2L,
                TalerAmount(1000000, 0, "KUDOS")
            )
        )

        testApplication {
            application {
                corebankWebApp(db, ctx)
            }

            authRoutine(client, "/accounts/foo/taler-wire-gateway/history/outgoing", HttpMethod.Get)

            // Check error when no transactions
            client.get("/accounts/bar/taler-wire-gateway/history/outgoing?delta=7") {
                basicAuth("bar", "secret")
            }.assertStatus(HttpStatusCode.NoContent)

            // Bar pays Foo three time
            repeat(3) {
                transfer(db, 2, bankAccountFoo)
            }
            // Should not show up in the taler wire gateway API history
            db.bankTransactionCreate(genTx("bogus foobar", 1, 2)).assertSuccess()
            // Foo pays Bar once, but that should not appear in the result.
            db.bankTransactionCreate(genTx("payout")).assertSuccess()
            // Bar pays Foo twice, we should see five valid transactions
            repeat(2) {
                transfer(db, 2, bankAccountFoo)
            }

            // Check ignore bogus subject
            client.get("/accounts/bar/taler-wire-gateway/history/outgoing?delta=7") {
                basicAuth("bar", "secret")
            }.assertOk().run {
                val j: OutgoingHistory = Json.decodeFromString(this.bodyAsText())
                assertEquals(5, j.outgoing_transactions.size)
            }
           
            // Check skip bogus subject
            client.get("/accounts/bar/taler-wire-gateway/history/outgoing?delta=5") {
                basicAuth("bar", "secret")
            }.assertOk().run {
                val j: OutgoingHistory = Json.decodeFromString(this.bodyAsText())
                assertEquals(5, j.outgoing_transactions.size)
            }

            // Testing ranges.
            val mockReservePub = randShortHashCode().encoded
            for (i in 1..400)
                transfer(db, 2, bankAccountFoo)

            // forward range:
            client.get("/accounts/bar/taler-wire-gateway/history/outgoing?delta=10&start=30") {
                basicAuth("bar", "secret")
            }.assertOk().run {
                val txt = this.bodyAsText()
                val history = Json.decodeFromString<OutgoingHistory>(txt)
                // testing the size is like expected.
                assert(history.outgoing_transactions.size == 10) {
                    println("outgoing_transactions has wrong size: ${history.outgoing_transactions.size}")
                    println("Response was: ${txt}")
                }
                // testing that the first row_id is at least the 'start' query param.
                assert(history.outgoing_transactions[0].row_id >= 30)
                // testing that the row_id increases.
                assert(history.outgoing_transactions.windowed(2).all { (a, b) -> a.row_id < b.row_id })
            }

            // backward range:
            client.get("/accounts/bar/taler-wire-gateway/history/outgoing?delta=-10&start=300") {
                basicAuth("bar", "secret")
            }.assertOk().run {
                val txt = this.bodyAsText()
                val history = Json.decodeFromString<OutgoingHistory>(txt)
                // testing the size is like expected.
                assert(history.outgoing_transactions.size == 10) {
                    println("outgoing_transactions has wrong size: ${history.outgoing_transactions.size}")
                    println("Response was: ${txt}")
                }
                // testing that the first row_id is at most the 'start' query param.
                assert(history.outgoing_transactions[0].row_id <= 300)
                // testing that the row_id decreases.
                assert(history.outgoing_transactions.windowed(2).all { (a, b) -> a.row_id > b.row_id })
            } 
        }
    }

    // Testing the /admin/add-incoming call from the TWG API.
    @Test
    fun addIncoming() {
        val (db, ctx) = commonSetup()
        // Give Bar reasonable debt allowance:
        assert(db.bankAccountSetMaxDebt(
            2L,
            TalerAmount(1000, 0, "KUDOS")
        ))
        testApplication {
            application {
                corebankWebApp(db, ctx)
            }

            authRoutine(client, "/accounts/foo/taler-wire-gateway/admin/add-incoming")

            val valid_req = json {
                "amount" to "KUDOS:44"
                "reserve_pub" to randEddsaPublicKey()
                "debit_account" to "${"payto://iban/BAR-IBAN-ABC"}"
            };
            client.post("/accounts/foo/taler-wire-gateway/admin/add-incoming") {
                basicAuth("foo", "pw")
                jsonBody(valid_req, deflate = true)
            }.assertOk()

            // Bad BASE32 reserve_pub
            client.post("/accounts/foo/taler-wire-gateway/admin/add-incoming") {
                basicAuth("foo", "pw")
                jsonBody(json(valid_req) { 
                    "reserve_pub" to "I love chocolate"
                })
            }.assertBadRequest()
            
            // Bad BASE32 len reserve_pub
            client.post("/accounts/foo/taler-wire-gateway/admin/add-incoming") {
                basicAuth("foo", "pw")
                jsonBody(json(valid_req) { 
                    "reserve_pub" to randBase32Crockford(31)
                })
            }.assertBadRequest()
        }
    }
    // Selecting withdrawal details from the Integration API endpoint.
    @Test
    fun intSelect() {
        val db = initDb()
        val ctx = getTestContext(suggestedExchange = "payto://iban/ABC123")
        val uuid = UUID.randomUUID()
        assertNotNull(db.customerCreate(customerFoo))
        assertNotNull(db.bankAccountCreate(bankAccountFoo))
        // insert new.
        assert(db.talerWithdrawalCreate(
            opUUID = uuid,
            walletBankAccount = 1L,
            amount = TalerAmount(1, 0, "KUDOS")
        ))
        testApplication {
            application {
                corebankWebApp(db, ctx)
            }
            val r = client.post("/taler-integration/withdrawal-operation/${uuid}") {
                jsonBody(BankWithdrawalOperationPostRequest(
                    reserve_pub = "RESERVE-FOO",
                    selected_exchange = "payto://iban/ABC123"
                ))
            }.assertOk()
            println(r.bodyAsText())
        }
    }
    // Showing withdrawal details from the Integrtion API endpoint.
    @Test
    fun intGet() {
        val db = initDb()
        val uuid = UUID.randomUUID()
        assert(db.customerCreate(customerFoo) != null)
        assert(db.bankAccountCreate(bankAccountFoo) != null)
        val ctx = getTestContext(suggestedExchange = "payto://iban/ABC123")
        // insert new.
        assert(db.talerWithdrawalCreate(
            opUUID = uuid,
            walletBankAccount = 1L,
            amount = TalerAmount(1, 0, "KUDOS")
        ))
        testApplication {
            application {
                corebankWebApp(db, ctx)
            }
            val r = client.get("/taler-integration/withdrawal-operation/${uuid}").assertOk()
            println(r.bodyAsText())
        }
    }
    // Testing withdrawal abort
    @Test
    fun withdrawalAbort() {
        val db = initDb()
        val uuid = UUID.randomUUID()
        val ctx = getTestContext()
        assert(db.customerCreate(customerFoo) != null)
        assert(db.bankAccountCreate(bankAccountFoo) != null)
        // insert new.
        assert(db.talerWithdrawalCreate(
            opUUID = uuid,
            walletBankAccount = 1L,
            amount = TalerAmount(1, 0, "KUDOS")
        ))
        val op = db.talerWithdrawalGet(uuid)
        assert(op?.aborted == false)
        assert(db.talerWithdrawalSetDetails(uuid, "exchange-payto", "reserve_pub"))
        testApplication {
            application {
                corebankWebApp(db, ctx)
            }
            client.post("/withdrawals/${uuid}/abort") {
                basicAuth("foo", "pw")
            }.assertOk()
        }
        val opAbo = db.talerWithdrawalGet(uuid)
        assert(opAbo?.aborted == true && opAbo.selectionDone == true)
    }
    // Testing withdrawal creation
    @Test
    fun withdrawalCreation() {
        val db = initDb()
        val ctx = getTestContext()
        assertNotNull(db.customerCreate(customerFoo))
        assertNotNull(db.bankAccountCreate(bankAccountFoo))
        testApplication {
            application {
                corebankWebApp(db, ctx)
            }
            // Creating the withdrawal as if the SPA did it.
            val r = client.post("/accounts/foo/withdrawals") {
                basicAuth("foo", "pw")
                jsonBody(BankAccountCreateWithdrawalRequest(TalerAmount(value = 9, frac = 0, currency = "KUDOS"))) 
            }.assertOk()
            val opId = Json.decodeFromString<BankAccountCreateWithdrawalResponse>(r.bodyAsText())
            // Getting the withdrawal from the bank.  Throws (failing the test) if not found.
            client.get("/withdrawals/${opId.withdrawal_id}") {
                basicAuth("foo", "pw")
            }.assertOk()
        }
    }
    // Testing withdrawal confirmation
    @Test
    fun withdrawalConfirmation() {
        val (db, ctx) = commonSetup()

        // Artificially making a withdrawal operation for Foo.
        val uuid = UUID.randomUUID()
        assert(db.talerWithdrawalCreate(
            opUUID = uuid,
            walletBankAccount = 1L,
            amount = TalerAmount(1, 0, "KUDOS")
        ))
        // Specifying Bar as the exchange, via its Payto URI.
        assert(db.talerWithdrawalSetDetails(
            opUuid = uuid,
            exchangePayto = "payto://iban/BAR-IBAN-ABC".lowercase(),
            reservePub = "UNCHECKED-RESERVE-PUB"
        ))

        // Starting the bank and POSTing as Foo to /confirm the operation.
        testApplication {
            application {
                corebankWebApp(db, ctx)
            }
            client.post("/withdrawals/${uuid}/confirm") {
                basicAuth("foo", "pw")
            }.assertOk()
        }
    }
    // Testing the generation of taler://withdraw-URIs.
    @Test
    fun testWithdrawUri() {
        // Checking the taler+http://-style.
        val withHttp = getTalerWithdrawUri(
            "http://example.com",
            "my-id"
        )
        assertEquals(withHttp, "taler+http://withdraw/example.com/taler-integration/my-id")
        // Checking the taler://-style
        val onlyTaler = getTalerWithdrawUri(
            "https://example.com/",
            "my-id"
        )
        // Note: this tests as well that no double slashes belong to the result
        assertEquals(onlyTaler, "taler://withdraw/example.com/taler-integration/my-id")
        // Checking the removal of subsequent slashes
        val manySlashes = getTalerWithdrawUri(
            "https://www.example.com//////",
            "my-id"
        )
        assertEquals(manySlashes, "taler://withdraw/www.example.com/taler-integration/my-id")
        // Checking with specified port number
        val withPort = getTalerWithdrawUri(
            "https://www.example.com:9876",
            "my-id"
        )
        assertEquals(withPort, "taler://withdraw/www.example.com:9876/taler-integration/my-id")
    }
}
