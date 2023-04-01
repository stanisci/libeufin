import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.testing.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Test
import tech.libeufin.nexus.*
import tech.libeufin.nexus.bankaccount.addPaymentInitiation
import tech.libeufin.nexus.bankaccount.ingestBankMessagesIntoAccount
import tech.libeufin.nexus.iso20022.CamtBankAccountEntry
import tech.libeufin.nexus.server.*
import tech.libeufin.nexus.xlibeufinbank.XlibeufinBankConnectionProtocol
import tech.libeufin.sandbox.BankAccountTransactionEntity
import tech.libeufin.sandbox.BankAccountTransactionsTable
import tech.libeufin.sandbox.sandboxApp
import tech.libeufin.sandbox.wireTransfer
import tech.libeufin.util.XLibeufinBankTransaction
import tech.libeufin.util.getIban
import java.net.URL

// Testing the x-libeufin-bank communication

class XLibeufinBankTest {
    private val mapper = jacksonObjectMapper()
    @Test
    fun urlParse() {
        val u = URL("http://localhost")
        println(u.authority)
    }

    /**
     * This test tries to submit a transaction to Sandbox
     * via the x-libeufin-bank connection and later - after
     * having downloaded its transactions - tries to reconcile
     * it as sent.
     */
    @Test
    fun submitTransaction() {
        withTestDatabase {
            prepSandboxDb()
            prepNexusDb()
            testApplication {
                application(sandboxApp)
                val pId = addPaymentInitiation(
                    Pain001Data(
                        creditorIban = FOO_USER_IBAN,
                        creditorBic = "SANDBOXX",
                        creditorName = "Tester",
                        subject = "test payment",
                        sum = "1",
                        currency = "TESTKUDOS"
                    ),
                    transaction {
                        NexusBankAccountEntity.findByName("bar") ?:
                        throw Exception("Test failed, env didn't provide Nexus bank account 'bar'")
                    }
                )
                val conn = XlibeufinBankConnectionProtocol()
                conn.submitPaymentInitiation(this.client, pId.id.value)
                val maybeArrivedPayment = transaction {
                    BankAccountTransactionEntity.find {
                        BankAccountTransactionsTable.pmtInfId eq pId.paymentInformationId
                    }.firstOrNull()
                }
                // Now look for the payment in the database.
                assert(maybeArrivedPayment != null)
            }
        }
    }
    /**
     * Testing that Nexus downloads one transaction from
     * Sandbox via the x-libeufin-bank protocol supplier
     * and stores it in the Nexus internal transactions
     * table.
     *
     * NOTE: the test should be extended by checking that
     * downloading twice the transaction doesn't lead to asset
     * duplication locally in Nexus.
     */
    @Test
    fun fetchTransaction() {
        withTestDatabase {
            prepSandboxDb()
            prepNexusDb()
            testApplication {
                // Creating the Sandbox transaction that's expected to be ingested.
                wireTransfer(
                    debitAccount = "bar",
                    creditAccount = "foo",
                    demobank = "default",
                    subject = "x-libeufin-bank test transaction",
                    amount = "TESTKUDOS:333"
                )
                val fooUser = getNexusUser("foo")
                // Creating the x-libeufin-bank connection to interact with Sandbox.
                val conn = XlibeufinBankConnectionProtocol()
                val jDetails = """{
                    "username": "foo",
                    "password": "foo",
                    "baseUrl": "http://localhost/demobanks/default/access-api"
                    }""".trimIndent()
                conn.createConnection(
                    connId = "x",
                    user = fooUser,
                    data = mapper.readTree(jDetails)
                )
                // Starting _Sandbox_ to check how it reacts to Nexus request.
                application(sandboxApp)
                /**
                 * Doing two rounds of download: the first is expected to
                 * record the payment as new, and the second is expected to
                 * ignore it because it has already it in the database.
                 */
                repeat(2) {
                    // Invoke transaction fetcher offered by the x-libeufin-bank connection.
                    conn.fetchTransactions(
                        fetchSpec = FetchSpecAllJson(
                            FetchLevel.STATEMENT,
                            null
                        ),
                        accountId = "foo",
                        bankConnectionId = "x",
                        client = client
                    )
                }
                // The messages are in the database now, invoke the
                // ingestion routine to parse them into the Nexus internal
                // format.
                ingestBankMessagesIntoAccount("x", "foo")
                // Asserting that the payment made it to the database in the Nexus format.
                transaction {
                    val maybeTx = NexusBankTransactionEntity.all()
                    // This assertion checks that the payment is not doubled in the database:
                    assert(maybeTx.count() == 1L)
                    val tx = maybeTx.first().parseDetailsIntoObject<CamtBankAccountEntry>()
                    assert(tx.getSingletonSubject() == "x-libeufin-bank test transaction")
                }
            }
        }
    }

    // Testing that Nexus responds with correct connection details.
    // Currently only testing that the request doesn't throw any error.
    @Test
    fun connectionDetails() {
        withTestDatabase {
            prepNexusDb()
            testApplication {
                application(nexusApp)
                val r = client.get("/bank-connections/bar") {
                    basicAuth("bar", "bar")
                    expectSuccess = true
                }
                println(r.bodyAsText())
            }
        }
    }
}