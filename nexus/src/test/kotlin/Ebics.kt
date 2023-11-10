import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.junit.Ignore
import org.junit.Test
import tech.libeufin.nexus.*
import tech.libeufin.nexus.ebics.*
import tech.libeufin.util.XMLUtil
import tech.libeufin.util.ebics_h004.EbicsUnsecuredRequest
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Ebics {
    // Checks XML is valid and INI.
    @Test
    fun iniMessage() {
        val msg = generateIniMessage(config, clientKeys)
        val ini = XMLUtil.convertStringToJaxb<EbicsUnsecuredRequest>(msg) // ensures is valid
        assertEquals(ini.value.header.static.orderDetails.orderType, "INI") // ensures is INI
    }

    // Checks XML is valid and HIA.
    @Test
    fun hiaMessage() {
        val msg = generateHiaMessage(config, clientKeys)
        val ini = XMLUtil.convertStringToJaxb<EbicsUnsecuredRequest>(msg) // ensures is valid
        assertEquals(ini.value.header.static.orderDetails.orderType, "HIA") // ensures is HIA
    }

    // Checks XML is valid and HPB.
    @Test
    fun hpbMessage() {
        val msg = generateHpbMessage(config, clientKeys)
        val ini = XMLUtil.convertStringToJaxb<EbicsUnsecuredRequest>(msg) // ensures is valid
        assertEquals(ini.value.header.static.orderDetails.orderType, "HPB") // ensures is HPB
    }
    // POSTs an EBICS message to the mock bank.  Tests
    // the main branches: unreachable bank, non-200 status
    // code, and 200.
    @Test
    fun postMessage() {
        val client404 = getMockedClient {
            respondError(HttpStatusCode.NotFound)
        }
        val clientNoResponse = getMockedClient {
            throw Exception("Network issue.")
        }
        val clientOk = getMockedClient {
            respondOk("Not EBICS anyway.")
        }
        runBlocking {
            assertNull(client404.postToBank("http://ignored.example.com/", "ignored"))
            assertNull(clientNoResponse.postToBank("http://ignored.example.com/", "ignored"))
            assertNotNull(clientOk.postToBank("http://ignored.example.com/", "ignored"))
        }
    }

    // Tests that internal repr. of keys lead to valid PDF.
    // Mainly tests that the function does not throw any error.
    @Test
    fun keysPdf() {
        val pdf = generateKeysPdf(clientKeys, config)
        File("/tmp/libeufin-nexus-test-keys.pdf").writeBytes(pdf)
    }
}