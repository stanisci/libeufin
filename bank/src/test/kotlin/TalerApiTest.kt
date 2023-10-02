import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import net.taler.wallet.crypto.Base32Crockford
import org.junit.Test
import tech.libeufin.bank.*
import tech.libeufin.util.CryptoUtil
import tech.libeufin.util.stripIbanPayto
import java.util.*

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
        assert(db.customerCreate(customerFoo) != null)
        assert(db.bankAccountCreate(bankAccountFoo) != null)
        assert(db.customerCreate(customerBar) != null)
        assert(db.bankAccountCreate(bankAccountBar) != null)
        // Do POST /transfer.
        testApplication {
            application {
                corebankWebApp(db, ctx)
            }
            val req = """
                    {
                      "request_uid": "entropic 0",
                      "wtid": "entropic 1",
                      "exchange_base_url": "http://exchange.example.com/",
                      "amount": "KUDOS:55",
                      "credit_account": "${stripIbanPayto(bankAccountBar.internalPaytoUri)}"
                    }
                """.trimIndent()
            // Checking exchange debt constraint.
            val resp = client.post("/accounts/foo/taler-wire-gateway/transfer") {
                basicAuth("foo", "pw")
                contentType(ContentType.Application.Json)
                expectSuccess = false
                setBody(req)
            }
            println(resp.bodyAsText())
            assert(resp.status == HttpStatusCode.Conflict)
            // Giving debt allowance and checking the OK case.
            assert(db.bankAccountSetMaxDebt(
                1L,
                TalerAmount(1000, 0, "KUDOS")
            ))
            client.post("/accounts/foo/taler-wire-gateway/transfer") {
                basicAuth("foo", "pw")
                contentType(ContentType.Application.Json)
                expectSuccess = true
                setBody(req)
            }
            // check idempotency
            client.post("/accounts/foo/taler-wire-gateway/transfer") {
                basicAuth("foo", "pw")
                contentType(ContentType.Application.Json)
                expectSuccess = true
                setBody(req)
            }
            // Trigger conflict due to reused request_uid
            val r = client.post("/accounts/foo/taler-wire-gateway/transfer") {
                basicAuth("foo", "pw")
                contentType(ContentType.Application.Json)
                expectSuccess = false
                setBody("""
                    {
                      "request_uid": "entropic 0",
                      "wtid": "entropic 1",
                      "exchange_base_url": "http://different-exchange.example.com/",
                      "amount": "KUDOS:33",
                      "credit_account": "BAR-IBAN-ABC"
                    }
                """.trimIndent())
            }
            assert(r.status == HttpStatusCode.Conflict)
            // Triggering currency mismatch
            val currencyMismatchResp = client.post("/accounts/foo/taler-wire-gateway/transfer") {
                basicAuth("foo", "pw")
                contentType(ContentType.Application.Json)
                expectSuccess = false
                setBody("""
                    {
                      "request_uid": "entropic 3",
                      "wtid": "entropic 4",
                      "exchange_base_url": "http://different-exchange.example.com/",
                      "amount": "EUR:33",
                      "credit_account": "BAR-IBAN-ABC"
                    }
                """.trimIndent())
            }
            assert(currencyMismatchResp.status == HttpStatusCode.BadRequest)
        }
    }

    /**
     * Testing the /history/incoming call from the TWG API.
     */
    @Test
    fun historyIncoming() {
        val db = initDb()
        val ctx = getTestContext()
        assert(db.customerCreate(customerFoo) != null)
        assert(db.bankAccountCreate(bankAccountFoo) != null)
        assert(db.customerCreate(customerBar) != null)
        assert(db.bankAccountCreate(bankAccountBar) != null)
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
        assert(db.bankTransactionCreate(genTx(reservePubOne)) == BankTransactionResult.SUCCESS)
        assert(db.bankTransactionCreate(genTx(reservePubTwo)) == BankTransactionResult.SUCCESS)
        // Should not show up in the taler wire gateway API history
        assert(db.bankTransactionCreate(genTx("bogus foobar")) == BankTransactionResult.SUCCESS)
        // Bar pays Foo once, but that should not appear in the result.
        assert(
            db.bankTransactionCreate(genTx("payout", creditorId = 1, debtorId = 2)) ==
                    BankTransactionResult.SUCCESS
        )
        // Bar expects two entries in the incoming history
        testApplication {
            application {
                corebankWebApp(db, ctx)
            }
            val resp = client.get("/accounts/bar/taler-wire-gateway/history/incoming?delta=5") {
                basicAuth("bar", "secret")
                expectSuccess = true
            }
            val j: IncomingHistory = Json.decodeFromString(resp.bodyAsText())
            assert(j.incoming_transactions.size == 2)
            // Testing ranges.
            val mockReservePub = Base32Crockford.encode(ByteArray(32))
            for (i in 1..400)
                assert(db.bankTransactionCreate(genTx(mockReservePub)) == BankTransactionResult.SUCCESS)
            // forward range:
            val range = client.get("/accounts/bar/taler-wire-gateway/history/incoming?delta=10&start=30") {
                basicAuth("bar", "secret")
                expectSuccess = true
            }
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
                expectSuccess = true
            }
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
        assert(db.customerCreate(customerFoo) != null)
        assert(db.bankAccountCreate(bankAccountFoo) != null)
        assert(db.customerCreate(customerBar) != null)
        assert(db.bankAccountCreate(bankAccountBar) != null)
        // Give Bar reasonable debt allowance:
        assert(db.bankAccountSetMaxDebt(
            2L,
            TalerAmount(1000, 0, "KUDOS")
        ))
        testApplication {
            application {
                corebankWebApp(db, ctx)
            }
            client.post("/accounts/foo/taler-wire-gateway/admin/add-incoming") {
                expectSuccess = true
                contentType(ContentType.Application.Json)
                basicAuth("foo", "pw")
                headers.set("Content-Encoding", "deflate")
                setBody(deflater("""
                    {"amount": "KUDOS:44",
                     "reserve_pub": "RESERVE-PUB-TEST",
                      "debit_account": "${"payto://iban/BAR-IBAN-ABC".lowercase()}"
                      }
                """.trimIndent()))
            }
        }
    }
    // Selecting withdrawal details from the Integration API endpoint.
    @Test
    fun intSelect() {
        val db = initDb()
        val ctx = getTestContext(suggestedExchange = "payto://iban/ABC123")
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
            val r = client.post("/taler-integration/withdrawal-operation/${uuid}") {
                expectSuccess = true
                contentType(ContentType.Application.Json)
                setBody("""
                    {"reserve_pub": "RESERVE-FOO", 
                     "selected_exchange": "payto://iban/ABC123" }
                """.trimIndent())
            }
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
            val r = client.get("/taler-integration/withdrawal-operation/${uuid}") {
                expectSuccess = true
            }
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
                expectSuccess = true
                basicAuth("foo", "pw")
            }
        }
        val opAbo = db.talerWithdrawalGet(uuid)
        assert(opAbo?.aborted == true && opAbo.selectionDone == true)
    }
    // Testing withdrawal creation
    @Test
    fun withdrawalCreation() {
        val db = initDb()
        val ctx = getTestContext()
        assert(db.customerCreate(customerFoo) != null)
        assert(db.bankAccountCreate(bankAccountFoo) != null)
        testApplication {
            application {
                corebankWebApp(db, ctx)
            }
            // Creating the withdrawal as if the SPA did it.
            val r = client.post("/accounts/foo/withdrawals") {
                basicAuth("foo", "pw")
                contentType(ContentType.Application.Json)
                expectSuccess = true
                setBody("""
                    {"amount": "KUDOS:9"}
                """.trimIndent())
            }
            val opId = Json.decodeFromString<BankAccountCreateWithdrawalResponse>(r.bodyAsText())
            // Getting the withdrawal from the bank.  Throws (failing the test) if not found.
            client.get("/withdrawals/${opId.withdrawal_id}") {
                expectSuccess = true
                basicAuth("foo", "pw")
            }
        }
    }
    // Testing withdrawal confirmation
    @Test
    fun withdrawalConfirmation() {
        val db = initDb()
        val ctx = getTestContext()
        // Creating Foo as the wallet owner and Bar as the exchange.
        assert(db.customerCreate(customerFoo) != null)
        assert(db.bankAccountCreate(bankAccountFoo) != null)
        assert(db.customerCreate(customerBar) != null)
        assert(db.bankAccountCreate(bankAccountBar) != null)

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
                expectSuccess = true // Sufficient to assert on success.
                basicAuth("foo", "pw")
            }
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
        assert(withHttp == "taler+http://withdraw/example.com/taler-integration/my-id")
        // Checking the taler://-style
        val onlyTaler = getTalerWithdrawUri(
            "https://example.com/",
            "my-id"
        )
        // Note: this tests as well that no double slashes belong to the result
        assert(onlyTaler == "taler://withdraw/example.com/taler-integration/my-id")
        // Checking the removal of subsequent slashes
        val manySlashes = getTalerWithdrawUri(
            "https://www.example.com//////",
            "my-id"
        )
        assert(manySlashes == "taler://withdraw/www.example.com/taler-integration/my-id")
        // Checking with specified port number
        val withPort = getTalerWithdrawUri(
            "https://www.example.com:9876",
            "my-id"
        )
        assert(withPort == "taler://withdraw/www.example.com:9876/taler-integration/my-id")
    }
}
