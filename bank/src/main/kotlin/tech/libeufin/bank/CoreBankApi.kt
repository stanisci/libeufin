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
import kotlin.random.Random

private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.bank.accountsMgmtHandlers")

fun Routing.coreBankApi(db: Database, ctx: BankApplicationContext) {
    get("/config") {
        call.respond(Config(ctx.currencySpecification))
    }
    get("/monitor") {
        call.authAdmin(db, TokenScope.readonly)
        val params = MonitorParams.extract(call.request.queryParameters)
        call.respond(db.monitor(params))
    }
    coreBankTokenApi(db)
    coreBankAccountsMgmtApi(db, ctx)
    coreBankTransactionsApi(db, ctx)
    coreBankWithdrawalApi(db, ctx)
}

private fun Routing.coreBankTokenApi(db: Database) {
    post("/accounts/{USERNAME}/token") {
        val (login, _) = call.authCheck(db, TokenScope.refreshable)
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
            Random.nextBytes(this)
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
        val customerDbRow = db.customerGetFromLogin(login)?.dbRowId ?: throw internalServerError(
            "Could not get customer '$login' database row ID"
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
    }
    delete("/accounts/{USERNAME}/token") {
        call.authCheck(db, TokenScope.readonly)
        val token = call.getAuthToken() ?: throw badRequest("Basic auth not supported here.")

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
}


private fun Routing.coreBankAccountsMgmtApi(db: Database, ctx: BankApplicationContext) {
    post("/accounts") { 
        // check if only admin is allowed to create new accounts
        if (ctx.restrictRegistration) {
            call.authAdmin(db, TokenScope.readwrite)
        } // auth passed, proceed with activity.
        val req = call.receive<RegisterAccountRequest>()
        // Prohibit reserved usernames:
        if (reservedAccounts.contains(req.username)) throw forbidden( 
            "Username '${req.username}' is reserved.",
            TalerErrorCode.TALER_EC_BANK_RESERVED_USERNAME_CONFLICT
        )
        // Checking idempotency.
        val maybeCustomerExists =
            db.customerGetFromLogin(req.username) // Can be null if previous call crashed before completion.
        val maybeHasBankAccount = maybeCustomerExists.run {
            if (this == null) return@run null
            db.bankAccountGetFromOwnerId(this.expectRowId())
        }
        val internalPayto = req.internal_payto_uri ?: IbanPayTo(genIbanPaytoUri())
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
                        maybeHasBankAccount.internalPaytoUri.canonical == internalPayto.canonical
            if (isIdentic) {
                call.respond(HttpStatusCode.Created)
                return@post
            }
            throw conflict(
                "Idempotency check failed.",
                TalerErrorCode.TALER_EC_END // FIXME: provide appropriate EC.
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
                BankTransactionResult.BALANCE_INSUFFICIENT -> throw internalServerError("Bonus impossible: admin has insufficient balance.")
                BankTransactionResult.SAME_ACCOUNT -> throw internalServerError("Bonus impossible: admin should not be creditor.")
                BankTransactionResult.SUCCESS -> { /* continue the execution */ }
            }
        }
        call.respond(HttpStatusCode.Created)
    }
    delete("/accounts/{USERNAME}") {
        val (login, _) = call.authCheck(db, TokenScope.readwrite, withAdmin = true, requireAdmin = ctx.restrictAccountDeletion)
        // Not deleting reserved names.
        if (reservedAccounts.contains(login)) throw forbidden( 
            "Cannot delete reserved accounts",
            TalerErrorCode.TALER_EC_BANK_RESERVED_USERNAME_CONFLICT
        )

        when (db.customerDeleteIfBalanceIsZero(login)) {
            CustomerDeletionResult.CUSTOMER_NOT_FOUND -> throw notFound(
                "Customer '$login' not found",
                TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT
            )
            CustomerDeletionResult.BALANCE_NOT_ZERO -> throw conflict(
                "Balance is not zero.",
                TalerErrorCode.TALER_EC_NONE // FIXME: need EC.
            )
            CustomerDeletionResult.SUCCESS -> call.respond(HttpStatusCode.NoContent)
        }
    }
    patch("/accounts/{USERNAME}") {
        val (login, isAdmin) = call.authCheck(db, TokenScope.readwrite, withAdmin = true)
        // admin is not allowed itself to change its own details.
        if (login == "admin") throw forbidden("admin account not patchable")
        // authentication OK, go on.
        val req = call.receive<AccountReconfiguration>()
        /**
         * This object holds the details of the customer that's affected
         * by this operation, as it MAY differ from the one being authenticated.
         * This typically happens when admin did the request.
         */
        val accountCustomer = db.customerGetFromLogin(login) ?: throw notFound(
            "Account $login not found",
            talerEc = TalerErrorCode.TALER_EC_END // FIXME, define EC.
        )
        // Check if a non-admin user tried to change their legal name
        if (!isAdmin && (req.name != null) && (req.name != accountCustomer.name))
            throw forbidden("non-admin user cannot change their legal name")
        // Preventing identical data to be overridden.
        val bankAccount = db.bankAccountGetFromOwnerId(accountCustomer.expectRowId())
            ?: throw internalServerError("Customer '${accountCustomer.login}' lacks bank account.")
        if (
            (req.is_exchange == bankAccount.isTalerExchange) &&
            (req.cashout_address == accountCustomer.cashoutPayto) &&
            (req.name == accountCustomer.name) &&
            (req.challenge_contact_data?.phone == accountCustomer.phone) &&
            (req.challenge_contact_data?.email == accountCustomer.email)
            ) {
            call.respond(HttpStatusCode.NoContent)
            return@patch
        }
        val dbRes = db.accountReconfig(
            login = accountCustomer.login,
            name = req.name,
            cashoutPayto = req.cashout_address,
            emailAddress = req.challenge_contact_data?.email,
            isTalerExchange = req.is_exchange,
            phoneNumber = req.challenge_contact_data?.phone
            )
        when (dbRes) {
            AccountReconfigDBResult.SUCCESS -> call.respond(HttpStatusCode.NoContent)
            AccountReconfigDBResult.CUSTOMER_NOT_FOUND -> {
                // Rare case.  Only possible if a deletion happened before the flow reaches here.
                logger.warn("Authenticated customer wasn't found any more in the database")
                throw notFound("Customer not found", TalerErrorCode.TALER_EC_END) // FIXME: needs EC
            }
            AccountReconfigDBResult.BANK_ACCOUNT_NOT_FOUND -> {
                // Bank's fault: no customer should lack a bank account.
                throw internalServerError("Customer '${accountCustomer.login}' lacks bank account")
            }
        }
    }
    patch("/accounts/{USERNAME}/auth") {
        val (login, _) = call.authCheck(db, TokenScope.readwrite)
        val req = call.receive<AccountPasswordChange>()
        val hashedPassword = CryptoUtil.hashpw(req.new_password)
        if (!db.customerChangePassword(
            login,
            hashedPassword
        )) throw notFound(
            "Account '$login' not found (despite it being authenticated by this call)",
            talerEc = TalerErrorCode.TALER_EC_END // FIXME: need at least GENERIC_NOT_FOUND.
        )
        call.respond(HttpStatusCode.NoContent)
    }
    get("/public-accounts") {
        // no authentication here.
        val publicAccounts = db.accountsGetPublic(ctx.currency)
        if (publicAccounts.isEmpty()) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(PublicAccountsResponse(publicAccounts))
        }
    }
    get("/accounts") {
        call.authAdmin(db, TokenScope.readonly)
        // Get optional param.
        val maybeFilter: String? = call.request.queryParameters["filter_name"]
        logger.debug("Filtering on '${maybeFilter}'")
        val queryParam = if (maybeFilter != null) {
            "%${maybeFilter}%"
        } else "%"
        val accounts = db.accountsGetForAdmin(queryParam)
        if (accounts.isEmpty()) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(ListBankAccountsResponse(accounts))
        }
    }
    get("/accounts/{USERNAME}") {
        val (login, _) = call.authCheck(db, TokenScope.readonly, withAdmin = true)
        val customerData = db.customerGetFromLogin(login) ?: throw notFound(
                "Customer '$login' not found in the database.",
                talerEc = TalerErrorCode.TALER_EC_END
        )
        val bankAccountData = db.bankAccountGetFromOwnerId(customerData.expectRowId())
            ?: throw internalServerError("Customer '$login' had no bank account despite they are customer.'")
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
    }
}

private fun Routing.coreBankTransactionsApi(db: Database, ctx: BankApplicationContext) {
    get("/accounts/{USERNAME}/transactions") {
        call.authCheck(db, TokenScope.readonly)
        val params = HistoryParams.extract(call.request.queryParameters)
        val bankAccount = call.bankAccount(db)

        val history: List<BankAccountTransactionInfo> = db.bankPoolHistory(params, bankAccount.bankAccountId!!)
        call.respond(BankAccountTransactionsResponse(history))
    }
    get("/accounts/{USERNAME}/transactions/{T_ID}") {
        call.authCheck(db, TokenScope.readonly)
        val tId = call.expectUriComponent("T_ID")
        val txRowId = try {
            tId.toLong()
        } catch (e: Exception) {
            logger.error(e.message)
            throw badRequest("TRANSACTION_ID is not a number: ${tId}")
        }
      
        val bankAccount = call.bankAccount(db)
        val tx = db.bankTransactionGetFromInternalId(txRowId) ?: throw notFound(
            "Bank transaction '$tId' not found",
            TalerErrorCode.TALER_EC_BANK_TRANSACTION_NOT_FOUND
        )
        if (tx.bankAccountId != bankAccount.bankAccountId) // TODO not found ?
            throw unauthorized("Client has no rights over the bank transaction: $tId") 

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
    }
    post("/accounts/{USERNAME}/transactions") {
        val (login, _ ) = call.authCheck(db, TokenScope.readwrite)
        val tx = call.receive<BankAccountTransactionCreate>()

        val subject = tx.payto_uri.message ?: throw badRequest("Wire transfer lacks subject")
        val amount = tx.payto_uri.amount ?: tx.amount ?: throw badRequest("Wire transfer lacks amount")
        checkInternalCurrency(ctx, amount)
        val result = db.bankTransaction(
            creditAccountPayto = tx.payto_uri,
            debitAccountUsername = login,
            subject = subject,
            amount = amount,
            timestamp = Instant.now(),
        )
        when (result) {
            BankTransactionResult.BALANCE_INSUFFICIENT -> throw conflict(
                "Insufficient funds",
                TalerErrorCode.TALER_EC_BANK_UNALLOWED_DEBIT
            )
            BankTransactionResult.SAME_ACCOUNT -> throw conflict(
                "Wire transfer attempted with credit and debit party being the same bank account",
                TalerErrorCode.TALER_EC_BANK_SAME_ACCOUNT
            )
            BankTransactionResult.NO_DEBTOR -> throw notFound(
                "Customer $login not found",
                TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT
            )
            BankTransactionResult.NO_CREDITOR -> throw notFound(
                "Creditor account was not found",
                TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT
            )
            BankTransactionResult.SUCCESS -> call.respond(HttpStatusCode.OK)
        }
    }
}

fun Routing.coreBankWithdrawalApi(db: Database, ctx: BankApplicationContext) {
    post("/accounts/{USERNAME}/withdrawals") {
        val (login, _) = call.authCheck(db, TokenScope.readwrite)
        val req = call.receive<BankAccountCreateWithdrawalRequest>() // Checking that the user has enough funds.
        
        checkInternalCurrency(ctx, req.amount)

        val opId = UUID.randomUUID()
        when (db.talerWithdrawalCreate(login, opId, req.amount)) {
            WithdrawalCreationResult.ACCOUNT_NOT_FOUND -> throw notFound(
                "Customer $login not found",
                TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT
            )
            WithdrawalCreationResult.ACCOUNT_IS_EXCHANGE -> throw conflict(
                "Exchange account cannot perform withdrawal operation",
                TalerErrorCode.TALER_EC_BANK_SAME_ACCOUNT
            )
            WithdrawalCreationResult.BALANCE_INSUFFICIENT -> throw conflict(
                "Insufficient funds to withdraw with Taler",
                TalerErrorCode.TALER_EC_BANK_UNALLOWED_DEBIT
            )
            WithdrawalCreationResult.SUCCESS -> {
                val bankBaseUrl = call.request.getBaseUrl() ?: throw internalServerError("Bank could not find its own base URL")
                call.respond(
                    BankAccountCreateWithdrawalResponse(
                        withdrawal_id = opId.toString(), taler_withdraw_uri = getTalerWithdrawUri(bankBaseUrl, opId.toString())
                    )
                )
            }
        }       
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
            WithdrawalConfirmationResult.BALANCE_INSUFFICIENT -> throw conflict(
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
    }
}