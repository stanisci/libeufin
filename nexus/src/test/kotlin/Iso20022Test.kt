package tech.libeufin.nexus
import CamtBankAccountEntry
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Ignore
import org.junit.Test
import org.w3c.dom.Document
import poFiCamt052
import poFiCamt054_2019_incoming
import poFiCamt054_2019_outgoing
import prepNexusDb
import tech.libeufin.nexus.bankaccount.getBankAccount
import tech.libeufin.nexus.iso20022.*
import tech.libeufin.nexus.server.EbicsDialects
import tech.libeufin.nexus.server.FetchLevel
import tech.libeufin.nexus.server.getBankConnection
import tech.libeufin.nexus.server.nexusApp
import tech.libeufin.util.DestructionError
import tech.libeufin.util.XMLUtil
import tech.libeufin.util.destructXml
import tech.libeufin.util.getNow
import withTestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

fun loadXmlResource(name: String): Document {
    val classLoader = ClassLoader.getSystemClassLoader()
    val res = classLoader.getResource(name)
    if (res == null) {
        throw Exception("resource $name not found");
    }
    return XMLUtil.parseStringIntoDom(res.readText())
}

class Iso20022Test {
    @Test(expected = DestructionError::class)
    fun testUniqueChild() {
        val xml = """
            <a>
              <b/>
              <b/>
            </a>
        """.trimIndent()
        // when XML is invalid, DestructionError is thrown.
        val doc = XMLUtil.parseStringIntoDom(xml)
        destructXml(doc) {
            requireRootElement("a") {
                requireOnlyChild {  }
            }
        }
    }

    /**
     * This test is currently ignored because the Camt sample being parsed
     * contains a money movement which is not a singleton.  This is not in
     * line with the current parsing logic (that expects the style used by GLS)
     */
    @Ignore
    fun testTransactionsImport() {
        val camt53 = loadXmlResource("iso20022-samples/camt.053/de.camt.053.001.02.xml")
        val r = parseCamtMessage(camt53)
        assertEquals("msg-001", r.messageId)
        assertEquals("2020-07-03T12:44:40+05:30", r.creationDateTime)
        assertEquals(CashManagementResponseType.Statement, r.messageType)
        assertEquals(1, r.reports.size)

        // First Entry
        assertTrue("100" == r.reports[0].entries[0].amount.value)
        assertEquals("EUR", r.reports[0].entries[0].amount.currency)
        assertEquals(CreditDebitIndicator.CRDT, r.reports[0].entries[0].creditDebitIndicator)
        assertEquals(EntryStatus.BOOK, r.reports[0].entries[0].status)
        assertEquals(null, r.reports[0].entries[0].entryRef)
        assertEquals("acctsvcrref-001", r.reports[0].entries[0].accountServicerRef)
        assertEquals("PMNT-RCDT-ESCT", r.reports[0].entries[0].bankTransactionCode)
        assertNotNull(r.reports[0].entries[0].batches?.get(0))
        assertEquals(
            "unstructured info one",
            r.reports[0].entries[0].batches?.get(0)?.batchTransactions?.get(0)?.details?.unstructuredRemittanceInformation
        )

        // Second Entry
        assertEquals(
            "unstructured info across lines",
            r.reports[0].entries[1].batches?.get(0)?.batchTransactions?.get(0)?.details?.unstructuredRemittanceInformation
        )

        // Third Entry
        // Make sure that round-tripping of entry CamtBankAccountEntry JSON works
        for (entry in r.reports.flatMap { it.entries }) {
            val txStr = jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(entry)
            val tx2 = jacksonObjectMapper().readValue(txStr, CamtBankAccountEntry::class.java)
            val tx2Str = jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(tx2)
            assertEquals(jacksonObjectMapper().readTree(txStr), jacksonObjectMapper().readTree(tx2Str))
        }

        println(jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(r))
    }

    /**
     * PoFi timestamps aren't zoned, therefore the usual ZonedDateTime
     * doesn't cover it.  They must switch to (java.time.)LocalDateTime.
     */
    @Test
    fun parsePostFinanceDate() {
        // 2011-12-03T10:15:30 from Java Doc as ISO_LOCAL_DATE_TIME.
        // 2023-05-09T11:04:09 from PoFi

        getTimestampInMillis(
            "2011-12-03T10:15:30",
            EbicsDialects.POSTFINANCE.dialectName
        )
        getTimestampInMillis(
            "2011-12-03T10:15:30Z" // ! with timezone
        )
    }

    @Test
    fun parsePoFiCamt054() {
        val doc = XMLUtil.parseStringIntoDom(poFiCamt054_2019_incoming)
        parseCamtMessage(doc, dialect = "pf")
    }

    /**
     * Testing how outgoing payments get ingested and how their
     * deduplication logic reacts, given that sometimes camt.054
     * was seen without the AcctSvcrRef.
     */
    @Test
    fun ingestPoFiCamt054_outgoing() {
        val doc = XMLUtil.parseStringIntoDom(poFiCamt054_2019_outgoing)
        withTestDatabase {
            prepNexusDb()
            transaction { assert(NexusBankTransactionEntity.all().count() == 0L) }
            ingestCamtMessageIntoAccount(
                "foo",
                doc,
                FetchLevel.NOTIFICATION,
                dialect = "pf"
            )
            transaction { assert(NexusBankTransactionEntity.all().count() == 1L) }
            // Checking that the payment doesn't get stored twice.
            ingestCamtMessageIntoAccount(
                "foo",
                doc,
                FetchLevel.NOTIFICATION,
                dialect = "pf"
            )
            transaction { assert(NexusBankTransactionEntity.all().count() == 1L) }
        }
    }

    @Test
    fun ingestPoFiCamt054() {
        val doc = XMLUtil.parseStringIntoDom(poFiCamt054_2019_incoming)
        withTestDatabase {
            prepNexusDb()
            // Checking that no transactions exist already in the database.
            transaction { assert(NexusBankTransactionEntity.all().count() == 0L) }
            ingestCamtMessageIntoAccount(
                "foo",
                doc,
                FetchLevel.NOTIFICATION,
                dialect = "pf"
            )
            // Checking that now ONE transaction exist in the database.
            transaction { assert(NexusBankTransactionEntity.all().count() == 1L) }
            // Checking now that the same payment doesn't get ingested twice.
            ingestCamtMessageIntoAccount(
                "foo",
                doc,
                FetchLevel.NOTIFICATION,
                dialect = "pf"
            )
            // The count should have stayed the same.
            transaction { assert(NexusBankTransactionEntity.all().count() == 1L) }
        }
    }
    // Checks that the 2019 pain.001 version validates.
    @Test
    fun validatePain001() {
        val pain001 = createPain001document(
            NexusPaymentInitiationData(
                debtorIban = "CH0889144371988976754",
                debtorBic = "POFICHBEXXX",
                debtorName = "Sample Debtor Name",
                currency = "CHF",
                amount = "5.00",
                creditorIban = "CH9789144829733648596",
                creditorName = "Sample Creditor Name",
                creditorBic = "POFICHBEXXX",
                paymentInformationId = "8aae7a2ded2f",
                preparationTimestamp = getNow().toInstant().toEpochMilli(),
                subject = "Unstructured remittance information",
                instructionId = "InstructionId",
                endToEndId = "71cfbdaf901f",
                messageId = "2a16b35ed69c"
            ),
            dialect = "pf"
        )
        val doc = XMLUtil.parseStringIntoDom(pain001)
        assert(XMLUtil.validateFromDom(doc))
    }

    @Test
    fun parsePostFinanceCamt052() {
        withTestDatabase {
            prepNexusDb()
            // Adjusting the MakeEnv.kt values to PoFi
            val fooBankAccount = getBankAccount("foo")
            val fooConnection = getBankConnection("foo")
            transaction {
                fooBankAccount.iban = "CH9789144829733648596"
                fooConnection.dialect = "pf"
            }
            testApplication {
                application(nexusApp)
                client.post("/bank-accounts/foo/test-camt-ingestion/C52") {
                    basicAuth("foo", "foo")
                    contentType(ContentType.Application.Xml)
                    setBody(poFiCamt052)
                }
            }
        }
    }
}
