/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023-2024 Taler Systems S.A.

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

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import tech.libeufin.bank.*
import tech.libeufin.bank.db.AccountDAO.AccountCreationResult
import tech.libeufin.bank.db.Database
import tech.libeufin.common.*
import tech.libeufin.common.db.dbInit
import tech.libeufin.common.db.pgDataSource
import java.nio.file.NoSuchFileException
import kotlin.io.path.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.readText
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/* ----- Setup ----- */

val merchantPayto = IbanPayto.rand()
val exchangePayto = IbanPayto.rand()
val customerPayto = IbanPayto.rand()
val unknownPayto  = IbanPayto.rand()
var tmpPayTo      = IbanPayto.rand()
val paytos = mapOf(
    "merchant" to merchantPayto, 
    "exchange" to exchangePayto, 
    "customer" to customerPayto
)

fun genTmpPayTo(): IbanPayto {
    tmpPayTo = IbanPayto.rand()
    return tmpPayTo
}

fun setup(
    conf: String = "test.conf",
    lambda: suspend (Database, BankConfig) -> Unit
) = runBlocking {
    val config = talerConfig(Path("conf/$conf"))
    val dbCfg = config.loadDbConfig()
    val ctx = config.loadBankConfig()
    pgDataSource(dbCfg.dbConnStr).run {
        dbInit(dbCfg, "libeufin-nexus", true)
        dbInit(dbCfg, "libeufin-bank", true)
    }
    Database(dbCfg, ctx.regionalCurrency, ctx.fiatCurrency).use {
        it.conn { conn ->
            val sqlProcedures = Path("${dbCfg.sqlDir}/libeufin-conversion-setup.sql")
            conn.execSQLUpdate(sqlProcedures.readText())
        }
        lambda(it, ctx)
    }
}

fun bankSetup(
    conf: String = "test.conf",    
    lambda: suspend ApplicationTestBuilder.(Database) -> Unit
) = setup(conf) { db, cfg -> 
    // Creating the exchange and merchant accounts first.
    val bonus = TalerAmount("KUDOS:0")
    assertIs<AccountCreationResult.Success>(db.account.create(
        login = "merchant",
        password = "merchant-password",
        name = "Merchant",
        internalPayto = merchantPayto,
        maxDebt = TalerAmount("KUDOS:10"),
        isTalerExchange = false,
        isPublic = false,
        bonus = bonus,
        checkPaytoIdempotent = false,
        email = null,
        phone = null,
        cashoutPayto = null,
        tanChannel = null,
        ctx = cfg.payto  
    ))
    assertIs<AccountCreationResult.Success>(db.account.create(
        login = "exchange",
        password = "exchange-password",
        name = "Exchange",
        internalPayto = exchangePayto,
        maxDebt = TalerAmount("KUDOS:10"),
        isTalerExchange = true,
        isPublic = false,
        bonus = bonus,
        checkPaytoIdempotent = false,
        email = null,
        phone = null,
        cashoutPayto = null,
        tanChannel = null,
        ctx = cfg.payto   
    ))
    assertIs<AccountCreationResult.Success>(db.account.create(
        login = "customer",
        password = "customer-password",
        name = "Customer",
        internalPayto = customerPayto,
        maxDebt = TalerAmount("KUDOS:10"),
        isTalerExchange = false,
        isPublic = false,
        bonus = bonus,
        checkPaytoIdempotent = false,
        email = null,
        phone = null,
        cashoutPayto = null,
        tanChannel = null,
        ctx = cfg.payto
    ))
    // Create admin account
    assertIs<AccountCreationResult.Success>(createAdminAccount(db, cfg, "admin-password"))
    testApplication {
        application {
            corebankWebApp(db, cfg)
        }
        if (cfg.allowConversion) {
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

fun dbSetup(lambda: suspend (Database) -> Unit) =
    setup { db, _ -> lambda(db) }

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
        val balance = it.balance
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
            "request_uid" to HashCode.rand()
            "amount" to TalerAmount(amount)
            "exchange_base_url" to "http://exchange.example.com/"
            "wtid" to ShortHashCode.rand()
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
            "reserve_pub" to EddsaPublicKey.rand()
            "debit_account" to merchantPayto
        }
    }.assertOk()
}

/** Perform a cashout operation of [amount] from customer */
suspend fun ApplicationTestBuilder.cashout(amount: String) {
    val res = client.postA("/accounts/customer/cashouts") {
        json {
            "request_uid" to ShortHashCode.rand()
            "amount_debit" to amount
            "amount_credit" to convert(amount)
        }
    } 
    if (res.status == HttpStatusCode.Conflict) {
        // Retry with cashout info
        fillCashoutInfo("customer")
        client.postA("/accounts/customer/cashouts") {
            json {
                "request_uid" to ShortHashCode.rand()
                "amount_debit" to amount
                "amount_credit" to convert(amount)
            }
        } 
    } else { 
        res
    }.assertOk()
}

/** Perform a whithrawal operation of [amount] from customer */
suspend fun ApplicationTestBuilder.withdrawal(amount: String) {
    client.postA("/accounts/merchant/withdrawals") {
        json { "amount" to amount } 
    }.assertOkJson<BankAccountCreateWithdrawalResponse> {
        val uuid = it.taler_withdraw_uri.split("/").last()
        withdrawalSelect(uuid)
        client.postA("/accounts/merchant/withdrawals/${uuid}/confirm")
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
            "reserve_pub" to EddsaPublicKey.rand()
            "selected_exchange" to exchangePayto
        }
    }.assertOk()
}

suspend fun ApplicationTestBuilder.convert(amount: String): TalerAmount {
    return client.get("/conversion-info/cashout-rate?amount_debit=$amount")
        .assertOkJson<ConversionResponse>().amount_credit
}

suspend fun tanCode(info: String): String? {
    try {
        val file = Path("/tmp/tan-$info.txt")
        val code = file.readText().split(" ", limit=2).first()
        file.deleteExisting()
        return code
    } catch (e: Exception) {
        if (e is NoSuchFileException) return null
        throw e
    }
}


/* ----- Assert ----- */

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
    val id = assertAcceptedJson<TanChallenge>().challenge_id
    val username = call.request.url.pathSegments[2]
    val res = call.client.postA("/accounts/$username/challenge/$id").assertOkJson<TanTransmission>()
    check(res.tan_channel, res.tan_info)
    val code = tanCode(res.tan_info)
    assertNotNull(code)
    call.client.postA("/accounts/$username/challenge/$id/confirm") {
        json { "tan" to code }
    }.assertNoContent()
    return call.client.request(this.call.request.url) {
        pwAuth(username)
        method = call.request.method
        headers["X-Challenge-Id"] = "$id"
    }
}

fun assertException(msg: String, lambda: () -> Unit) {
    try {
        lambda()
        throw Exception("Expected failure")
    } catch (e: Exception) {
        assert(e.message!!.startsWith(msg)) { "${e.message}" }
    }
}

/* ----- Auth ----- */

/** Auto auth get request */
suspend inline fun HttpClient.getA(url: String, builder: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
    return get(url) {
        pwAuth()
        builder(this)
    }
}

/** Auto auth post request */
suspend inline fun HttpClient.postA(url: String, builder: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
    return post(url) {
        pwAuth()
        builder(this)
    }
}

/** Auto auth patch request */
suspend inline fun HttpClient.patchA(url: String, builder: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
    return patch(url) {
        pwAuth()
        builder(this)
    }
}

/** Auto auth delete request */
suspend inline fun HttpClient.deleteA(url: String, builder: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
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

fun randBase32Crockford(length: Int) = Base32Crockford.encode(ByteArray(length).rand())
fun randIncomingSubject(reservePub: EddsaPublicKey): String = "$reservePub"
fun randOutgoingSubject(wtid: ShortHashCode, url: ExchangeUrl): String = "$wtid $url"