import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.Test
import tech.libeufin.bank.Database
import tech.libeufin.bank.RegisterAccountRequest
import tech.libeufin.bank.webApp
import tech.libeufin.util.execCommand

class LibeuFinApiTest {
    fun initDb(): Database {
        execCommand(
            listOf(
                "libeufin-bank-dbinit",
                "-d",
                "libeufincheck",
                "-r"
            ),
            throwIfFails = true
        )
        val db = Database("jdbc:postgresql:///libeufincheck")
        return db
    }
    @Test
    fun createAccountTest() {
        testApplication {
            System.setProperty(
                "BANK_DB_CONNECTION_STRING",
                "jdbc:postgresql:///libeufincheck"
            )
            val db = initDb()
            db.configSet("max_debt_ordinary_customers", "KUDOS:11")
            db.configSet("only_admin_registrations", "yes")
            application(webApp)
            client.post("/accounts") {
                contentType(ContentType.Application.Json)
                basicAuth("admin", "bar")
                setBody("""{
                    "username": "foo",
                    "password": "bar",
                    "name": "Jane"
                }""".trimIndent())
            }
        }
    }
}