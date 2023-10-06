import io.ktor.http.*
import io.ktor.client.statement.*
import io.ktor.client.request.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import net.taler.wallet.crypto.Base32Crockford
import kotlin.test.assertEquals
import tech.libeufin.bank.*

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

/* ----- Body helper ----- */

inline fun <reified B> HttpRequestBuilder.jsonBody(b: B, deflate: Boolean = false) {
    val json = Json.encodeToString(kotlinx.serialization.serializer<B>(), b);
    contentType(ContentType.Application.Json)
    if (deflate) {
        headers.set("Content-Encoding", "deflate")
        setBody(deflater(json))
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