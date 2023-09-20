import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.Ignore
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
    // Testing withdrawal abort
    @Test
    fun withdrawalAbort() {
        val db = initDb()
        val uuid = UUID.randomUUID()
        assert(db.customerCreate(customerFoo) != null)
        assert(db.bankAccountCreate(bankAccountFoo))
        // insert new.
        assert(db.talerWithdrawalCreate(
            opUUID = uuid,
            walletBankAccount = 1L,
            amount = TalerAmount(1, 0)
        ))
        val op = db.talerWithdrawalGet(uuid)
        assert(op?.aborted == false)
        testApplication {
            application(webApp)
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
        assert(db.bankAccountCreate(bankAccountFoo))
        testApplication {
            application(webApp)
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
        val bankAccountBar = BankAccount(
            internalPaytoUri = "BAR-IBAN-ABC",
            lastNexusFetchRowId = 1L,
            owningCustomerId = 2L,
            hasDebt = false,
            maxDebt = TalerAmount(10, 1, "KUDOS")
        )
        val customerBar = Customer(
            login = "bar",
            passwordHash = "hash",
            name = "Bar",
            phone = "+00",
            email = "foo@b.ar",
            cashoutPayto = "payto://external-IBAN",
            cashoutCurrency = "KUDOS"
        )

        // Creating Foo as the wallet owner and Bar as the exchange.
        assert(db.customerCreate(customerFoo) != null)
        assert(db.bankAccountCreate(bankAccountFoo))
        assert(db.customerCreate(customerBar) != null)
        assert(db.bankAccountCreate(bankAccountBar))

        // Artificially making a withdrawal operation for Foo.
        val uuid = UUID.randomUUID()
        assert(db.talerWithdrawalCreate(
            opUUID = uuid,
            walletBankAccount = 1L,
            amount = TalerAmount(1, 0)
        ))
        // Specifying Bar as the exchange, via its Payto URI.
        assert(db.talerWithdrawalSetDetails(
            opUuid = uuid,
            exchangePayto = "BAR-IBAN-ABC",
            reservePub = "UNCHECKED-RESERVE-PUB"
        ))

        // Starting the bank and POSTing as Foo to /confirm the operation.
        testApplication {
            application(webApp)
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