import io.ktor.http.*
import io.ktor.client.statement.*
import io.ktor.client.request.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import net.taler.wallet.crypto.Base32Crockford
import kotlin.test.assertEquals
import tech.libeufin.bank.*
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream

/* ----- Setup ----- */

fun setup(
    conf: String = "test.conf",
    lambda: suspend (Database, BankApplicationContext) -> Unit
){
    val config = TalerConfig(BANK_CONFIG_SOURCE)
    config.load("conf/$conf")
    val dbConnStr = config.requireString("libeufin-bankdb-postgres", "config")
    val sqlPath = config.requirePath("libeufin-bankdb-postgres", "SQL_DIR")
    resetDatabaseTables(dbConnStr, sqlPath)
    initializeDatabaseTables(dbConnStr, sqlPath)
    val ctx = BankApplicationContext.readFromConfig(config)
    Database(dbConnStr, ctx.currency).use {
        runBlocking {
            lambda(it, ctx)
        }
    }
}

fun setupDb(lambda: suspend (Database) -> Unit) {
    setup() { db, _ -> lambda(db) }
}

/* ----- Assert ----- */

fun HttpResponse.assertStatus(status: HttpStatusCode): HttpResponse {
    assertEquals(status, this.status);
    return this
}
fun HttpResponse.assertOk(): HttpResponse = assertStatus(HttpStatusCode.OK)
fun HttpResponse.assertBadRequest(): HttpResponse = assertStatus(HttpStatusCode.BadRequest)

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
        assertEquals(msg, e.message)
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

fun randHashCode(): HashCode {
    return HashCode(randBase32Crockford(64))
}

fun randShortHashCode(): ShortHashCode {
    return ShortHashCode(randBase32Crockford(32))
}

fun randEddsaPublicKey(): EddsaPublicKey {
    return EddsaPublicKey(randBase32Crockford(32))
}