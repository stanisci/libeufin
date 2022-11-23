import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import org.junit.Ignore
import org.junit.Test
import org.w3c.dom.Document
import tech.libeufin.nexus.*
import tech.libeufin.sandbox.*
import tech.libeufin.util.*
import tech.libeufin.util.ebics_h004.EbicsRequest
import tech.libeufin.util.ebics_h004.EbicsResponse
import tech.libeufin.util.ebics_h004.EbicsTypes


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
@Ignore
class SchedulingTest {
    /**
     * Instruct the server to return invalid CAMT content.
     */
    @Test
    fun inject() {
        withNexusAndSandboxUser {
            val payload = """
                Invalid Camt Document
            """.trimIndent()
            withTestApplication(
                getCustomEbicsServer(EbicsResponses(payload))
            ) {
                runBlocking {
                    runTask(
                        client,
                        TaskSchedule(
                            0L,
                            "test-schedule",
                            "fetch",
                            "bank-account",
                            "mock-bank-account",
                            params = "{\"level\":\"report\",\"rangeType\":\"all\"}"
                        )
                    )
                }
            }
        }
    }
    /**
     * Create two payments and asks for C52.
     */
    @Test
    fun ordinary() {
        withNexusAndSandboxUser { // DB prep
            for (t in 1 .. 2) {
                wireTransfer(
                    "bank",
                    "foo",
                    "default",
                    "1HJX78AH7WAGBDJTCXJ4JKX022DBCHERA051KH7D3EC48X09G4RG",
                    "TESTKUDOS:5",
                    "xxx"
                )
            }
            withTestApplication(sandboxApp) {
                runBlocking {
                    runTask(
                        client,
                        TaskSchedule(
                            0L,
                            "test-schedule",
                            "fetch",
                            "bank-account",
                            "mock-bank-account",
                            params = "{\"level\":\"report\",\"rangeType\":\"all\"}"
                        )
                    )
                }
            }
        }
    }
}