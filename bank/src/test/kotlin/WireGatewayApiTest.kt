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
    suspend fun Database.genTransfer(from: String, to: IbanPayTo, amount: String = "KUDOS:10") {
        talerTransferCreate(
            req = TransferRequest(
                request_uid = randHashCode(),
                amount = TalerAmount(amount),
                exchange_base_url = ExchangeUrl("http://exchange.example.com/"),
                wtid = randShortHashCode(),
                credit_account = to
            ),
            username = from,
            timestamp = Instant.now()
        ).run {
            assertEquals(TalerTransferResult.SUCCESS, txResult)
        }
    }

    suspend fun Database.genIncoming(to: String, from: IbanPayTo) {
        talerAddIncomingCreate(
            req = AddIncomingRequest(
                reserve_pub = randShortHashCode(),
                amount = TalerAmount(10, 0, "KUDOS"),
                debit_account = from,
            ),
            username = to,
            timestamp = Instant.now()
        ).run {
            assertEquals(TalerAddIncomingResult.SUCCESS, txResult)
        }
    }

    // Test endpoint is correctly authenticated 
    suspend fun ApplicationTestBuilder.authRoutine(path: String, body: JsonObject? = null, method: HttpMethod = HttpMethod.Post) {
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

        // Not exchange account
        client.request(path) {
            this.method = method
            if (body != null) jsonBody(body)
            basicAuth("merchant", "merchant-password")
        }.assertConflict().assertErr(TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT)
    }

    // Testing the POST /transfer call from the TWG API.
    @Test
    fun transfer() = bankSetup { db -> 
        val valid_req = json {
            "request_uid" to randHashCode()
            "amount" to "KUDOS:55"
            "exchange_base_url" to "http://exchange.example.com/"
            "wtid" to randShortHashCode()
            "credit_account" to "payto://iban/MERCHANT-IBAN-XYZ"
        };

        authRoutine("/accounts/merchant/taler-wire-gateway/transfer", valid_req)

        // Checking exchange debt constraint.
        client.post("/accounts/exchange/taler-wire-gateway/transfer") {
            basicAuth("exchange", "exchange-password")
            jsonBody(valid_req)
        }.assertConflict().assertErr(TalerErrorCode.TALER_EC_BANK_UNALLOWED_DEBIT)

        // Giving debt allowance and checking the OK case.
        assert(db.bankAccountSetMaxDebt(
            2L,
            TalerAmount(1000, 0, "KUDOS")
        ))
        client.post("/accounts/exchange/taler-wire-gateway/transfer") {
            basicAuth("exchange", "exchange-password")
            jsonBody(valid_req)
        }.assertOk()

        // check idempotency
        client.post("/accounts/exchange/taler-wire-gateway/transfer") {
            basicAuth("exchange", "exchange-password")
            jsonBody(valid_req)
        }.assertOk()

        // Trigger conflict due to reused request_uid
        client.post("/accounts/exchange/taler-wire-gateway/transfer") {
            basicAuth("exchange", "exchange-password")
            jsonBody(
                json(valid_req) { 
                    "wtid" to randShortHashCode()
                    "exchange_base_url" to "http://different-exchange.example.com/"
                }
            )
        }.assertConflict().assertErr(TalerErrorCode.TALER_EC_BANK_TRANSFER_REQUEST_UID_REUSED)

        // Currency mismatch
        client.post("/accounts/exchange/taler-wire-gateway/transfer") {
            basicAuth("exchange", "exchange-password")
            jsonBody(
                json(valid_req) {
                    "amount" to "EUR:33"
                }
            )
        }.assertBadRequest().assertErr(TalerErrorCode.TALER_EC_GENERIC_CURRENCY_MISMATCH)

        // Unknown account
        client.post("/accounts/exchange/taler-wire-gateway/transfer") {
            basicAuth("exchange", "exchange-password")
            jsonBody(
                json(valid_req) { 
                    "request_uid" to randHashCode()
                    "wtid" to randShortHashCode()
                    "credit_account" to "payto://iban/UNKNOWN-IBAN-XYZ"
                }
            )
        }.assertNotFound().assertErr(TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT)

        // Same account
        client.post("/accounts/exchange/taler-wire-gateway/transfer") {
            basicAuth("exchange", "exchange-password")
            jsonBody(
                json(valid_req) { 
                    "request_uid" to randHashCode()
                    "wtid" to randShortHashCode()
                    "credit_account" to "payto://iban/EXCHANGE-IBAN-XYZ"
                }
            )
        }.assertConflict().assertErr(TalerErrorCode.TALER_EC_BANK_SAME_ACCOUNT)

        // Bad BASE32 wtid
        client.post("/accounts/exchange/taler-wire-gateway/transfer") {
            basicAuth("exchange", "exchange-password")
            jsonBody(
                json(valid_req) { 
                    "wtid" to "I love chocolate"
                }
            )
        }.assertBadRequest()
        
        // Bad BASE32 len wtid
        client.post("/accounts/exchange/taler-wire-gateway/transfer") {
            basicAuth("exchange", "exchange-password")
            jsonBody(
                json(valid_req) { 
                    "wtid" to randBase32Crockford(31)
                }
            )
        }.assertBadRequest()

        // Bad BASE32 request_uid
        client.post("/accounts/exchange/taler-wire-gateway/transfer") {
            basicAuth("exchange", "exchange-password")
            jsonBody(
                json(valid_req) { 
                    "request_uid" to "I love chocolate"
                }
            )
        }.assertBadRequest()

        // Bad BASE32 len wtid
        client.post("/accounts/exchange/taler-wire-gateway/transfer") {
            basicAuth("exchange", "exchange-password")
            jsonBody(
                json(valid_req) { 
                    "request_uid" to randBase32Crockford(65)
                }
            )
        }.assertBadRequest()
    }
    
    /**
     * Testing the /history/incoming call from the TWG API.
     */
    @Test
    fun historyIncoming() = bankSetup { db -> 
        // Give Foo reasonable debt allowance:
        assert(
            db.bankAccountSetMaxDebt(
                1L,
                TalerAmount(1000000, 0, "KUDOS")
            )
        )

        suspend fun HttpResponse.assertHistory(size: Int) {
            assertOk()
            val txt = bodyAsText()
            val history = Json.decodeFromString<IncomingHistory>(txt)
            val params = HistoryParams.extract(call.request.url.parameters)
       
            // testing the size is like expected.
            assert(history.incoming_transactions.size == size) {
                println("incoming_transactions has wrong size: ${history.incoming_transactions.size}")
                println("Response was: ${txt}")
            }
            if (params.delta < 0) {
                // testing that the first row_id is at most the 'start' query param.
                assert(history.incoming_transactions[0].row_id <= params.start)
                // testing that the row_id decreases.
                if (history.incoming_transactions.size > 1)
                    assert(history.incoming_transactions.windowed(2).all { (a, b) -> a.row_id > b.row_id })
            } else {
                // testing that the first row_id is at least the 'start' query param.
                assert(history.incoming_transactions[0].row_id >= params.start)
                // testing that the row_id increases.
                if (history.incoming_transactions.size > 1)
                    assert(history.incoming_transactions.windowed(2).all { (a, b) -> a.row_id < b.row_id })
            }
        }

        authRoutine("/accounts/merchant/taler-wire-gateway/history/incoming?delta=7", method = HttpMethod.Get)

        // Check error when no transactions
        client.get("/accounts/exchange/taler-wire-gateway/history/incoming?delta=7") {
            basicAuth("exchange", "exchange-password")
        }.assertNoContent()

        // Gen three transactions using clean add incoming logic
        repeat(3) {
            db.genIncoming("exchange", IbanPayTo("payto://iban/MERCHANT-IBAN-XYZ"))
        }
        // Should not show up in the taler wire gateway API history
        db.bankTransactionCreate(genTx("bogus foobar")).assertSuccess()
        // Bar pays Foo once, but that should not appear in the result.
        db.bankTransactionCreate(genTx("payout", creditorId = 1, debtorId = 2)).assertSuccess()
        // Gen one transaction using raw bank transaction logic
        db.bankTransactionCreate(
            genTx(IncomingTxMetadata(randShortHashCode()).encode(), 2, 1)
        ).assertSuccess()
        // Gen one transaction using withdraw logic
        client.post("/accounts/merchant/withdrawals") {
            basicAuth("merchant", "merchant-password")
            jsonBody(json { "amount" to "KUDOS:9" }) 
        }.assertOk().run {
            val resp = Json.decodeFromString<BankAccountCreateWithdrawalResponse>(bodyAsText())
            val uuid = resp.taler_withdraw_uri.split("/").last()
            client.post("/taler-integration/withdrawal-operation/${uuid}") {
                jsonBody(json {
                    "reserve_pub" to randEddsaPublicKey()
                    "selected_exchange" to IbanPayTo("payto://iban/EXCHANGE-IBAN-XYZ")
                })
            }.assertOk()
            client.post("/withdrawals/${uuid}/confirm") {
                basicAuth("merchant", "merchant-password")
            }.assertNoContent()
        }

        // Check ignore bogus subject
        client.get("/accounts/exchange/taler-wire-gateway/history/incoming?delta=7") {
            basicAuth("exchange", "exchange-password")
        }.assertHistory(5)
        
        // Check skip bogus subject
        client.get("/accounts/exchange/taler-wire-gateway/history/incoming?delta=5") {
            basicAuth("exchange", "exchange-password")
        }.assertHistory(5)
        
        // Check no useless polling
        assertTime(0, 200) {
            client.get("/accounts/exchange/taler-wire-gateway/history/incoming?delta=-6&start=15&long_poll_ms=1000") {
                basicAuth("exchange", "exchange-password")
            }.assertHistory(5)
        }

        // Check no polling when find transaction
        assertTime(0, 200) {
            client.get("/accounts/exchange/taler-wire-gateway/history/incoming?delta=6&long_poll_ms=60") {
                basicAuth("exchange", "exchange-password")
            }.assertHistory(5)
        }

        coroutineScope {
            launch {  // Check polling succeed
                assertTime(200, 300) {
                    client.get("/accounts/exchange/taler-wire-gateway/history/incoming?delta=2&start=14&long_poll_ms=1000") {
                        basicAuth("exchange", "exchange-password")
                    }.assertHistory(1)
                }
            }
            launch {  // Check polling timeout
                assertTime(200, 400) {
                    client.get("/accounts/exchange/taler-wire-gateway/history/incoming?delta=1&start=16&long_poll_ms=300") {
                        basicAuth("exchange", "exchange-password")
                    }.assertNoContent()
                }
            }
            delay(200)
            db.genIncoming("exchange", IbanPayTo("payto://iban/MERCHANT-IBAN-XYZ"))
        }

        // Test trigger by raw transaction
        coroutineScope {
            launch {
                assertTime(200, 300) {
                    client.get("/accounts/exchange/taler-wire-gateway/history/incoming?delta=7&start=16&long_poll_ms=1000") {
                        basicAuth("exchange", "exchange-password")
                    }.assertHistory(1)
                }
            }
            delay(200)
            db.bankTransactionCreate(
                genTx(IncomingTxMetadata(randShortHashCode()).encode(), 2, 1)
            ).assertSuccess()   
        }

        // Test trigger by withdraw operationr
        coroutineScope {
            launch {
                assertTime(200, 300) {
                    client.get("/accounts/exchange/taler-wire-gateway/history/incoming?delta=7&start=18&long_poll_ms=1000") {
                        basicAuth("exchange", "exchange-password")
                    }.assertHistory(1)
                }
            }
            delay(200)
            client.post("/accounts/merchant/withdrawals") {
                basicAuth("merchant", "merchant-password")
                jsonBody(json { "amount" to "KUDOS:9" }) 
            }.assertOk().run {
                val resp = Json.decodeFromString<BankAccountCreateWithdrawalResponse>(bodyAsText())
                val uuid = resp.taler_withdraw_uri.split("/").last()
                client.post("/taler-integration/withdrawal-operation/${uuid}") {
                    jsonBody(json {
                        "reserve_pub" to randEddsaPublicKey()
                        "selected_exchange" to IbanPayTo("payto://iban/EXCHANGE-IBAN-XYZ")
                    })
                }.assertOk()
                client.post("/withdrawals/${uuid}/confirm") {
                    basicAuth("merchant", "merchant-password")
                }.assertNoContent()
            }
        }

        // Testing ranges. 
        repeat(20) {
            db.genIncoming("exchange", IbanPayTo("payto://iban/MERCHANT-IBAN-XYZ"))
        }

        // forward range:
        client.get("/accounts/exchange/taler-wire-gateway/history/incoming?delta=10&start=20") {
            basicAuth("exchange", "exchange-password")
        }.assertHistory(10)

        // backward range:
        client.get("/accounts/exchange/taler-wire-gateway/history/incoming?delta=-10&start=25") {
            basicAuth("exchange", "exchange-password")
        }.assertHistory(10)
    }

    
    /**
     * Testing the /history/outgoing call from the TWG API.
     */
    @Test
    fun historyOutgoing() = bankSetup { db -> 
        // Give Bar reasonable debt allowance:
        assert(
            db.bankAccountSetMaxDebt(
                2L,
                TalerAmount(1000000, 0, "KUDOS")
            )
        )

        suspend fun HttpResponse.assertHistory(size: Int) {
            assertOk()
            val txt = this.bodyAsText()
            val history = Json.decodeFromString<OutgoingHistory>(txt)
            val params = HistoryParams.extract(this.call.request.url.parameters)
       
            // testing the size is like expected.
            assert(history.outgoing_transactions.size == size) {
                println("outgoing_transactions has wrong size: ${history.outgoing_transactions.size}")
                println("Response was: ${txt}")
            }
            if (params.delta < 0) {
                // testing that the first row_id is at most the 'start' query param.
                assert(history.outgoing_transactions[0].row_id <= params.start)
                // testing that the row_id decreases.
                if (history.outgoing_transactions.size > 1)
                    assert(history.outgoing_transactions.windowed(2).all { (a, b) -> a.row_id > b.row_id })
            } else {
                // testing that the first row_id is at least the 'start' query param.
                assert(history.outgoing_transactions[0].row_id >= params.start)
                // testing that the row_id increases.
                if (history.outgoing_transactions.size > 1)
                    assert(history.outgoing_transactions.windowed(2).all { (a, b) -> a.row_id < b.row_id })
            }
        }

        authRoutine("/accounts/merchant/taler-wire-gateway/history/outgoing?delta=7", method = HttpMethod.Get)

        // Check error when no transactions
        client.get("/accounts/exchange/taler-wire-gateway/history/outgoing?delta=7") {
            basicAuth("exchange", "exchange-password")
        }.assertNoContent()

        // Gen three transactions using clean transfer logic
        repeat(3) {
            db.genTransfer("exchange", IbanPayTo("payto://iban/MERCHANT-IBAN-XYZ"))
        }
        // Should not show up in the taler wire gateway API history
        db.bankTransactionCreate(genTx("bogus foobar", 1, 2)).assertSuccess()
        // Foo pays Bar once, but that should not appear in the result.
        db.bankTransactionCreate(genTx("payout")).assertSuccess()
        // Gen two transactions using raw bank transaction logic
        repeat(2) {
            db.bankTransactionCreate(
                genTx(OutgoingTxMetadata(randShortHashCode(), ExchangeUrl("http://exchange.example.com/")).encode(), 1, 2)
            ).assertSuccess()
        }

        // Check ignore bogus subject
        client.get("/accounts/exchange/taler-wire-gateway/history/outgoing?delta=7") {
            basicAuth("exchange", "exchange-password")
        }.assertHistory(5)
        
        // Check skip bogus subject
        client.get("/accounts/exchange/taler-wire-gateway/history/outgoing?delta=5") {
                    basicAuth("exchange", "exchange-password")
        }.assertHistory(5)

        // Check no useless polling
        assertTime(0, 200) {
            client.get("/accounts/exchange/taler-wire-gateway/history/outgoing?delta=-6&start=15&long_poll_ms=1000") {
                basicAuth("exchange", "exchange-password")
            }.assertHistory(5)
        }

        // Check no polling when find transaction
        assertTime(0, 200) {
            client.get("/accounts/exchange/taler-wire-gateway/history/outgoing?delta=6&long_poll_ms=1000") {
                basicAuth("exchange", "exchange-password")
            }.assertHistory(5)
        }

        coroutineScope {
            launch {  // Check polling succeed forward
                assertTime(200, 300) {
                    client.get("/accounts/exchange/taler-wire-gateway/history/outgoing?delta=2&start=14&long_poll_ms=1000") {
                        basicAuth("exchange", "exchange-password")
                    }.assertHistory(1)
                }
            }
            launch {  // Check polling timeout forward
                assertTime(200, 400) {
                    client.get("/accounts/exchange/taler-wire-gateway/history/outgoing?delta=1&start=16&long_poll_ms=300") {
                        basicAuth("exchange", "exchange-password")
                    }.assertNoContent()
                }
            }
            delay(200)
            db.genTransfer("exchange", IbanPayTo("payto://iban/MERCHANT-IBAN-XYZ")) 
        }

        // Testing ranges.
        repeat(20) {
            db.genTransfer("exchange", IbanPayTo("payto://iban/MERCHANT-IBAN-XYZ"))
        }

        // forward range:
        client.get("/accounts/exchange/taler-wire-gateway/history/outgoing?delta=10&start=20") {
            basicAuth("exchange", "exchange-password")
        }.assertHistory(10)

        // backward range:
        client.get("/accounts/exchange/taler-wire-gateway/history/outgoing?delta=-10&start=25") {
            basicAuth("exchange", "exchange-password")
        }.assertHistory(10)
    }

    // Testing the /admin/add-incoming call from the TWG API.
    @Test
    fun addIncoming() = bankSetup { db -> 
        val valid_req = json {
            "amount" to "KUDOS:44"
            "reserve_pub" to randEddsaPublicKey()
            "debit_account" to "payto://iban/MERCHANT-IBAN-XYZ"
        };

        authRoutine("/accounts/merchant/taler-wire-gateway/admin/add-incoming", valid_req)

        // Checking exchange debt constraint.
        client.post("/accounts/exchange/taler-wire-gateway/admin/add-incoming") {
            basicAuth("exchange", "exchange-password")
            jsonBody(valid_req)
        }.assertConflict().assertErr(TalerErrorCode.TALER_EC_BANK_UNALLOWED_DEBIT)

        // Giving debt allowance and checking the OK case.
        assert(db.bankAccountSetMaxDebt(
            1L,
            TalerAmount(1000, 0, "KUDOS")
        ))

        client.post("/accounts/exchange/taler-wire-gateway/admin/add-incoming") {
            basicAuth("exchange", "exchange-password")
            jsonBody(valid_req, deflate = true)
        }.assertOk()

        // Trigger conflict due to reused reserve_pub
        client.post("/accounts/exchange/taler-wire-gateway/admin/add-incoming") {
            basicAuth("exchange", "exchange-password")
            jsonBody(valid_req)
        }.assertConflict().assertErr(TalerErrorCode.TALER_EC_BANK_DUPLICATE_RESERVE_PUB_SUBJECT)

        // Currency mismatch
        client.post("/accounts/exchange/taler-wire-gateway/admin/add-incoming") {
            basicAuth("exchange", "exchange-password")
            jsonBody(
                json(valid_req) {
                    "amount" to "EUR:33"
                }
            )
        }.assertBadRequest().assertErr(TalerErrorCode.TALER_EC_GENERIC_CURRENCY_MISMATCH)

        // Unknown account
        client.post("/accounts/exchange/taler-wire-gateway/admin/add-incoming") {
            basicAuth("exchange", "exchange-password")
            jsonBody(
                json(valid_req) { 
                    "reserve_pub" to randEddsaPublicKey()
                    "debit_account" to "payto://iban/UNKNOWN-IBAN-XYZ"
                }
            )
        }.assertNotFound().assertErr(TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT)

        // Same account
        client.post("/accounts/exchange/taler-wire-gateway/admin/add-incoming") {
            basicAuth("exchange", "exchange-password")
            jsonBody(
                json(valid_req) { 
                    "reserve_pub" to randEddsaPublicKey()
                    "debit_account" to "payto://iban/EXCHANGE-IBAN-XYZ"
                }
            )
        }.assertConflict().assertErr(TalerErrorCode.TALER_EC_BANK_SAME_ACCOUNT)

        // Bad BASE32 reserve_pub
        client.post("/accounts/exchange/taler-wire-gateway/admin/add-incoming") {
            basicAuth("exchange", "exchange-password")
            jsonBody(json(valid_req) { 
                "reserve_pub" to "I love chocolate"
            })
        }.assertBadRequest()
        
        // Bad BASE32 len reserve_pub
        client.post("/accounts/exchange/taler-wire-gateway/admin/add-incoming") {
            basicAuth("exchange", "exchange-password")
            jsonBody(json(valid_req) { 
                "reserve_pub" to randBase32Crockford(31)
            })
        }.assertBadRequest()
    }
}