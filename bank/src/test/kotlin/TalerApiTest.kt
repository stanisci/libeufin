import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
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

    // Testing the POST /transfer call from the TWG API.
    @Test
    fun transfer() {
        val db = initDb()
        val ctx = getTestContext()
        // Creating the exchange and merchant accounts first.
        assertNotNull(db.customerCreate(customerFoo))
        assertNotNull(db.bankAccountCreate(bankAccountFoo))
        assertNotNull(db.customerCreate(customerBar))
        assertNotNull(db.bankAccountCreate(bankAccountBar))
        // Do POST /transfer.
        testApplication {
            application {
                corebankWebApp(db, ctx)
            }

            val valid_req = json {
                put("request_uid", randHashCode())
                put("amount", "KUDOS:55")
                put("exchange_base_url", "http://exchange.example.com/")
                put("wtid", randShortHashCode())
                put("credit_account", "${stripIbanPayto(bankAccountBar.internalPaytoUri)}")
            };

            // Unkown account
            client.post("/accounts/foo/taler-wire-gateway/transfer") {
                basicAuth("unknown", "password")
                jsonBody(valid_req)
            }.assertStatus(HttpStatusCode.Unauthorized)

            // Wrong password
            client.post("/accounts/foo/taler-wire-gateway/transfer") {
                basicAuth("foo", "password")
                jsonBody(valid_req)
            }.assertStatus(HttpStatusCode.Unauthorized)

            // Wrong account
            client.post("/accounts/foo/taler-wire-gateway/transfer") {
                basicAuth("bar", "secret")
                jsonBody(valid_req)
            }.assertStatus(HttpStatusCode.Forbidden)

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
                        put("wtid", randShortHashCode())
                        put("exchange_base_url", "http://different-exchange.example.com/")
                    }
                )
            }.assertStatus(HttpStatusCode.Conflict)

            // Triggering currency mismatch
            client.post("/accounts/foo/taler-wire-gateway/transfer") {
                basicAuth("foo", "pw")
                jsonBody(
                    json(valid_req) { 
                        put("request_uid", randHashCode())
                        put("wtid", randShortHashCode())
                        put("amount", "EUR:33")
                    }
                )
            }.assertBadRequest()

            // Bad BASE32 wtid
            client.post("/accounts/foo/taler-wire-gateway/transfer") {
                basicAuth("foo", "pw")
                jsonBody(
                    json(valid_req) { 
                        put("wtid", "I love chocolate")
                    }
                )
            }.assertBadRequest()
            
            // Bad BASE32 len wtid
            client.post("/accounts/foo/taler-wire-gateway/transfer") {
                basicAuth("foo", "pw")
                jsonBody(
                    json(valid_req) { 
                        put("wtid", randBase32Crockford(31))
                    }
                )
            }.assertBadRequest()

            // Bad BASE32 request_uid
            client.post("/accounts/foo/taler-wire-gateway/transfer") {
                basicAuth("foo", "pw")
                jsonBody(
                    json(valid_req) { 
                        put("request_uid", "I love chocolate")
                    }
                )
            }.assertBadRequest()

            // Bad BASE32 len wtid
            client.post("/accounts/foo/taler-wire-gateway/transfer") {
                basicAuth("foo", "pw")
                jsonBody(
                    json(valid_req) { 
                        put("request_uid", randBase32Crockford(65))
                    }
                )
            }.assertBadRequest()
        }
    }

    /**
     * Testing the /history/incoming call from the TWG API.
     */
    @Test
    fun historyIncoming() {
        val db = initDb()
        val ctx = getTestContext()
        assertNotNull(db.customerCreate(customerFoo))
        assertNotNull(db.bankAccountCreate(bankAccountFoo))
        assertNotNull(db.customerCreate(customerBar))
        assertNotNull(db.bankAccountCreate(bankAccountBar))
        // Give Foo reasonable debt allowance:
        assert(
            db.bankAccountSetMaxDebt(
                1L,
                TalerAmount(1000000, 0, "KUDOS")
            )
        )
        // Foo pays Bar (the exchange) twice.
        val reservePubOne = "5ZFS98S1K4Y083W95GVZK638TSRE44RABVASB3AFA3R95VCW17V0"
        val reservePubTwo = "TFBT5NEVT8D2GETZ4DRF7C69XZHKHJ15296HRGB1R5ARNK0SP8A0"
        db.bankTransactionCreate(genTx(reservePubOne)).assertSuccess()
        db.bankTransactionCreate(genTx(reservePubTwo)).assertSuccess()
        // Should not show up in the taler wire gateway API history
        db.bankTransactionCreate(genTx("bogus foobar")).assertSuccess()
        // Bar pays Foo once, but that should not appear in the result.
        db.bankTransactionCreate(genTx("payout", creditorId = 1, debtorId = 2)).assertSuccess()

        // Bar expects two entries in the incoming history
        testApplication {
            application {
                corebankWebApp(db, ctx)
            }
            val resp = client.get("/accounts/bar/taler-wire-gateway/history/incoming?delta=5") {
                basicAuth("bar", "secret")
            }.assertOk()
            val j: IncomingHistory = Json.decodeFromString(resp.bodyAsText())
            assertEquals(2, j.incoming_transactions.size)
            // Testing ranges.
            val mockReservePub = Base32Crockford.encode(ByteArray(32))
            for (i in 1..400)
                db.bankTransactionCreate(genTx(mockReservePub)).assertSuccess()
            // forward range:
            val range = client.get("/accounts/bar/taler-wire-gateway/history/incoming?delta=10&start=30") {
                basicAuth("bar", "secret")
            }.assertOk()
            val rangeObj = Json.decodeFromString<IncomingHistory>(range.bodyAsText())
            // testing the size is like expected.
            assert(rangeObj.incoming_transactions.size == 10) {
                println("incoming_transaction has wrong size: ${rangeObj.incoming_transactions.size}")
                println("Response was: ${range.bodyAsText()}")
            }
            // testing that the first row_id is at least the 'start' query param.
            assert(rangeObj.incoming_transactions[0].row_id >= 30)
            // testing that the row_id increases.
            for (idx in 1..9)
                assert(rangeObj.incoming_transactions[idx].row_id > rangeObj.incoming_transactions[idx - 1].row_id)
            // backward range:
            val rangeBackward = client.get("/accounts/bar/taler-wire-gateway/history/incoming?delta=-10&start=300") {
                basicAuth("bar", "secret")
            }.assertOk()
            val rangeBackwardObj = Json.decodeFromString<IncomingHistory>(rangeBackward.bodyAsText())
            // testing the size is like expected.
            assert(rangeBackwardObj.incoming_transactions.size == 10) {
                println("incoming_transaction has wrong size: ${rangeBackwardObj.incoming_transactions.size}")
                println("Response was: ${rangeBackward.bodyAsText()}")
            }
            // testing that the first row_id is at most the 'start' query param.
            assert(rangeBackwardObj.incoming_transactions[0].row_id <= 300)
            // testing that the row_id decreases.
            for (idx in 1..9)
                assert(
                    rangeBackwardObj.incoming_transactions[idx].row_id < rangeBackwardObj.incoming_transactions[idx - 1].row_id
                ) {
                    println("negative delta didn't return decreasing row_id's in idx: $idx")
                    println("[$idx] -> ${rangeBackwardObj.incoming_transactions[idx].row_id}")
                    println("[${idx - 1}] -> ${rangeBackwardObj.incoming_transactions[idx - 1].row_id}")
                    println(rangeBackward.bodyAsText())
                }
        }
    }

    // Testing the /admin/add-incoming call from the TWG API.
    @Test
    fun addIncoming() {
        val db = initDb()
        val ctx = getTestContext()
        assertNotNull(db.customerCreate(customerFoo))
        assertNotNull(db.bankAccountCreate(bankAccountFoo))
        assertNotNull(db.customerCreate(customerBar))
        assertNotNull(db.bankAccountCreate(bankAccountBar))
        // Give Bar reasonable debt allowance:
        assert(db.bankAccountSetMaxDebt(
            2L,
            TalerAmount(1000, 0, "KUDOS")
        ))
        testApplication {
            application {
                corebankWebApp(db, ctx)
            }
            val valid_req = json {
                put("amount", "KUDOS:44")
                put("reserve_pub", randEddsaPublicKey())
                put("debit_account", "${"payto://iban/BAR-IBAN-ABC"}")
            };
            client.post("/accounts/foo/taler-wire-gateway/admin/add-incoming") {
                basicAuth("foo", "pw")
                jsonBody(valid_req, deflate = true)
            }.assertOk()

            // Bad BASE32 reserve_pub
            client.post("/accounts/foo/taler-wire-gateway/admin/add-incoming") {
                basicAuth("foo", "pw")
                jsonBody(json(valid_req) { 
                    put("reserve_pub", "I love chocolate")
                })
            }.assertBadRequest()
            
            // Bad BASE32 len reserve_pub
            client.post("/accounts/foo/taler-wire-gateway/admin/add-incoming") {
                basicAuth("foo", "pw")
                jsonBody(json(valid_req) { 
                    put("reserve_pub", randBase32Crockford(31))
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
        val db = initDb()
        val ctx = getTestContext()
        // Creating Foo as the wallet owner and Bar as the exchange.
        assertNotNull(db.customerCreate(customerFoo))
        assertNotNull(db.bankAccountCreate(bankAccountFoo))
        assertNotNull(db.customerCreate(customerBar))
        assertNotNull(db.bankAccountCreate(bankAccountBar))

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
