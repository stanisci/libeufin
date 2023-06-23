import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.nexus.bankaccount.addPaymentInitiation
import tech.libeufin.nexus.bankaccount.fetchBankAccountTransactions
import tech.libeufin.nexus.bankaccount.getBankAccount
import tech.libeufin.nexus.ebics.doEbicsUploadTransaction
import tech.libeufin.nexus.ebics.getEbicsSubscriberDetails
import tech.libeufin.nexus.getConnectionPlugin
import tech.libeufin.nexus.getNexusUser
import tech.libeufin.nexus.server.*
import tech.libeufin.util.EbicsStandardOrderParams
import java.io.BufferedReader
import java.io.File

// Submits a Z54 to the bank, expecting a camt.054 back.
private fun downloadPayment() {
    val httpClient = HttpClient()
    runBlocking {
        fetchBankAccountTransactions(
            client = httpClient,
            fetchSpec = FetchSpecLatestJson(
                level = FetchLevel.NOTIFICATION,
                bankConnection = null
            ),
            accountId = "foo"
        )
    }
}

/* Simulates one incoming payment for the test platorm's bank account.
 * The QRR format is NOT used in Taler, it is just convenient.
 * */
private fun uploadQrrPayment() {
    val httpClient = HttpClient()
    val qrr = """
        Product;Channel;Account;Currency;Amount;Reference;Name;Street;Number;Postcode;City;Country;DebtorAddressLine;DebtorAddressLine;DebtorAccount;ReferenceType;UltimateDebtorName;UltimateDebtorStreet;UltimateDebtorNumber;UltimateDebtorPostcode;UltimateDebtorTownName;UltimateDebtorCountry;UltimateDebtorAddressLine;UltimateDebtorAddressLine;RemittanceInformationText
        QRR;PO;CH9789144829733648596;CHF;33;;D009;Musterstrasse;1;1111;Musterstadt;CH;;;;NON;D009;Musterstrasse;1;1111;Musterstadt;CH;;;Taler-Demo
    """.trimIndent()
    runBlocking {
        doEbicsUploadTransaction(
            httpClient,
            getEbicsSubscriberDetails("postfinance"),
            "XTC",
            qrr.toByteArray(Charsets.UTF_8),
            EbicsStandardOrderParams()
        )
    }
}

/**
 * Submits a XE2 (+ pain.001 version 2019) message to the bank.
 *
 * Causes one DBIT payment to show up in the camt.054.  This one
 * however lacks the AcctSvcrRef, so other ways to pin it are needed.
 * Notably, EndToEndId is mandatory in pain.001 _and_ is controlled
 * by the sender.  Hence, the sender can itself ensure the EndToEndId
 * uniqueness.
 */
private fun uploadPain001Payment() {
    transaction {
        addPaymentInitiation(
            Pain001Data(
                creditorIban = "CH9300762011623852957",
                creditorBic = "POFICHBEXXX",
                creditorName = "Muster Frau",
                sum = "2",
                currency = "CHF",
                subject = "Muster Zahlung 0",
                endToEndId = "Zufall"
            ),
            getBankAccount("foo")
        )
    }
    val ebicsConn = getConnectionPlugin("ebics")
    val httpClient = HttpClient()
    runBlocking {
        ebicsConn.submitPaymentInitiation(httpClient, 1L)
    }
}

fun main() {
    // Loads EBICS subscriber's keys from disk.
    val bufferedReader: BufferedReader = File("/tmp/pofi.json").bufferedReader()
    val accessDataTxt = bufferedReader.use { it.readText() }
    val ebicsConn = getConnectionPlugin("ebics")
    val accessDataJson = jacksonObjectMapper().readTree(accessDataTxt)

    // Creates a connection handle to the bank, using the loaded keys.
    withTestDatabase {
        prepNexusDb()
        transaction {
            ebicsConn.createConnectionFromBackup(
                connId = "postfinance",
                user = getNexusUser("foo"),
                passphrase = "secret",
                accessDataJson
            )
            val fooBankAccount = getBankAccount("foo")
            fooBankAccount.defaultBankConnection = getBankConnection("postfinance")
            fooBankAccount.iban = "CH9789144829733648596"
        }
    }
    // uploadQrrPayment()
    // downloadPayment()
}