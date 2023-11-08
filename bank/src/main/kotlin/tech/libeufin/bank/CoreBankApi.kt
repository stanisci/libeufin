/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023 Taler Systems S.A.

 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.

 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.

 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */
package tech.libeufin.bank

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.TimeUnit
import java.io.File
import kotlin.random.Random
import net.taler.common.errorcodes.TalerErrorCode
import net.taler.wallet.crypto.Base32Crockford
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.future.await

private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.bank.accountsMgmtHandlers")

fun Routing.coreBankApi(db: Database, ctx: BankConfig) {
    get("/config") {
        call.respond(
            Config(
                currency = ctx.currencySpecification,
                have_cashout = ctx.haveCashout,
                fiat_currency = ctx.fiatCurrency,
                conversion_info = ctx.conversionInfo,
                allow_registrations = !ctx.restrictRegistration,
                allow_deletions = !ctx.restrictAccountDeletion
            )
        )
    }
    authAdmin(db, TokenScope.readonly) {
        get("/monitor") {
            val params = MonitorParams.extract(call.request.queryParameters)
            call.respond(db.monitor(params))
        }
    }
    coreBankTokenApi(db)
    coreBankAccountsMgmtApi(db, ctx)
    coreBankTransactionsApi(db, ctx)
    coreBankWithdrawalApi(db, ctx)
    coreBankCashoutApi(db, ctx)
}

private fun Routing.coreBankTokenApi(db: Database) {
    val TOKEN_DEFAULT_DURATION: Duration = Duration.ofDays(1L)
    auth(db, TokenScope.refreshable) {
        post("/accounts/{USERNAME}/token") {
            val maybeAuthToken = call.getAuthToken()
            val req = call.receive<TokenRequest>()
            /**
             * This block checks permissions ONLY IF the call was authenticated with a token. Basic auth
             * gets always granted.
             */
            if (maybeAuthToken != null) {
                val tokenBytes = Base32Crockford.decode(maybeAuthToken)
                val refreshingToken =
                        db.bearerTokenGet(tokenBytes)
                                ?: throw internalServerError(
                                        "Token used to auth not found in the database!"
                                )
                if (refreshingToken.scope == TokenScope.readonly && req.scope == TokenScope.readwrite)
                        throw forbidden(
                                "Cannot generate RW token from RO",
                                TalerErrorCode.GENERIC_TOKEN_PERMISSION_INSUFFICIENT
                        )
            }
            val tokenBytes = ByteArray(32).apply { Random.nextBytes(this) }
            val tokenDuration: Duration = req.duration?.d_us ?: TOKEN_DEFAULT_DURATION

            val creationTime = Instant.now()
            val expirationTimestamp =
                    if (tokenDuration == ChronoUnit.FOREVER.duration) {
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
            if (!db.bearerTokenCreate(
                login = username,
                content = tokenBytes,
                creationTime = creationTime,
                expirationTime = expirationTimestamp,
                scope = req.scope,
                isRefreshable = req.refreshable
            )) {
                throw internalServerError("Failed at inserting new token in the database")
            }
            call.respond(
                TokenSuccessResponse(
                    access_token = Base32Crockford.encode(tokenBytes),
                    expiration = TalerProtocolTimestamp(t_s = expirationTimestamp)
                )
            )
        }
    }
    auth(db, TokenScope.readonly) {
        delete("/accounts/{USERNAME}/token") {
            val token = call.getAuthToken() ?: throw badRequest("Basic auth not supported here.")

            /**
             * Not sanity-checking the token, as it was used by the authentication already. If harder
             * errors happen, then they'll get Ktor respond with 500.
             */
            db.bearerTokenDelete(Base32Crockford.decode(token))
            /**
             * Responding 204 regardless of it being actually deleted or not. If it wasn't found, then
             * it must have been deleted before we reached here, but the token was valid as it served
             * the authentication => no reason to fail the request.
             */
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun Routing.coreBankAccountsMgmtApi(db: Database, ctx: BankConfig) {
    authAdmin(db, TokenScope.readwrite, ctx.restrictRegistration) {
        post("/accounts") {
            val req = call.receive<RegisterAccountRequest>()
            // Prohibit reserved usernames:
            if (reservedAccounts.contains(req.username))
                throw conflict(
                    "Username '${req.username}' is reserved.",
                    TalerErrorCode.BANK_RESERVED_USERNAME_CONFLICT
                )

            val internalPayto = req.internal_payto_uri ?: IbanPayTo(genIbanPaytoUri())
            val result = db.accountCreate(
                login = req.username,
                name = req.name,
                email = req.challenge_contact_data?.email,
                phone = req.challenge_contact_data?.phone,
                cashoutPayto = req.cashout_payto_uri,
                password = req.password,
                internalPaytoUri = internalPayto,
                isPublic = req.is_public,
                isTalerExchange = req.is_taler_exchange,
                maxDebt = ctx.defaultCustomerDebtLimit,
                bonus = if (ctx.registrationBonusEnabled && !req.is_taler_exchange) ctx.registrationBonus
                        else null
            )

            when (result) {
                CustomerCreationResult.BALANCE_INSUFFICIENT -> throw conflict(
                    "Insufficient admin funds to grant bonus",
                    TalerErrorCode.BANK_UNALLOWED_DEBIT
                )
                CustomerCreationResult.CONFLICT_LOGIN -> throw conflict(
                    "Customer username reuse '${req.username}'",
                    TalerErrorCode.BANK_REGISTER_USERNAME_REUSE
                )
                CustomerCreationResult.CONFLICT_PAY_TO -> throw conflict(
                    "Bank internalPayToUri reuse '${internalPayto.canonical}'",
                    TalerErrorCode.BANK_REGISTER_PAYTO_URI_REUSE
                )
                CustomerCreationResult.SUCCESS -> call.respond(HttpStatusCode.Created)
            }
        }
    }
    auth(
        db,
        TokenScope.readwrite,
        allowAdmin = true,
        requireAdmin = ctx.restrictAccountDeletion
    ) {
        delete("/accounts/{USERNAME}") {
            // Not deleting reserved names.
            if (reservedAccounts.contains(username))
                throw conflict(
                    "Cannot delete reserved accounts",
                    TalerErrorCode.BANK_RESERVED_USERNAME_CONFLICT
                )

            when (db.customerDeleteIfBalanceIsZero(username)) {
                CustomerDeletionResult.CUSTOMER_NOT_FOUND -> throw notFound(
                    "Account '$username' not found",
                    TalerErrorCode.BANK_UNKNOWN_ACCOUNT
                )
                CustomerDeletionResult.BALANCE_NOT_ZERO -> throw conflict(
                    "Account balance is not zero.",
                    TalerErrorCode.BANK_ACCOUNT_BALANCE_NOT_ZERO
                )
                CustomerDeletionResult.SUCCESS -> call.respond(HttpStatusCode.NoContent)
            }
        }
    }
    auth(db, TokenScope.readwrite, allowAdmin = true) {
        patch("/accounts/{USERNAME}") {
            val req = call.receive<AccountReconfiguration>()
            req.debit_threshold?.run { ctx.checkInternalCurrency(this) }

            if (req.is_taler_exchange != null && username == "admin")
                throw forbidden("admin account cannot be an exchange")

            val res = db.accountReconfig(
                login = username,
                name = req.name,
                cashoutPayto = req.cashout_payto_uri,
                emailAddress = req.challenge_contact_data?.email,
                isTalerExchange = req.is_taler_exchange,
                phoneNumber = req.challenge_contact_data?.phone,
                debtLimit = req.debit_threshold,
                isAdmin = isAdmin
            )
            when (res) {
                CustomerPatchResult.SUCCESS -> call.respond(HttpStatusCode.NoContent)
                CustomerPatchResult.ACCOUNT_NOT_FOUND -> throw notFound(
                    "Account '$username' not found",
                    TalerErrorCode.BANK_UNKNOWN_ACCOUNT
                )
                CustomerPatchResult.CONFLICT_LEGAL_NAME -> 
                    throw forbidden("non-admin user cannot change their legal name")
                CustomerPatchResult.CONFLICT_DEBT_LIMIT -> 
                    throw forbidden("non-admin user cannot change their debt limit")
            }
        }
    }
    auth(db, TokenScope.readwrite) {
        patch("/accounts/{USERNAME}/auth") {
            val req = call.receive<AccountPasswordChange>()
            val hashedPassword = CryptoUtil.hashpw(req.new_password)
            if (!db.customerChangePassword(username, hashedPassword))
                throw notFound(
                    "Account '$username' not found",
                    TalerErrorCode.BANK_UNKNOWN_ACCOUNT
                )
            call.respond(HttpStatusCode.NoContent)
        }
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
    authAdmin(db, TokenScope.readonly) {
        get("/accounts") {
            // Get optional param.
            val maybeFilter: String? = call.request.queryParameters["filter_name"]
            logger.debug("Filtering on '${maybeFilter}'")
            val queryParam =
                    if (maybeFilter != null) {
                        "%${maybeFilter}%"
                    } else "%"
            val accounts = db.accountsGetForAdmin(queryParam)
            if (accounts.isEmpty()) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(ListBankAccountsResponse(accounts))
            }
        }
    }
    auth(db, TokenScope.readonly, allowAdmin = true) {
        get("/accounts/{USERNAME}") {
            val account = db.accountDataFromLogin(username) ?: throw notFound(
                "Account '$username' not found.",
                TalerErrorCode.BANK_UNKNOWN_ACCOUNT
            )
            call.respond(account)
        }
    }
}

private fun Routing.coreBankTransactionsApi(db: Database, ctx: BankConfig) {
    auth(db, TokenScope.readonly) {
        get("/accounts/{USERNAME}/transactions") {
            val params = HistoryParams.extract(call.request.queryParameters)
            val bankAccount = call.bankAccount(db)
    
            val history: List<BankAccountTransactionInfo> =
                    db.bankPoolHistory(params, bankAccount.bankAccountId)
            call.respond(BankAccountTransactionsResponse(history))
        }
        get("/accounts/{USERNAME}/transactions/{T_ID}") {
            val tId = call.expectUriComponent("T_ID")
            val txRowId =
                    try {
                        tId.toLong()
                    } catch (e: Exception) {
                        logger.error(e.message)
                        throw badRequest("TRANSACTION_ID is not a number: ${tId}")
                    }
    
            val bankAccount = call.bankAccount(db)
            val tx =
                    db.bankTransactionGetFromInternalId(txRowId)
                            ?: throw notFound(
                                    "Bank transaction '$tId' not found",
                                    TalerErrorCode.BANK_TRANSACTION_NOT_FOUND
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
    }
    auth(db, TokenScope.readwrite) {
        post("/accounts/{USERNAME}/transactions") {
            val tx = call.receive<BankAccountTransactionCreate>()
            val subject = tx.payto_uri.message ?: throw badRequest("Wire transfer lacks subject")
            val amount =
                    tx.payto_uri.amount ?: tx.amount ?: throw badRequest("Wire transfer lacks amount")
            ctx.checkInternalCurrency(amount)
            val result = db.bankTransaction(
                creditAccountPayto = tx.payto_uri,
                debitAccountUsername = username,
                subject = subject,
                amount = amount,
                timestamp = Instant.now(),
            )
            when (result) {
                BankTransactionResult.NO_DEBTOR -> throw notFound(
                    "Account '$username' not found",
                    TalerErrorCode.BANK_UNKNOWN_ACCOUNT
                )
                BankTransactionResult.SAME_ACCOUNT -> throw conflict(
                    "Wire transfer attempted with credit and debit party being the same bank account",
                    TalerErrorCode.BANK_SAME_ACCOUNT 
                )
                BankTransactionResult.NO_CREDITOR -> throw conflict(
                    "Creditor account was not found",
                    TalerErrorCode.BANK_UNKNOWN_CREDITOR
                )
                BankTransactionResult.BALANCE_INSUFFICIENT -> throw conflict(
                    "Insufficient funds",
                    TalerErrorCode.BANK_UNALLOWED_DEBIT
                )
                BankTransactionResult.SUCCESS -> call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private fun Routing.coreBankWithdrawalApi(db: Database, ctx: BankConfig) {
    auth(db, TokenScope.readwrite) {
        post("/accounts/{USERNAME}/withdrawals") {
            val req = call.receive<BankAccountCreateWithdrawalRequest>()
            ctx.checkInternalCurrency(req.amount)
            val opId = UUID.randomUUID()
            when (db.withdrawal.create(username, opId, req.amount)) {
                WithdrawalCreationResult.ACCOUNT_NOT_FOUND -> throw notFound(
                    "Account '$username' not found",
                    TalerErrorCode.BANK_UNKNOWN_ACCOUNT
                )
                WithdrawalCreationResult.ACCOUNT_IS_EXCHANGE -> throw conflict(
                    "Exchange account cannot perform withdrawal operation",
                    TalerErrorCode.BANK_ACCOUNT_IS_EXCHANGE
                )
                WithdrawalCreationResult.BALANCE_INSUFFICIENT -> throw conflict(
                    "Insufficient funds to withdraw with Taler",
                    TalerErrorCode.BANK_UNALLOWED_DEBIT
                )
                WithdrawalCreationResult.SUCCESS -> {
                    val bankBaseUrl = call.request.getBaseUrl()
                        ?: throw internalServerError("Bank could not find its own base URL")
                    call.respond(
                        BankAccountCreateWithdrawalResponse(
                            withdrawal_id = opId.toString(),
                            taler_withdraw_uri = getTalerWithdrawUri(bankBaseUrl, opId.toString())
                        )
                    )
                }
            }
        }
    }
    get("/withdrawals/{withdrawal_id}") {
        val op = call.getWithdrawal(db, "withdrawal_id")
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
        val opId = call.uuidUriComponent("withdrawal_id")
        when (db.withdrawal.abort(opId)) {
            AbortResult.NOT_FOUND -> throw notFound(
                "Withdrawal operation $opId not found",
                TalerErrorCode.BANK_TRANSACTION_NOT_FOUND
            )
            AbortResult.CONFIRMED -> throw conflict(
                "Cannot abort confirmed withdrawal", 
                TalerErrorCode.BANK_ABORT_CONFIRM_CONFLICT
            )
            AbortResult.SUCCESS -> call.respond(HttpStatusCode.NoContent)
        }
    }
    post("/withdrawals/{withdrawal_id}/confirm") {
        val opId = call.uuidUriComponent("withdrawal_id")
        when (db.withdrawal.confirm(opId, Instant.now())) {
            WithdrawalConfirmationResult.OP_NOT_FOUND -> throw notFound(
                "Withdrawal operation $opId not found",
                TalerErrorCode.BANK_TRANSACTION_NOT_FOUND
            )
            WithdrawalConfirmationResult.ABORTED -> throw conflict(
                "Cannot confirm an aborted withdrawal",
                TalerErrorCode.BANK_CONFIRM_ABORT_CONFLICT
            )
            WithdrawalConfirmationResult.NOT_SELECTED -> throw conflict(
                "Cannot confirm an unselected withdrawal",
                TalerErrorCode.BANK_CONFIRM_INCOMPLETE
            )
            WithdrawalConfirmationResult.BALANCE_INSUFFICIENT -> throw conflict(
                "Insufficient funds",
                TalerErrorCode.BANK_UNALLOWED_DEBIT
            )
            WithdrawalConfirmationResult.EXCHANGE_NOT_FOUND -> throw conflict(
                "Exchange to withdraw from not found",
                TalerErrorCode.BANK_UNKNOWN_CREDITOR
            )
            WithdrawalConfirmationResult.SUCCESS -> call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun Routing.coreBankCashoutApi(db: Database, ctx: BankConfig) {
    val TAN_RETRY_COUNTER: Int = 3;
    val TAN_VALIDITY_PERIOD: Duration = Duration.ofHours(1)
    val TAN_RETRANSMISSION_PERIOD: Duration = Duration.ofMinutes(1)

    auth(db, TokenScope.readwrite) {
        post("/accounts/{USERNAME}/cashouts") {
            val req = call.receive<CashoutRequest>()

            ctx.checkInternalCurrency(req.amount_debit)
            ctx.checkFiatCurrency(req.amount_credit)

            val tanChannel = req.tan_channel ?: TanChannel.sms
            val tanScript = when (tanChannel) {
                TanChannel.sms -> ctx.tanSms
                TanChannel.email -> ctx.tanEmail
            } ?: throw libeufinError( 
                HttpStatusCode.NotImplemented,
                "Unsupported tan channel $tanChannel",
                TalerErrorCode.BANK_TAN_CHANNEL_NOT_SUPPORTED
            )

            val res = db.cashout.create(
                accountUsername = username, 
                requestUid = req.request_uid,
                cashoutUuid = UUID.randomUUID(), 
                amountDebit = req.amount_debit, 
                amountCredit = req.amount_credit, 
                subject = req.subject ?: "", // TODO default subject
                tanChannel = tanChannel, 
                tanCode = UUID.randomUUID().toString(),
                now = Instant.now(), 
                retryCounter = TAN_RETRY_COUNTER,
                validityPeriod = TAN_VALIDITY_PERIOD
            )
            when (res.status) {
                CashoutCreationResult.ACCOUNT_NOT_FOUND -> throw notFound(
                    "Account '$username' not found",
                    TalerErrorCode.BANK_UNKNOWN_ACCOUNT
                )
                CashoutCreationResult.BAD_CONVERSION -> throw conflict(
                    "Wrong currency conversion",
                    TalerErrorCode.BANK_BAD_CONVERSION
                )
                CashoutCreationResult.ACCOUNT_IS_EXCHANGE -> throw conflict(
                    "Exchange account cannot perform cashout operation",
                    TalerErrorCode.BANK_ACCOUNT_IS_EXCHANGE
                )
                CashoutCreationResult.BALANCE_INSUFFICIENT -> throw conflict(
                    "Insufficient funds to withdraw with Taler",
                    TalerErrorCode.BANK_UNALLOWED_DEBIT
                )
                CashoutCreationResult.MISSING_TAN_INFO -> throw conflict(
                    "Account '$username' missing info for tan channel ${req.tan_channel}",
                    TalerErrorCode.BANK_MISSING_TAN_INFO
                )
                CashoutCreationResult.REQUEST_UID_REUSE -> throw conflict(
                    "request_uid used already",
                    TalerErrorCode.BANK_TRANSFER_REQUEST_UID_REUSED
                )
                CashoutCreationResult.SUCCESS -> {
                    res.tanCode?.run {
                        val exitValue = withContext(Dispatchers.IO) {
                            val process = ProcessBuilder(tanScript, res.tanInfo).start()
                            try {
                                process.outputWriter().use { it.write(res.tanCode) }
                                process.onExit().await()
                            } catch (e: Exception) {
                                process.destroy()
                            }
                            process.exitValue()
                        }
                        if (exitValue != 0) {
                            throw libeufinError(
                                HttpStatusCode.BadGateway,
                                "Tan channel script failure with exit value $exitValue",
                                TalerErrorCode.BANK_TAN_CHANNEL_SCRIPT_FAILED
                            )
                        }
                        db.cashout.markSent(res.id!!, Instant.now(), TAN_RETRANSMISSION_PERIOD)
                    }
                    call.respond(CashoutPending(res.id.toString()))
                }
            }
        }
        post("/accounts/{USERNAME}/cashouts/{CASHOUT_ID}/abort") {
            val opId = call.uuidUriComponent("CASHOUT_ID")
            when (db.cashout.abort(opId)) {
                AbortResult.NOT_FOUND -> throw notFound(
                    "Cashout operation $opId not found",
                    TalerErrorCode.BANK_TRANSACTION_NOT_FOUND
                )
                AbortResult.CONFIRMED -> throw conflict(
                    "Cannot abort confirmed cashout",
                    TalerErrorCode.BANK_ABORT_CONFIRM_CONFLICT
                )
                AbortResult.SUCCESS -> call.respond(HttpStatusCode.NoContent)
            }
        }
        post("/accounts/{USERNAME}/cashouts/{CASHOUT_ID}/confirm") {
            val req = call.receive<CashoutConfirm>()
            val opId = call.uuidUriComponent("CASHOUT_ID")
            when (db.cashout.confirm(
                opUuid = opId,
                tanCode = req.tan,
                timestamp = Instant.now()
            )) {
                CashoutConfirmationResult.OP_NOT_FOUND -> throw notFound(
                    "Cashout operation $opId not found",
                    TalerErrorCode.BANK_TRANSACTION_NOT_FOUND
                )
                CashoutConfirmationResult.ABORTED -> throw conflict(
                    "Cannot confirm an aborted cashout",
                    TalerErrorCode.BANK_CONFIRM_ABORT_CONFLICT
                )
                CashoutConfirmationResult.BAD_TAN_CODE -> throw forbidden(
                    "Incorrect TAN code",
                    TalerErrorCode.BANK_TAN_CHALLENGE_FAILED
                )
                CashoutConfirmationResult.NO_RETRY -> throw libeufinError(
                    HttpStatusCode.TooManyRequests,
                    "Too many failed confirmation attempt",
                    TalerErrorCode.BANK_TAN_RATE_LIMITED
                )
                CashoutConfirmationResult.NO_CASHOUT_PAYTO -> throw conflict(
                    "Missing cashout payto uri",
                    TalerErrorCode.BANK_CONFIRM_INCOMPLETE
                )
                CashoutConfirmationResult.BALANCE_INSUFFICIENT -> throw conflict(
                    "Insufficient funds",
                    TalerErrorCode.BANK_UNALLOWED_DEBIT
                )
                CashoutConfirmationResult.BAD_CONVERSION -> throw conflict(
                    "Wrong currency conversion",
                    TalerErrorCode.BANK_BAD_CONVERSION
                )
                CashoutConfirmationResult.SUCCESS -> call.respond(HttpStatusCode.NoContent)
            }
        }
    }
    auth(db, TokenScope.readonly) {
        get("/accounts/{USERNAME}/cashouts") {
            // TODO
        }
        get("/accounts/{USERNAME}/cashouts/{CASHOUT_ID}") {
            // TODO
        }
    }
    authAdmin(db, TokenScope.readonly) {
        get("/cashouts") {
            // TODO
        }
    }
    get("/cashout-rate") {
        val params = RateParams.extract(call.request.queryParameters)

        params.debit?.let { ctx.checkInternalCurrency(it) }
        params.credit?.let { ctx.checkFiatCurrency(it) }

        if (params.debit != null) {
            val credit = db.conversion.internalToFiat(params.debit) ?:
                throw conflict(
                    "${params.debit} is too small to be converted",
                    TalerErrorCode.BANK_BAD_CONVERSION
                )
            call.respond(ConversionResponse(params.debit, credit))
        } else {
            call.respond(HttpStatusCode.NotImplemented) // TODO
        }
    }
    get("/cashin-rate") {
        val params = RateParams.extract(call.request.queryParameters)

        params.debit?.let { ctx.checkFiatCurrency(it) }
        params.credit?.let { ctx.checkInternalCurrency(it) }

        if (params.debit != null) {
            val credit = db.conversion.fiatToInternal(params.debit) ?:
                throw conflict(
                    "${params.debit} is too small to be converted",
                    TalerErrorCode.BANK_BAD_CONVERSION
                )
            call.respond(ConversionResponse(params.debit, credit))
        } else {
            call.respond(HttpStatusCode.NotImplemented) // TODO
        }
    }
}
