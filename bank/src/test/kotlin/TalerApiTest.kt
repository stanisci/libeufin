import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.Test
import tech.libeufin.bank.*
import tech.libeufin.util.CryptoUtil
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
        internalPaytoUri = "FOO-IBAN-XYZ",
        lastNexusFetchRowId = 1L,
        owningCustomerId = 1L,
        hasDebt = false,
        maxDebt = TalerAmount(10, 1, "KUDOS")
    )
    val bankAccountBar = BankAccount(
        internalPaytoUri = "BAR-IBAN-ABC",
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
        // Creating the exchange and merchant accounts first.
        assert(db.customerCreate(customerFoo) != null)
        assert(db.bankAccountCreate(bankAccountFoo) != null)
        assert(db.customerCreate(customerBar) != null)
        assert(db.bankAccountCreate(bankAccountBar) != null)
        // Give the exchange reasonable debt allowance:
        assert(db.bankAccountSetMaxDebt(
            1L,
            TalerAmount(1000, 0, "KUDOS")
        ))
        // Do POST /transfer.
        testApplication {
            application {
                corebankWebApp(db)
            }
            val req = """
                    {
                      "request_uid": "entropic 0",
                      "wtid": "entropic 1",
                      "exchange_base_url": "http://exchange.example.com/",
                      "amount": "KUDOS:33",
                      "credit_account": "BAR-IBAN-ABC"
                    }
                """.trimIndent()
            val resp = client.post("/accounts/foo/taler-wire-gateway/transfer") {
                basicAuth("foo", "pw")
                contentType(ContentType.Application.Json)
                expectSuccess = true
                setBody(req)
            }
            // println(resp.bodyAsText())
            // check idempotency
            val idemResp = client.post("/accounts/foo/taler-wire-gateway/transfer") {
                basicAuth("foo", "pw")
                contentType(ContentType.Application.Json)
                expectSuccess = true
                setBody(req)
            }
            // println(idemResp.bodyAsText())
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
            /* Triggering currency mismatch.  This mainly tests
             * the TalerAmount "@Contextual" parser.  */
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
    // Testing the /history/incoming call from the TWG API.
    @Test
    fun historyIncoming() {
        val db = initDb()
        assert(db.customerCreate(customerFoo) != null)
        assert(db.bankAccountCreate(bankAccountFoo) != null)
        assert(db.customerCreate(customerBar) != null)
        assert(db.bankAccountCreate(bankAccountBar) != null)
        // Give Foo reasonable debt allowance:
        assert(db.bankAccountSetMaxDebt(
            1L,
            TalerAmount(1000, 0, "KUDOS")
        ))
        // Foo pays Bar (the exchange) twice.
        assert(db.bankTransactionCreate(genTx("withdrawal 1")) == Database.BankTransactionResult.SUCCESS)
        assert(db.bankTransactionCreate(genTx("withdrawal 2")) == Database.BankTransactionResult.SUCCESS)
        // Bar pays Foo once, but that should not appear in the result.
        assert(
            db.bankTransactionCreate(genTx("payout", creditorId = 1, debtorId = 2)) ==
                    Database.BankTransactionResult.SUCCESS
            )
        // Bar expects two entries in the incoming history
        testApplication {
            application {
                corebankWebApp(db)
            }
            val resp = client.get("/accounts/bar/taler-wire-gateway/history/incoming?delta=5") {
                basicAuth("bar", "secret")
                expectSuccess = true
            }
            val j: IncomingHistory = Json.decodeFromString(resp.bodyAsText())
            assert(j.incoming_transactions.size == 2)
        }
    }

    // Testing the /admin/add-incoming call from the TWG API.
    @Test
    fun addIncoming() {
        val db = initDb()
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
                corebankWebApp(db)
            }
            client.post("/accounts/foo/taler-wire-gateway/admin/add-incoming") {
                expectSuccess = true
                contentType(ContentType.Application.Json)
                basicAuth("foo", "pw")
                setBody("""
                    {"amount": "KUDOS:44",
                     "reserve_pub": "RESERVE-PUB-TEST",
                      "debit_account": "BAR-IBAN-ABC"
                      }
                """.trimIndent())
            }
        }
    }
    // Selecting withdrawal details from the Integrtion API endpoint.
    @Test
    fun intSelect() {
        val db = initDb()
        val uuid = UUID.randomUUID()
        assert(db.customerCreate(customerFoo) != null)
        assert(db.bankAccountCreate(bankAccountFoo) != null)
        db.configSet(
            "suggested_exchange",
            "payto://suggested-exchange"
        )
        // insert new.
        assert(db.talerWithdrawalCreate(
            opUUID = uuid,
            walletBankAccount = 1L,
            amount = TalerAmount(1, 0, "KUDOS")
        ))
        testApplication {
            application {
                corebankWebApp(db)
            }
            val r = client.post("/taler-integration/withdrawal-operation/${uuid}") {
                expectSuccess = true
                contentType(ContentType.Application.Json)
                setBody("""
                    {"reserve_pub": "RESERVE-FOO", 
                     "selected_exchange": "payto://selected/foo/exchange" }
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
        db.configSet(
            "suggested_exchange",
            "payto://suggested-exchange"
        )
        // insert new.
        assert(db.talerWithdrawalCreate(
            opUUID = uuid,
            walletBankAccount = 1L,
            amount = TalerAmount(1, 0, "KUDOS")
        ))
        testApplication {
            application {
                corebankWebApp(db)
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
        testApplication {
            application {
                corebankWebApp(db)
            }
            client.post("/accounts/foo/withdrawals/${uuid}/abort") {
                expectSuccess = true
                basicAuth("foo", "pw")
            }
        }
        val opAbo = db.talerWithdrawalGet(uuid)
        assert(opAbo?.aborted == true)
    }
    // Testing withdrawal creation
    @Test
    fun withdrawalCreation() {
        val db = initDb()
        assert(db.customerCreate(customerFoo) != null)
        assert(db.bankAccountCreate(bankAccountFoo) != null)
        testApplication {
            application {
                corebankWebApp(db)
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
            client.get("/accounts/foo/withdrawals/${opId.withdrawal_id}") {
                expectSuccess = true
                basicAuth("foo", "pw")
            }
        }
    }
    // Testing withdrawal confirmation
    @Test
    fun withdrawalConfirmation() {
        val db = initDb()
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
            exchangePayto = "BAR-IBAN-ABC",
            reservePub = "UNCHECKED-RESERVE-PUB"
        ))

        // Starting the bank and POSTing as Foo to /confirm the operation.
        testApplication {
            application {
                corebankWebApp(db)
            }
            client.post("/accounts/foo/withdrawals/${uuid}/confirm") {
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
