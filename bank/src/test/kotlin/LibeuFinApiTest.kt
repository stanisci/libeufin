import io.ktor.auth.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.netty.handler.codec.http.HttpResponseStatus
import kotlinx.serialization.json.Json
import net.taler.wallet.crypto.Base32Crockford
import org.junit.Test
import tech.libeufin.bank.*
import tech.libeufin.util.CryptoUtil
import tech.libeufin.util.getNowUs
import java.time.Duration
import kotlin.random.Random

class LibeuFinApiTest {
    private val customerFoo = Customer(
    login = "foo",
    passwordHash = CryptoUtil.hashpw("pw"),
    name = "Foo",
    phone = "+00",
    email = "foo@b.ar",
    cashoutPayto = "payto://external-IBAN",
    cashoutCurrency = "KUDOS"
    )
    // Checking the POST /token handling.
    @Test
    fun tokenTest() {
        val db = initDb()
        assert(db.customerCreate(customerFoo) != null)
        testApplication {
            application(webApp)
            client.post("/accounts/foo/token") {
                expectSuccess = true
                contentType(ContentType.Application.Json)
                basicAuth("foo", "pw")
                setBody("""
                    {"scope": "readonly"}
                """.trimIndent())
            }
            // foo tries on bar endpoint
            val r = client.post("/accounts/bar/token") {
                expectSuccess = false
                basicAuth("foo", "pw")
            }
            assert(r.status == HttpStatusCode.Forbidden)
            // Make ad-hoc token for foo.
            val fooTok = ByteArray(32).apply { Random.nextBytes(this) }
            assert(db.bearerTokenCreate(BearerToken(
                content = fooTok,
                bankCustomer = 1L, // only foo exists.
                scope = TokenScope.readonly,
                creationTime = getNowUs(),
                isRefreshable = true,
                expirationTime = getNowUs() + (Duration.ofHours(1).toMillis() * 1000)
            )))
            // Testing the bearer-token:-scheme.
            client.post("/accounts/foo/token") {
                headers.set("Authorization", "Bearer bearer-token:${Base32Crockford.encode(fooTok)}")
                contentType(ContentType.Application.Json)
                setBody("{\"scope\": \"readonly\"}")
                expectSuccess = true
            }
        }
    }

    /**
     * Testing the retrieval of account information.
     * The tested logic is the one usually needed by SPAs
     * to show customers their status.
     */
    @Test
    fun getAccountTest() {
        // Artificially insert a customer and bank account in the database.
        val db = initDb()
        val customerRowId = db.customerCreate(Customer(
            "foo",
            CryptoUtil.hashpw("pw"),
            "Foo"
        ))
        assert(customerRowId != null)
        assert(db.bankAccountCreate(
            BankAccount(
                hasDebt = false,
                internalPaytoUri = "payto://iban/SANDBOXX/FOO-IBAN",
                maxDebt = TalerAmount(100, 0),
                owningCustomerId = customerRowId!!
            )
        ))
        testApplication {
            application(webApp)
            val r = client.get("/accounts/foo") {
                expectSuccess = true
                basicAuth("foo", "pw")
            }
            val obj: AccountData = Json.decodeFromString(r.bodyAsText())
            assert(obj.name == "Foo")
            // Checking admin can.
            val adminRowId = db.customerCreate(Customer(
                "admin",
                CryptoUtil.hashpw("admin"),
                "Admin"
            ))
            assert(adminRowId != null)
            assert(db.bankAccountCreate(BankAccount(
                hasDebt = false,
                internalPaytoUri = "payto://iban/SANDBOXX/ADMIN-IBAN",
                maxDebt = TalerAmount(100, 0),
                owningCustomerId = adminRowId!!
            )))
            client.get("/accounts/foo") {
                expectSuccess = true
                basicAuth("admin", "admin")
            }
            val shouldNot = client.get("/accounts/foo") {
                basicAuth("not", "not")
                expectSuccess = false
            }
            assert(shouldNot.status == HttpStatusCode.Unauthorized)
        }
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
            // Bank needs those to operate:
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