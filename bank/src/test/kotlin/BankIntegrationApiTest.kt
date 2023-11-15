import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.HttpClient
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import net.taler.wallet.crypto.Base32Crockford
import net.taler.common.errorcodes.TalerErrorCode
import org.junit.Test
import tech.libeufin.bank.*
import tech.libeufin.util.CryptoUtil
import java.util.*
import java.time.Instant
import kotlin.test.*
import randHashCode

class BankIntegrationApiTest {
    // GET /taler-integration/config
    @Test
    fun config() = bankSetup { _ ->
        client.get("/taler-integration/config").assertOk()
    }

    // GET /taler-integration/withdrawal-operation/UUID
    @Test
    fun get() = bankSetup { _ ->
        // Check OK
        client.postA("/accounts/merchant/withdrawals") {
            json { "amount" to "KUDOS:9" } 
        }.assertOkJson<BankAccountCreateWithdrawalResponse> { resp ->
            val uuid = resp.taler_withdraw_uri.split("/").last()
            client.get("/taler-integration/withdrawal-operation/$uuid")
                .assertOk()
        }

        // Check unknown
        client.get("/taler-integration/withdrawal-operation/${UUID.randomUUID()}")
            .assertNotFound(TalerErrorCode.BANK_TRANSACTION_NOT_FOUND)
        
        // Check bad UUID
        client.get("/taler-integration/withdrawal-operation/chocolate")
            .assertBadRequest()
    }

    // POST /taler-integration/withdrawal-operation/UUID
    @Test
    fun select() = bankSetup { _ ->
        val reserve_pub = randEddsaPublicKey()
        val req = obj {
            "reserve_pub" to reserve_pub
            "selected_exchange" to exchangePayto
        }

        // Check bad UUID
        client.post("/taler-integration/withdrawal-operation/chocolate") {
            json(req)
        }.assertBadRequest()

        // Check unknown
        client.post("/taler-integration/withdrawal-operation/${UUID.randomUUID()}") {
            json(req)
        }.assertNotFound(TalerErrorCode.BANK_TRANSACTION_NOT_FOUND)

        client.postA("/accounts/merchant/withdrawals") {
            json { "amount" to "KUDOS:1" } 
        }.assertOkJson<BankAccountCreateWithdrawalResponse> {
            val uuid = it.taler_withdraw_uri.split("/").last()

            // Check OK
            client.post("/taler-integration/withdrawal-operation/$uuid") {
                json(req)
            }.assertOk()
            // Check idempotence
            client.post("/taler-integration/withdrawal-operation/$uuid") {
                json(req)
            }.assertOk()
            // Check already selected
            client.post("/taler-integration/withdrawal-operation/$uuid") {
                json(req) {
                    "reserve_pub" to randEddsaPublicKey()
                }
            }.assertConflict(TalerErrorCode.BANK_WITHDRAWAL_OPERATION_RESERVE_SELECTION_CONFLICT)
        }   

        client.postA("/accounts/merchant/withdrawals") {
            json { "amount" to "KUDOS:1" } 
        }.assertOkJson<BankAccountCreateWithdrawalResponse> {
            val uuid = it.taler_withdraw_uri.split("/").last()

            // Check reserve_pub_reuse
            client.post("/taler-integration/withdrawal-operation/$uuid") {
                json(req)
            }.assertConflict(TalerErrorCode.BANK_DUPLICATE_RESERVE_PUB_SUBJECT)
            // Check unknown account
            client.post("/taler-integration/withdrawal-operation/$uuid") {
                json {
                    "reserve_pub" to randEddsaPublicKey()
                    "selected_exchange" to unknownPayto
                }
            }.assertConflict(TalerErrorCode.BANK_UNKNOWN_ACCOUNT)
            // Check account not exchange
            client.post("/taler-integration/withdrawal-operation/$uuid") {
                json {
                    "reserve_pub" to randEddsaPublicKey()
                    "selected_exchange" to merchantPayto
                }
            }.assertConflict(TalerErrorCode.BANK_ACCOUNT_IS_NOT_EXCHANGE)
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