import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
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
    private val customerBar = Customer(
        login = "bar",
        passwordHash = CryptoUtil.hashpw("pw"),
        name = "Bar",
        phone = "+99",
        email = "bar@example.com",
        cashoutPayto = "payto://external-IBAN",
        cashoutCurrency = "KUDOS"
    )

    private fun genBankAccount(rowId: Long) = BankAccount(
        hasDebt = false,
        internalPaytoUri = "payto://iban/SANDBOXX/${rowId}-IBAN",
        maxDebt = TalerAmount(100, 0, "KUDOS"),
        owningCustomerId = rowId
    )

    @Test
    fun getConfig() {
        val db = initDb()
        testApplication {
            application { corebankWebApp(db) }
            val r = client.get("/config") {
                expectSuccess = true
            }
            println(r.bodyAsText())
        }
    }
    /**
     * Testing GET /transactions.  This test checks that the sign
     * of delta gets honored by the HTTP handler, namely that the
     * records appear in ASC or DESC order, according to the sign
     * of delta.
     */
    @Test
    fun testHistory() {
        val db = initDb()
        val fooId = db.customerCreate(customerFoo); assert(fooId != null)
        assert(db.bankAccountCreate(genBankAccount(fooId!!)) != null)
        val barId = db.customerCreate(customerBar); assert(barId != null)
        assert(db.bankAccountCreate(genBankAccount(barId!!)) != null)
        for (i in 1..10) { db.bankTransactionCreate(genTx("test-$i")) }
        testApplication {
            application {
                corebankWebApp(db)
            }
            val asc = client.get("/accounts/foo/transactions?delta=2") {
                basicAuth("foo", "pw")
                expectSuccess = true
            }
            var obj = Json.decodeFromString<BankAccountTransactionsResponse>(asc.bodyAsText())
            assert(obj.transactions.size == 2)
            assert(obj.transactions[0].row_id < obj.transactions[1].row_id)
            val desc = client.get("/accounts/foo/transactions?delta=-2") {
                basicAuth("foo", "pw")
                expectSuccess = true
            }
            obj = Json.decodeFromString(desc.bodyAsText())
            assert(obj.transactions.size == 2)
            assert(obj.transactions[0].row_id > obj.transactions[1].row_id)
        }
    }

    // Testing the creation of bank transactions.
    @Test
    fun postTransactionsTest() {
        val db = initDb()
        // foo account
        val fooId = db.customerCreate(customerFoo); assert(fooId != null)
        assert(db.bankAccountCreate(genBankAccount(fooId!!)) != null)
        // bar account
        val barId = db.customerCreate(customerBar); assert(barId != null)
        assert(db.bankAccountCreate(genBankAccount(barId!!)) != null)
        // accounts exist, now create one transaction.
        testApplication {
            application {
                corebankWebApp(db)
            }
            client.post("/accounts/foo/transactions") {
                expectSuccess = true
                basicAuth("foo", "pw")
                contentType(ContentType.Application.Json)
                // expectSuccess = true
                setBody("""{
                    "payto_uri": "payto://iban/SANDBOXX/${barId}-IBAN?message=payout", 
                    "amount": "KUDOS:3.3"
                }
                """.trimIndent())
            }
            // Getting the only tx that exists in the DB, hence has ID == 1.
            val r = client.get("/accounts/foo/transactions/1") {
                basicAuth("foo", "pw")
                expectSuccess = true
            }
            val obj: BankAccountTransactionInfo = Json.decodeFromString(r.bodyAsText())
            assert(obj.subject == "payout")
        }
    }
    // Checking the POST /token handling.
    @Test
    fun tokenTest() {
        val db = initDb()
        assert(db.customerCreate(customerFoo) != null)
        testApplication {
            application {
                corebankWebApp(db)
            }
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
                maxDebt = TalerAmount(100, 0, "KUDOS"),
                owningCustomerId = customerRowId!!
            )
        ) != null)
        testApplication {
            application {
                corebankWebApp(db)
            }
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
                maxDebt = TalerAmount(100, 0, "KUDOS"),
                owningCustomerId = adminRowId!!
            )) != null)
            client.get("/accounts/foo") {
                expectSuccess = true
                basicAuth("admin", "admin")
            }
            val shouldNot = client.get("/accounts/foo") {
                basicAuth("not", "not")
                expectSuccess = false
            }
            assert(shouldNot.status == HttpStatusCode.NotFound)
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
            application {
                corebankWebApp(db)
            }
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
