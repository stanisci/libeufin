import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.HttpClient
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
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
        internalPaytoUri = IbanPayTo("payto://iban/FOO-IBAN-XYZ"),
        lastNexusFetchRowId = 1L,
        owningCustomerId = 1L,
        hasDebt = false,
        maxDebt = TalerAmount(10, 1, "KUDOS"),
        isTalerExchange = false
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
    val bankAccountBar = BankAccount(
        internalPaytoUri = IbanPayTo("payto://iban/BAR-IBAN-ABC"),
        lastNexusFetchRowId = 1L,
        owningCustomerId = 2L,
        hasDebt = false,
        maxDebt = TalerAmount(10, 1, "KUDOS"),
        isTalerExchange = true
    )
    

    suspend fun Database.genTransfer(from: String, to: BankAccount, amount: String = "KUDOS:10") {
        talerTransferCreate(
            req = TransferRequest(
                request_uid = randHashCode(),
                amount = TalerAmount(amount),
                exchange_base_url = ExchangeUrl("http://exchange.example.com/"),
                wtid = randShortHashCode(),
                credit_account = to.internalPaytoUri
            ),
            username = from,
            timestamp = Instant.now()
        ).run {
            assertEquals(TalerTransferResult.SUCCESS, txResult)
        }
    }

    suspend fun Database.genIncoming(to: String, from: BankAccount) {
        talerAddIncomingCreate(
            req = AddIncomingRequest(
                reserve_pub = randShortHashCode(),
                amount = TalerAmount( 10, 0, "KUDOS"),
                debit_account = from.internalPaytoUri,
            ),
            username = to,
            timestamp = Instant.now()
        ).run {
            assertEquals(TalerAddIncomingResult.SUCCESS, txResult)
        }
    }

    fun commonSetup(lambda: suspend (Database, BankApplicationContext) -> Unit) {
        setup { db, ctx -> 
            // Creating the exchange and merchant accounts first.
            assertNotNull(db.customerCreate(customerFoo))
            assertNotNull(db.bankAccountCreate(bankAccountFoo))
            assertNotNull(db.customerCreate(customerBar))
            assertNotNull(db.bankAccountCreate(bankAccountBar))
            lambda(db, ctx)
        }
    }

    // Test endpoint is correctly authenticated 
    suspend fun authRoutine(client: HttpClient, path: String, body: JsonObject? = null, method: HttpMethod = HttpMethod.Post) {
        // No body when authentication must happen before parsing the body
        
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
        }.assertStatus(HttpStatusCode.Unauthorized)

        // Not exchange account
        client.request(path) {
            this.method = method
            if (body != null) jsonBody(body)
            basicAuth("foo", "pw")
        }.assertStatus(HttpStatusCode.Conflict)
    }

    // Testing the POST /transfer call from the TWG API.
    @Test
    fun transfer() = commonSetup { db, ctx -> 
        // Do POST /transfer.
        testApplication {
            application {
                corebankWebApp(db, ctx)
            }

            val valid_req = json {
                "request_uid" to randHashCode()
                "amount" to "KUDOS:55"
                "exchange_base_url" to "http://exchange.example.com/"
                "wtid" to randShortHashCode()
                "credit_account" to bankAccountFoo.internalPaytoUri
            };

            authRoutine(client, "/accounts/foo/taler-wire-gateway/transfer", valid_req)

            // Checking exchange debt constraint.
            client.post("/accounts/bar/taler-wire-gateway/transfer") {
                basicAuth("bar", "secret")
                jsonBody(valid_req)
            }.assertStatus(HttpStatusCode.Conflict)

            // Giving debt allowance and checking the OK case.
            assert(db.bankAccountSetMaxDebt(
                2L,
                TalerAmount(1000, 0, "KUDOS")
            ))
            client.post("/accounts/bar/taler-wire-gateway/transfer") {
                basicAuth("bar", "secret")
                jsonBody(valid_req)
            }.assertOk()

            // check idempotency
            client.post("/accounts/bar/taler-wire-gateway/transfer") {
                basicAuth("bar", "secret")
                jsonBody(valid_req)
            }.assertOk()

            // Trigger conflict due to reused request_uid
            client.post("/accounts/bar/taler-wire-gateway/transfer") {
                basicAuth("bar", "secret")
                jsonBody(
                    json(valid_req) { 
                        "wtid" to randShortHashCode()
                        "exchange_base_url" to "http://different-exchange.example.com/"
                    }
                )
            }.assertStatus(HttpStatusCode.Conflict)

            // Currency mismatch
            client.post("/accounts/bar/taler-wire-gateway/transfer") {
                basicAuth("bar", "secret")
                jsonBody(
                    json(valid_req) {
                        "amount" to "EUR:33"
                    }
                )
            }.assertBadRequest()

            // Unknown account
            client.post("/accounts/bar/taler-wire-gateway/transfer") {
                basicAuth("bar", "secret")
                jsonBody(
                    json(valid_req) { 
                        "request_uid" to randHashCode()
                        "wtid" to randShortHashCode()
                        "credit_account" to "payto://iban/UNKNOWN-IBAN-XYZ"
                    }
                )
            }.assertStatus(HttpStatusCode.NotFound)

            // Bad BASE32 wtid
            client.post("/accounts/bar/taler-wire-gateway/transfer") {
                basicAuth("bar", "secret")
                jsonBody(
                    json(valid_req) { 
                        "wtid" to "I love chocolate"
                    }
                )
            }.assertBadRequest()
            
            // Bad BASE32 len wtid
            client.post("/accounts/bar/taler-wire-gateway/transfer") {
                basicAuth("bar", "secret")
                jsonBody(
                    json(valid_req) { 
                        "wtid" to randBase32Crockford(31)
                    }
                )
            }.assertBadRequest()

            // Bad BASE32 request_uid
            client.post("/accounts/bar/taler-wire-gateway/transfer") {
                basicAuth("bar", "secret")
                jsonBody(
                    json(valid_req) { 
                        "request_uid" to "I love chocolate"
                    }
                )
            }.assertBadRequest()

            // Bad BASE32 len wtid
            client.post("/accounts/bar/taler-wire-gateway/transfer") {
                basicAuth("bar", "secret")
                jsonBody(
                    json(valid_req) { 
                        "request_uid" to randBase32Crockford(65)
                    }
                )
            }.assertBadRequest()
        }
    }
    
    /**
     * Testing the /history/incoming call from the TWG API.
     */
    @Test
    fun historyIncoming() = commonSetup { db, ctx -> 
        // Give Foo reasonable debt allowance:
        assert(
            db.bankAccountSetMaxDebt(
                1L,
                TalerAmount(1000000, 0, "KUDOS")
            )
        )

        suspend fun HttpResponse.assertHistory(size: Int) {
            assertOk()
            val txt = this.bodyAsText()
            val history = Json.decodeFromString<IncomingHistory>(txt)
            val params = getHistoryParams(this.call.request.url.parameters)
       
            // testing the size is like expected.
            assert(history.incoming_transactions.size == size) {
                println("incoming_transactions has wrong size: ${history.incoming_transactions.size}")
                println("Response was: ${txt}")
            }
            if (params.delta < 0) {
                // testing that the first row_id is at most the 'start' query param.
                assert(history.incoming_transactions[0].row_id <= params.start)
                // testing that the row_id decreases.
                assert(history.incoming_transactions.windowed(2).all { (a, b) -> a.row_id > b.row_id })
            } else {
                // testing that the first row_id is at least the 'start' query param.
                assert(history.incoming_transactions[0].row_id >= params.start)
                // testing that the row_id increases.
                assert(history.incoming_transactions.windowed(2).all { (a, b) -> a.row_id < b.row_id })
            }
        }

        testApplication {
            application {
                corebankWebApp(db, ctx)
            }

            authRoutine(client, "/accounts/foo/taler-wire-gateway/history/incoming?delta=7", method = HttpMethod.Get)

            // Check error when no transactions
            client.get("/accounts/bar/taler-wire-gateway/history/incoming?delta=7") {
                basicAuth("bar", "secret")
            }.assertStatus(HttpStatusCode.NoContent)

            // Gen three transactions using clean add incoming logic
            repeat(3) {
                db.genIncoming("bar", bankAccountFoo)
            }
            // Should not show up in the taler wire gateway API history
            db.bankTransactionCreate(genTx("bogus foobar")).assertSuccess()
            // Bar pays Foo once, but that should not appear in the result.
            db.bankTransactionCreate(genTx("payout", creditorId = 1, debtorId = 2)).assertSuccess()
            // Gen two transactions using row bank transaction logic
            repeat(2) {
                db.bankTransactionCreate(
                    genTx(IncomingTxMetadata(randShortHashCode()).encode(), 2, 1)
                ).assertSuccess()
            }

            // Check ignore bogus subject
            client.get("/accounts/bar/taler-wire-gateway/history/incoming?delta=7") {
                basicAuth("bar", "secret")
            }.assertHistory(5)
           
            // Check skip bogus subject
            client.get("/accounts/bar/taler-wire-gateway/history/incoming?delta=5") {
                basicAuth("bar", "secret")
            }.assertHistory(5)
            
            // Check no useless polling
            assertTime(0, 300) {
                client.get("/accounts/bar/taler-wire-gateway/history/incoming?delta=-6&start=15&long_poll_ms=1000") {
                    basicAuth("bar", "secret")
                }.assertHistory(5)
            }

            // Check polling end
            client.get("/accounts/bar/taler-wire-gateway/history/incoming?delta=6&long_poll_ms=60") {
                basicAuth("bar", "secret")
            }.assertHistory(5)

            runBlocking {
                joinAll(
                    launch {  // Check polling succeed forward
                        assertTime(200, 1000) {
                            client.get("/accounts/bar/taler-wire-gateway/history/incoming?delta=6&long_poll_ms=1000") {
                                basicAuth("bar", "secret")
                            }.assertHistory(6)
                        }
                    },
                    launch {  // Check polling succeed backward
                        assertTime(200, 1000) {
                            client.get("/accounts/bar/taler-wire-gateway/history/incoming?delta=-6&long_poll_ms=1000") {
                                basicAuth("bar", "secret")
                            }.assertHistory(6)
                        }
                    },
                    launch {  // Check polling timeout forward
                        assertTime(200, 400) {
                            client.get("/accounts/bar/taler-wire-gateway/history/incoming?delta=8&long_poll_ms=300") {
                                basicAuth("bar", "secret")
                            }.assertHistory(6)
                        }
                    },
                    launch {  // Check polling timeout backward
                        assertTime(200, 400) {
                            client.get("/accounts/bar/taler-wire-gateway/history/incoming?delta=-8&long_poll_ms=300") {
                                basicAuth("bar", "secret")
                            }.assertHistory(6)
                        }
                    },
                    launch {
                        delay(200)
                        db.genIncoming("bar", bankAccountFoo)
                    }
                )
            }

            // Testing ranges. 
            repeat(300) {
                db.genIncoming("bar", bankAccountFoo)
            }

            // forward range:
            client.get("/accounts/bar/taler-wire-gateway/history/incoming?delta=10&start=30") {
                basicAuth("bar", "secret")
            }.assertHistory(10)

            // backward range:
            client.get("/accounts/bar/taler-wire-gateway/history/incoming?delta=-10&start=300") {
                basicAuth("bar", "secret")
            }.assertHistory(10)
        }
    }

    
    /**
     * Testing the /history/outgoing call from the TWG API.
     */
    @Test
    fun historyOutgoing() = commonSetup { db, ctx -> 
        // Give Bar reasonable debt allowance:
        assert(
            db.bankAccountSetMaxDebt(
                2L,
                TalerAmount(1000000, 0, "KUDOS")
            )
        )

        suspend fun HttpResponse.assertHistory(size: Int) {
            assertOk()
            val txt = this.bodyAsText()
            val history = Json.decodeFromString<OutgoingHistory>(txt)
            val params = getHistoryParams(this.call.request.url.parameters)
       
            // testing the size is like expected.
            assert(history.outgoing_transactions.size == size) {
                println("outgoing_transactions has wrong size: ${history.outgoing_transactions.size}")
                println("Response was: ${txt}")
            }
            if (params.delta < 0) {
                // testing that the first row_id is at most the 'start' query param.
                assert(history.outgoing_transactions[0].row_id <= params.start)
                // testing that the row_id decreases.
                assert(history.outgoing_transactions.windowed(2).all { (a, b) -> a.row_id > b.row_id })
            } else {
                // testing that the first row_id is at least the 'start' query param.
                assert(history.outgoing_transactions[0].row_id >= params.start)
                // testing that the row_id increases.
                assert(history.outgoing_transactions.windowed(2).all { (a, b) -> a.row_id < b.row_id })
            }
        }

        testApplication {
            application {
                corebankWebApp(db, ctx)
            }

            authRoutine(client, "/accounts/foo/taler-wire-gateway/history/outgoing?delta=7", method = HttpMethod.Get)

            // Check error when no transactions
            client.get("/accounts/bar/taler-wire-gateway/history/outgoing?delta=7") {
                basicAuth("bar", "secret")
            }.assertStatus(HttpStatusCode.NoContent)

            // Gen three transactions using clean transfer logic
            repeat(3) {
                db.genTransfer("bar", bankAccountFoo)
            }
            // Should not show up in the taler wire gateway API history
            db.bankTransactionCreate(genTx("bogus foobar", 1, 2)).assertSuccess()
            // Foo pays Bar once, but that should not appear in the result.
            db.bankTransactionCreate(genTx("payout")).assertSuccess()
            // Gen two transactions using row bank transaction logic
            repeat(2) {
                db.bankTransactionCreate(
                    genTx(OutgoingTxMetadata(randShortHashCode(), ExchangeUrl("http://exchange.example.com/")).encode(), 1, 2)
                ).assertSuccess()
            }

            // Check ignore bogus subject
            client.get("/accounts/bar/taler-wire-gateway/history/outgoing?delta=7") {
                basicAuth("bar", "secret")
            }.assertHistory(5)
           
            // Check skip bogus subject
            client.get("/accounts/bar/taler-wire-gateway/history/outgoing?delta=5") {
                basicAuth("bar", "secret")
            }.assertHistory(5)

            // Check no useless polling
            assertTime(0, 300) {
                client.get("/accounts/bar/taler-wire-gateway/history/outgoing?delta=-6&start=15&long_poll_ms=1000") {
                    basicAuth("bar", "secret")
                }.assertHistory(5)
            }

            // Check polling end
            client.get("/accounts/bar/taler-wire-gateway/history/outgoing?delta=6&long_poll_ms=60") {
                basicAuth("bar", "secret")
            }.assertHistory(5)

            runBlocking {
                joinAll(
                    launch {  // Check polling succeed forward
                        assertTime(200, 1000) {
                            client.get("/accounts/bar/taler-wire-gateway/history/outgoing?delta=6&long_poll_ms=1000") {
                                basicAuth("bar", "secret")
                            }.assertHistory(6)
                        }
                    },
                    launch {  // Check polling succeed backward
                        assertTime(200, 1000) {
                            client.get("/accounts/bar/taler-wire-gateway/history/outgoing?delta=-6&long_poll_ms=1000") {
                                basicAuth("bar", "secret")
                            }.assertHistory(6)
                        }
                    },
                    launch {  // Check polling timeout forward
                        assertTime(200, 400) {
                            client.get("/accounts/bar/taler-wire-gateway/history/outgoing?delta=8&long_poll_ms=300") {
                                basicAuth("bar", "secret")
                            }.assertHistory(6)
                        }
                    },
                    launch {  // Check polling timeout backward
                        assertTime(200, 400) {
                            client.get("/accounts/bar/taler-wire-gateway/history/outgoing?delta=-8&long_poll_ms=300") {
                                basicAuth("bar", "secret")
                            }.assertHistory(6)
                        }
                    },
                    launch {
                        delay(200)
                        db.genTransfer("bar", bankAccountFoo)
                    }
                )
            }

            // Testing ranges.
            repeat(300) {
                db.genTransfer("bar", bankAccountFoo)
            }

            // forward range:
            client.get("/accounts/bar/taler-wire-gateway/history/outgoing?delta=10&start=30") {
                basicAuth("bar", "secret")
            }.assertHistory(10)

            // backward range:
            client.get("/accounts/bar/taler-wire-gateway/history/outgoing?delta=-10&start=300") {
                basicAuth("bar", "secret")
            }.assertHistory(10)
        }
    }

    // Testing the /admin/add-incoming call from the TWG API.
    @Test
    fun addIncoming() = commonSetup { db, ctx -> 
        testApplication {
            application {
                corebankWebApp(db, ctx)
            }

            val valid_req = json {
                "amount" to "KUDOS:44"
                "reserve_pub" to randEddsaPublicKey()
                "debit_account" to bankAccountFoo.internalPaytoUri
            };

            authRoutine(client, "/accounts/foo/taler-wire-gateway/admin/add-incoming", valid_req)

            // Checking exchange debt constraint.
            client.post("/accounts/bar/taler-wire-gateway/admin/add-incoming") {
                basicAuth("bar", "secret")
                jsonBody(valid_req)
            }.assertStatus(HttpStatusCode.Conflict)

            // Giving debt allowance and checking the OK case.
            assert(db.bankAccountSetMaxDebt(
                1L,
                TalerAmount(1000, 0, "KUDOS")
            ))

            client.post("/accounts/bar/taler-wire-gateway/admin/add-incoming") {
                basicAuth("bar", "secret")
                jsonBody(valid_req, deflate = true)
            }.assertOk()

            // Trigger conflict due to reused reserve_pub
             client.post("/accounts/bar/taler-wire-gateway/admin/add-incoming") {
                basicAuth("bar", "secret")
                jsonBody(valid_req)
            }.assertStatus(HttpStatusCode.Conflict)

            // Currency mismatch
            client.post("/accounts/bar/taler-wire-gateway/admin/add-incoming") {
                basicAuth("bar", "secret")
                jsonBody(
                    json(valid_req) {
                        "amount" to "EUR:33"
                    }
                )
            }.assertBadRequest()

            // Unknown account
            client.post("/accounts/bar/taler-wire-gateway/admin/add-incoming") {
                basicAuth("bar", "secret")
                jsonBody(
                    json(valid_req) { 
                        "reserve_pub" to randEddsaPublicKey()
                        "debit_account" to "payto://iban/UNKNOWN-IBAN-XYZ"
                    }
                )
            }.assertStatus(HttpStatusCode.NotFound)

            // Bad BASE32 reserve_pub
            client.post("/accounts/bar/taler-wire-gateway/admin/add-incoming") {
                basicAuth("bar", "secret")
                jsonBody(json(valid_req) { 
                    "reserve_pub" to "I love chocolate"
                })
            }.assertBadRequest()
            
            // Bad BASE32 len reserve_pub
            client.post("/accounts/bar/taler-wire-gateway/admin/add-incoming") {
                basicAuth("bar", "secret")
                jsonBody(json(valid_req) { 
                    "reserve_pub" to randBase32Crockford(31)
                })
            }.assertBadRequest()
        }
    }
    // Selecting withdrawal details from the Integration API endpoint.
    @Test
    fun intSelect() = setup { db, ctx ->
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
                    selected_exchange = IbanPayTo("payto://iban/ABC123")
                ))
            }.assertOk()
            println(r.bodyAsText())
        }
    }
    // Showing withdrawal details from the Integrtion API endpoint.
    @Test
    fun intGet() = setup { db, ctx ->
        val uuid = UUID.randomUUID()
        assert(db.customerCreate(customerFoo) != null)
        assert(db.bankAccountCreate(bankAccountFoo) != null)
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
    fun withdrawalAbort() = setup { db, ctx ->
        val uuid = UUID.randomUUID()
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
        assert(db.talerWithdrawalSetDetails(uuid, IbanPayTo("payto://iban/exchange-payto"), "reserve_pub"))
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
    fun withdrawalCreation() = setup { db, ctx ->
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
    fun withdrawalConfirmation() = commonSetup { db, ctx -> 

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
            exchangePayto = IbanPayTo("payto://iban/BAR-IBAN-ABC"),
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