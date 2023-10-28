import io.ktor.client.*
import kotlinx.coroutines.runBlocking
import org.junit.Ignore
import org.junit.Test
import tech.libeufin.nexus.*
import tech.libeufin.nexus.ebics.doEbicsCustomDownload
import tech.libeufin.nexus.ebics.fetchBankAccounts
import tech.libeufin.nexus.ebics.submitPayment
import tech.libeufin.util.IbanPayto
import tech.libeufin.util.parsePayto
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Tests only manual, that's why they are @Ignore

private fun prep(): EbicsSetupConfig {
    val handle = TalerConfig(NEXUS_CONFIG_SOURCE)
    val ebicsUserId = File("/tmp/pofi-ebics-user-id.txt").readText()
    val ebicsPartnerId = File("/tmp/pofi-ebics-partner-id.txt").readText()
    handle.loadFromString(getPofiConfig(ebicsUserId, ebicsPartnerId))
    return EbicsSetupConfig(handle)
}

@Ignore
class Iso20022 {
    @Test
    fun sendPayment() {
        val cfg = prep()
        val xml = createPain001(
            "random",
            Instant.now(),
            cfg.myIbanAccount,
            TalerAmount(4, 0, "CHF"),
            "Test reimbursement, part 2",
            parsePayto("payto://iban/CH9300762011623852957?receiver-name=NotGiven")!!
        )
        runBlocking {
            assertTrue(submitPayment(
                xml,
                cfg,
                loadPrivateKeysFromDisk(cfg.clientPrivateKeysFilename)!!,
                loadBankKeys(cfg.bankPublicKeysFilename)!!,
                HttpClient()
            ))
        }
    }
}

@Ignore
class PostFinance {
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

    @Test
    fun customDownload() {
        val cfg = prep()
        val clientKeys = loadPrivateKeysFromDisk(cfg.clientPrivateKeysFilename)
        val bankKeys = loadBankKeys(cfg.bankPublicKeysFilename)
        runBlocking {
            val xml = doEbicsCustomDownload(
                messageType = "HTD",
                cfg = cfg,
                bankKeys = bankKeys!!,
                clientKeys = clientKeys!!,
                client = HttpClient()
            )
            println(xml)
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
        println(htd)
    }
}