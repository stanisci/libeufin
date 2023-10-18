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
import tech.libeufin.util.stripIbanPayto
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
        client.post("/accounts/merchant/withdrawals") {
            basicAuth("merchant", "merchant-password")
            jsonBody(json { "amount" to "KUDOS:9" }) 
        }.assertOk().run {
            val resp = Json.decodeFromString<BankAccountCreateWithdrawalResponse>(bodyAsText())
            val uuid = resp.taler_withdraw_uri.split("/").last()
            client.get("/taler-integration/withdrawal-operation/${uuid}")
                .assertOk()
        }

        // Check unknown
        client.get("/taler-integration/withdrawal-operation/${UUID.randomUUID()}")
            .assertNotFound()
        
        // Check bad UUID
        client.get("/taler-integration/withdrawal-operation/chocolate")
            .assertBadRequest()
    }

    // POST /taler-integration/withdrawal-operation/UUID
    @Test
    fun select() = bankSetup { _ ->
        val reserve_pub = randEddsaPublicKey()
        val req = json {
            "reserve_pub" to reserve_pub
            "selected_exchange" to IbanPayTo("payto://iban/EXCHANGE-IBAN-XYZ")
        }

        // Check bad UUID
        client.post("/taler-integration/withdrawal-operation/chocolate") {
            jsonBody(req)
        }.assertBadRequest()

        // Check unknown
        client.post("/taler-integration/withdrawal-operation/${UUID.randomUUID()}") {
            jsonBody(req)
        }.assertNotFound()

        client.post("/accounts/merchant/withdrawals") {
            basicAuth("merchant", "merchant-password")
            jsonBody(json { "amount" to "KUDOS:1" }) 
        }.assertOk().run {
            val resp = Json.decodeFromString<BankAccountCreateWithdrawalResponse>(bodyAsText())
            val uuid = resp.taler_withdraw_uri.split("/").last()

            // Check OK
            client.post("/taler-integration/withdrawal-operation/${uuid}") {
                jsonBody(req)
            }.assertOk()
            // Check idempotence
            client.post("/taler-integration/withdrawal-operation/${uuid}") {
                jsonBody(req)
            }.assertOk()
            // Check already selected
            client.post("/taler-integration/withdrawal-operation/${uuid}") {
                jsonBody(json(req) {
                    "reserve_pub" to randEddsaPublicKey()
                })
            }.assertConflict().assertErr(TalerErrorCode.TALER_EC_BANK_WITHDRAWAL_OPERATION_RESERVE_SELECTION_CONFLICT)
        }   

        client.post("/accounts/merchant/withdrawals") {
            basicAuth("merchant", "merchant-password")
            jsonBody(json { "amount" to "KUDOS:1" }) 
        }.assertOk().run {
            val resp = Json.decodeFromString<BankAccountCreateWithdrawalResponse>(bodyAsText())
            val uuid = resp.taler_withdraw_uri.split("/").last()

            // Check reserve_pub_reuse
            client.post("/taler-integration/withdrawal-operation/${uuid}") {
                jsonBody(req)
            }.assertConflict().assertErr(TalerErrorCode.TALER_EC_BANK_DUPLICATE_RESERVE_PUB_SUBJECT)
            // Check unknown account
            client.post("/taler-integration/withdrawal-operation/${uuid}") {
                jsonBody(json {
                    "reserve_pub" to randEddsaPublicKey()
                    "selected_exchange" to IbanPayTo("payto://iban/UNKNOWN-IBAN-XYZ")
                })
            }.assertConflict().assertErr(TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT)
            // Check account not exchange
            client.post("/taler-integration/withdrawal-operation/${uuid}") {
                jsonBody(json {
                    "reserve_pub" to randEddsaPublicKey()
                    "selected_exchange" to IbanPayTo("payto://iban/MERCHANT-IBAN-XYZ")
                })
            }.assertConflict().assertErr(TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT)
        }
    }

    // Testing withdrawal abort
    @Test
    fun withdrawalAbort() = bankSetup { db ->
        val uuid = UUID.randomUUID()
        // insert new.
        assertEquals(WithdrawalCreationResult.SUCCESS, db.talerWithdrawalCreate(
            opUUID = uuid,
            walletAccountUsername = "merchant",
            amount = TalerAmount(1, 0, "KUDOS")
        ))
        val op = db.talerWithdrawalGet(uuid)
        assert(op?.aborted == false)
        assertEquals(WithdrawalSelectionResult.SUCCESS,
            db.talerWithdrawalSetDetails(uuid, IbanPayTo("payto://iban/EXCHANGE-IBAN-XYZ"), randEddsaPublicKey()).first
        )
       
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
        assertEquals(WithdrawalCreationResult.SUCCESS, db.talerWithdrawalCreate(
            opUUID = uuid,
            walletAccountUsername = "merchant",
            amount = TalerAmount(1, 0, "KUDOS")
        ))
        // Specifying the exchange via its Payto URI.
        assertEquals(WithdrawalSelectionResult.SUCCESS,
            db.talerWithdrawalSetDetails(
                opUuid = uuid,
                exchangePayto = IbanPayTo("payto://iban/EXCHANGE-IBAN-XYZ"),
                reservePub = randEddsaPublicKey()
            ).first
        )

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