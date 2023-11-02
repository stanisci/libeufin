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

fun setup(
    conf: String = "test.conf",
    lambda: suspend (Database, BankConfig) -> Unit
) {
    val config = talerConfig("conf/$conf")
    val dbCfg = config.loadDbConfig()
    resetDatabaseTables(dbCfg, "libeufin-bank")
    initializeDatabaseTables(dbCfg, "libeufin-bank")
    val ctx = config.loadBankConfig()
    Database(dbCfg.dbConnStr, ctx.currency, ctx.fiatCurrency).use {
        runBlocking {
            ctx.conversionInfo?.run { it.conversionUpdateConfig(this) }
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
        assertEquals(CustomerCreationResult.SUCCESS, db.accountCreate(
            login = "merchant",
            password = "merchant-password",
            name = "Merchant",
            internalPaytoUri = IbanPayTo("payto://iban/MERCHANT-IBAN-XYZ"),
            maxDebt = TalerAmount(10, 0, "KUDOS"),
            isTalerExchange = false,
            isPublic = false,
            bonus = null
        ))
        assertEquals(CustomerCreationResult.SUCCESS, db.accountCreate(
            login = "exchange",
            password = "exchange-password",
            name = "Exchange",
            internalPaytoUri = IbanPayTo("payto://iban/EXCHANGE-IBAN-XYZ"),
            maxDebt = TalerAmount(10, 0, "KUDOS"),
            isTalerExchange = true,
            isPublic = false,
            bonus = null
        ))
        assertEquals(CustomerCreationResult.SUCCESS, db.accountCreate(
            login = "customer",
            password = "customer-password",
            name = "Customer",
            internalPaytoUri = IbanPayTo("payto://iban/CUSTOMER-IBAN-XYZ"),
            maxDebt = TalerAmount(10, 0, "KUDOS"),
            isTalerExchange = false,
            isPublic = false,
            bonus = null
        ))
        // Create admin account
        assert(maybeCreateAdminAccount(db, ctx, "admin-password"))
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

/* ----- Common actions ----- */

suspend fun ApplicationTestBuilder.setMaxDebt(account: String, maxDebt: TalerAmount) {
    client.patch("/accounts/$account") { 
        basicAuth("admin", "admin-password")
        jsonBody {  "debit_threshold" to maxDebt  }
    }.assertNoContent()
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
    val err = json<TalerError>()
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

inline suspend fun HttpRequestBuilder.jsonBody(
    from: JsonObject = JsonObject(emptyMap()), 
    deflate: Boolean = false, 
    builderAction: JsonBuilder.() -> Unit
) {
    jsonBody(json(from, builderAction), deflate)
}

inline suspend fun <reified B> HttpResponse.json(): B =
    Json.decodeFromString(kotlinx.serialization.serializer<B>(), bodyAsText())

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

fun randBytes(lenght: Int): ByteArray {
    val bytes = ByteArray(lenght)
    kotlin.random.Random.nextBytes(bytes)
    return bytes
}

fun randBase32Crockford(lenght: Int) = Base32Crockford.encode(randBytes(lenght))

fun randHashCode(): HashCode = HashCode(randBase32Crockford(64))
fun randShortHashCode(): ShortHashCode = ShortHashCode(randBase32Crockford(32))
fun randEddsaPublicKey(): EddsaPublicKey = EddsaPublicKey(randBase32Crockford(32))