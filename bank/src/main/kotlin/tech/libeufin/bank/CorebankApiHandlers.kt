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

    delete("/accounts/{USERNAME}/token") {
        throw internalServerError("Token deletion not implemented.")
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
            Instant.MAX
        } else {
            try {
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
            stripIbanPayto(req.internal_payto_uri)
        } else {
            stripIbanPayto(genIbanPaytoUri())
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
                Database.BankTransactionResult.NO_CREDITOR -> throw internalServerError("Bonus impossible: creditor not found, despite its recent creation.")

                Database.BankTransactionResult.NO_DEBTOR -> throw internalServerError("Bonus impossible: admin not found.")

                Database.BankTransactionResult.CONFLICT -> throw internalServerError("Bonus impossible: admin has insufficient balance.")

                Database.BankTransactionResult.SUCCESS -> {/* continue the execution */
                }
            }
        }
        call.respond(HttpStatusCode.Created)
        return@post
    }

    get("/accounts/{USERNAME}") {
        val c = call.authenticateBankRequest(db, TokenScope.readonly) ?: throw unauthorized("Login failed")
        val resourceName = call.maybeUriComponent("USERNAME") ?: throw badRequest(
            hint = "No username found in the URI", talerErrorCode = TalerErrorCode.TALER_EC_GENERIC_PARAMETER_MISSING
        ) // Checking resource name only if Basic auth was used. Successful tokens do not need this check, they just pass.
        if (((c.login != resourceName) && (c.login != "admin")) && (call.getAuthToken() == null)) throw forbidden("No rights on the resource.")
        val customerData = db.customerGetFromLogin(c.login)
            ?: throw internalServerError("Customer '${c.login} despite being authenticated.'")
        val customerInternalId = customerData.dbRowId
            ?: throw internalServerError("Customer '${c.login} had no row ID despite it was found in the database.'")
        val bankAccountData = db.bankAccountGetFromOwnerId(customerInternalId)
            ?: throw internalServerError("Customer '${c.login} had no bank account despite they are customer.'")
        val balance = Balance(
            amount = bankAccountData.balance ?: throw internalServerError("Account '${c.login}' lacks balance!"),
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
                debit_threshold = bankAccountData.maxDebt.toString(),
                payto_uri = bankAccountData.internalPaytoUri,
                contact_data = ChallengeContactData(
                    email = customerData.email, phone = customerData.phone
                ),
                cashout_payto_uri = customerData.cashoutPayto,
            )
        )
        return@get
    }

    post("/accounts/{USERNAME}/withdrawals") {
        val c = call.authenticateBankRequest(db, TokenScope.readwrite)
            ?: throw unauthorized() // Admin not allowed to withdraw in the name of customers:
        val accountName = call.expectUriComponent("USERNAME")
        if (c.login != accountName) throw unauthorized("User ${c.login} not allowed to withdraw for account '${accountName}'")
        val req = call.receive<BankAccountCreateWithdrawalRequest>() // Checking that the user has enough funds.
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

    get("/accounts/{USERNAME}/transactions") {
        val c = call.authenticateBankRequest(db, TokenScope.readonly) ?: throw unauthorized()
        val resourceName = call.expectUriComponent("USERNAME")
        if (c.login != resourceName && c.login != "admin") throw forbidden() // Collecting params.
        val historyParams = getHistoryParams(call.request) // Making the query.
        val bankAccount = db.bankAccountGetFromOwnerId(c.expectRowId())
            ?: throw internalServerError("Customer '${c.login}' lacks bank account.")
        val bankAccountId = bankAccount.expectRowId()
        val history: List<BankAccountTransaction> = db.bankTransactionGetHistory(
            start = historyParams.start, delta = historyParams.delta, bankAccountId = bankAccountId
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
        val c = call.authenticateBankRequest(db, TokenScope.readwrite) ?: throw unauthorized()
        val resourceName = call.expectUriComponent("USERNAME") // admin has no rights here.
        if ((c.login != resourceName) && (call.getAuthToken() == null)) throw forbidden()
        val txData = call.receive<BankAccountTransactionCreate>()
        val payto = parsePayto(txData.payto_uri) ?: throw badRequest("Invalid creditor Payto")
        val paytoWithoutParams = stripIbanPayto(txData.payto_uri)
        val subject = payto.message ?: throw badRequest("Wire transfer lacks subject")
        val debtorId = c.dbRowId
            ?: throw internalServerError("Debtor database ID not found") // This performs already a SELECT on the bank account, like the wire transfer will do as well later!
        logger.info("creditor payto: $paytoWithoutParams")
        val creditorCustomerData = db.bankAccountGetFromInternalPayto(paytoWithoutParams) ?: throw notFound(
            "Creditor account not found",
            TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT
        )
        if (txData.amount.currency != ctx.currency) throw badRequest(
            "Wrong currency: ${txData.amount.currency}", talerErrorCode = TalerErrorCode.TALER_EC_GENERIC_CURRENCY_MISMATCH
        )
        val dbInstructions = BankInternalTransaction(
            debtorAccountId = debtorId,
            creditorAccountId = creditorCustomerData.owningCustomerId,
            subject = subject,
            amount = txData.amount,
            transactionDate = Instant.now()
        )
        val res = db.bankTransactionCreate(dbInstructions)
        when (res) {
            Database.BankTransactionResult.CONFLICT -> throw conflict(
                "Insufficient funds",
                TalerErrorCode.TALER_EC_BANK_UNALLOWED_DEBIT
            )
            Database.BankTransactionResult.NO_CREDITOR -> throw internalServerError("Creditor not found despite previous checks.")
            Database.BankTransactionResult.NO_DEBTOR -> throw internalServerError("Debtor not found despite the request was authenticated.")
            Database.BankTransactionResult.SUCCESS -> call.respond(HttpStatusCode.OK)
        }
        return@post
    }

    get("/accounts/{USERNAME}/transactions/{T_ID}") {
        val c = call.authenticateBankRequest(db, TokenScope.readonly) ?: throw unauthorized()
        val accountOwner = call.expectUriComponent("USERNAME") // auth ok, check rights.
        if (c.login != "admin" && c.login != accountOwner) throw forbidden() // rights ok, check tx exists.
        val tId = call.expectUriComponent("T_ID")
        val txRowId = try {
            tId.toLong()
        } catch (e: Exception) {
            logger.error(e.message)
            throw badRequest("TRANSACTION_ID is not a number: ${tId}")
        }
        val customerRowId = c.dbRowId ?: throw internalServerError("Authenticated client lacks database entry")
        val tx = db.bankTransactionGetFromInternalId(txRowId) ?: throw notFound(
            "Bank transaction '$tId' not found",
            TalerErrorCode.TALER_EC_BANK_TRANSACTION_NOT_FOUND
        )
        val customerBankAccount = db.bankAccountGetFromOwnerId(customerRowId)
            ?: throw internalServerError("Customer '${c.login}' lacks bank account.")
        if (tx.bankAccountId != customerBankAccount.bankAccountId) throw forbidden("Client has no rights over the bank transaction: $tId") // auth and rights, respond.
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