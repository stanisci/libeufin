import io.ktor.http.*
import io.ktor.client.statement.*
import io.ktor.client.request.*
import io.ktor.server.testing.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import net.taler.wallet.crypto.Base32Crockford
import net.taler.common.errorcodes.TalerErrorCode
import kotlin.test.*
import tech.libeufin.bank.*
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import tech.libeufin.util.CryptoUtil
import tech.libeufin.util.*

/* ----- Setup ----- */

val customerMerchant = Customer(
    login = "merchant",
    passwordHash = CryptoUtil.hashpw("merchant-password"),
    name = "Merchant",
    phone = "+00",
    email = "merchant@libeufin-bank.com",
    cashoutPayto = "payto://external-IBAN",
    cashoutCurrency = "KUDOS"
)
val bankAccountMerchant = BankAccount(
    internalPaytoUri = IbanPayTo("payto://iban/MERCHANT-IBAN-XYZ"),
    lastNexusFetchRowId = 1L,
    owningCustomerId = 1L,
    hasDebt = false,
    maxDebt = TalerAmount(10, 1, "KUDOS"),
)
val customerExchange = Customer(
    login = "exchange",
    passwordHash = CryptoUtil.hashpw("exchange-password"),
    name = "Exchange",
    phone = "+00",
    email = "exchange@libeufin-bank.com",
    cashoutPayto = "payto://external-IBAN",
    cashoutCurrency = "KUDOS"
)
val bankAccountExchange = BankAccount(
    internalPaytoUri = IbanPayTo("payto://iban/EXCHANGE-IBAN-XYZ"),
    lastNexusFetchRowId = 1L,
    owningCustomerId = 2L,
    hasDebt = false,
    maxDebt = TalerAmount(10, 1, "KUDOS"),
    isTalerExchange = true
)

fun setup(
    conf: String = "test.conf",
    lambda: suspend (Database, BankApplicationContext) -> Unit
) {
    val config = talerConfig("conf/$conf")
    val dbCfg = config.loadDbConfig()
    resetDatabaseTables(dbCfg, "libeufin-bank")
    initializeDatabaseTables(dbCfg, "libeufin-bank")
    val ctx = config.loadBankApplicationContext()
    Database(dbCfg.dbConnStr, ctx.currency).use {
        runBlocking {
            lambda(it, ctx)
        }
    }
}

fun bankSetup(
    conf: String = "test.conf",    
    lambda: suspend ApplicationTestBuilder.(Database) -> Unit
) {
    setup(conf) { db, ctx -> 
        // Creating the exchange and merchant accounts first.
        assertNotNull(db.customerCreate(customerMerchant))
        assertNotNull(db.bankAccountCreate(bankAccountMerchant))
        assertNotNull(db.customerCreate(customerExchange))
        assertNotNull(db.bankAccountCreate(bankAccountExchange))
        // Create admin account
        assertNotNull(db.customerCreate(
            Customer(
                "admin",
                CryptoUtil.hashpw("admin-password"),
                "CFO"
            )
        ))
        assert(maybeCreateAdminAccount(db, ctx))
        testApplication {
            application {
                corebankWebApp(db, ctx)
            }
            lambda(db)
        }
    }
}

fun dbSetup(lambda: suspend (Database) -> Unit) {
    setup() { db, _ -> lambda(db) }
}

/* ----- Assert ----- */

fun HttpResponse.assertStatus(status: HttpStatusCode): HttpResponse {
    assertEquals(status, this.status);
    return this
}
fun HttpResponse.assertOk(): HttpResponse = assertStatus(HttpStatusCode.OK)
fun HttpResponse.assertCreated(): HttpResponse = assertStatus(HttpStatusCode.Created)
fun HttpResponse.assertNoContent(): HttpResponse = assertStatus(HttpStatusCode.NoContent)
fun HttpResponse.assertNotFound(): HttpResponse = assertStatus(HttpStatusCode.NotFound)
fun HttpResponse.assertUnauthorized(): HttpResponse = assertStatus(HttpStatusCode.Unauthorized)
fun HttpResponse.assertConflict(): HttpResponse = assertStatus(HttpStatusCode.Conflict)
fun HttpResponse.assertBadRequest(): HttpResponse = assertStatus(HttpStatusCode.BadRequest)
fun HttpResponse.assertForbidden(): HttpResponse = assertStatus(HttpStatusCode.Forbidden)


suspend fun HttpResponse.assertErr(code: TalerErrorCode): HttpResponse {
    val err = Json.decodeFromString<TalerError>(bodyAsText())
    assertEquals(code.code, err.code)
    return this
}

fun BankTransactionResult.assertSuccess() {
    assertEquals(BankTransactionResult.SUCCESS, this)
}

suspend fun assertTime(min: Int, max: Int, lambda: suspend () -> Unit) {
    val start = System.currentTimeMillis()
    lambda()
    val end = System.currentTimeMillis()
    val time = end - start
    assert(time >= min) { "Expected to last at least $min ms, lasted $time" }
    assert(time <= max) { "Expected to last at most $max ms, lasted $time" }
}

fun assertException(msg: String, lambda: () -> Unit) {
    try {
        lambda()
        throw Exception("Expected failure")
    } catch (e: Exception) {
        assert(e.message!!.startsWith(msg)) { "${e.message}" }
    }
}

/* ----- Body helper ----- */

inline fun <reified B> HttpRequestBuilder.jsonBody(b: B, deflate: Boolean = false) {
    val json = Json.encodeToString(kotlinx.serialization.serializer<B>(), b);
    contentType(ContentType.Application.Json)
    if (deflate) {
        headers.set("Content-Encoding", "deflate")
        val bos = ByteArrayOutputStream()
        val ios = DeflaterOutputStream(bos)
        ios.write(json.toByteArray())
        ios.finish()
        setBody(bos.toByteArray())
    } else {
        setBody(json)
    }
}

/* ----- Json DSL ----- */

inline fun json(from: JsonObject = JsonObject(emptyMap()), builderAction: JsonBuilder.() -> Unit): JsonObject {
    val builder = JsonBuilder(from)
    builder.apply(builderAction)
    return JsonObject(builder.content)
}

class JsonBuilder(from: JsonObject) {
    val content: MutableMap<String, JsonElement> = from.toMutableMap()

    infix inline fun <reified T> String.to(v: T) {
        val json = Json.encodeToJsonElement(kotlinx.serialization.serializer<T>(), v);
        content.put(this, json)
    }
}

/* ----- Random data generation ----- */

fun randBase32Crockford(lenght: Int): String {
    val bytes = ByteArray(lenght)
    kotlin.random.Random.nextBytes(bytes)
    return Base32Crockford.encode(bytes)
}

fun randHashCode(): HashCode = HashCode(randBase32Crockford(64))
fun randShortHashCode(): ShortHashCode = ShortHashCode(randBase32Crockford(32))
fun randEddsaPublicKey(): EddsaPublicKey = EddsaPublicKey(randBase32Crockford(32))