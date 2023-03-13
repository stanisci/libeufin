import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Ignore
import org.junit.Test
import tech.libeufin.sandbox.*
import java.io.File
import java.math.BigDecimal
import java.util.*

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

    /**
     * Checking that the ordinary user foo doesn't get to access bar's
     * data, but admin does.
     */
    @Test
    fun accessAccountsTest() {
        withTestDatabase {
            prepSandboxDb()
            testApplication {
                application(sandboxApp)
                val R = client.get("/demobanks/default/circuit-api/accounts/bar") {
                    basicAuth("foo", "foo")
                    expectSuccess = false
                }
                assert(R.status.value == HttpStatusCode.Forbidden.value)
                client.get("/demobanks/default/circuit-api/accounts/bar") {
                    basicAuth("admin", "foo")
                    expectSuccess = true
                }
            }
        }
    }
    // Only tests that the calls get a 2xx status code.
    @Test
    fun listAccountsTest() {
        withTestDatabase {
            prepSandboxDb()
            testApplication {
                application(sandboxApp)
                var R = client.get("/demobanks/default/circuit-api/accounts") {
                    basicAuth("admin", "foo")
                }
                println(R.bodyAsText())
                client.get("/demobanks/default/circuit-api/accounts/baz") {
                    basicAuth("admin", "foo")
                }
            }
        }
    }
    @Test
    fun badUuidTest() {
        withTestDatabase {
            prepSandboxDb()
            testApplication {
                application(sandboxApp)
                val R = client.post("/demobanks/default/circuit-api/cashouts/---invalid_UUID---/confirm") {
                    expectSuccess = false
                    basicAuth("foo", "foo")
                    contentType(ContentType.Application.Json)
                    setBody("{\"tan\":\"foo\"}")
                }
                assert(R.status.value == HttpStatusCode.BadRequest.value)
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

    @Test
    fun listCashouts() {
        withTestDatabase {
            prepSandboxDb()
            testApplication {
                application(sandboxApp)
                var R = client.get("/demobanks/default/circuit-api/cashouts") {
                    expectSuccess = true
                    basicAuth("admin", "foo")
                }
                assert(R.status.value == HttpStatusCode.NoContent.value)
                transaction {
                    CashoutOperationEntity.new {
                        tan = "unused"
                        uuid = UUID.randomUUID()
                        amountDebit = "unused"
                        amountCredit = "unused"
                        subject = "unused"
                        creationTime = 0L
                        tanChannel = SupportedTanChannels.FILE // change type to enum?
                        account = "foo"
                        status = CashoutOperationStatus.PENDING
                        cashoutAddress = "not used"
                        buyAtRatio = "1"
                        buyInFee = "1"
                        sellAtRatio = "1"
                        sellOutFee = "1"
                    }
                }
                R = client.get("/demobanks/default/circuit-api/cashouts") {
                    expectSuccess = true
                    basicAuth("admin", "foo")
                }
                assert(R.status.value == HttpStatusCode.OK.value)
                // Extract the UUID and check it.
                val mapper = ObjectMapper()
                var respJson = mapper.readTree(R.bodyAsText())
                val uuid = respJson.get("cashouts").get(0).asText()
                R = client.get("/demobanks/default/circuit-api/cashouts/$uuid") {
                    expectSuccess = true
                    basicAuth("admin", "foo")
                }
                assert(R.status.value == HttpStatusCode.OK.value)
                respJson = mapper.readTree(R.bodyAsText())
                val status = respJson.get("status").asText()
                assert(status.uppercase() == "PENDING")
                println(R.bodyAsText())
                // Check that bar doesn't get foo's cash-out
                R = client.get("/demobanks/default/circuit-api/cashouts?account=foo") {
                    expectSuccess = false
                    basicAuth("bar", "bar")
                }
                assert(R.status.value == HttpStatusCode.Forbidden.value)
                // Check that foo can get its own
                R = client.get("/demobanks/default/circuit-api/cashouts?account=foo") {
                    expectSuccess = false
                    basicAuth("foo", "foo")
                }
                assert(R.status.value == HttpStatusCode.OK.value)
            }
        }
    }

    // Testing that only the admin can change an account legal name.
    @Test
    fun patchPerm() {
        withTestDatabase {
            prepSandboxDb()
            testApplication {
                application(sandboxApp)
                val R =client.patch("/demobanks/default/circuit-api/accounts/foo") {
                    contentType(ContentType.Application.Json)
                    basicAuth("foo", "foo")
                    expectSuccess = false
                    setBody("""
                        {
                          "name": "new name",
                          "contact_data": {},
                          "cashout_address": "payto://iban/OUTSIDE"
                        }
                    """.trimIndent())
                }
                assert(R.status.value == HttpStatusCode.Forbidden.value)
                client.patch("/demobanks/default/circuit-api/accounts/foo") {
                    contentType(ContentType.Application.Json)
                    basicAuth("admin", "foo")
                    expectSuccess = true
                    setBody("""
                        {
                          "name": "new name",
                          "contact_data": {},
                          "cashout_address": "payto://iban/OUTSIDE"
                        }
                    """.trimIndent())
                }
            }
        }
    }
    // Tests the creation and confirmation of a cash-out operation.
    @Test
    fun cashout() {
        withTestDatabase {
            prepSandboxDb()
            testApplication {
                application(sandboxApp)
                // Register a new account.
                client.post("/demobanks/default/circuit-api/accounts") {
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
                // Forcing different debt limit:
                transaction {
                    val configRaw = DemobankConfigPairEntity.find {
                        DemobankConfigPairsTable.demobankName eq "default" and(
                                DemobankConfigPairsTable.configKey eq "usersDebtLimit"
                                )
                    }.first()
                    configRaw.configValue = 0.toString()
                }
                val initialBalance = "TESTKUDOS:50.00"
                val balanceAfterCashout = "TESTKUDOS:30.00"
                wireTransfer(
                    debitAccount = "admin",
                    creditAccount = "shop",
                    subject = "cash-out",
                    amount = initialBalance
                )
                // Check the balance before cashing out.
                var R = client.get("/demobanks/default/access-api/accounts/shop") {
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
                        "amount_credit": "CHF:19",
                        "tan_channel": "file"
                    }""".trimIndent())
                }
                assert(R.status.value == HttpStatusCode.Accepted.value)
                val operationUuid = mapper.readTree(R.readBytes()).get("uuid").asText()
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
                // Attempt to cash-out with wrong regional currency.
                R = client.post("/demobanks/default/circuit-api/cashouts") {
                    contentType(ContentType.Application.Json)
                    basicAuth("shop", "secret")
                    setBody("""{
                        "amount_debit": "NOTFOUND:20",
                        "amount_credit": "CHF:19",
                        "tan_channel": "file"
                    }""".trimIndent())
                    expectSuccess = false
                }
                assert(R.status.value == HttpStatusCode.BadRequest.value)
                // Attempt to cash-out with wrong fiat currency.
                R = client.post("/demobanks/default/circuit-api/cashouts") {
                    contentType(ContentType.Application.Json)
                    basicAuth("shop", "secret")
                    setBody("""{
                        "amount_debit": "TESTKUDOS:20",
                        "amount_credit": "NOTFOUND:19",
                        "tan_channel": "file"
                    }""".trimIndent())
                    expectSuccess = false
                }
                assert(R.status.value == HttpStatusCode.BadRequest.value)
                // Create a new cash-out and delete it.
                R = client.post("/demobanks/default/circuit-api/cashouts") {
                    contentType(ContentType.Application.Json)
                    basicAuth("shop", "secret")
                    setBody("""{
                        "amount_debit": "TESTKUDOS:20",
                        "amount_credit": "CHF:19",
                        "tan_channel": "file"
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

    // Tests the database RegEx filter on customer names.
    @Ignore // Since no assert takes place.
    @Test
    fun customerFilter() {
        withTestDatabase {
            prepSandboxDb()
            testApplication {
                application(sandboxApp)
                val R = client.get("/demobanks/default/circuit-api/accounts?filter=b") {
                    basicAuth("admin", "foo")
                    expectSuccess = true
                }
                println(R.bodyAsText())
            }
        }
    }

    /**
     * Testing that deleting a user doesn't cause a _different_ user
     * to lose data.
     */
    @Test
    fun deletionIsolation() {
        withTestDatabase {
            prepSandboxDb()
            transaction {
                // Admin makes sure foo has balance 100.
                wireTransfer(
                    "admin",
                    "foo",
                    subject = "set to 100",
                    amount = "TESTKUDOS:100"
                )
                val fooBalance = getBalance("foo")
                assert(fooBalance == BigDecimal("100"))
                // Foo pays 3 to bar.
                wireTransfer(
                    "foo",
                    "bar",
                    subject = "donation",
                    amount = "TESTKUDOS:3"
                )
                val barBalance = getBalance("bar")
                assert(barBalance == BigDecimal("3"))
                // Deleting foo from the system.
                transaction {
                    val uBankAccount = getBankAccountFromLabel("foo")
                    val uCustomerProfile = getCustomer("foo")
                    uBankAccount.delete()
                    uCustomerProfile.delete()
                }
                val barBalanceUpdate = getBalance("bar")
                assert(barBalanceUpdate == BigDecimal("3"))
            }
        }
    }

    @Test
    fun tanCommandTest() {
        /**
         * 'tee' allows to test the SMS/e-mail command execution
         * because it relates to STDIN and the first command line argument
         * in the same way the SMS/e-mail command is expected to.
         */
        val tanLocation = File("/tmp/libeufin-tan-cmd-test.txt")
        val tanContent = "libeufin"
        if (tanLocation.exists()) tanLocation.delete()
        runTanCommand(
            command = "tee",
            address = tanLocation.path,
            message = tanContent
        )
        val maybeTan = tanLocation.readText()
        assert(maybeTan == tanContent)
    }
}