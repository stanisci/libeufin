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

class BankIntegrationApiTest {
    // Selecting withdrawal details from the Integration API endpoint.
    @Test
    fun intSelect() = bankSetup { db ->
        val uuid = UUID.randomUUID()
        // insert new.
        assert(db.talerWithdrawalCreate(
            opUUID = uuid,
            walletBankAccount = 1L,
            amount = TalerAmount(1, 0, "KUDOS")
        ))
       
        val r = client.post("/taler-integration/withdrawal-operation/${uuid}") {
            jsonBody(BankWithdrawalOperationPostRequest(
                reserve_pub = "RESERVE-FOO",
                selected_exchange = IbanPayTo("payto://iban/ABC123")
            ))
        }.assertOk()
        println(r.bodyAsText())
    }

    // Showing withdrawal details from the Integrtion API endpoint.
    @Test
    fun intGet() = bankSetup { db ->
        val uuid = UUID.randomUUID()
        // insert new.
        assert(db.talerWithdrawalCreate(
            opUUID = uuid,
            walletBankAccount = 1L,
            amount = TalerAmount(1, 0, "KUDOS")
        ))

        val r = client.get("/taler-integration/withdrawal-operation/${uuid}").assertOk()
        println(r.bodyAsText())
    }

    // Testing withdrawal abort
    @Test
    fun withdrawalAbort() = bankSetup { db ->
        val uuid = UUID.randomUUID()
        // insert new.
        assert(db.talerWithdrawalCreate(
            opUUID = uuid,
            walletBankAccount = 1L,
            amount = TalerAmount(1, 0, "KUDOS")
        ))
        val op = db.talerWithdrawalGet(uuid)
        assert(op?.aborted == false)
        assert(db.talerWithdrawalSetDetails(uuid, IbanPayTo("payto://iban/exchange-payto"), "reserve_pub"))
       
        client.post("/withdrawals/${uuid}/abort") {
            basicAuth("merchant", "merchant-password")
        }.assertOk()
        
        val opAbo = db.talerWithdrawalGet(uuid)
        assert(opAbo?.aborted == true && opAbo.selectionDone == true)
    }

    // Testing withdrawal creation
    @Test
    fun withdrawalCreation() = bankSetup { _ ->
        // Creating the withdrawal as if the SPA did it.
        val r = client.post("/accounts/merchant/withdrawals") {
            basicAuth("merchant", "merchant-password")
            jsonBody(BankAccountCreateWithdrawalRequest(TalerAmount(value = 9, frac = 0, currency = "KUDOS"))) 
        }.assertOk()
        val opId = Json.decodeFromString<BankAccountCreateWithdrawalResponse>(r.bodyAsText())
        // Getting the withdrawal from the bank.  Throws (failing the test) if not found.
        client.get("/withdrawals/${opId.withdrawal_id}") {
            basicAuth("merchant", "merchant-password")
        }.assertOk()
    }

    // Testing withdrawal confirmation
    @Test
    fun withdrawalConfirmation() = bankSetup { db -> 
        // Artificially making a withdrawal operation for merchant.
        val uuid = UUID.randomUUID()
        assert(db.talerWithdrawalCreate(
            opUUID = uuid,
            walletBankAccount = 1L,
            amount = TalerAmount(1, 0, "KUDOS")
        ))
        // Specifying the exchange via its Payto URI.
        assert(db.talerWithdrawalSetDetails(
            opUuid = uuid,
            exchangePayto = IbanPayTo("payto://iban/EXCHANGE-IBAN-XYZ"),
            reservePub = "UNCHECKED-RESERVE-PUB"
        ))

        // Starting the bank and POSTing as Foo to /confirm the operation.
        client.post("/withdrawals/${uuid}/confirm") {
            basicAuth("merchant", "merchant-password")
        }.assertOk()
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