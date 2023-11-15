import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.HttpClient
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import org.junit.Test
import tech.libeufin.bank.*
import tech.libeufin.util.CryptoUtil
import net.taler.common.errorcodes.TalerErrorCode
import java.util.*
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import randHashCode

class WireGatewayApiTest {
    // Test endpoint is correctly authenticated 
    suspend fun ApplicationTestBuilder.authRoutine(path: String, body: JsonObject? = null, method: HttpMethod = HttpMethod.Post, requireAdmin: Boolean = false) {
        // No body when authentication must happen before parsing the body
        
        // Unknown account
        client.request(path) {
            this.method = method
            basicAuth("unknown", "password")
        }.assertUnauthorized()

        // Wrong password
        client.request(path) {
            this.method = method
            basicAuth("merchant", "wrong-password")
        }.assertUnauthorized()

        // Wrong account
        client.request(path) {
            this.method = method
            basicAuth("exchange", "merchant-password")
        }.assertUnauthorized()

        if (requireAdmin) {
             // Not exchange account
            client.request(path) {
                this.method = method
                if (body != null) json(body)
                pwAuth("merchant")
            }.assertUnauthorized()
        }

        // Not exchange account
        client.request(path) {
            this.method = method
            if (body != null) json(body)
            if (requireAdmin)
                pwAuth("admin")
            else pwAuth("merchant")
        }.assertConflict(TalerErrorCode.BANK_ACCOUNT_IS_NOT_EXCHANGE)
    }

    // Testing the POST /transfer call from the TWG API.
    @Test
    fun transfer() = bankSetup { _ -> 
        val valid_req = obj {
            "request_uid" to randHashCode()
            "amount" to "KUDOS:55"
            "exchange_base_url" to "http://exchange.example.com/"
            "wtid" to randShortHashCode()
            "credit_account" to merchantPayto
        };

        authRoutine("/accounts/merchant/taler-wire-gateway/transfer", valid_req)

        // Checking exchange debt constraint.
        client.postA("/accounts/exchange/taler-wire-gateway/transfer") {
            json(valid_req)
        }.assertConflict(TalerErrorCode.BANK_UNALLOWED_DEBIT)

        // Giving debt allowance and checking the OK case.
        setMaxDebt("exchange", TalerAmount("KUDOS:1000"))
        client.postA("/accounts/exchange/taler-wire-gateway/transfer") {
            json(valid_req)
        }.assertOk()

        // check idempotency
        client.postA("/accounts/exchange/taler-wire-gateway/transfer") {
            json(valid_req)
        }.assertOk()

        // Trigger conflict due to reused request_uid
        client.postA("/accounts/exchange/taler-wire-gateway/transfer") {
            json(valid_req) { 
                "wtid" to randShortHashCode()
                "exchange_base_url" to "http://different-exchange.example.com/"
            }
        }.assertConflict(TalerErrorCode.BANK_TRANSFER_REQUEST_UID_REUSED)

        // Currency mismatch
        client.postA("/accounts/exchange/taler-wire-gateway/transfer") {
            json(valid_req) {
                "amount" to "EUR:33"
            }
        }.assertBadRequest(TalerErrorCode.GENERIC_CURRENCY_MISMATCH)

        // Unknown account
        client.postA("/accounts/exchange/taler-wire-gateway/transfer") {
            json(valid_req) { 
                "request_uid" to randHashCode()
                "wtid" to randShortHashCode()
                "credit_account" to unknownPayto
            }
        }.assertConflict(TalerErrorCode.BANK_UNKNOWN_CREDITOR)

        // Same account
        client.postA("/accounts/exchange/taler-wire-gateway/transfer") {
            json(valid_req) { 
                "request_uid" to randHashCode()
                "wtid" to randShortHashCode()
                "credit_account" to exchangePayto
            }
        }.assertConflict(TalerErrorCode.BANK_ACCOUNT_IS_EXCHANGE)

        // Bad BASE32 wtid
        client.postA("/accounts/exchange/taler-wire-gateway/transfer") {
            json(valid_req) { 
                "wtid" to "I love chocolate"
            }
        }.assertBadRequest()
        
        // Bad BASE32 len wtid
        client.postA("/accounts/exchange/taler-wire-gateway/transfer") {
            json(valid_req) { 
                "wtid" to randBase32Crockford(31)
            }
        }.assertBadRequest()

        // Bad BASE32 request_uid
        client.postA("/accounts/exchange/taler-wire-gateway/transfer") {
            json(valid_req) { 
                "request_uid" to "I love chocolate"
            }
        }.assertBadRequest()

        // Bad BASE32 len wtid
        client.postA("/accounts/exchange/taler-wire-gateway/transfer") {
            json(valid_req) { 
                "request_uid" to randBase32Crockford(65)
            }
        }.assertBadRequest()
    }
    
    /**
     * Testing the /history/incoming call from the TWG API.
     */
    @Test
    fun historyIncoming() = bankSetup { 
        // Give Foo reasonable debt allowance:
        setMaxDebt("merchant", TalerAmount("KUDOS:1000"))

        suspend fun HttpResponse.assertHistory(size: Int) {
            assertHistoryIds<IncomingHistory>(size) {
                it.incoming_transactions.map { it.row_id }
            }
        }

        authRoutine("/accounts/merchant/taler-wire-gateway/history/incoming?delta=7", method = HttpMethod.Get)

        // Check error when no transactions
        client.getA("/accounts/exchange/taler-wire-gateway/history/incoming?delta=7")
            .assertNoContent()

        // Gen three transactions using clean add incoming logic
        repeat(3) {
            addIncoming("KUDOS:10")
        }
        // Should not show up in the taler wire gateway API history
        tx("merchant", "KUDOS:10", "exchange", "bogus")
        // Exchange pays merchant once, but that should not appear in the result
        tx("exchange", "KUDOS:10", "merchant", "ignored")
        // Gen one transaction using raw bank transaction logic
        tx("merchant", "KUDOS:10", "exchange", IncomingTxMetadata(randShortHashCode()).encode())
        // Gen one transaction using withdraw logic
        client.postA("/accounts/merchant/withdrawals") {
            json { "amount" to "KUDOS:9" } 
        }.assertOkJson<BankAccountCreateWithdrawalResponse> {
            val uuid = it.taler_withdraw_uri.split("/").last()
            client.post("/taler-integration/withdrawal-operation/${uuid}") {
                json {
                    "reserve_pub" to randEddsaPublicKey()
                    "selected_exchange" to exchangePayto
                }
            }.assertOk()
            client.post("/withdrawals/${uuid}/confirm") {
                pwAuth("merchant")
            }.assertNoContent()
        }

        // Check ignore bogus subject
        client.getA("/accounts/exchange/taler-wire-gateway/history/incoming?delta=7") 
            .assertHistory(5)
        
        // Check skip bogus subject
        client.getA("/accounts/exchange/taler-wire-gateway/history/incoming?delta=5")
            .assertHistory(5)
        
        // Check no useless polling
        assertTime(0, 200) {
            client.getA("/accounts/exchange/taler-wire-gateway/history/incoming?delta=-6&start=15&long_poll_ms=1000")
                .assertHistory(5)
        }

        // Check no polling when find transaction
        assertTime(0, 200) {
            client.getA("/accounts/exchange/taler-wire-gateway/history/incoming?delta=6&long_poll_ms=60")
                .assertHistory(5)
        }

        coroutineScope {
            launch {  // Check polling succeed
                assertTime(200, 300) {
                    client.getA("/accounts/exchange/taler-wire-gateway/history/incoming?delta=2&start=14&long_poll_ms=1000")
                        .assertHistory(1)
                }
            }
            launch {  // Check polling timeout
                assertTime(200, 400) {
                    client.getA("/accounts/exchange/taler-wire-gateway/history/incoming?delta=1&start=16&long_poll_ms=300")
                        .assertNoContent()
                }
            }
            delay(200)
            addIncoming("KUDOS:10")
        }

        // Test trigger by raw transaction
        coroutineScope {
            launch {
                assertTime(200, 300) {
                    client.getA("/accounts/exchange/taler-wire-gateway/history/incoming?delta=7&start=16&long_poll_ms=1000")
                        .assertHistory(1)
                }
            }
            delay(200)
            tx("merchant", "KUDOS:10", "exchange", IncomingTxMetadata(randShortHashCode()).encode())
        }

        // Test trigger by withdraw operationr
        coroutineScope {
            launch {
                assertTime(200, 300) {
                    client.getA("/accounts/exchange/taler-wire-gateway/history/incoming?delta=7&start=18&long_poll_ms=1000") 
                        .assertHistory(1)
                }
            }
            delay(200)
            client.postA("/accounts/merchant/withdrawals") {
                json { "amount" to "KUDOS:9" } 
            }.assertOkJson<BankAccountCreateWithdrawalResponse> {
                val uuid = it.taler_withdraw_uri.split("/").last()
                client.post("/taler-integration/withdrawal-operation/${uuid}") {
                    json {
                        "reserve_pub" to randEddsaPublicKey()
                        "selected_exchange" to exchangePayto
                    }
                }.assertOk()
                client.postA("/withdrawals/${uuid}/confirm")
                    .assertNoContent()
            }
        }

        // Testing ranges. 
        repeat(20) {
            addIncoming("KUDOS:10")
        }

        // forward range:
        client.getA("/accounts/exchange/taler-wire-gateway/history/incoming?delta=10&start=20")
            .assertHistory(10)

        // backward range:
        client.getA("/accounts/exchange/taler-wire-gateway/history/incoming?delta=-10&start=25")
            .assertHistory(10)
    }

    
    /**
     * Testing the /history/outgoing call from the TWG API.
     */
    @Test
    fun historyOutgoing() = bankSetup {
        setMaxDebt("exchange", TalerAmount("KUDOS:1000000"))

        suspend fun HttpResponse.assertHistory(size: Int) {
            assertHistoryIds<OutgoingHistory>(size) {
                it.outgoing_transactions.map { it.row_id }
            }
        }

        authRoutine("/accounts/merchant/taler-wire-gateway/history/outgoing?delta=7", method = HttpMethod.Get)

        // Check error when no transactions
        client.getA("/accounts/exchange/taler-wire-gateway/history/outgoing?delta=7")
            .assertNoContent()

        // Gen three transactions using clean transfer logic
        repeat(3) {
            transfer("KUDOS:10")
        }
        // Should not show up in the taler wire gateway API history
        tx("exchange", "KUDOS:10", "merchant", "bogus")
        // Merchant pays exchange once, but that should not appear in the result
        tx("merchant", "KUDOS:10", "exchange", "ignored")
        // Gen two transactions using raw bank transaction logic
        repeat(2) {
            tx("exchange", "KUDOS:10", "merchant", OutgoingTxMetadata(randShortHashCode(), ExchangeUrl("http://exchange.example.com/")).encode())
        }

        // Check ignore bogus subject
        client.getA("/accounts/exchange/taler-wire-gateway/history/outgoing?delta=7") 
            .assertHistory(5)
        
        // Check skip bogus subject
        client.getA("/accounts/exchange/taler-wire-gateway/history/outgoing?delta=5")
            .assertHistory(5)

        // Check no useless polling
        assertTime(0, 200) {
            client.getA("/accounts/exchange/taler-wire-gateway/history/outgoing?delta=-6&start=15&long_poll_ms=1000")
                .assertHistory(5)
        }

        // Check no polling when find transaction
        assertTime(0, 200) {
            client.getA("/accounts/exchange/taler-wire-gateway/history/outgoing?delta=6&long_poll_ms=1000")
                .assertHistory(5)
        }

        coroutineScope {
            launch {  // Check polling succeed forward
                assertTime(200, 300) {
                    client.getA("/accounts/exchange/taler-wire-gateway/history/outgoing?delta=2&start=14&long_poll_ms=1000")
                        .assertHistory(1)
                }
            }
            launch {  // Check polling timeout forward
                assertTime(200, 400) {
                    client.getA("/accounts/exchange/taler-wire-gateway/history/outgoing?delta=1&start=16&long_poll_ms=300")
                        .assertNoContent()
                }
            }
            delay(200)
            transfer("KUDOS:10")
        }

        // Testing ranges.
        repeat(20) {
            transfer("KUDOS:10")
        }

        // forward range:
        client.getA("/accounts/exchange/taler-wire-gateway/history/outgoing?delta=10&start=20") 
            .assertHistory(10)

        // backward range:
        client.getA("/accounts/exchange/taler-wire-gateway/history/outgoing?delta=-10&start=25") 
            .assertHistory(10)
    }

    // Testing the /admin/add-incoming call from the TWG API.
    @Test
    fun addIncoming() = bankSetup { _ -> 
        val valid_req = obj {
            "amount" to "KUDOS:44"
            "reserve_pub" to randEddsaPublicKey()
            "debit_account" to merchantPayto
        };

        authRoutine("/accounts/merchant/taler-wire-gateway/admin/add-incoming", valid_req, requireAdmin = true)

        // Checking exchange debt constraint.
        client.postA("/accounts/exchange/taler-wire-gateway/admin/add-incoming") {
            json(valid_req)
        }.assertConflict(TalerErrorCode.BANK_UNALLOWED_DEBIT)

        // Giving debt allowance and checking the OK case.
        setMaxDebt("merchant", TalerAmount("KUDOS:1000"))
        client.postA("/accounts/exchange/taler-wire-gateway/admin/add-incoming") {
            json(valid_req, deflate = true)
        }.assertOk()

        // Trigger conflict due to reused reserve_pub
        client.postA("/accounts/exchange/taler-wire-gateway/admin/add-incoming") {
            json(valid_req)
        }.assertConflict(TalerErrorCode.BANK_DUPLICATE_RESERVE_PUB_SUBJECT)

        // Currency mismatch
        client.postA("/accounts/exchange/taler-wire-gateway/admin/add-incoming") {
            json(valid_req) { "amount" to "EUR:33" }
        }.assertBadRequest(TalerErrorCode.GENERIC_CURRENCY_MISMATCH)

        // Unknown account
        client.postA("/accounts/exchange/taler-wire-gateway/admin/add-incoming") {
            json(valid_req) { 
                "reserve_pub" to randEddsaPublicKey()
                "debit_account" to unknownPayto
            }
        }.assertConflict(TalerErrorCode.BANK_UNKNOWN_DEBTOR)

        // Same account
        client.postA("/accounts/exchange/taler-wire-gateway/admin/add-incoming") {
            json(valid_req) { 
                "reserve_pub" to randEddsaPublicKey()
                "debit_account" to exchangePayto
            }
        }.assertConflict(TalerErrorCode.BANK_ACCOUNT_IS_EXCHANGE)

        // Bad BASE32 reserve_pub
        client.postA("/accounts/exchange/taler-wire-gateway/admin/add-incoming") {
            json(valid_req) { 
                "reserve_pub" to "I love chocolate"
            }
        }.assertBadRequest()
        
        // Bad BASE32 len reserve_pub
        client.postA("/accounts/exchange/taler-wire-gateway/admin/add-incoming") {
            json(valid_req) { 
                "reserve_pub" to randBase32Crockford(31)
            }
        }.assertBadRequest()
    }
}