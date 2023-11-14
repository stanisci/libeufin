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
import java.io.File
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
            ctx.conversionInfo?.run { it.conversion.updateConfig(this) }
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

suspend fun ApplicationTestBuilder.assertBalance(account: String, info: CreditDebitInfo, amount: String) {
    client.get("/accounts/$account") { 
        basicAuth("admin", "admin-password")
    }.assertOk().run { 
        val balance = json<AccountData>().balance;
        assertEquals(info, balance.credit_debit_indicator)
        assertEquals(TalerAmount(amount), balance.amount)
    }
}

suspend fun ApplicationTestBuilder.tx(from: String, amount: String, to: String): Long {
    return client.post("/accounts/$from/transactions") {
        basicAuth("$from", "$from-password")
        jsonBody {
            "payto_uri" to "payto://iban/$to-IBAN-XYZ?message=tx&amount=$amount"
        }
    }.assertOk().run {
        json<TransactionCreateResponse>().row_id
    }
}

suspend fun ApplicationTestBuilder.transfer(amount: String) {
    client.post("/accounts/exchange/taler-wire-gateway/transfer") {
        basicAuth("exchange", "exchange-password")
        jsonBody {
            "request_uid" to randHashCode()
            "amount" to TalerAmount(amount)
            "exchange_base_url" to "http://exchange.example.com/"
            "wtid" to randShortHashCode()
            "credit_account" to "payto://iban/MERCHANT-IBAN-XYZ"
        }
    }.assertOk()
}

suspend fun ApplicationTestBuilder.addIncoming(amount: String) {
    client.post("/accounts/exchange/taler-wire-gateway/admin/add-incoming") {
        basicAuth("admin", "admin-password")
        jsonBody {
            "amount" to TalerAmount(amount)
            "reserve_pub" to randEddsaPublicKey()
            "debit_account" to "payto://iban/MERCHANT-IBAN-XYZ"
        }
    }.assertOk()
}

suspend fun ApplicationTestBuilder.cashout(amount: String) {
    client.post("/accounts/customer/cashouts") {
        basicAuth("customer", "customer-password")
        jsonBody(json {
            "request_uid" to randShortHashCode()
            "amount_debit" to amount
            "amount_credit" to convert(amount)
        })
    }.assertOk().run {
        val id = json<CashoutPending>().cashout_id
        client.post("/accounts/customer/cashouts/$id/confirm") {
            basicAuth("customer", "customer-password")
            jsonBody { "tan" to smsCode("+99") }
        }.assertNoContent()
    }
}

suspend fun ApplicationTestBuilder.convert(amount: String): TalerAmount {
    client.get("/cashout-rate?amount_debit=$amount").assertOk().run {
        return json<ConversionResponse>().amount_credit
    }
}

suspend fun smsCode(info: String): String? {
    val file = File("/tmp/tan-$info.txt");
    if (file.exists()) {
        val code = file.readText()
        file.delete()
        return code;
    } else {
        return null
    }
}


/* ----- Assert ----- */

suspend fun HttpResponse.assertStatus(status: HttpStatusCode, err: TalerErrorCode?): HttpResponse {
    assertEquals(status, this.status);
    if (err != null) assertErr(err)
    return this
}
suspend fun HttpResponse.assertOk(): HttpResponse
    = assertStatus(HttpStatusCode.OK, null)
suspend fun HttpResponse.assertCreated(): HttpResponse 
    = assertStatus(HttpStatusCode.Created, null)
suspend fun HttpResponse.assertNoContent(): HttpResponse 
    = assertStatus(HttpStatusCode.NoContent, null)
suspend fun HttpResponse.assertNotFound(err: TalerErrorCode?): HttpResponse 
    = assertStatus(HttpStatusCode.NotFound, err)
suspend fun HttpResponse.assertUnauthorized(): HttpResponse 
    = assertStatus(HttpStatusCode.Unauthorized, null)
suspend fun HttpResponse.assertConflict(err: TalerErrorCode?): HttpResponse 
    = assertStatus(HttpStatusCode.Conflict, err)
suspend fun HttpResponse.assertBadRequest(err: TalerErrorCode? = null): HttpResponse 
    = assertStatus(HttpStatusCode.BadRequest, err)
suspend fun HttpResponse.assertForbidden(err: TalerErrorCode? = null): HttpResponse 
    = assertStatus(HttpStatusCode.Forbidden, err)
suspend fun HttpResponse.assertNotImplemented(err: TalerErrorCode? = null): HttpResponse 
    = assertStatus(HttpStatusCode.NotImplemented, err)


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

inline suspend fun <reified B> HttpResponse.assertHistoryIds(size: Int, ids: (B) -> List<Long>): B {
    assertOk()
    val body = json<B>()
    val history = ids(body)
    val params = PageParams.extract(call.request.url.parameters)

    // testing the size is like expected.
    assertEquals(size, history.size)
    if (params.delta < 0) {
        // testing that the first id is at most the 'start' query param.
        assert(history[0] <= params.start)
        // testing that the id decreases.
        if (history.size > 1)
            assert(history.windowed(2).all { (a, b) -> a > b })
    } else {
        // testing that the first id is at least the 'start' query param.
        assert(history[0] >= params.start)
        // testing that the id increases.
        if (history.size > 1)
            assert(history.windowed(2).all { (a, b) -> a < b })
    }

    return body
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

inline fun HttpRequestBuilder.jsonBody(
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