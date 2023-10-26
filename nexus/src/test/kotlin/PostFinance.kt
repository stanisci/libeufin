import io.ktor.client.*
import kotlinx.coroutines.runBlocking
import org.junit.Ignore
import org.junit.Test
import tech.libeufin.nexus.*
import tech.libeufin.nexus.ebics.fetchBankAccounts
import tech.libeufin.util.IbanPayto
import tech.libeufin.util.parsePayto
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Iso20022 {
    @Test
    fun sendPayment() {
        val xml = createPain001(
            "random",
            Instant.now(),
            parsePayto("payto://iban/POFICHBE/CH9789144829733648596?receiver-name=NotGiven")!!,
            TalerAmount(4, 0, "CHF"),
            "Test reimbursement",
            parsePayto("payto://iban/CH9300762011623852957?receiver-name=NotGiven")!!
        )
        println(xml)
        File("/tmp/pain.001-test.xml").writeText(xml)
    }
}

@Ignore
class PostFinance {
    private fun prep(): EbicsSetupConfig {
        val handle = TalerConfig(NEXUS_CONFIG_SOURCE)
        val ebicsUserId = File("/tmp/pofi-ebics-user-id.txt").readText()
        val ebicsPartnerId = File("/tmp/pofi-ebics-partner-id.txt").readText()
        handle.loadFromString(getPofiConfig(ebicsUserId, ebicsPartnerId))
        return EbicsSetupConfig(handle)
    }

    // Tests sending client keys to the PostFinance test platform.
    @Test
    fun postClientKeys() {
        val cfg = prep()
        runBlocking {
            val httpClient = HttpClient()
            assertTrue(doKeysRequestAndUpdateState(cfg, clientKeys, httpClient, KeysOrderType.INI))
            assertTrue(doKeysRequestAndUpdateState(cfg, clientKeys, httpClient, KeysOrderType.HIA))
        }
    }

    // Tests getting the PostFinance keys from their test platform.
    @Test
    fun getBankKeys() {
        val cfg = prep()
        val keys = loadPrivateKeysFromDisk(cfg.clientPrivateKeysFilename)
        assertNotNull(keys)
        assertTrue(keys.submitted_ini)
        assertTrue(keys.submitted_hia)
        runBlocking {
            assertTrue(
                doKeysRequestAndUpdateState(
                cfg,
                keys,
                HttpClient(),
                KeysOrderType.HPB
            )
            )
        }
    }

    // Tests the HTD message type.
    @Test
    fun fetchAccounts() {
        val cfg = prep()
        val clientKeys = loadPrivateKeysFromDisk(cfg.clientPrivateKeysFilename)
        assertNotNull(clientKeys)
        val bankKeys = loadBankKeys(cfg.bankPublicKeysFilename)
        assertNotNull(bankKeys)
        val htd = runBlocking { fetchBankAccounts(cfg, clientKeys, bankKeys, HttpClient()) }
        extractBankAccountMetadata(cfg, htd!!, false)
    }
}