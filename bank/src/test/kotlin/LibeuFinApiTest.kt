import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.Test
import tech.libeufin.bank.*
import tech.libeufin.util.CryptoUtil
import tech.libeufin.util.execCommand

class LibeuFinApiTest {
    fun initDb(): Database {
        System.setProperty(
            "BANK_DB_CONNECTION_STRING",
            "jdbc:postgresql:///libeufincheck"
        )
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

    /**
     * Testing the account creation, its idempotency and
     * the restriction to admin to create accounts.
     */
    @Test
    fun createAccountTest() {
        testApplication {
            val db = initDb()
            val ibanPayto = genIbanPaytoUri()
            // Bank needs that to operate:
            db.configSet("max_debt_ordinary_customers", "KUDOS:11")
            application(webApp)
            var resp = client.post("/accounts") {
                expectSuccess = false
                contentType(ContentType.Application.Json)
                setBody("""{
                    "username": "foo",
                    "password": "bar",
                    "name": "Jane",
                    "internal_payto_uri": "$ibanPayto"
                }""".trimIndent())
            }
            assert(resp.status == HttpStatusCode.Created)
            // Testing idempotency.
            resp = client.post("/accounts") {
                expectSuccess = false
                contentType(ContentType.Application.Json)
                setBody("""{
                    "username": "foo",
                    "password": "bar",
                    "name": "Jane",
                    "internal_payto_uri": "$ibanPayto"
                }""".trimIndent())
            }
            assert(resp.status == HttpStatusCode.Created)
            // Creating the administrator.
            db.customerCreate(Customer(
                "admin",
                CryptoUtil.hashpw("pass"),
                "CFO"
            ))
            db.configSet("only_admin_registrations", "yes")
            // Ordinary user tries, should fail.
            resp = client.post("/accounts") {
                expectSuccess = false
                basicAuth("foo", "bar")
                contentType(ContentType.Application.Json)
                setBody("""{
                    "username": "baz",
                    "password": "xyz",
                    "name": "Mallory"
                }""".trimIndent())
            }
            assert(resp.status == HttpStatusCode.Unauthorized)
            // admin tries, should succeed
            resp = client.post("/accounts") {
                expectSuccess = false
                basicAuth("admin", "pass")
                contentType(ContentType.Application.Json)
                setBody("""{
                    "username": "baz",
                    "password": "xyz",
                    "name": "Mallory"
                }""".trimIndent())
            }
            assert(resp.status == HttpStatusCode.Created)
        }
    }
}