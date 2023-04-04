import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.util.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Test
import tech.libeufin.nexus.bankaccount.getBankAccount
import tech.libeufin.sandbox.*

class SandboxAccessApiTest {
    val mapper = ObjectMapper()

    /**
     * Testing that ..access-api/withdrawals/{wopid} and
     * ..access-api/accounts/{account_name}/withdrawals/{wopid}
     * are handled in the same way.
     */
    @Test
    fun doubleUriStyle() {
        // Creating one withdrawal operation.
        withTestDatabase {
            prepSandboxDb()
            val wo: TalerWithdrawalEntity = transaction {
                TalerWithdrawalEntity.new {
                    this.amount = "TESTKUDOS:3.3"
                    walletBankAccount = getBankAccountFromLabel("foo")
                    selectedExchangePayto = "payto://iban/SANDBOXX/${BAR_USER_IBAN}"
                    reservePub = "not used"
                    selectionDone = true
                }
            }
            testApplication {
                application(sandboxApp)
                // Showing withdrawal info.
                val get_with_account = client.get("/demobanks/default/access-api/accounts/foo/withdrawals/${wo.wopid}") {
                    expectSuccess = true
                }
                val get_without_account = client.get("/demobanks/default/access-api/withdrawals/${wo.wopid}") {
                    expectSuccess = true
                }
                assert(get_without_account.bodyAsText() == get_with_account.bodyAsText())
                assert(get_with_account.bodyAsText() == get_without_account.bodyAsText())
                // Confirming a withdrawal.
                val confirm_with_account = client.post("/demobanks/default/access-api/accounts/foo/withdrawals/${wo.wopid}/confirm") {
                    expectSuccess = true
                }
                val confirm_without_account = client.post("/demobanks/default/access-api/withdrawals/${wo.wopid}/confirm") {
                    expectSuccess = true
                }
                assert(confirm_with_account.status.value == confirm_without_account.status.value)
                assert(confirm_with_account.bodyAsText() == confirm_without_account.bodyAsText())
                // Aborting one withdrawal.
                var wo_to_abort = transaction {
                    TalerWithdrawalEntity.new {
                        this.amount = "TESTKUDOS:3.3"
                        walletBankAccount = getBankAccountFromLabel("foo")
                        selectedExchangePayto = "payto://iban/SANDBOXX/${BAR_USER_IBAN}"
                        reservePub = "not used"
                        selectionDone = true
                    }
                }
                val abort_with_account = client.post("/demobanks/default/access-api/accounts/foo/withdrawals/${wo_to_abort.wopid}/abort") {
                    expectSuccess = true
                }
                wo_to_abort = transaction {
                    TalerWithdrawalEntity.new {
                        this.amount = "TESTKUDOS:3.3"
                        walletBankAccount = getBankAccountFromLabel("foo")
                        selectedExchangePayto = "payto://iban/SANDBOXX/${BAR_USER_IBAN}"
                        reservePub = "not used"
                        selectionDone = true
                    }
                }
                val abort_without_account = client.post("/demobanks/default/access-api/withdrawals/${wo_to_abort.wopid}/abort") {
                    expectSuccess = true
                }
                assert(abort_with_account.status.value == abort_without_account.status.value)
                // Not checking the content as they abort two different operations.
            }
        }
    }

    // Move funds between accounts.
    @Test
    fun wireTransfer() {
        withTestDatabase {
            prepSandboxDb()
            testApplication {
                application(sandboxApp)
                runBlocking {
                    // Foo gives 20 to Bar
                    client.post("/demobanks/default/access-api/accounts/foo/transactions") {
                        expectSuccess = true
                        contentType(ContentType.Application.Json)
                        basicAuth("foo", "foo")
                        setBody("""{
                            "paytoUri": "payto://iban/${BAR_USER_IBAN}?message=test",
                            "amount": "TESTKUDOS:20"
                        }""".trimIndent()
                        )
                    }
                    // Foo checks its balance: -20
                    var R = client.get("/demobanks/default/access-api/accounts/foo") {
                        basicAuth("foo", "foo")
                    }
                    val mapper = ObjectMapper()
                    var j = mapper.readTree(R.readBytes())
                    assert(j.get("balance").get("amount").asText() == "TESTKUDOS:20")
                    assert(j.get("balance").get("credit_debit_indicator").asText().lowercase() == "debit")
                    // Bar checks its balance: 20
                    R = client.get("/demobanks/default/access-api/accounts/bar") {
                        basicAuth("bar", "bar")
                    }
                    j = mapper.readTree(R.readBytes())
                    assert(j.get("balance").get("amount").asText() == "TESTKUDOS:20")
                    assert(j.get("balance").get("credit_debit_indicator").asText().lowercase() == "credit")
                    // Foo tries with an invalid amount
                    R = client.post("/demobanks/default/access-api/accounts/foo/transactions") {
                        contentType(ContentType.Application.Json)
                        basicAuth("foo", "foo")
                        setBody("""{
                            "paytoUri": "payto://iban/${BAR_USER_IBAN}?message=test",
                            "amount": "TESTKUDOS:20.001"
                        }""".trimIndent()
                        )
                    }
                    assert(R.status.value == HttpStatusCode.BadRequest.value)
                }
            }
        }
    }

    // Tests the time range filter of Access API's GET /transactions
    @Test
    fun timeRangedTransactions() {
        fun getTxs(respJson: String): JsonNode {
            val mapper = ObjectMapper()
            return mapper.readTree(respJson).get("transactions")
        }
        withTestDatabase {
            prepSandboxDb()
            testApplication {
                application(sandboxApp)
                var R = client.get("/demobanks/default/access-api/accounts/foo/transactions") {
                    expectSuccess = true
                    basicAuth("foo", "foo")
                }
                assert(getTxs(R.bodyAsText()).size() == 0) // Checking that no transactions exist.
                wireTransfer(
                    "admin",
                    "foo",
                    "default",
                    "#0",
                    "TESTKUDOS:2"
                )
                R = client.get("/demobanks/default/access-api/accounts/foo/transactions") {
                    expectSuccess = true
                    basicAuth("foo", "foo")
                }
                assert(getTxs(R.bodyAsText()).size() == 1) // Checking that #0 shows up.
                // Asking up to a point in the past, where no txs should exist.
                R = client.get("/demobanks/default/access-api/accounts/foo/transactions?until_ms=3000") {
                    expectSuccess = true
                    basicAuth("foo", "foo")
                }
                assert(getTxs(R.bodyAsText()).size() == 0) // Not expecting any transaction.
                // Moving the transaction back in the past
                transaction {
                    val tx_0 = BankAccountTransactionEntity.find {
                        BankAccountTransactionsTable.subject eq "#0" and
                                (BankAccountTransactionsTable.direction eq "CRDT")
                    }.first()
                    tx_0.date = 10000
                }
                // Picking the past transaction from one including time range,
                // therefore expecting one entry in the result
                R = client.get("/demobanks/default/access-api/accounts/foo/transactions?from_ms=9000&until_ms=11000") {
                    expectSuccess = true
                    basicAuth("foo", "foo")
                }
                assert(getTxs(R.bodyAsText()).size() == 1)
                // Not enough txs to fill the second page, expecting no txs therefore.
                R = client.get("/demobanks/default/access-api/accounts/foo/transactions?page=2&size=1") {
                    expectSuccess = true
                    basicAuth("foo", "foo")
                }
                assert(getTxs(R.bodyAsText()).size() == 0)
                // Creating one more tx and asking the second 1-sized page, expecting therefore one result.
                wireTransfer(
                    "admin",
                    "foo",
                    "default",
                    "#1",
                    "TESTKUDOS:2"
                )
                R = client.get("/demobanks/default/access-api/accounts/foo/transactions?page=2&size=1") {
                    expectSuccess = true
                    basicAuth("foo", "foo")
                }
                assert(getTxs(R.bodyAsText()).size() == 1)
            }
        }
    }

    // Tests for #7482
    @Test
    fun highAmountWithdraw() {
        withTestDatabase {
            prepSandboxDb(usersDebtLimit = 900000000)
            testApplication {
                application(sandboxApp)
                // Create the operation.
                val r = client.post("/demobanks/default/access-api/accounts/foo/withdrawals") {
                    expectSuccess = true
                    setBody("{\"amount\": \"TESTKUDOS:500000000\"}")
                    contentType(ContentType.Application.Json)
                    basicAuth("foo", "foo")
                }
                println(r.bodyAsText())
                val j = mapper.readTree(r.readBytes())
                val op = j.get("withdrawal_id").asText()
                // Select exchange and specify a reserve pub.
                client.post("/demobanks/default/integration-api/withdrawal-operation/$op") {
                    expectSuccess = true
                    contentType(ContentType.Application.Json)
                    setBody("""{
                        "selected_exchange":"payto://iban/${BAR_USER_IBAN}",
                        "reserve_pub": "not-used"
                    }""".trimIndent())
                }
                // Confirm the operation.
                client.post("/demobanks/default/access-api/accounts/foo/withdrawals/$op/confirm") {
                    expectSuccess = true
                    basicAuth("foo", "foo")
                }
                // Check the withdrawal amount in the unique transaction.
                val t = client.get("/demobanks/default/access-api/accounts/foo/transactions") {
                    basicAuth("foo", "foo")
                }
                val amount = mapper.readTree(t.readBytes()).get("transactions").get(0).get("amount").asText()
                assert(amount == "500000000")
            }
        }
    }
    @Test
    fun withdrawWithHighBalance() {
        withTestDatabase {
            prepSandboxDb()
            /**
             * A problem appeared (Sandbox responding "insufficient funds")
             * when B - A > T, where B is the balance, A the potential amount
             * to withdraw and T is the debit threshold for the user.  T is
             * 1000 here, therefore setting B as 2000 and A as 1 should get
             * this case tested.
             */
            wireTransfer(
                "admin",
                "foo",
                "default",
                "bring balance to high amount",
                "TESTKUDOS:2000"
            )
            testApplication {
                this.application(sandboxApp)
                runBlocking {
                    client.post("/demobanks/default/access-api/accounts/foo/withdrawals") {
                        expectSuccess = true
                        setBody("{\"amount\": \"TESTKUDOS:1\"}")
                        contentType(ContentType.Application.Json)
                        basicAuth("foo", "foo")
                    }
                }
            }
        }
    }
    // Check successful and failing case due to insufficient funds.
    @Test
    fun debitWithdraw() {
        withTestDatabase {
            prepSandboxDb()
            testApplication {
                this.application(sandboxApp)
                runBlocking {
                    // Normal, successful withdrawal.
                    client.post("/demobanks/default/access-api/accounts/foo/withdrawals") {
                        expectSuccess = true
                        setBody("{\"amount\": \"TESTKUDOS:1\"}")
                        contentType(ContentType.Application.Json)
                        basicAuth("foo", "foo")
                    }
                    // Withdrawal over the debit threshold.
                    val r: HttpResponse = client.post("/demobanks/default/access-api/accounts/foo/withdrawals") {
                        expectSuccess = false
                        contentType(ContentType.Application.Json)
                        basicAuth("foo", "foo")
                        setBody("{\"amount\": \"TESTKUDOS:99999999999\"}")
                    }
                    assert(HttpStatusCode.Forbidden.value == r.status.value)
                }
            }
        }
    }

    /**
     * Tests that 'admin' and 'bank' are not possible to register
     * and that after 'admin' logs in it gets access to the bank's
     * main account.
     */ // FIXME: avoid giving Content-Type at every request.
    @Test
    fun adminRegisterAndLoginTest() {
        withTestDatabase {
            prepSandboxDb()
            testApplication {
                application(sandboxApp)
                runBlocking {
                    val registerAdmin = mapper.writeValueAsString(object {
                        val username = "admin"
                        val password = "y"
                    })
                    val registerBank = mapper.writeValueAsString(object {
                        val username = "bank"
                        val password = "y"
                    })
                    for (b in mutableListOf<String>(registerAdmin, registerBank)) {
                        val r = client.post("/demobanks/default/access-api/testing/register") {
                            setBody(b)
                            contentType(ContentType.Application.Json)
                            expectSuccess = false
                        }
                        assert(r.status.value == HttpStatusCode.Forbidden.value)
                    }
                    // Set arbitrary balance to the bank.
                    wireTransfer(
                        "foo",
                        "admin",
                        "default",
                        "setting the balance",
                        "TESTKUDOS:99"
                    )
                    // Get admin's balance.  Not asserting; it
                    // fails on != 200 responses.
                    val r = client.get("/demobanks/default/access-api/accounts/admin") {
                        expectSuccess = true
                        basicAuth("admin", "foo")
                    }
                    println(r)
                }
            }
        }
    }

    // Checks that the debit threshold belongs to the balance response.
    @Test
    fun debitInfoCheck() {
        withTestDatabase {
            prepSandboxDb()
            testApplication {
                application(sandboxApp)
                var r = client.get("/demobanks/default/access-api/accounts/foo") {
                    expectSuccess = true
                    basicAuth("foo", "foo")
                }
                // Checking that the response holds the debit threshold.
                val mapper = ObjectMapper()
                var respJson = mapper.readTree(r.bodyAsText())
                var debitThreshold = respJson.get("debitThreshold").asText()
                assert(debitThreshold == "1000")
                r = client.get("/demobanks/default/access-api/accounts/admin") {
                    expectSuccess = true
                    basicAuth("admin", "foo")
                }
                respJson = mapper.readTree(r.bodyAsText())
                debitThreshold = respJson.get("debitThreshold").asText()
                assert(debitThreshold == "10000")
            }
        }
    }

    @Test
    fun registerTest() {
        // Test IBAN conflict detection.
        withSandboxTestDatabase {
            testApplication {
                application(sandboxApp)
                runBlocking {
                    val bodyFoo = mapper.writeValueAsString(object {
                        val username = "x"
                        val password = "y"
                        val iban = FOO_USER_IBAN
                    })
                    val bodyBar = mapper.writeValueAsString(object {
                        val username = "y"
                        val password = "y"
                        val iban = FOO_USER_IBAN // conflicts
                    })
                    val bodyBaz = mapper.writeValueAsString(object {
                        val username = "y"
                        val password = "y"
                        val iban = BAR_USER_IBAN
                    })
                    // Succeeds.
                    client.post("/demobanks/default/access-api/testing/register") {
                        setBody(bodyFoo)
                        contentType(ContentType.Application.Json)
                        expectSuccess = true
                    }
                    // Hits conflict, because of the same IBAN.
                    val r = client.post("/demobanks/default/access-api/testing/register") {
                        setBody(bodyBar)
                        expectSuccess = false
                        contentType(ContentType.Application.Json)
                    }
                    assert(r.status.value == HttpStatusCode.Conflict.value)
                    // Succeeds, because of a new IBAN.
                    client.post("/demobanks/default/access-api/testing/register") {
                        setBody(bodyBaz)
                        expectSuccess = true
                        contentType(ContentType.Application.Json)
                    }
                }
            }

        }
    }
}