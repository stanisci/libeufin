/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023 Taler Systems S.A.

 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.

 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.

 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.DeflaterOutputStream
import kotlin.test.*
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import net.taler.common.errorcodes.TalerErrorCode
import net.taler.wallet.crypto.Base32Crockford
import tech.libeufin.bank.*
import tech.libeufin.bank.AccountDAO.*
import tech.libeufin.util.*

/* ----- Setup ----- */

val merchantPayto = IbanPayTo(genIbanPaytoUri())
val exchangePayto = IbanPayTo(genIbanPaytoUri())
val customerPayto = IbanPayTo(genIbanPaytoUri())
val unknownPayto  = IbanPayTo(genIbanPaytoUri())
var tmpPayTo      = IbanPayTo(genIbanPaytoUri())
val paytos = mapOf(
    "merchant" to merchantPayto, 
    "exchange" to exchangePayto, 
    "customer" to customerPayto
)

fun genTmpPayTo(): IbanPayTo {
    tmpPayTo = IbanPayTo(genIbanPaytoUri())
    return tmpPayTo
}

fun setup(
    conf: String = "test.conf",
    lambda: suspend (Database, BankConfig) -> Unit
) {
    val config = talerConfig("conf/$conf")
    val dbCfg = config.loadDbConfig()
    val ctx = config.loadBankConfig()
    Database(dbCfg.dbConnStr, ctx.regionalCurrency, ctx.fiatCurrency).use {
        runBlocking {
            it.conn { conn ->
                resetDatabaseTables(conn, dbCfg, "libeufin-nexus")
                initializeDatabaseTables(conn, dbCfg, "libeufin-nexus")
                resetDatabaseTables(conn, dbCfg, "libeufin-bank")
                initializeDatabaseTables(conn, dbCfg, "libeufin-bank")
                val sqlProcedures = File("${dbCfg.sqlDir}/libeufin-conversion-setup.sql")
                conn.execSQLUpdate(sqlProcedures.readText())
            }
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
        val bonus = TalerAmount("KUDOS:0")
        assertEquals(AccountCreationResult.Success, db.account.create(
            login = "merchant",
            password = "merchant-password",
            name = "Merchant",
            internalPaytoUri = merchantPayto,
            maxDebt = TalerAmount("KUDOS:10"),
            isTalerExchange = false,
            isPublic = false,
            bonus = bonus,
            checkPaytoIdempotent = false
        ))
        assertEquals(AccountCreationResult.Success, db.account.create(
            login = "exchange",
            password = "exchange-password",
            name = "Exchange",
            internalPaytoUri = exchangePayto,
            maxDebt = TalerAmount("KUDOS:10"),
            isTalerExchange = true,
            isPublic = false,
            bonus = bonus,
            checkPaytoIdempotent = false
        ))
        assertEquals(AccountCreationResult.Success, db.account.create(
            login = "customer",
            password = "customer-password",
            name = "Customer",
            internalPaytoUri = customerPayto,
            maxDebt = TalerAmount("KUDOS:10"),
            isTalerExchange = false,
            isPublic = false,
            bonus = bonus,
            checkPaytoIdempotent = false
        ))
        // Create admin account
        assertEquals(AccountCreationResult.Success, maybeCreateAdminAccount(db, ctx, "admin-password"))
        testApplication {
            application {
                corebankWebApp(db, ctx)
            }
            if (ctx.allowConversion) {
                // Set conversion rates
                client.post("/conversion-info/conversion-rate") {
                    pwAuth("admin")
                    json {
                        "cashin_ratio" to "0.8"
                        "cashin_fee" to "KUDOS:0.02"
                        "cashin_tiny_amount" to "KUDOS:0.01"
                        "cashin_rounding_mode" to "nearest"
                        "cashin_min_amount" to "EUR:0"
                        "cashout_ratio" to "1.25"
                        "cashout_fee" to "EUR:0.003"
                        "cashout_tiny_amount" to "EUR:0.00000001"
                        "cashout_rounding_mode" to "zero"
                        "cashout_min_amount" to "KUDOS:0.1"
                    }
                }.assertNoContent()
            }
            lambda(db)
        }
    }
}

fun dbSetup(lambda: suspend (Database) -> Unit) {
    setup() { db, _ -> lambda(db) }
}

/* ----- Common actions ----- */

/** Set [account] debit threshold to [maxDebt] amount */
suspend fun ApplicationTestBuilder.setMaxDebt(account: String, maxDebt: String) {
    client.patch("/accounts/$account") { 
        pwAuth("admin")
        json { "debit_threshold" to maxDebt }
    }.assertNoContent()
}

/** Check [account] balance is [amount], [amount] is prefixed with + for credit and - for debit */
suspend fun ApplicationTestBuilder.assertBalance(account: String, amount: String) {
    client.get("/accounts/$account") { 
        pwAuth("admin")
    }.assertOkJson<AccountData> {
        val balance = it.balance;
        val fmt = "${if (balance.credit_debit_indicator == CreditDebitInfo.debit) '-' else '+'}${balance.amount}"
        assertEquals(amount, fmt, "For $account")
    }
}

/** Check [account] tan channel and info */
suspend fun ApplicationTestBuilder.tanInfo(account: String): Pair<TanChannel?, String?> {
    val res = client.getA("/accounts/$account").assertOkJson<AccountData>()
    val channel: TanChannel? = res.tan_channel
    return Pair(channel, when (channel) {
        TanChannel.sms -> res.contact_data!!.phone.get()
        TanChannel.email -> res.contact_data!!.email.get()
        null -> null
        else -> null
    })
}

/** Perform a bank transaction of [amount] [from] account [to] account with [subject} */
suspend fun ApplicationTestBuilder.tx(from: String, amount: String, to: String, subject: String = "payout"): Long {
    return client.postA("/accounts/$from/transactions") {
        json {
            "payto_uri" to "${paytos[to] ?: tmpPayTo}?message=${subject.encodeURLQueryComponent()}&amount=$amount"
        }
    }.maybeChallenge().assertOkJson<TransactionCreateResponse>().row_id
}

/** Perform a taler outgoing transaction of [amount] from exchange to merchant */
suspend fun ApplicationTestBuilder.transfer(amount: String) {
    client.postA("/accounts/exchange/taler-wire-gateway/transfer") {
        json {
            "request_uid" to randHashCode()
            "amount" to TalerAmount(amount)
            "exchange_base_url" to "http://exchange.example.com/"
            "wtid" to randShortHashCode()
            "credit_account" to merchantPayto
        }
    }.assertOk()
}

/** Perform a taler incoming transaction of [amount] from merchant to exchange */
suspend fun ApplicationTestBuilder.addIncoming(amount: String) {
    client.post("/accounts/exchange/taler-wire-gateway/admin/add-incoming") {
        pwAuth("admin")
        json {
            "amount" to TalerAmount(amount)
            "reserve_pub" to randEddsaPublicKey()
            "debit_account" to merchantPayto
        }
    }.assertOk()
}

/** Perform a cashout operation of [amount] from customer */
suspend fun ApplicationTestBuilder.cashout(amount: String) {
    val res = client.postA("/accounts/customer/cashouts") {
        json {
            "request_uid" to randShortHashCode()
            "amount_debit" to amount
            "amount_credit" to convert(amount)
        }
    } 
    if (res.status == HttpStatusCode.Conflict) {
        // Retry with cashout info
        fillCashoutInfo("customer")
        client.postA("/accounts/customer/cashouts") {
            json {
                "request_uid" to randShortHashCode()
                "amount_debit" to amount
                "amount_credit" to convert(amount)
            }
        } 
    } else { 
        res
    }.assertOkJson<CashoutPending> {
        client.postA("/accounts/customer/cashouts/${it.cashout_id}/confirm") {
            json { "tan" to tanCode("+99") }
        }.assertNoContent()
    }
}

/** Perform a whithrawal operation of [amount] from customer */
suspend fun ApplicationTestBuilder.withdrawal(amount: String) {
    client.postA("/accounts/merchant/withdrawals") {
        json { "amount" to amount } 
    }.assertOkJson<BankAccountCreateWithdrawalResponse> {
        val uuid = it.taler_withdraw_uri.split("/").last()
        withdrawalSelect(uuid)
        client.postA("/withdrawals/${uuid}/confirm")
            .assertNoContent()
    }
}

suspend fun ApplicationTestBuilder.fillCashoutInfo(account: String) {
    client.patch("/accounts/$account") {
        pwAuth("admin")
        json {
            "cashout_payto_uri" to unknownPayto
            "contact_data" to obj {
                "phone" to "+99"
            }
        }
    }.assertNoContent()
}

suspend fun ApplicationTestBuilder.fillTanInfo(account: String) {
    client.patch("/accounts/$account") {
        pwAuth("admin")
        json {
            "contact_data" to obj {
                "phone" to "+${Random.nextInt(0, 10000)}"
            }
            "tan_channel" to "sms"
        }
    }.assertNoContent()
}

suspend fun ApplicationTestBuilder.withdrawalSelect(uuid: String) {
    client.post("/taler-integration/withdrawal-operation/$uuid") {
        json {
            "reserve_pub" to randEddsaPublicKey()
            "selected_exchange" to exchangePayto
        }
    }.assertOk()
}

suspend fun ApplicationTestBuilder.convert(amount: String): TalerAmount {
    return client.get("/conversion-info/cashout-rate?amount_debit=$amount")
        .assertOkJson<ConversionResponse>().amount_credit
}

suspend fun tanCode(info: String): String? {
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
suspend fun HttpResponse.assertNoContent(): HttpResponse 
    = assertStatus(HttpStatusCode.NoContent, null)
suspend fun HttpResponse.assertAccepted(): HttpResponse 
    = assertStatus(HttpStatusCode.Accepted, null)
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

suspend fun HttpResponse.maybeChallenge(): HttpResponse {
    return if (this.status == HttpStatusCode.Accepted) {
        this.assertChallenge()
    } else {
        this
    }
}

suspend fun HttpResponse.assertChallenge(
    check: suspend (TanChannel, String) -> Unit = { _, _ -> }
): HttpResponse {
    val id = this.assertAcceptedJson<TanChallenge>().challenge_id
    val username = this.call.request.url.pathSegments[2]
    val res = this.call.client.postA("/accounts/$username/challenge/$id").assertOkJson<TanTransmission>()
    check(res.tan_channel, res.tan_info)
    val code = tanCode(res.tan_info)
    assertNotNull(code)
    return this.call.client.postA("/accounts/$username/challenge/$id/confirm") {
        json { "tan" to code }
    }
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

inline suspend fun <reified B> HttpResponse.assertOkJson(lambda: (B) -> Unit = {}): B {
    assertOk()
    val body = json<B>()
    lambda(body)
    return body
}

inline suspend fun <reified B> HttpResponse.assertAcceptedJson(lambda: (B) -> Unit = {}): B {
    assertAccepted()
    val body = json<B>()
    lambda(body)
    return body
}

/* ----- Auth ----- */

/** Auto auth get request */
inline suspend fun HttpClient.getA(url: String, builder: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
    return get(url) {
        pwAuth()
        builder(this)
    }
}

/** Auto auth post request */
inline suspend fun HttpClient.postA(url: String, builder: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
    return post(url) {
        pwAuth()
        builder(this)
    }
}

/** Auto auth patch request */
inline suspend fun HttpClient.patchA(url: String, builder: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
    return patch(url) {
        pwAuth()
        builder(this)
    }
}

/** Auto auth delete request */
inline suspend fun HttpClient.deleteA(url: String, builder: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
    return delete(url) {
        pwAuth()
        builder(this)
    }
}

fun HttpRequestBuilder.pwAuth(username: String? = null) {
    if (username != null) {
        basicAuth("$username", "$username-password")
    } else if (url.pathSegments.contains("admin")) {
        basicAuth("admin", "admin-password")
    } else if (url.pathSegments[1] == "accounts") {
        // Extract username from path
        val login = url.pathSegments[2]
        basicAuth("$login", "$login-password")
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

fun randIncomingSubject(reservePub: EddsaPublicKey): String {
    return "$reservePub"
}

fun randOutgoingSubject(wtid: ShortHashCode, url: ExchangeUrl): String {
    return "$wtid $url"
}