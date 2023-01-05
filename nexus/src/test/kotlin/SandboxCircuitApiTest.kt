import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.util.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Test
import tech.libeufin.nexus.server.client
import tech.libeufin.sandbox.*

class SandboxCircuitApiTest {
    // Get /config, fails if != 200.
    @Test
    fun config() {
        withSandboxTestDatabase {
            testApplication {
                application(sandboxApp)
                runBlocking {
                    val r= client.get("/demobanks/default/circuit-api/config")
                    println(r.bodyAsText())
                }
            }
        }
    }
    @Test
    fun contactDataValidation() {
        // Phone number.
        assert(checkPhoneNumber("+987"))
        assert(!checkPhoneNumber("987"))
        assert(!checkPhoneNumber("foo"))
        assert(!checkPhoneNumber(""))
        assert(!checkPhoneNumber("+00"))
        assert(checkPhoneNumber("+4900"))
        // E-mail address
        assert(checkEmailAddress("test@example.com"))
        assert(!checkEmailAddress("0@0.0"))
        assert(!checkEmailAddress("foo.bar"))
        assert(checkEmailAddress("foo.bar@example.com"))
        assert(!checkEmailAddress("foo+bar@example.com"))
    }

    // Test the creation and confirmation of a cash-out operation.
    @Test
    fun cashout() {
        withTestDatabase {
            prepSandboxDb()
            testApplication {
                application(sandboxApp)
                // Register a new account.
                var R = client.post("/demobanks/default/circuit-api/accounts") {
                    expectSuccess = true
                    contentType(ContentType.Application.Json)
                    basicAuth("admin", "foo")
                    setBody("""
                            {"username":"shop",
                             "password": "secret",
                             "contact_data": {},
                             "name": "Test",
                             "cashout_address": "payto://iban/SAMPLE"                   
                             }
                        """.trimIndent())
                }
                // Give initial balance to the new account.
                val demobank = getDefaultDemobank()
                transaction { demobank.usersDebtLimit = 0 }
                val initialBalance = "TESTKUDOS:50.00"
                val balanceAfterCashout = "TESTKUDOS:30.00"
                wireTransfer(
                    debitAccount = "admin",
                    creditAccount = "shop",
                    subject = "cash-out",
                    amount = initialBalance
                )
                // Check the balance before cashing out.
                R = client.get("/demobanks/default/access-api/accounts/shop") {
                    basicAuth("shop", "secret")
                }
                val mapper = ObjectMapper()
                var respJson = mapper.readTree(R.bodyAsText())
                assert(respJson.get("balance").get("amount").asText() == initialBalance)
                // Configure the user phone number, before the cash-out.
                R = client.patch("/demobanks/default/circuit-api/accounts/shop") {
                    contentType(ContentType.Application.Json)
                    basicAuth("shop", "secret")
                    setBody("""
                        {
                          "contact_data": {
                            "phone": "+98765"
                          },
                          "cashout_address": "payto://iban/SAMPLE"
                        }
                    """.trimIndent())
                }
                assert(R.status.value == HttpStatusCode.NoContent.value)
                /**
                 * Cash-out a portion.  Ordering a cash-out of 20 TESTKUDOS
                 * should result in the following final amount, that the user
                 * will see as incoming in the fiat bank account: 19 = 20 * 0.95 - 0.00.
                 * Note: ratios and fees are currently hard-coded.
                 */
                R = client.post("/demobanks/default/circuit-api/cashouts") {
                    contentType(ContentType.Application.Json)
                    basicAuth("shop", "secret")
                    setBody("""{
                        "amount_debit": "TESTKUDOS:20",
                        "amount_credit": "KUDOS:19"
                    }""".trimIndent())
                }
                assert(R.status.value == HttpStatusCode.Accepted.value)
                var operationUuid = mapper.readTree(R.readBytes()).get("uuid").asText()
                // Check that the operation is found by the bank.
                R = client.get("/demobanks/default/circuit-api/cashouts/${operationUuid}") {
                    // Asking as the Admin but for the 'shop' account.
                    basicAuth("admin", "foo")
                }
                // Check that the status is pending.
                assert(mapper.readTree(R.readBytes()).get("status").asText() == "PENDING")
                // Now confirm the operation.
                client.post("/demobanks/default/circuit-api/cashouts/${operationUuid}/confirm") {
                    basicAuth("shop", "secret")
                    contentType(ContentType.Application.Json)
                    setBody("{\"tan\":\"foo\"}")
                    expectSuccess = true
                }
                // Check that the operation is found by the bank and set to 'confirmed'.
                R = client.get("/demobanks/default/circuit-api/cashouts/${operationUuid}") {
                    // Asking as the Admin but for the 'shop' account.
                    basicAuth("foo", "foo")
                }
                assert(mapper.readTree(R.readBytes()).get("status").asText() == "CONFIRMED")
                // Check that the amount got deducted by the account.
                R = client.get("/demobanks/default/access-api/accounts/shop") {
                    basicAuth("shop", "secret")
                }
                respJson = mapper.readTree(R.bodyAsText())
                assert(respJson.get("balance").get("amount").asText() == balanceAfterCashout)

                // Create a new cash-out and delete it.
                R = client.post("/demobanks/default/circuit-api/cashouts") {
                    contentType(ContentType.Application.Json)
                    basicAuth("shop", "secret")
                    setBody("""{
                        "amount_debit": "TESTKUDOS:20",
                        "amount_credit": "KUDOS:19"
                    }""".trimIndent())
                }
                assert(R.status.value == HttpStatusCode.Accepted.value)
                val toAbort = mapper.readTree(R.readBytes()).get("uuid").asText()
                // Check it exists.
                R = client.get("/demobanks/default/circuit-api/cashouts/${toAbort}") {
                    // Asking as the Admin but for the 'shop' account.
                    basicAuth("foo", "foo")
                }
                assert(R.status.value == HttpStatusCode.OK.value)
                // Ask to delete the operation.
                R = client.post("/demobanks/default/circuit-api/cashouts/${toAbort}/abort") {
                    basicAuth("admin", "foo")
                }
                assert(R.status.value == HttpStatusCode.NoContent.value)
                // Check actual disappearance.
                R = client.get("/demobanks/default/circuit-api/cashouts/${toAbort}") {
                    // Asking as the Admin but for the 'shop' account.
                    basicAuth("foo", "foo")
                }
                assert(R.status.value == HttpStatusCode.NotFound.value)
                // Ask to delete a confirmed operation.
                R = client.post("/demobanks/default/circuit-api/cashouts/${operationUuid}/abort") {
                    basicAuth("admin", "foo")
                }
                assert(R.status.value == HttpStatusCode.PreconditionFailed.value)
            }
        }
    }

    // Test user registration and deletion.
    @Test
    fun registration() {
        withSandboxTestDatabase {
            testApplication {
                application(sandboxApp)
                runBlocking {
                    // Successful registration.
                    var R = client.post("/demobanks/default/circuit-api/accounts") {
                        expectSuccess = true
                        contentType(ContentType.Application.Json)
                        basicAuth("admin", "foo")
                        setBody("""
                            {"username":"shop",
                             "password": "secret",
                             "contact_data": {},
                             "name": "Test",
                             "cashout_address": "payto://iban/SAMPLE"                   
                             }
                        """.trimIndent())
                    }
                    assert(R.status.value == HttpStatusCode.NoContent.value)
                    // Check accounts list.
                    R = client.get("/demobanks/default/circuit-api/accounts") {
                        basicAuth("admin", "foo")
                        expectSuccess = true
                    }
                    println(R.bodyAsText())
                    // Update contact data.
                    R = client.patch("/demobanks/default/circuit-api/accounts/shop") {
                        contentType(ContentType.Application.Json)
                        basicAuth("shop", "secret")
                        setBody("""
                            {"contact_data": {"email": "user@example.com"},
                             "cashout_address": "payto://iban/SAMPLE"
                            }
                        """.trimIndent())
                    }
                    assert(R.status.value == HttpStatusCode.NoContent.value)
                    // Get user data via the Access API.
                    R = client.get("/demobanks/default/access-api/accounts/shop") {
                        basicAuth("shop", "secret")
                    }
                    assert(R.status.value == HttpStatusCode.OK.value)
                    // Get Circuit data via the Circuit API.
                    R = client.get("/demobanks/default/circuit-api/accounts/shop") {
                        basicAuth("shop", "secret")
                    }
                    println(R.bodyAsText())
                    assert(R.status.value == HttpStatusCode.OK.value)
                    // Change password.
                    R = client.patch("/demobanks/default/circuit-api/accounts/shop/auth") {
                        basicAuth("shop", "secret")
                        setBody("{\"new_password\":\"new_secret\"}")
                        contentType(ContentType.Application.Json)
                    }
                    assert(R.status.value == HttpStatusCode.NoContent.value)
                    // Check that the password changed: expect 401 with previous password.
                    R = client.get("/demobanks/default/access-api/accounts/shop") {
                        basicAuth("shop", "secret")
                    }
                    assert(R.status.value == HttpStatusCode.Unauthorized.value)
                    // Check that the password changed: expect 200 with current password.
                    R = client.get("/demobanks/default/access-api/accounts/shop") {
                        basicAuth("shop", "new_secret")
                    }
                    assert(R.status.value == HttpStatusCode.OK.value)
                    // Change user balance.
                    transaction {
                        val account = BankAccountEntity.find {
                            BankAccountsTable.label eq "shop"
                        }.firstOrNull() ?: throw Exception("Circuit test account not found in the database!")
                        account.bonus("TESTKUDOS:30")
                        account
                    }
                    // Delete account.  Fails because the balance is not zero.
                    R = client.delete("/demobanks/default/circuit-api/accounts/shop") {
                        basicAuth("admin", "foo")
                    }
                    assert(R.status.value == HttpStatusCode.PreconditionFailed.value)
                    // Bring the balance again to zero
                    transaction {
                        wireTransfer(
                            "shop",
                            "admin",
                            "default",
                            "deletion condition",
                            "TESTKUDOS:30"
                        )
                    }
                    // Now delete the account successfully.
                    R = client.delete("/demobanks/default/circuit-api/accounts/shop") {
                        basicAuth("admin", "foo")
                    }
                    assert(R.status.value == HttpStatusCode.NoContent.value)
                    // Check actual deletion.
                    R = client.get("/demobanks/default/access-api/accounts/shop") {
                        basicAuth("shop", "secret")
                    }
                    assert(R.status.value == HttpStatusCode.NotFound.value)
                }
            }
        }
    }
}