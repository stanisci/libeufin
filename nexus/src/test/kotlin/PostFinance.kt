import io.ktor.client.*
import kotlinx.coroutines.runBlocking
import org.junit.Ignore
import org.junit.Test
import tech.libeufin.nexus.*
import tech.libeufin.nexus.ebics.*
import tech.libeufin.util.ebics_h005.Ebics3Request
import tech.libeufin.util.parsePayto
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
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

    private val yesterday: Instant = Instant.now().minus(1, ChronoUnit.DAYS)

    @Test // asks a pain.002, links with pain.001's MsgId
    fun getAck() {
        download(prepAckRequest3(startDate = yesterday)
        )?.unzipForEach { name, content ->
            println(name)
            println(content)
        }
    }

    /**
     * With the "mit Detailavisierung" option, each entry has an
     * AcctSvcrRef & wire transfer subject.
     */
    @Test
    fun getStatement() {
        val inflatedBytes = download(prepStatementRequest3())
        inflatedBytes?.unzipForEach { name, content ->
            println(name)
            println(content)
        }
    }

    @Test
    fun getNotification() {
        val inflatedBytes = download(
            prepNotificationRequest3(
                // startDate = yesterday,
                isAppendix = true
            )
        )
        inflatedBytes?.unzipForEach { name, content ->
            println(name)
            println(content)
        }
    }

    /**
     * Never shows the subject.
     */
    @Test
    fun getReport() {
        download(prepReportRequest3(yesterday))?.unzipForEach { name, content ->
            println(name)
            println(content)
        }
    }

    @Test
    fun simulateIncoming() {
        val cfg = prep()
        val orderService: Ebics3Request.OrderDetails.Service = Ebics3Request.OrderDetails.Service().apply {
            serviceName = "OTH"
            scope = "BIL"
            messageName = Ebics3Request.OrderDetails.Service.MessageName().apply {
                value = "csv"
            }
            serviceOption = "CH002LMF"
        }
        val instruction = """
            Product;Channel;Account;Currency;Amount;Reference;Name;Street;Number;Postcode;City;Country;DebtorAddressLine;DebtorAddressLine;DebtorAccount;ReferenceType;UltimateDebtorName;UltimateDebtorStreet;UltimateDebtorNumber;UltimateDebtorPostcode;UltimateDebtorTownName;UltimateDebtorCountry;UltimateDebtorAddressLine;UltimateDebtorAddressLine;RemittanceInformationText
            QRR;PO;CH9789144829733648596;CHF;1;;D009;Musterstrasse;1;1111;Musterstadt;CH;;;;NON;D009;Musterstrasse;1;1111;Musterstadt;CH;;;Taler-Demo
        """.trimIndent()

        runBlocking {
            try {
                doEbicsUpload(
                    HttpClient(),
                    cfg,
                    loadPrivateKeysFromDisk(cfg.clientPrivateKeysFilename)!!,
                    loadBankKeys(cfg.bankPublicKeysFilename)!!,
                    orderService,
                    instruction.toByteArray(Charsets.UTF_8)
                )
            }
            catch (e: EbicsUploadException) {
                logger.error(e.message)
                logger.error("bank EC: ${e.bankErrorCode}, EBICS EC: ${e.ebicsErrorCode}")
            }
        }
    }

    fun download(req: Ebics3Request.OrderDetails.BTOrderParams): ByteArray? {
        val cfg = prep()
        val bankKeys = loadBankKeys(cfg.bankPublicKeysFilename)!!
        val myKeys = loadPrivateKeysFromDisk(cfg.clientPrivateKeysFilename)!!
        val initXml = createEbics3DownloadInitialization(
            cfg,
            bankKeys,
            myKeys,
            orderParams = req
        )
        return runBlocking {
            doEbicsDownload(
                HttpClient(),
                cfg,
                myKeys,
                bankKeys,
                initXml,
                isEbics3 = true,
                tolerateEmptyResult = true
            )
        }
    }

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
            // Not asserting, as it throws in case of errors.
            submitPain001(
                xml,
                cfg,
                loadPrivateKeysFromDisk(cfg.clientPrivateKeysFilename)!!,
                loadBankKeys(cfg.bankPublicKeysFilename)!!,
                HttpClient()
            )
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
            ))
        }
    }

    // Arbitrary download request for manual tests.
    @Test
    fun customDownload() {
        val cfg = prep()
        val clientKeys = loadPrivateKeysFromDisk(cfg.clientPrivateKeysFilename)
        val bankKeys = loadBankKeys(cfg.bankPublicKeysFilename)
        runBlocking {
            val bytes = doEbicsCustomDownload(
                messageType = "HTD",
                cfg = cfg,
                bankKeys = bankKeys!!,
                clientKeys = clientKeys!!,
                client = HttpClient()
            )
            println(bytes.toString())
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