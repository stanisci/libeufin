import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import io.ktor.client.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.nexus.bankaccount.addPaymentInitiation
import tech.libeufin.nexus.bankaccount.fetchBankAccountTransactions
import tech.libeufin.nexus.bankaccount.getBankAccount
import tech.libeufin.nexus.ebics.EbicsUploadSpec
import tech.libeufin.nexus.ebics.doEbicsUploadTransaction
import tech.libeufin.nexus.ebics.getEbicsSubscriberDetails
import tech.libeufin.nexus.getConnectionPlugin
import tech.libeufin.nexus.getNexusUser
import tech.libeufin.nexus.server.*
import tech.libeufin.util.ebics_h005.Ebics3Request
import java.io.BufferedReader
import java.io.File
import kotlin.system.exitProcess

// Asks a camt.054 to the bank.
private fun downloadPayments() {
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

/* Simulates one incoming payment for the 'payee' argument.
 * It pays the test platform's bank account if none is found.
 * The QRR format is NOT used in Taler, it is just convenient.
 * */
private fun uploadQrrPayment(maybePayee: String? = null) {
    val payee = if (maybePayee == null) {
        val localAccount = getBankAccount("foo")
        localAccount.iban
    } else maybePayee
    val httpClient = HttpClient()
    val qrr = """
        Product;Channel;Account;Currency;Amount;Reference;Name;Street;Number;Postcode;City;Country;DebtorAddressLine;DebtorAddressLine;DebtorAccount;ReferenceType;UltimateDebtorName;UltimateDebtorStreet;UltimateDebtorNumber;UltimateDebtorPostcode;UltimateDebtorTownName;UltimateDebtorCountry;UltimateDebtorAddressLine;UltimateDebtorAddressLine;RemittanceInformationText
        QRR;PO;$payee;CHF;33;;D009;Musterstrasse;1;1111;Musterstadt;CH;;;;NON;D009;Musterstrasse;1;1111;Musterstadt;CH;;;Taler-Demo
    """.trimIndent()
    runBlocking {
        doEbicsUploadTransaction(
            httpClient,
            getEbicsSubscriberDetails("postfinance"),
            EbicsUploadSpec(
                ebics3Service = Ebics3Request.OrderDetails.Service().apply {
                    serviceName = "OTH"
                    scope = "BIL"
                    serviceOption = "CH002LMF"
                    messageName = Ebics3Request.OrderDetails.Service.MessageName().apply {
                        value = "csv"
                    }
                },
                isEbics3 = true
            ),
            qrr.toByteArray(Charsets.UTF_8)
        )
    }
}

/**
 * Submits a pain.001 version 2019 message to the bank.
 *
 * Causes one DBIT payment to show up in the camt.054.  This one
 * however lacks the AcctSvcrRef, so other ways to pin it are needed.
 * Notably, EndToEndId is mandatory in pain.001 _and_ is controlled
 * by the sender.  Hence, the sender can itself ensure the EndToEndId
 * uniqueness.
 */
private fun uploadPain001Payment(
    subject: String,
    creditorIban: String = "CH9300762011623852957" // random creditor
) {
    transaction {
        addPaymentInitiation(
            Pain001Data(
                creditorIban = creditorIban,
                creditorBic = "POFICHBEXXX",
                creditorName = "Muster Frau",
                sum = "2",
                currency = "CHF",
                subject = subject,
                endToEndId = "Zufall"
            ),
            getBankAccount("foo")
        )
    }
    val ebicsConn = getConnectionPlugin("ebics")
    val httpClient = HttpClient()
    runBlocking { ebicsConn.submitPaymentInitiation(httpClient, 1L) }
}

class PostFinanceCommand : CliktCommand() {
    private val myIban by option(
        help = "IBAN as assigned by the PostFinance test platform."
    ).default("CH9789144829733648596")
    override fun run() { prepare(myIban) }
}
class Download : CliktCommand("Download the latest camt.054 from the bank") {
    // Ask 'notification' to the bank.
    override fun run() {
        // uploadPain001Payment("auto")
        downloadPayments()
    }
}

class Upload : CliktCommand("Upload a pain.001 to the bank") {
    private val subject by option(help = "Payment subject").default("Muster Zahlung")
    override fun run() { uploadPain001Payment(subject) }
}

class GenIncoming : CliktCommand("Uploads a CSV document to create one incoming payment") {
    override fun run() {
        val bankAccount = getBankAccount("foo")
        uploadQrrPayment(bankAccount.iban)
    }
}

private fun prepare(iban: String) {
    // Loads EBICS subscriber's keys from disk.
    // The keys should be found under libeufin-internal.git/convenience/
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
            // Hooks the PoFi details to the local bank account.
            // No need to run the canonical steps (creating account, downloading bank accounts, ..)
            fooBankAccount.defaultBankConnection = getBankConnection("postfinance")
            fooBankAccount.iban = iban
        }
    }
}
fun main(args: Array<String>) {
    PostFinanceCommand().subcommands(Download(), Upload(), GenIncoming()).main(args)
}