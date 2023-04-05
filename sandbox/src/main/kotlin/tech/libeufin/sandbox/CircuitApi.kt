package tech.libeufin.sandbox

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.sandbox.CashoutOperationsTable.uuid
import tech.libeufin.util.*
import java.io.File
import java.io.InputStreamReader
import java.math.BigDecimal
import java.math.MathContext
import java.util.concurrent.TimeUnit
import kotlin.text.toByteArray

// CIRCUIT API TYPES
/**
 * This type is used by clients to ask the bank a cash-out
 * estimate to show to the customer before they confirm the
 * cash-out creation.
 */
data class CircuitCashoutEstimateRequest(
    /**
     * This is the amount that the customer will get deducted
     * from their regio bank account to fuel the cash-out operation.
     */
    val amount_debit: String
)
data class CircuitCashoutRequest(
    val subject: String?,
    val amount_debit: String, // As specified by the user via the SPA.
    val amount_credit: String, // What actually to transfer after the rates.
    /**
     * The String type here allows more flexibility with regard to
     * the supported TAN methods.  This way, supported TAN methods
     * can be specified via the configuration or when starting the
     * bank.  OTOH, catching unsupported TAN methods only via the
     * 'enum' type would require to change the source code upon every
     * change in the TAN policy.
     */
    val tan_channel: String?
)
const val FIAT_CURRENCY = "CHF" // FIXME: make configurable.
// Configuration response:
data class ConfigResp(
    val name: String = "circuit",
    val version: String = SANDBOX_VERSION,
    val ratios_and_fees: RatioAndFees,
    val fiat_currency: String = FIAT_CURRENCY
)

// After fixing #7527, the values held by this
// type must be read from the configuration.
data class RatioAndFees(
    val buy_at_ratio: Float = 1F,
    val sell_at_ratio: Float = 0.95F,
    val buy_in_fee: Float = 0F,
    val sell_out_fee: Float = 0F
)
val ratiosAndFees = RatioAndFees()

// User registration request
data class CircuitAccountRequest(
    val username: String,
    val password: String,
    val contact_data: CircuitContactData,
    val name: String,
    val cashout_address: String, // payto
    val internal_iban: String? // Shall be "= null" ?
)
// User contact data to send the TAN.
data class CircuitContactData(
    val email: String?,
    val phone: String?
)

data class CircuitAccountReconfiguration(
    val contact_data: CircuitContactData,
    val cashout_address: String,
    val name: String? = null
)

data class AccountPasswordChange(
    val new_password: String
)

/**
 * That doesn't belong to the Access API because it
 * contains the cash-out address and the contact data.
 */
data class CircuitAccountInfo(
    val username: String,
    val iban: String,
    val contact_data: CircuitContactData,
    val name: String,
    val cashout_address: String
)

data class CashoutOperationInfo(
    val status: CashoutOperationStatus,
    val amount_credit: String,
    val amount_debit: String,
    val subject: String,
    val creation_time: Long, // milliseconds
    val confirmation_time: Long?, // milliseconds
    val tan_channel: SupportedTanChannels,
    val account: String,
    val cashout_address: String,
    val ratios_and_fees: RatioAndFees
)

data class CashoutConfirmation(val tan: String)

// Validate phone number
fun checkPhoneNumber(phoneNumber: String): Boolean {
    // From Taler TypeScript
    // /^\+[0-9 ]*$/;
    val regex = "^\\+[1-9][0-9]+$"
    val R = Regex(regex)
    return R.matches(phoneNumber)
}

// Validate e-mail address
fun checkEmailAddress(emailAddress: String): Boolean {
    // From Taler TypeScript:
    // /^(([^<>()[\]\\.,;:\s@"]+(\.[^<>()[\]\\.,;:\s@"]+)*)|(".+"))@((\[[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}])|(([a-zA-Z\-0-9]+\.)+[a-zA-Z]{2,}))$/;
    val regex = "^[a-z0-9\\.]+@[a-z0-9\\.]+\\.[a-z]{2,3}$"
    val R = Regex(regex)
    return R.matches(emailAddress)
}

fun throwIfInstitutionalName(resourceName: String) {
    if (resourceName == "bank" || resourceName == "admin")
        throw forbidden("Can't operate on institutional resource '$resourceName'")
}

fun generateCashoutSubject(
    amountCredit: AmountWithCurrency,
    amountDebit: AmountWithCurrency
): String {
    return "Cash-out of ${amountDebit.currency}:${amountDebit.amount}" +
            " to ${amountCredit.currency}:${amountCredit.amount}"
}

fun BigDecimal.roundToTwoDigits(): BigDecimal {
    val twoDigitsRounding = MathContext(2)
    return this.round(twoDigitsRounding)
}

/**
 * By default, it takes the amount in the regional currency
 * and applies ratio and fees to convert it to fiat.  If the
 * 'fromCredit' parameter is true, then it does the inverse
 * operation: returns the regional amount that would lead to
 * such fiat amount given in the 'amount' parameter.
 */
fun applyCashoutRatioAndFee(
    amount: BigDecimal,
    ratiosAndFees: RatioAndFees,
    fromCredit: Boolean = false
): BigDecimal {
    // Normal case, when the calculation starts from the regional amount.
    if (!fromCredit) {
        return ((amount * ratiosAndFees.sell_at_ratio.toBigDecimal()) -
                ratiosAndFees.sell_out_fee.toBigDecimal()).roundToTwoDigits()
    }
    // UI convenient case, when the calculation start from the
    // desired fiat amount that the user wants eventually be paid.
    return ((amount + ratiosAndFees.sell_out_fee.toBigDecimal()) /
            ratiosAndFees.sell_at_ratio.toBigDecimal()).roundToTwoDigits()
}

/**
 * NOTE: future versions take the supported TAN method from
 * the configuration, or options passed when starting the bank.
 */
const val LIBEUFIN_TAN_TMP_FILE = "/tmp/libeufin-cashout-tan.txt"
enum class SupportedTanChannels {
    SMS,
    EMAIL,
    FILE // Test channel writing the TAN to the LIBEUFIN_TAN_TMP_FILE location.
}
fun isTanChannelSupported(tanChannel: String): Boolean {
    enumValues<SupportedTanChannels>().forEach {
        if (tanChannel.uppercase() == it.name) return true
    }
    return false
}

var EMAIL_TAN_CMD: String? = null
var SMS_TAN_CMD: String? = null

/**
 * Runs the command and returns True/False if that succeeded/failed.
 * A failed command causes "500 Internal Server Error" to be responded
 * along a cash-out creation.  'address' is a phone number or a e-mail address,
 * according to which TAN channel is used.  'message' carries the TAN.
 *
 * The caller is expected to manage the exceptions thrown by this function.
 */
fun runTanCommand(command: String, address: String, message: String): Boolean {
    val prep = ProcessBuilder(command, address)
    prep.redirectErrorStream(true) // merge STDOUT and STDERR
    val proc = prep.start()
    proc.outputStream.write(message.toByteArray())
    proc.outputStream.flush(); proc.outputStream.close()
    var isSuccessful = false
    // Wait the command to finish.
    proc.waitFor(10L, TimeUnit.SECONDS)
    // Check if timed out.  Kill if so.
    if (proc.isAlive) {
        logger.error("TAN command '$command' timed out, killing it.")
        proc.destroy()
        // Check if exited gracefully.  Kill forcibly if not.
        proc.waitFor(5L, TimeUnit.SECONDS)
        if (proc.isAlive) {
            logger.error("TAN command '$command' didn't terminate after killing it.  Try forcefully.")
            proc.destroyForcibly()
        }
    }
    // Check if successful.  Switch the state if so.
    if (proc.exitValue() == 0) isSuccessful = true
    // Log STDOUT and STDERR if failed.
    if (!isSuccessful)
        logger.error(InputStreamReader(proc.inputStream).readText())
    return isSuccessful
}

fun circuitApi(circuitRoute: Route) {
    // Abort a cash-out operation.
    circuitRoute.post("/cashouts/{uuid}/abort") {
        call.request.basicAuth() // both admin and author allowed
        val arg = call.expectUriComponent("uuid")
        // Parse and check the UUID.
        val maybeUuid = parseUuid(arg)
        val maybeOperation = transaction {
            CashoutOperationEntity.find { uuid eq maybeUuid }.firstOrNull()
        }
        if (maybeOperation == null)
            throw notFound("Cash-out operation $uuid not found.")
        if (maybeOperation.status == CashoutOperationStatus.CONFIRMED)
            throw SandboxError(
                HttpStatusCode.PreconditionFailed,
                "Cash-out operation '$uuid' was confirmed already."
            )
        if (maybeOperation.status != CashoutOperationStatus.PENDING)
            throw internalServerError("Found an unsupported cash-out operation state: ${maybeOperation.status}")
        // Operation found and pending: delete from the database.
        transaction { maybeOperation.delete() }
        call.respond(HttpStatusCode.NoContent)
        return@post
    }
    // Confirm a cash-out operation
    circuitRoute.post("/cashouts/{uuid}/confirm") {
        val user = call.request.basicAuth()
        // Exclude admin from this operation.
        if (user == "admin" || user == "bank")
            throw conflict("Institutional user '$user' shouldn't confirm any cash-out.")
        // Get the operation identifier.
        val operationUuid = parseUuid(call.expectUriComponent("uuid"))
        val op = transaction {
            CashoutOperationEntity.find {
                uuid eq operationUuid
            }.firstOrNull()
        }
        // 404 if the operation is not found.
        if (op == null)
            throw notFound("Cash-out operation $operationUuid not found")
        /**
         * Check the TAN.  Give precedence to the TAN found
         * in the environment, for testing purposes.  If that's
         * not found, then check with the actual TAN found in
         * the database.
         */
        val req = call.receive<CashoutConfirmation>()
        val maybeTanFromEnv = System.getenv("LIBEUFIN_CASHOUT_TEST_TAN")
        if (maybeTanFromEnv != null)
            logger.warn("TAN being read from the environment.  Assuming tests are being run")
        val checkTan = maybeTanFromEnv ?: op.tan
        if (req.tan != checkTan)
            throw forbidden("The confirmation of '${op.uuid}' has a wrong TAN '${req.tan}'")
        /**
         * Correct TAN.  Wire the funds to the admin's bank account.  After
         * this step, the conversion monitor should detect this payment and
         * soon initiate the final transfer towards the user fiat bank account.
         * NOTE: the funds availability got already checked when this operation
         * was created.  On top of that, the 'wireTransfer()' helper does also
         * check for funds availability.  */
        val customer = maybeGetCustomer(user ?: throw SandboxError(
            HttpStatusCode.ServiceUnavailable,
            "This endpoint isn't served when the authentication is disabled."
        ))
        transaction {
            if (op.cashoutAddress != customer?.cashout_address) throw conflict(
                "Inconsistent cash-out address: ${op.cashoutAddress} vs ${customer?.cashout_address}"
            )
            // 412 if the operation got already confirmed.
            if (op.status == CashoutOperationStatus.CONFIRMED)
                throw SandboxError(
                    HttpStatusCode.PreconditionFailed,
                    "Cash-out operation $operationUuid was already confirmed."
                )
            wireTransfer(
                debitAccount = op.account,
                creditAccount = "admin",
                subject = op.subject,
                amount = op.amountDebit
            )
            op.status = CashoutOperationStatus.CONFIRMED
            op.confirmationTime = getUTCnow().toInstant().toEpochMilli()
        }
        call.respond(HttpStatusCode.NoContent)
        return@post
    }
    // Retrieve the status of a cash-out operation.
    circuitRoute.get("/cashouts/{uuid}") {
        call.request.basicAuth() // both admin and author
        val operationUuid = call.expectUriComponent("uuid")
        // Parse and check the UUID.
        val maybeUuid = parseUuid(operationUuid)
        // Get the operation from the database.
        val maybeOperation = transaction {
            CashoutOperationEntity.find { uuid eq maybeUuid }.firstOrNull()
        }
        if (maybeOperation == null)
            throw notFound("Cash-out operation $operationUuid not found.")
        val ret = CashoutOperationInfo(
            amount_credit = maybeOperation.amountCredit,
            amount_debit = maybeOperation.amountDebit,
            subject = maybeOperation.subject,
            status = maybeOperation.status,
            creation_time = maybeOperation.creationTime,
            confirmation_time = maybeOperation.confirmationTime,
            tan_channel = maybeOperation.tanChannel,
            account = maybeOperation.account,
            cashout_address = maybeOperation.cashoutAddress,
            ratios_and_fees = RatioAndFees(
                buy_in_fee = maybeOperation.buyInFee.toFloat(),
                buy_at_ratio = maybeOperation.buyAtRatio.toFloat(),
                sell_out_fee = maybeOperation.sellOutFee.toFloat(),
                sell_at_ratio = maybeOperation.sellAtRatio.toFloat()
            )
        )
        call.respond(ret)
        return@get
    }
    // Gets the list of all the cash-out operations,
    // or those belonging to the account given as a parameter.
    circuitRoute.get("/cashouts") {
        val user = call.request.basicAuth()
        val whichAccount = call.request.queryParameters["account"]
        /**
         * Only admin's allowed to omit the target account (= get
         * all the accounts) or to check other customers cash-out
         * operations.
         */
        if (user != "admin" && whichAccount != user) throw forbidden(
            "Ordinary users can only request their own account"
        )
        /**
         * At this point, the client has the rights over the account(s)
         * whose operations are to be returned.  Double-checking that
         * Admin doesn't ask its own cash-outs, since that's not supported.
         */
        if (whichAccount == "admin") throw badRequest("Cash-out for admin is not supported")

        // Preparing the response.
        val node = jacksonObjectMapper().createObjectNode()
        val maybeArray = node.putArray("cashouts")

        if (whichAccount == null) { // no target account, return all the cash-outs
            transaction {
                CashoutOperationEntity.all().forEach {
                    maybeArray.add(it.uuid.toString())
                }
            }
        } else { // do filter on the target account.
            transaction {
                CashoutOperationEntity.find {
                    CashoutOperationsTable.account eq whichAccount
                }.forEach {
                    maybeArray.add(it.uuid.toString())
                }
            }
        }
        if (maybeArray.size() == 0) {
            call.respond(HttpStatusCode.NoContent)
            return@get
        }
        call.respond(node)
        return@get
    }
    circuitRoute.get("/cashouts/estimates") {
        call.request.basicAuth()
        val demobank = ensureDemobank(call)
        // Optionally parsing param 'amount_debit' into number and checking its currency
        val maybeAmountDebit: String? = call.request.queryParameters["amount_debit"]
        val amountDebit: BigDecimal? = if (maybeAmountDebit != null) {
            val amount = parseAmount(maybeAmountDebit)
            if (amount.currency != demobank.config.currency) throw badRequest(
                "parameter 'amount_debit' has the wrong currency: ${amount.currency}"
            )
            try { amount.amount.toBigDecimal() } catch (e: Exception) {
                throw badRequest("Cannot extract a number from 'amount_debit'")
            }
        } else null
        // Optionally parsing param 'amount_credit' into number and checking its currency
        val maybeAmountCredit: String? = call.request.queryParameters["amount_credit"]
        val amountCredit: BigDecimal? = if (maybeAmountCredit != null) {
            val amount = parseAmount(maybeAmountCredit)
            if (amount.currency != FIAT_CURRENCY) throw badRequest(
                "parameter 'amount_credit' has the wrong currency: ${amount.currency}"
            )
            try { amount.amount.toBigDecimal() } catch (e: Exception) {
                throw badRequest("Cannot extract a number from 'amount_credit'")
            }
        } else null
        val respAmountCredit = if (amountDebit != null) {
            val estimate = applyCashoutRatioAndFee(amountDebit, ratiosAndFees)
            if (amountCredit != null && estimate != amountCredit) throw badRequest(
                "Wrong calculation found in 'amount_credit', bank estimates: $estimate"
            )
            estimate
        } else null
        if (amountDebit == null && amountCredit == null) throw badRequest(
            "Both 'amount_credit' and 'amount_debit' are missing"
        )
        val respAmountDebit = if (amountCredit != null) {
            val estimate = applyCashoutRatioAndFee(
                amountCredit,
                ratiosAndFees,
                fromCredit = true
            )
            if (amountDebit != null && estimate != amountDebit) throw badRequest(
                "Wrong calculation found in 'amount_credit', bank estimates: $estimate"
            )
            estimate
        } else null
        call.respond(object {
            val amount_credit = "$FIAT_CURRENCY:$respAmountCredit"
            val amount_debit = "${demobank.config.currency}:$respAmountDebit"
        })
        return@get
    }

    // Create a cash-out operation.
    circuitRoute.post("/cashouts") {
        val user = call.request.basicAuth()
        if (user == "admin" || user == "bank") throw forbidden("$user can't cash-out.")
        // No suitable default user, when the authentication is disabled.
        if (user == null) throw SandboxError(
            HttpStatusCode.ServiceUnavailable,
            "This endpoint isn't served when the authentication is disabled."
        )
        val req = call.receive<CircuitCashoutRequest>()

        // validate amounts: well-formed and supported currency.
        val amountDebit = parseAmount(req.amount_debit) // amount before rates.
        val amountCredit = parseAmount(req.amount_credit) // amount after rates, as expected by the client
        val demobank = ensureDemobank(call)
        // Currency check of the cash-out's circuit part.
        if (amountDebit.currency != demobank.config.currency)
            throw badRequest("'${req::amount_debit.name}' (${req.amount_debit})" +
                    " doesn't match the regional currency (${demobank.config.currency})"
            )
        // Currency check of the cash-out's fiat part.
        if (amountCredit.currency != FIAT_CURRENCY)
            throw badRequest("'${req::amount_credit.name}' (${req.amount_credit})" +
                    " doesn't match the fiat currency ($FIAT_CURRENCY)."
            )
        // check if TAN is supported.  Default to SMS, if that's missing.
        val tanChannel = req.tan_channel?.uppercase() ?: SupportedTanChannels.SMS.name
        if (!isTanChannelSupported(tanChannel))
            throw SandboxError(
                HttpStatusCode.ServiceUnavailable,
                "TAN channel '$tanChannel' not supported."
            )
        // check if the user contact data would allow the TAN channel.
        val customer: DemobankCustomerEntity? = maybeGetCustomer(username = user)
        if (customer == null) throw internalServerError(
            "Customer profile '$user' not found after authenticating it."
        )
        if (customer.cashout_address == null) throw SandboxError(
            HttpStatusCode.PreconditionFailed,
            "Cash-out address not found.  Did the user register via Circuit API?"
        )
        if ((tanChannel == SupportedTanChannels.EMAIL.name) && (customer.email == null))
            throw conflict("E-mail address not found for '$user'.  Can't send the TAN")
        if ((tanChannel == SupportedTanChannels.SMS.name) && (customer.phone == null))
            throw conflict("Phone number not found for '$user'.  Can't send the TAN")
        // check rates correctness
        val amountDebitAsNumber = BigDecimal(amountDebit.amount)
        val expectedAmountCredit = applyCashoutRatioAndFee(amountDebitAsNumber, ratiosAndFees)
        val amountCreditAsNumber = BigDecimal(amountCredit.amount).roundToTwoDigits()
        if (expectedAmountCredit != amountCreditAsNumber) {
            throw badRequest("Rates application are incorrect." +
                    "  The expected amount to credit is: ${expectedAmountCredit}," +
                    " but ${amountCredit.amount} was specified.")
        }
        // check that the balance is sufficient
        val balance = getBalance(
            user,
            demobank.name,
            withPending = true
        )
        val balanceCheck = balance - amountDebitAsNumber
        if (balanceCheck < BigDecimal.ZERO && balanceCheck.abs() > BigDecimal(demobank.config.usersDebtLimit))
            throw SandboxError(
                HttpStatusCode.PreconditionFailed,
                "Cash-out not possible due to insufficient funds.  Balance ${balance.toPlainString()} would reach ${balanceCheck.toPlainString()}"
            )
        // generate a subject if that's missing
        val cashoutSubject = req.subject ?: generateCashoutSubject(
            amountCredit = amountCredit,
            amountDebit = amountDebit
        )
        val op = transaction {
            CashoutOperationEntity.new {
                this.amountDebit = req.amount_debit
                this.amountCredit = req.amount_credit
                this.buyAtRatio = ratiosAndFees.buy_at_ratio.toString()
                this.buyInFee = ratiosAndFees.buy_in_fee.toString()
                this.sellAtRatio = ratiosAndFees.sell_at_ratio.toString()
                this.sellOutFee = ratiosAndFees.sell_out_fee.toString()
                this.subject = cashoutSubject
                this.creationTime = getUTCnow().toInstant().toEpochMilli()
                this.tanChannel = SupportedTanChannels.valueOf(tanChannel)
                this.account = user
                this.tan = getRandomString(5)
                this.cashoutAddress = customer.cashout_address ?: throw internalServerError(
                    "Cash-out address for '$user' not found, after previous check succeeded"
                )
            }
        }
        // Send the TAN.
        when (tanChannel) {
            SupportedTanChannels.EMAIL.name -> {
                val isSuccessful = try {
                    runTanCommand(
                        command = EMAIL_TAN_CMD ?: throw internalServerError(
                            "E-mail TAN supported but the command" +
                                    " was not found.  See the --email-tan option from 'serve'"
                        ),
                        address = customer.email ?: throw internalServerError(
                            "Customer has no e-mail address, but previous check should" +
                                    " have detected it!"
                        ),
                        message = op.tan
                    )
                } catch (e: Exception) {
                    logger.error(
                        "Sending the e-mail TAN failed for ${customer.email}." +
                                "  The command threw this exception: ${e.message}"
                    )
                    false
                }
                if (!isSuccessful)
                    throw internalServerError(
                        "E-mail TAN command failed for ${customer.email}."
                    )
            }
            SupportedTanChannels.SMS.name -> {
                val isSuccessful = try {
                    runTanCommand(
                        command = SMS_TAN_CMD ?: throw internalServerError(
                            "SMS TAN supported but the command" +
                                    " was not found.  See the --sms-tan option from 'serve'"
                        ),
                        address = customer.phone ?: throw internalServerError(
                        "Customer has no phone number, but previous check should" +
                                " have detected it!"

                        ),
                        message = op.tan
                    )

                } catch (e: Exception) {
                    throw internalServerError(
                        "Sending the SMS TAN failed for ${customer.phone}." +
                                " The command threw this exception: ${e.message}"
                    )
                }
                if (!isSuccessful)
                    throw internalServerError(
                        "SMS TAN command failed for ${customer.phone}.")
            }
            SupportedTanChannels.FILE.name -> {
                try {
                    File(LIBEUFIN_TAN_TMP_FILE).writeText(op.tan)
                } catch (e: Exception) {
                    logger.error(e.message)
                    throw internalServerError("File TAN failed: could not write to $LIBEUFIN_TAN_TMP_FILE")
                }
            }
            else ->
                throw internalServerError("The bank tried an unsupported TAN channel: $tanChannel.")
        }
        call.respond(HttpStatusCode.Accepted, object {val uuid = op.uuid})
        return@post
    }
    // Get Circuit-relevant account data.
    circuitRoute.get("/accounts/{resourceName}") {
        val username = call.request.basicAuth()
        val resourceName = call.expectUriComponent("resourceName")
        throwIfInstitutionalName(resourceName)
        if (!allowOwnerOrAdmin(username, resourceName)) throw forbidden(
            "User $username has no rights over $resourceName"
        )
        val customer = getCustomer(resourceName)
        /**
         * CUSTOMER AND BANK ACCOUNT INVARIANT.
         *
         * After having found a 'customer' associated with the resourceName
         * - see previous line -, the bank must ensure that a 'bank account'
         * exist under the same resourceName.  If that fails, the bank broke the
         * invariant and should respond 500.
         */
        val bankAccount = getBankAccountFromLabel(resourceName, withBankFault = true)
        /**
         * Throwing when name or cash-out address aren't found ensures
         * that the customer was indeed added via the Circuit API, as opposed
         * to the Access API.
         */
        val maybeError = "$resourceName not managed by the Circuit API."
        call.respond(CircuitAccountInfo(
            username = customer.username,
            name = customer.name ?: throw notFound(maybeError),
            cashout_address = customer.cashout_address ?: throw notFound(maybeError),
            contact_data = CircuitContactData(
                email = customer.email,
                phone = customer.phone
            ),
            iban = bankAccount.iban
        ))
        return@get
    }

    // Get summary of all the accounts.
    circuitRoute.get("/accounts") {
        call.request.basicAuth(onlyAdmin = true)
        val maybeFilter: String? = call.request.queryParameters["filter"]
        /**
         * Equip the given filter with left and right catch-all wildcards,
         * otherwise use one catch-all wildcard.
         */
        val filter = if (maybeFilter != null) {
            "%${maybeFilter}%"
        } else "%"
        val customers = mutableListOf<Any>()
        val demobank = ensureDemobank(call)
        transaction {
            DemobankCustomerEntity.find{
                // like() is case insensitive.
                DemobankCustomersTable.name.like(filter)
            }.forEach {
                customers.add(object {
                    val username = it.username
                    val name = it.name
                    val balance = getBalanceForJson(
                        getBalance(it.username, demobank.name),
                        demobank.config.currency
                    )
                    val debitThreshold = getMaxDebitForUser(
                        it.username,
                        demobank.name
                    )
                })
            }
        }
        if (customers.size == 0) {
            call.respond(HttpStatusCode.NoContent)
            return@get
        }
        call.respond(object {val customers = customers})
        return@get
    }

    // Change password.
    circuitRoute.patch("/accounts/{customerUsername}/auth") {
        val username = call.request.basicAuth()
        val customerUsername = call.expectUriComponent("customerUsername")
        throwIfInstitutionalName(customerUsername)
        if (!allowOwnerOrAdmin(username, customerUsername)) throw forbidden(
            "User $username has no rights over $customerUsername"
        )
        // Flow here means admin or username have the rights for this operation.
        val req = call.receive<AccountPasswordChange>()
        /**
         * The resource/customer might still not exist, in case admin has requested.
         * On the other hand, when ordinary customers request, their existence is checked
         * along the basic authentication check.
         */
        transaction {
            val customer = getCustomer(customerUsername) // throws 404, if not found.
            customer.passwordHash = CryptoUtil.hashpw(req.new_password)
        }
        call.respond(HttpStatusCode.NoContent)
        return@patch
    }
    // Change account (mostly contact) data.
    circuitRoute.patch("/accounts/{resourceName}") {
        val username = call.request.basicAuth()
        if (username == null)
            throw internalServerError("Authentication disabled, don't have a default for this request.")
        val resourceName = call.expectUriComponent("resourceName")
        throwIfInstitutionalName(resourceName)
        if(!allowOwnerOrAdmin(username, resourceName)) throw forbidden(
            "User $username has no rights over $resourceName"
        )
        // account found and authentication succeeded
        val req = call.receive<CircuitAccountReconfiguration>()
        // Only admin's allowed to change the legal name
        if (req.name != null && username != "admin") throw forbidden(
            "Only admin can change the user legal name"
        )
        if ((req.contact_data.email != null) && (!checkEmailAddress(req.contact_data.email)))
            throw badRequest("Invalid e-mail address: ${req.contact_data.email}")
        if ((req.contact_data.phone != null) && (!checkPhoneNumber(req.contact_data.phone)))
            throw badRequest("Invalid phone number: ${req.contact_data.phone}")
        try { parsePayto(req.cashout_address) }
        catch (e: InvalidPaytoError) {
            throw badRequest("Invalid cash-out address: ${req.cashout_address}")
        }
        transaction {
            val user = getCustomer(resourceName)
            user.email = req.contact_data.email
            user.phone = req.contact_data.phone
            user.cashout_address = req.cashout_address
        }
        call.respond(HttpStatusCode.NoContent)
        return@patch
    }
    // Create new account.
    circuitRoute.post("/accounts") {
        call.request.basicAuth(onlyAdmin = true)
        val req = call.receive<CircuitAccountRequest>()
        // Validity and availability check on the input data.
        if (req.contact_data.email != null) {
            if (!checkEmailAddress(req.contact_data.email))
                throw badRequest("Invalid e-mail address: ${req.contact_data.email}.  Won't register")
            val maybeEmailConflict = transaction {
                DemobankCustomerEntity.find {
                    DemobankCustomersTable.email eq req.contact_data.email
                }.firstOrNull()
            }
            // Warning since two individuals claimed one same e-mail address.
            if (maybeEmailConflict != null)
                throw conflict("Won't register user ${req.username}: e-mail conflict on ${req.contact_data.email}")
        }
        if (req.contact_data.phone != null) {
            if (!checkPhoneNumber(req.contact_data.phone))
                throw badRequest("Invalid phone number: ${req.contact_data.phone}.  Won't register")

            val maybePhoneConflict = transaction {
                DemobankCustomerEntity.find {
                    DemobankCustomersTable.phone eq req.contact_data.phone
                }.firstOrNull()
            }
            // Warning since two individuals claimed one same phone number.
            if (maybePhoneConflict != null)
                throw conflict("Won't register user ${req.username}: phone conflict on ${req.contact_data.phone}")
        }
        /**
         * Check that cash-out address parses.  IBAN is not
         * check-summed in this version; the cash-out operation
         * just fails for invalid IBANs and the user has then
         * the chance to update their IBAN.
         */
        try {
            parsePayto(req.cashout_address)
        }
        catch (e: InvalidPaytoError) {
            throw badRequest("Won't register account ${req.username}: invalid cash-out address: ${req.cashout_address}")
        }
        transaction {
            val newAccount = insertNewAccount(
                username = req.username,
                password = req.password,
                name = req.name,
                iban = req.internal_iban,
                demobank = ensureDemobank(call).name
            )
            newAccount.customer.phone = req.contact_data.phone
            newAccount.customer.email = req.contact_data.email
            newAccount.customer.cashout_address = req.cashout_address
        }
        call.respond(HttpStatusCode.NoContent)
        return@post
    }
    // Get (conversion rates via) config values.
    circuitRoute.get("/config") {
        call.respond(ConfigResp(ratios_and_fees = ratiosAndFees))
        return@get
    }
    // Only Admin and only when balance is zero.
    circuitRoute.delete("/accounts/{resourceName}") {
        call.request.basicAuth(onlyAdmin = true)
        val resourceName = call.expectUriComponent("resourceName")
        throwIfInstitutionalName(resourceName)
        val customer = getCustomer(resourceName)
        val bankAccount = getBankAccountFromLabel(
            resourceName,
            withBankFault = true // See comment "CUSTOMER AND BANK ACCOUNT INVARIANT".
        )
        val balance: BigDecimal = getBalance(bankAccount)
        if (!isAmountZero(balance)) {
            logger.error("Account $resourceName has $balance balance.  Won't delete it")
            throw SandboxError(
                HttpStatusCode.PreconditionFailed,
                "Account $resourceName doesn't have zero balance.  Won't delete it"
            )
        }
        transaction {
            bankAccount.delete()
            customer.delete()
        }
        call.respond(HttpStatusCode.NoContent)
        return@delete
    }
}