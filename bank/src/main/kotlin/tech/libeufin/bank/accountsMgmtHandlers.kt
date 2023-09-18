package tech.libeufin.bank

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.taler.common.errorcodes.TalerErrorCode
import tech.libeufin.util.CryptoUtil
import tech.libeufin.util.maybeUriComponent

/**
 * This function collects all the /accounts handlers that
 * create, update, delete, show bank accounts.  No histories
 * and wire transfers should belong here.
 */
fun Routing.accountsMgmtHandlers() {
    post("/accounts") {
        // check if only admin.
        val maybeOnlyAdmin = db.configGet("only_admin_registrations")
        if (maybeOnlyAdmin?.lowercase() == "yes") {
            val customer: Customer? = call.myAuth(TokenScope.readwrite)
            if (customer == null || customer.login != "admin")
                throw LibeufinBankException(
                    httpStatus = HttpStatusCode.Unauthorized,
                    talerError = TalerError(
                        code = TalerErrorCode.TALER_EC_BANK_LOGIN_FAILED.code,
                        hint = "Either 'admin' not authenticated or an ordinary user tried this operation."
                    )
                )
        }
        // auth passed, proceed with activity.
        val req = call.receive<RegisterAccountRequest>()
        // Prohibit reserved usernames:
        if (req.username == "admin" || req.username == "bank")
            throw LibeufinBankException(
                httpStatus = HttpStatusCode.Conflict,
                talerError = TalerError(
                    code = GENERIC_UNDEFINED, // FIXME: this waits GANA.
                    hint = "Username '${req.username}' is reserved."
                )
            )
        // Checking imdepotency.
        val maybeCustomerExists = db.customerGetFromLogin(req.username)
        // Can be null if previous call crashed before completion.
        val maybeHasBankAccount = maybeCustomerExists.run {
            if (this == null) return@run null
            db.bankAccountGetFromOwnerId(this.expectRowId())
        }
        if (maybeCustomerExists != null && maybeHasBankAccount != null) {
            tech.libeufin.bank.logger.debug("Registering username was found: ${maybeCustomerExists.login}")
            // Checking _all_ the details are the same.
            val isIdentic =
                maybeCustomerExists.name == req.name &&
                        maybeCustomerExists.email == req.challenge_contact_data?.email &&
                        maybeCustomerExists.phone == req.challenge_contact_data?.phone &&
                        maybeCustomerExists.cashoutPayto == req.cashout_payto_uri &&
                        CryptoUtil.checkpw(req.password, maybeCustomerExists.passwordHash) &&
                        maybeHasBankAccount.isPublic == req.is_public &&
                        maybeHasBankAccount.isTalerExchange == req.is_taler_exchange &&
                        maybeHasBankAccount.internalPaytoUri == req.internal_payto_uri
            if (isIdentic) {
                call.respond(HttpStatusCode.Created)
                return@post
            }
            throw LibeufinBankException(
                httpStatus = HttpStatusCode.Conflict,
                talerError = TalerError(
                    code = GENERIC_UNDEFINED, // GANA needs this.
                    hint = "Idempotency check failed."
                )
            )
        }
        // From here: fresh user being added.
        val newCustomer = Customer(
            login = req.username,
            name = req.name,
            email = req.challenge_contact_data?.email,
            phone = req.challenge_contact_data?.phone,
            cashoutPayto = req.cashout_payto_uri,
            // Following could be gone, if included in cashout_payto_uri
            cashoutCurrency = db.configGet("cashout_currency"),
            passwordHash = CryptoUtil.hashpw(req.password),
        )
        val newCustomerRowId = db.customerCreate(newCustomer)
            ?: throw internalServerError("New customer INSERT failed despite the previous checks")
        /* Crashing here won't break data consistency between customers
         * and bank accounts, because of the idempotency.  Client will
         * just have to retry.  */
        val maxDebt = db.configGet("max_debt_ordinary_customers").run {
            if (this == null) throw internalServerError("Max debt not configured")
            parseTalerAmount(this)
        }
        val bonus = db.configGet("registration_bonus")
        val initialBalance = if (bonus != null) parseTalerAmount(bonus) else TalerAmount(0, 0)
        val newBankAccount = BankAccount(
            hasDebt = false,
            internalPaytoUri = req.internal_payto_uri ?: genIbanPaytoUri(),
            owningCustomerId = newCustomerRowId,
            isPublic = req.is_public,
            isTalerExchange = req.is_taler_exchange,
            maxDebt = maxDebt,
            balance = initialBalance
        )
        if (!db.bankAccountCreate(newBankAccount))
            throw internalServerError("Could not INSERT bank account despite all the checks.")
        call.respond(HttpStatusCode.Created)
        return@post
    }
    get("/accounts/{USERNAME}") {
        val c = call.myAuth(TokenScope.readonly) ?: throw unauthorized("Login failed")
        val resourceName = call.maybeUriComponent("USERNAME") ?: throw badRequest(
            hint = "No username found in the URI",
            talerErrorCode = TalerErrorCode.TALER_EC_GENERIC_PARAMETER_MISSING
        )
        // Checking resource name only if Basic auth was used.
        // Successful tokens do not need this check, they just pass.
        if (
            ((c.login != resourceName)
            && (c.login != "admin"))
            && (call.getAuthToken() == null)
            )
            throw forbidden("No rights on the resource.")
        val customerData = db.customerGetFromLogin(c.login) ?: throw internalServerError("Customer '${c.login} despite being authenticated.'")
        val customerInternalId = customerData.dbRowId ?: throw internalServerError("Customer '${c.login} had no row ID despite it was found in the database.'")
        val bankAccountData = db.bankAccountGetFromOwnerId(customerInternalId) ?: throw internalServerError("Customer '${c.login} had no bank account despite they are customer.'")
        call.respond(AccountData(
            name = customerData.name,
            balance = bankAccountData.balance,
            debit_threshold = bankAccountData.maxDebt,
            payto_uri = bankAccountData.internalPaytoUri,
            contact_data = ChallengeContactData(
                email = customerData.email,
                phone = customerData.phone
            ),
            cashout_payto_uri = customerData.cashoutPayto,
            has_debit = bankAccountData.hasDebt
        ))
        return@get
    }
}