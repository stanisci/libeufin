package tech.libeufin.bank

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.taler.common.errorcodes.TalerErrorCode
import net.taler.wallet.crypto.Base32Crockford
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.util.*
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.bank.accountsMgmtHandlers")

/**
 * This function collects all the /accounts handlers that
 * create, update, delete, show bank accounts.  No histories
 * and wire transfers should belong here.
 */
fun Routing.accountsMgmtHandlers(db: Database, ctx: BankApplicationContext) {

    // TOKEN ENDPOINTS
    delete("/accounts/{USERNAME}/token") {
        val c = call.authenticateBankRequest(db, TokenScope.readonly) ?: throw unauthorized()
        /**
         * The following command ensures that this call was
         * authenticated with the bearer token and NOT with
         * basic auth. FIXME: this "409 Conflict" case is not documented.
         */
        val token = call.getAuthToken() ?: throw badRequest("Basic auth not supported here.")
        val resourceName = call.getResourceName("USERNAME")
        /**
         * The following check makes sure that the token belongs
         * to the username contained in {USERNAME}.
         */
        if (!resourceName.canI(c, withAdmin = true)) throw forbidden()

        /**
         * Not sanity-checking the token, as it was used by the authentication already.
         * If harder errors happen, then they'll get Ktor respond with 500.
         */
        db.bearerTokenDelete(Base32Crockford.decode(token))
        /**
         * Responding 204 regardless of it being actually deleted or not.
         * If it wasn't found, then it must have been deleted before we
         * reached here, but the token was valid as it served the authentication
         * => no reason to fail the request.
         */
        call.respond(HttpStatusCode.NoContent)
    }
    post("/accounts/{USERNAME}/token") {
        val customer =
            call.authenticateBankRequest(db, TokenScope.refreshable) ?: throw unauthorized("Authentication failed")
        val endpointOwner = call.maybeUriComponent("USERNAME")
        if (customer.login != endpointOwner) throw forbidden(
            "User has no rights on this enpoint",
            TalerErrorCode.TALER_EC_GENERIC_FORBIDDEN
        )
        val maybeAuthToken = call.getAuthToken()
        val req = call.receive<TokenRequest>()
        /**
         * This block checks permissions ONLY IF the call was authenticated
         * with a token.  Basic auth gets always granted.
         */
        if (maybeAuthToken != null) {
            val tokenBytes = Base32Crockford.decode(maybeAuthToken)
            val refreshingToken = db.bearerTokenGet(tokenBytes) ?: throw internalServerError(
                "Token used to auth not found in the database!"
            )
            if (refreshingToken.scope == TokenScope.readonly && req.scope == TokenScope.readwrite) throw forbidden(
                "Cannot generate RW token from RO", TalerErrorCode.TALER_EC_GENERIC_TOKEN_PERMISSION_INSUFFICIENT
            )
        }
        val tokenBytes = ByteArray(32).apply {
            Random().nextBytes(this)
        }
        val tokenDuration: Duration = req.duration?.d_us ?: TOKEN_DEFAULT_DURATION

        val creationTime = Instant.now()
        val expirationTimestamp = if (tokenDuration == ChronoUnit.FOREVER.duration) {
            logger.debug("Creating 'forever' token.")
            Instant.MAX
        } else {
            try {
                logger.debug("Creating token with days duration: ${tokenDuration.toDays()}")
                creationTime.plus(tokenDuration)
            } catch (e: Exception) {
                logger.error("Could not add token duration to current time: ${e.message}")
                throw badRequest("Bad token duration: ${e.message}")
            }
        }
        val customerDbRow = customer.dbRowId ?: throw internalServerError(
            "Could not get customer '${customer.login}' database row ID"
        )
        val token = BearerToken(
            bankCustomer = customerDbRow,
            content = tokenBytes,
            creationTime = creationTime,
            expirationTime = expirationTimestamp,
            scope = req.scope,
            isRefreshable = req.refreshable
        )
        if (!db.bearerTokenCreate(token))
            throw internalServerError("Failed at inserting new token in the database")
        call.respond(
            TokenSuccessResponse(
                access_token = Base32Crockford.encode(tokenBytes), expiration = TalerProtocolTimestamp(
                    t_s = expirationTimestamp
                )
            )
        )
        return@post
    }
    // ACCOUNT ENDPOINTS
    get("/public-accounts") {
        // no authentication here.
        val publicAccounts = db.accountsGetPublic(ctx.currency)
        if (publicAccounts.isEmpty()) {
            call.respond(HttpStatusCode.NoContent)
            return@get
        }
        call.respond(
            PublicAccountsResponse().apply {
                publicAccounts.forEach {
                    this.public_accounts.add(it)
                }
            }
        )
        return@get
    }
    get("/accounts") {
        val c = call.authenticateBankRequest(db, TokenScope.readonly) ?: throw unauthorized()
        if (c.login != "admin") throw forbidden("Only admin allowed.")
        // Get optional param.
        val maybeFilter: String? = call.request.queryParameters["filter_name"]
        logger.debug("Filtering on '${maybeFilter}'")
        val queryParam = if (maybeFilter != null) {
            "%${maybeFilter}%"
        } else "%"
        val dbRes = db.accountsGetForAdmin(queryParam)
        if (dbRes.isEmpty()) {
            call.respond(HttpStatusCode.NoContent)
            return@get
        }
        call.respond(
            ListBankAccountsResponse().apply {
                dbRes.forEach { element ->
                    this.accounts.add(element)
                }
            }
        )
        return@get
    }
    post("/accounts") { // check if only admin is allowed to create new accounts
        if (ctx.restrictRegistration) {
            val customer: Customer? = call.authenticateBankRequest(db, TokenScope.readwrite)
            if (customer == null || customer.login != "admin") throw LibeufinBankException(
                httpStatus = HttpStatusCode.Unauthorized,
                talerError = TalerError(
                    code = TalerErrorCode.TALER_EC_GENERIC_UNAUTHORIZED.code,
                    hint = "Either 'admin' not authenticated or an ordinary user tried this operation."
                )
            )
        } // auth passed, proceed with activity.
        val req = call.receive<RegisterAccountRequest>() // Prohibit reserved usernames:
        if (req.username == "admin" || req.username == "bank") throw LibeufinBankException(
            httpStatus = HttpStatusCode.Conflict,
            talerError = TalerError(
                code = TalerErrorCode.TALER_EC_BANK_RESERVED_USERNAME_CONFLICT.code,
                hint = "Username '${req.username}' is reserved."
            )
        ) // Checking idempotency.
        val maybeCustomerExists =
            db.customerGetFromLogin(req.username) // Can be null if previous call crashed before completion.
        val maybeHasBankAccount = maybeCustomerExists.run {
            if (this == null) return@run null
            db.bankAccountGetFromOwnerId(this.expectRowId())
        }
        val internalPayto: String = if (req.internal_payto_uri != null) {
            stripIbanPayto(req.internal_payto_uri) ?: throw badRequest("internal_payto_uri is invalid")
        } else {
            stripIbanPayto(genIbanPaytoUri()) ?: throw internalServerError("Bank generated an invalid internal payto URI")
        }
        if (maybeCustomerExists != null && maybeHasBankAccount != null) {
            logger.debug("Registering username was found: ${maybeCustomerExists.login}") // Checking _all_ the details are the same.
            val isIdentic =
                maybeCustomerExists.name == req.name &&
                        maybeCustomerExists.email == req.challenge_contact_data?.email &&
                        maybeCustomerExists.phone == req.challenge_contact_data?.phone &&
                        maybeCustomerExists.cashoutPayto == req.cashout_payto_uri &&
                        CryptoUtil.checkpw(req.password, maybeCustomerExists.passwordHash) &&
                        maybeHasBankAccount.isPublic == req.is_public &&
                        maybeHasBankAccount.isTalerExchange == req.is_taler_exchange &&
                        maybeHasBankAccount.internalPaytoUri == internalPayto
            if (isIdentic) {
                call.respond(HttpStatusCode.Created)
                return@post
            }
            throw LibeufinBankException(
                httpStatus = HttpStatusCode.Conflict, talerError = TalerError(
                    code = GENERIC_UNDEFINED, // FIXME: provide appropriate EC.
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
            cashoutPayto = req.cashout_payto_uri, // Following could be gone, if included in cashout_payto_uri
            cashoutCurrency = ctx.cashoutCurrency,
            passwordHash = CryptoUtil.hashpw(req.password),
        )
        val newCustomerRowId = db.customerCreate(newCustomer)
            ?: throw internalServerError("New customer INSERT failed despite the previous checks") // Crashing here won't break data consistency between customers and bank accounts, because of the idempotency.  Client will just have to retry.
        val maxDebt = ctx.defaultCustomerDebtLimit
        val newBankAccount = BankAccount(
            hasDebt = false,
            internalPaytoUri = internalPayto,
            owningCustomerId = newCustomerRowId,
            isPublic = req.is_public,
            isTalerExchange = req.is_taler_exchange,
            maxDebt = maxDebt
        )
        val newBankAccountId = db.bankAccountCreate(newBankAccount)
            ?: throw internalServerError("Could not INSERT bank account despite all the checks.")

        // The new account got created, now optionally award the registration
        // bonus to it.
        val bonusAmount = if (ctx.registrationBonusEnabled && !req.is_taler_exchange) ctx.registrationBonus else null
        if (bonusAmount != null) {
            val adminCustomer =
                db.customerGetFromLogin("admin") ?: throw internalServerError("Admin customer not found")
            val adminBankAccount = db.bankAccountGetFromOwnerId(adminCustomer.expectRowId())
                ?: throw internalServerError("Admin bank account not found")
            val adminPaysBonus = BankInternalTransaction(
                creditorAccountId = newBankAccountId,
                debtorAccountId = adminBankAccount.expectRowId(),
                amount = bonusAmount,
                subject = "Registration bonus.",
                transactionDate = Instant.now()
            )
            when (db.bankTransactionCreate(adminPaysBonus)) {
                BankTransactionResult.NO_CREDITOR -> throw internalServerError("Bonus impossible: creditor not found, despite its recent creation.")
                BankTransactionResult.NO_DEBTOR -> throw internalServerError("Bonus impossible: admin not found.")
                BankTransactionResult.CONFLICT -> throw internalServerError("Bonus impossible: admin has insufficient balance.")
                BankTransactionResult.SUCCESS -> {/* continue the execution */
                }
            }
        }
        call.respond(HttpStatusCode.Created)
        return@post
    }
    get("/accounts/{USERNAME}") {
        val c = call.authenticateBankRequest(db, TokenScope.readonly) ?: throw unauthorized("Login failed")
        val resourceName = call.expectUriComponent("USERNAME")
        if (!resourceName.canI(c, withAdmin = true)) throw forbidden()
        val customerData = db.customerGetFromLogin(resourceName) ?: throw notFound(
                "Customer '$resourceName' not found in the database.",
                talerEc = TalerErrorCode.TALER_EC_END
        )
        val bankAccountData = db.bankAccountGetFromOwnerId(customerData.expectRowId())
            ?: throw internalServerError("Customer '$resourceName' had no bank account despite they are customer.'")
        val balance = Balance(
            amount = bankAccountData.balance ?: throw internalServerError("Account '${customerData.login}' lacks balance!"),
            credit_debit_indicator = if (bankAccountData.hasDebt) {
                CorebankCreditDebitInfo.debit
            } else {
                CorebankCreditDebitInfo.credit
            }
        )
        call.respond(
            AccountData(
                name = customerData.name,
                balance = balance,
                debit_threshold = bankAccountData.maxDebt,
                payto_uri = bankAccountData.internalPaytoUri,
                contact_data = ChallengeContactData(
                    email = customerData.email, phone = customerData.phone
                ),
                cashout_payto_uri = customerData.cashoutPayto,
            )
        )
        return@get
    }
    delete("/accounts/{USERNAME}") {
        val c = call.authenticateBankRequest(db, TokenScope.readwrite) ?: throw unauthorized()
        val resourceName = call.expectUriComponent("USERNAME")
        // Checking rights.
        if (c.login != "admin" && ctx.restrictAccountDeletion)
            throw forbidden("Only admin allowed.")
        if (!resourceName.canI(c, withAdmin = true))
            throw forbidden("Insufficient rights on this account.")
        // Not deleting reserved names.
        if (resourceName == "bank" || resourceName == "admin")
            throw forbidden("Cannot delete reserved accounts.")
        val res = db.customerDeleteIfBalanceIsZero(resourceName)
        when (res) {
            CustomerDeletionResult.CUSTOMER_NOT_FOUND ->
                throw notFound(
                    "Customer '$resourceName' not found",
                    talerEc = TalerErrorCode.TALER_EC_NONE // FIXME: need EC.
                    )
            CustomerDeletionResult.BALANCE_NOT_ZERO ->
                throw LibeufinBankException(
                    httpStatus = HttpStatusCode.PreconditionFailed,
                    talerError = TalerError(
                        hint = "Balance is not zero.",
                        code = TalerErrorCode.TALER_EC_NONE.code // FIXME: need EC.
                    )
                )
            CustomerDeletionResult.SUCCESS -> call.respond(HttpStatusCode.NoContent)
        }
        return@delete
    }
    patch("/accounts/{USERNAME}/auth") {
        val c = call.authenticateBankRequest(db, TokenScope.readwrite) ?: throw unauthorized()
        val accountName = call.getResourceName("USERNAME")
        if (!accountName.canI(c, withAdmin = true)) throw forbidden()
        val req = call.receive<AccountPasswordChange>()
        val hashedPassword = CryptoUtil.hashpw(req.new_password)
        /**
         * FIXME: should it check if the password used to authenticate
         * FIXME: this request _is_ the one being overridden in the database?
         */
        if (!db.customerChangePassword(
                accountName,
                hashedPassword
        ))
            throw notFound(
                "Account '$accountName' not found (despite it being authenticated by this call)",
                talerEc = TalerErrorCode.TALER_EC_END // FIXME: need at least GENERIC_NOT_FOUND.
            )
        call.respond(HttpStatusCode.NoContent)
        return@patch
    }
    patch("/accounts/{USERNAME}") {
        val c = call.authenticateBankRequest(db, TokenScope.readwrite) ?: throw unauthorized()
        val accountName = call.getResourceName("USERNAME")
        // preventing user non-admin X trying on resource Y.
        if (!accountName.canI(c, withAdmin = true)) throw forbidden()
        // admin is not allowed itself to change its own details.
        if (accountName == "admin") throw forbidden("admin account not patchable")
        // authentication OK, go on.
        val req = call.receive<AccountReconfiguration>()
        /**
         * This object holds the details of the customer that's affected
         * by this operation, as it MAY differ from the one being authenticated.
         * This typically happens when admin did the request.
         */
        val accountCustomer = db.customerGetFromLogin(accountName) ?: throw notFound(
            "Account $accountName not found",
            talerEc = TalerErrorCode.TALER_EC_END // FIXME, define EC.
        )
        // Check if a non-admin user tried to change their legal name
        if (c.login != "admin" && req.name != accountCustomer.name)
            throw forbidden("non-admin user cannot change their legal name")
        // Preventing identical data to be overridden.
        val bankAccount = db.bankAccountGetFromOwnerId(accountCustomer.expectRowId())
            ?: throw internalServerError("Customer '${accountCustomer.login}' lacks bank account.")
        if (
            (req.is_exchange == bankAccount.isTalerExchange) &&
            (req.cashout_address == accountCustomer.cashoutPayto) &&
            (req.name == c.name) &&
            (req.challenge_contact_data?.phone == accountCustomer.phone) &&
            (req.challenge_contact_data?.email == accountCustomer.email)
            ) {
            call.respond(HttpStatusCode.NoContent)
            return@patch
        }
        // Not identical, go on writing the DB.
        throw NotImplementedError("DB part missing.")
    }
    // WITHDRAWAL ENDPOINTS
    post("/accounts/{USERNAME}/withdrawals") {
        val c = call.authenticateBankRequest(db, TokenScope.readwrite)
            ?: throw unauthorized() // Admin not allowed to withdraw in the name of customers:
        val accountName = call.expectUriComponent("USERNAME")
        if (c.login != accountName) throw unauthorized("User ${c.login} not allowed to withdraw for account '${accountName}'")
        val req = call.receive<BankAccountCreateWithdrawalRequest>() // Checking that the user has enough funds.
        if(req.amount.currency != ctx.currency)
            throw badRequest("Wrong currency: ${req.amount.currency}")
        val b = db.bankAccountGetFromOwnerId(c.expectRowId())
            ?: throw internalServerError("Customer '${c.login}' lacks bank account.")
        if (!isBalanceEnough(
                balance = b.expectBalance(), due = req.amount, maxDebt = b.maxDebt, hasBalanceDebt = b.hasDebt
            )
        ) throw forbidden(
            hint = "Insufficient funds to withdraw with Taler",
            talerErrorCode = TalerErrorCode.TALER_EC_BANK_UNALLOWED_DEBIT
        ) // Auth and funds passed, create the operation now!
        val opId = UUID.randomUUID()
        if (!db.talerWithdrawalCreate(
                opId, b.expectRowId(), req.amount
            )
        ) throw internalServerError("Bank failed at creating the withdraw operation.")

        val bankBaseUrl = call.request.getBaseUrl() ?: throw internalServerError("Bank could not find its own base URL")
        call.respond(
            BankAccountCreateWithdrawalResponse(
                withdrawal_id = opId.toString(), taler_withdraw_uri = getTalerWithdrawUri(bankBaseUrl, opId.toString())
            )
        )
        return@post
    }
    get("/withdrawals/{withdrawal_id}") {
        val op = getWithdrawal(db, call.expectUriComponent("withdrawal_id"))
        call.respond(
            BankAccountGetWithdrawalResponse(
                amount = op.amount,
                aborted = op.aborted,
                confirmation_done = op.confirmationDone,
                selection_done = op.selectionDone,
                selected_exchange_account = op.selectedExchangePayto,
                selected_reserve_pub = op.reservePub
            )
        )
        return@get
    }
    post("/withdrawals/{withdrawal_id}/abort") {
        val op = getWithdrawal(db, call.expectUriComponent("withdrawal_id")) // Idempotency:
        if (op.aborted) {
            call.respondText("{}", ContentType.Application.Json)
            return@post
        } // Op is found, it'll now fail only if previously confirmed (DB checks).
        if (!db.talerWithdrawalAbort(op.withdrawalUuid)) throw conflict(
            hint = "Cannot abort confirmed withdrawal", talerEc = TalerErrorCode.TALER_EC_END
        )
        call.respondText("{}", ContentType.Application.Json)
        return@post
    }
    post("/withdrawals/{withdrawal_id}/confirm") {
        val op = getWithdrawal(db, call.expectUriComponent("withdrawal_id")) // Checking idempotency:
        if (op.confirmationDone) {
            call.respondText("{}", ContentType.Application.Json)
            return@post
        }
        if (op.aborted) throw conflict(
            hint = "Cannot confirm an aborted withdrawal", talerEc = TalerErrorCode.TALER_EC_BANK_CONFIRM_ABORT_CONFLICT
        ) // Checking that reserve GOT indeed selected.
        if (!op.selectionDone) throw LibeufinBankException(
            httpStatus = HttpStatusCode.UnprocessableEntity, talerError = TalerError(
                hint = "Cannot confirm an unselected withdrawal", code = TalerErrorCode.TALER_EC_END.code
            )
        ) // Confirmation conditions are all met, now put the operation
        // to the selected state _and_ wire the funds to the exchange.
        // Note: 'when' helps not to omit more result codes, should more
        // be added.
        when (db.talerWithdrawalConfirm(op.withdrawalUuid, Instant.now())) {
            WithdrawalConfirmationResult.BALANCE_INSUFFICIENT ->
                throw conflict(
                "Insufficient funds",
                    TalerErrorCode.TALER_EC_BANK_UNALLOWED_DEBIT
                )
            WithdrawalConfirmationResult.OP_NOT_FOUND ->
                /**
                 * Despite previous checks, the database _still_ did not
                 * find the withdrawal operation, that's on the bank.
                 */
                throw internalServerError("Withdrawal operation (${op.withdrawalUuid}) not found")

            WithdrawalConfirmationResult.EXCHANGE_NOT_FOUND ->
                /**
                 * That can happen because the bank did not check the exchange
                 * exists when POST /withdrawals happened, or because the exchange
                 * bank account got removed before this confirmation.
                 */
                throw conflict(
                    hint = "Exchange to withdraw from not found",
                    talerEc = TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT
                )

            WithdrawalConfirmationResult.CONFLICT -> throw internalServerError("Bank didn't check for idempotency")

            WithdrawalConfirmationResult.SUCCESS -> call.respondText(
                "{}", ContentType.Application.Json
            )
        }
        return@post
    }
    // TRANSACTION ENDPOINT
    get("/accounts/{USERNAME}/transactions") {
        val c = call.authenticateBankRequest(db, TokenScope.readonly) ?: throw unauthorized()
        val resourceName = call.getResourceName("USERNAME")
        if (!resourceName.canI(c, withAdmin = true)) throw forbidden()
        val historyParams = getHistoryParams(call.request)
        val resourceCustomer = db.customerGetFromLogin(resourceName) ?: throw notFound(
            hint = "Customer '$resourceName' not found in the database",
            talerEc = TalerErrorCode.TALER_EC_END // FIXME: need EC.
        )
        val bankAccount = db.bankAccountGetFromOwnerId(resourceCustomer.expectRowId())
            ?: throw internalServerError("Customer '${resourceCustomer.login}' lacks bank account.")
        val history: List<BankAccountTransaction> = db.bankTransactionGetHistory(
            start = historyParams.start,
            delta = historyParams.delta,
            bankAccountId = bankAccount.expectRowId()
        )
        val res = BankAccountTransactionsResponse(transactions = mutableListOf())
        history.forEach {
            res.transactions.add(
                BankAccountTransactionInfo(
                    debtor_payto_uri = it.debtorPaytoUri,
                    creditor_payto_uri = it.creditorPaytoUri,
                    subject = it.subject,
                    amount = it.amount,
                    direction = it.direction,
                    date = TalerProtocolTimestamp(it.transactionDate),
                    row_id = it.dbRowId ?: throw internalServerError(
                        "Transaction timestamped with '${it.transactionDate}' did not have row ID"
                    )
                )
            )
        }
        call.respond(res)
        return@get
    }
    // Creates a bank transaction.
    post("/accounts/{USERNAME}/transactions") {
        val c: Customer = call.authenticateBankRequest(db, TokenScope.readwrite) ?: throw unauthorized()
        val resourceName = call.expectUriComponent("USERNAME") // admin has no rights here.
        if ((c.login != resourceName) && (call.getAuthToken() == null)) throw forbidden()
        val txData = call.receive<BankAccountTransactionCreate>()
        val payto = parsePayto(txData.payto_uri) ?: throw badRequest("Invalid creditor Payto")
        val subject = payto.message ?: throw badRequest("Wire transfer lacks subject")
        val debtorBankAccount = db.bankAccountGetFromOwnerId(c.expectRowId())
            ?: throw internalServerError("Debtor bank account not found")
        if (txData.amount.currency != ctx.currency) throw badRequest(
            "Wrong currency: ${txData.amount.currency}",
            talerErrorCode = TalerErrorCode.TALER_EC_GENERIC_CURRENCY_MISMATCH
        )
        if (!isBalanceEnough(
            balance = debtorBankAccount.expectBalance(),
            due = txData.amount,
            hasBalanceDebt = debtorBankAccount.hasDebt,
            maxDebt = debtorBankAccount.maxDebt
        ))
            throw conflict(hint = "Insufficient balance.", talerEc = TalerErrorCode.TALER_EC_BANK_UNALLOWED_DEBIT)
        logger.info("creditor payto: ${txData.payto_uri}")
        val creditorBankAccount = db.bankAccountGetFromInternalPayto("payto://iban/${payto.iban.lowercase()}")
            ?: throw notFound(
                "Creditor account not found",
                TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT
            )
        val dbInstructions = BankInternalTransaction(
            debtorAccountId = debtorBankAccount.expectRowId(),
            creditorAccountId = creditorBankAccount.expectRowId(),
            subject = subject,
            amount = txData.amount,
            transactionDate = Instant.now()
        )
        val res = db.bankTransactionCreate(dbInstructions)
        when (res) {
            BankTransactionResult.CONFLICT -> throw conflict(
                "Insufficient funds",
                TalerErrorCode.TALER_EC_BANK_UNALLOWED_DEBIT
            )
            BankTransactionResult.NO_CREDITOR -> throw internalServerError("Creditor not found despite previous checks.")
            BankTransactionResult.NO_DEBTOR -> throw internalServerError("Debtor not found despite the request was authenticated.")
            BankTransactionResult.SUCCESS -> call.respond(HttpStatusCode.OK)
        }
        return@post
    }
    get("/accounts/{USERNAME}/transactions/{T_ID}") {
        val c = call.authenticateBankRequest(db, TokenScope.readonly) ?: throw unauthorized()
        val accountName = call.getResourceName("USERNAME")
        if (!accountName.canI(c, withAdmin = true)) throw forbidden()
        val tId = call.expectUriComponent("T_ID")
        val txRowId = try {
            tId.toLong()
        } catch (e: Exception) {
            logger.error(e.message)
            throw badRequest("TRANSACTION_ID is not a number: ${tId}")
        }
        val tx = db.bankTransactionGetFromInternalId(txRowId) ?: throw notFound(
            "Bank transaction '$tId' not found",
            TalerErrorCode.TALER_EC_BANK_TRANSACTION_NOT_FOUND
        )
        val accountCustomer = db.customerGetFromLogin(accountName) ?: throw notFound(
            hint = "Customer $accountName not found",
            talerEc = TalerErrorCode.TALER_EC_END // FIXME: need EC.
        )
        val customerBankAccount = db.bankAccountGetFromOwnerId(accountCustomer.expectRowId())
            ?: throw internalServerError("Customer '${accountCustomer.login}' lacks bank account.")
        if (tx.bankAccountId != customerBankAccount.bankAccountId) throw forbidden("Client has no rights over the bank transaction: $tId")
        call.respond(
            BankAccountTransactionInfo(
                amount = tx.amount,
                creditor_payto_uri = tx.creditorPaytoUri,
                debtor_payto_uri = tx.debtorPaytoUri,
                date = TalerProtocolTimestamp(tx.transactionDate),
                direction = tx.direction,
                subject = tx.subject,
                row_id = txRowId
            )
        )
        return@get
    }
}