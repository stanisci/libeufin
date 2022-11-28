import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Ignore
import org.junit.Test
import org.w3c.dom.Document
import tech.libeufin.nexus.*
import tech.libeufin.nexus.bankaccount.addPaymentInitiation
import tech.libeufin.nexus.bankaccount.fetchBankAccountTransactions
import tech.libeufin.nexus.ebics.EbicsBankConnectionProtocol
import tech.libeufin.nexus.server.FetchLevel
import tech.libeufin.nexus.server.FetchSpecAllJson
import tech.libeufin.nexus.server.FetchSpecJson
import tech.libeufin.nexus.server.Pain001Data
import tech.libeufin.sandbox.*
import tech.libeufin.util.*
import tech.libeufin.util.ebics_h004.EbicsRequest
import tech.libeufin.util.ebics_h004.EbicsResponse
import tech.libeufin.util.ebics_h004.EbicsTypes

/**
 * This source is NOT a test case -- as it uses no assertions --
 * but merely a tool to download and submit payments to the bank
 * via Nexus.
 * /

 */
/**
 * Data to make the test server return for EBICS
 * phases.  Currently only init is supported.
 */
data class EbicsResponses(
    val init: String,
    val download: String? = null,
    val receipt: String? = null
)

/**
 * Minimal server responding always the 'init' field of a EbicsResponses
 * object along a download EBICS message.  Suitable to set arbitrary data
 * in said response.  Signs the response assuming the client is the one
 * created a MakeEnv.kt.
 */
fun getCustomEbicsServer(r: EbicsResponses, endpoint: String = "/ebicsweb"): Application.() -> Unit {
    val ret: Application.() -> Unit = {
        install(ContentNegotiation) {
            register(ContentType.Text.Xml, XMLEbicsConverter())
            register(ContentType.Text.Plain, XMLEbicsConverter())
        }
        routing {
            post(endpoint) {
                val requestDocument = this.call.receive<Document>()
                val req = requestDocument.toObject<EbicsRequest>()
                val clientKey = CryptoUtil.loadRsaPublicKey(userKeys.enc.public.encoded)
                val msgId = EbicsOrderUtil.generateTransactionId()
                val resp: EbicsResponse = if (
                    req.header.mutable.transactionPhase == EbicsTypes.TransactionPhaseType.INITIALISATION
                ) {
                    val payload = prepareEbicsPayload(r.init, clientKey)
                    EbicsResponse.createForDownloadInitializationPhase(
                        msgId,
                        1,
                        4096,
                        payload.second, // for key material
                        payload.first // actual payload
                    )
                } else {
                    // msgId doesn't have to match the one used for the init phase.
                    EbicsResponse.createForDownloadReceiptPhase(msgId, true)
                }
                val sigEbics = XMLUtil.signEbicsResponse(
                    resp,
                    CryptoUtil.loadRsaPrivateKey(bankKeys.auth.private.encoded)
                )
                call.respond(sigEbics)
            }
        }
    }
    return ret
}

/**
 * Remove @Ignore, after having put asserts along tests,
 * and having had access to runTask and TaskSchedule, that
 * are now 'private'.
 */
// @Ignore
class SchedulingTest {
    /**
     * Instruct the server to return invalid CAMT content.
     */
    @Test
    fun download() {
        withNexusAndSandboxUser {
            withTestApplication(sandboxApp) {
                val conn = EbicsBankConnectionProtocol()
                runBlocking {
                    conn.fetchTransactions(
                        fetchSpec = FetchSpecAllJson(
                            level = FetchLevel.REPORT,
                            "foo"
                        ),
                        client,
                        "foo",
                        "mock-bank-account"
                    )
                }
            }
        }
    }

    @Test
    fun upload() {
        withNexusAndSandboxUser {
            withTestApplication(sandboxApp) {
                val conn = EbicsBankConnectionProtocol()
                runBlocking {
                    // Create Pain.001 to be submitted.
                    addPaymentInitiation(
                        Pain001Data(
                            creditorIban = getIban(),
                            creditorBic = "SANDBOXX",
                            creditorName = "Tester",
                            subject = "test payment",
                            sum = Amount(1),
                            currency = "TESTKUDOS"
                        ),
                        transaction {
                            NexusBankAccountEntity.findByName(
                                "mock-bank-account"
                            ) ?: throw Exception("Test failed")
                        }
                    )
                    conn.submitPaymentInitiation(
                        client,
                        1L
                    )
                }
            }
        }
    }
}