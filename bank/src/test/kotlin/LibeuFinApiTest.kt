import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.Test
import tech.libeufin.bank.Database
import tech.libeufin.bank.webApp

class LibeuFinApiTest {
    @Test
    fun createAccountTest() {
        testApplication {
            System.setProperty(
                "BANK_DB_CONNECTION_STRING",
                "jdbc:postgresql:///libeufincheck"
            )
            val db = Database("jdbc:postgresql:///libeufincheck")
            db.configSet("max_debt_ordinary_customers", "KUDOS:11")
            application(webApp)
            client.post("/test-json") {
                expectSuccess = true
                contentType(ContentType.Application.Json)
            }
        }
    }
}