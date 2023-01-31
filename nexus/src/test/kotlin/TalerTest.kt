import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Ignore
import org.junit.Test
import tech.libeufin.nexus.*
import tech.libeufin.nexus.bankaccount.fetchBankAccountTransactions
import tech.libeufin.nexus.server.FetchLevel
import tech.libeufin.nexus.server.FetchSpecAllJson
import tech.libeufin.nexus.server.client
import tech.libeufin.nexus.server.nexusApp
import tech.libeufin.sandbox.sandboxApp
import tech.libeufin.sandbox.wireTransfer

// This class tests the features related to the Taler facade.
class TalerTest {

    @Ignore // Ignoring because no assert takes place.
    @Test // Triggering a refund because of a duplicate reserve pub.
    fun refundTest() {
        withNexusAndSandboxUser {
            // Creating a Taler facade for the user 'foo'.
            testApplication {
                application(nexusApp)
                client.post("/facades") {
                    expectSuccess = true
                    contentType(ContentType.Application.Json)
                    basicAuth("foo", "foo")
                    setBody("""
                        { "name":"foo-facade",
                          "type":"taler-wire-gateway",
                          "config": {
                            "bankAccount":"foo",
                            "bankConnection":"foo",
                            "currency":"TESTKUDOS",
                            "reserveTransferLevel":"report"
                          }
                    }""".trimIndent()
                    )
                }
            }
            wireTransfer(
                "bar",
                "foo",
                demobank = "default",
                "5WFM8PXN7Y315RVZFJ280299B94W1HR1AAHH6XNDYEJBC0T3E5N0",
                "TESTKUDOS:3"
            )
            testApplication {
                application(sandboxApp)
                // Nexus downloads the fresh transaction.
                fetchBankAccountTransactions(
                    client,
                    fetchSpec = FetchSpecAllJson(
                        level = FetchLevel.REPORT,
                        "foo"
                    ),
                    "foo"
                )
            }
            wireTransfer(
                "bar",
                "foo",
                demobank = "default",
                "5WFM8PXN7Y315RVZFJ280299B94W1HR1AAHH6XNDYEJBC0T3E5N0",
                "TESTKUDOS:3"
            )
            testApplication {
                application(sandboxApp)
                // Nexus downloads the new transaction, having a duplicate subject.
                fetchBankAccountTransactions(
                    client,
                    fetchSpec = FetchSpecAllJson(
                        level = FetchLevel.REPORT,
                        "foo"
                    ),
                    "foo"
                )
            }
        }
    }
}