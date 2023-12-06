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
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import net.taler.common.errorcodes.TalerErrorCode
import net.taler.wallet.crypto.Base32Crockford
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.bank.AccountDAO.*
import tech.libeufin.bank.CashoutDAO.*
import tech.libeufin.bank.ExchangeDAO.*
import tech.libeufin.bank.TransactionDAO.*
import tech.libeufin.bank.WithdrawalDAO.*
import tech.libeufin.util.*

private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.bank.accountsMgmtHandlers")

fun Routing.coreBankApi(db: Database, ctx: BankConfig) {
    get("/config") {
        call.respond(
            Config(
                currency = ctx.regionalCurrency,
                currency_specification = ctx.regionalCurrencySpec,
                allow_conversion = ctx.allowConversion,
                allow_registrations = ctx.allowRegistration,
                allow_deletions = ctx.allowAccountDeletion,
                default_debit_threshold = ctx.defaultDebtLimit,
                supported_tan_channels = ctx.tanChannels.keys,
                allow_edit_name = ctx.allowEditName,
                allow_edit_cashout_payto_uri = ctx.allowEditCashout
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
    coreBankAccountsApi(db, ctx)
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
                        db.token.get(tokenBytes)
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
                            throw badRequest("Bad token duration: ${e.message}")
                        }
                    }
            if (!db.token.create(
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
            db.token.delete(Base32Crockford.decode(token))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

suspend fun createAccount(db: Database, ctx: BankConfig, req: RegisterAccountRequest, isAdmin: Boolean): Pair<AccountCreationResult, IbanPayTo>  {
    // Prohibit reserved usernames:
    if (RESERVED_ACCOUNTS.contains(req.username))
        throw conflict(
            "Username '${req.username}' is reserved.",
            TalerErrorCode.BANK_RESERVED_USERNAME_CONFLICT
        )

    if (req.debit_threshold != null && !isAdmin)
        throw conflict(
            "only admin account can choose the debit limit",
            TalerErrorCode.BANK_NON_ADMIN_PATCH_DEBT_LIMIT
        )


    val internalPayto = req.payto_uri ?: req.internal_payto_uri ?: IbanPayTo(genIbanPaytoUri())
    val contactData = req.contact_data ?: req.challenge_contact_data
    val res = db.account.create(
        login = req.username,
        name = req.name,
        email = contactData?.email?.get(),
        phone = contactData?.phone?.get(),
        cashoutPayto = req.cashout_payto_uri,
        password = req.password,
        internalPaytoUri = internalPayto,
        isPublic = req.is_public,
        isTalerExchange = req.is_taler_exchange,
        maxDebt = req.debit_threshold ?: ctx.defaultDebtLimit,
        bonus = if (!req.is_taler_exchange) ctx.registrationBonus 
                else TalerAmount(0, 0, ctx.regionalCurrency),
        checkPaytoIdempotent = req.internal_payto_uri != null
    )
    return Pair(res, internalPayto)
}

suspend fun patchAccount(db: Database, ctx: BankConfig, req: AccountReconfiguration, username: String, isAdmin: Boolean): AccountPatchResult {
    req.debit_threshold?.run { ctx.checkRegionalCurrency(this) }
    val contactData = req.contact_data ?: req.challenge_contact_data

    return db.account.reconfig( 
        login = username,
        name = req.name,
        cashoutPayto = req.cashout_payto_uri, 
        email = contactData?.email ?: Option.None,
        phone = contactData?.phone ?: Option.None,
        isPublic = req.is_public,
        debtLimit = req.debit_threshold,
        isAdmin = isAdmin,
        allowEditName = ctx.allowEditName,
        allowEditCashout = ctx.allowEditCashout
    )
}

private fun Routing.coreBankAccountsApi(db: Database, ctx: BankConfig) {
    authAdmin(db, TokenScope.readwrite, !ctx.allowRegistration) {
        post("/accounts") {
            val req = call.receive<RegisterAccountRequest>()
            val (result, internalPayto) = createAccount(db, ctx, req, isAdmin);
            when (result) {
                AccountCreationResult.BonusBalanceInsufficient -> throw conflict(
                    "Insufficient admin funds to grant bonus",
                    TalerErrorCode.BANK_UNALLOWED_DEBIT
                )
                AccountCreationResult.LoginReuse -> throw conflict(
                    "Account username reuse '${req.username}'",
                    TalerErrorCode.BANK_REGISTER_USERNAME_REUSE
                )
                AccountCreationResult.PayToReuse -> throw conflict(
                    "Bank internalPayToUri reuse '${internalPayto.canonical}'",
                    TalerErrorCode.BANK_REGISTER_PAYTO_URI_REUSE
                )
                AccountCreationResult.Success -> call.respond(RegisterAccountResponse(internalPayto.canonical))
            }
        }
    }
    auth(
        db,
        TokenScope.readwrite,
        allowAdmin = true,
        requireAdmin = !ctx.allowAccountDeletion
    ) {
        delete("/accounts/{USERNAME}") {
            // Not deleting reserved names.
            if (RESERVED_ACCOUNTS.contains(username))
                throw conflict(
                    "Cannot delete reserved accounts",
                    TalerErrorCode.BANK_RESERVED_USERNAME_CONFLICT
                )
            if (username == "exchange" && ctx.allowConversion)
                throw conflict(
                    "Cannot delete 'exchange' accounts when conversion is enabled",
                    TalerErrorCode.BANK_RESERVED_USERNAME_CONFLICT
                )

            when (db.account.delete(username)) {
                AccountDeletionResult.UnknownAccount -> throw notFound(
                    "Account '$username' not found",
                    TalerErrorCode.BANK_UNKNOWN_ACCOUNT
                )
                AccountDeletionResult.BalanceNotZero -> throw conflict(
                    "Account balance is not zero.",
                    TalerErrorCode.BANK_ACCOUNT_BALANCE_NOT_ZERO
                )
                AccountDeletionResult.Success -> call.respond(HttpStatusCode.NoContent)
            }
        }
    }
    auth(db, TokenScope.readwrite, allowAdmin = true) {
        patch("/accounts/{USERNAME}") {
            val req = call.receive<AccountReconfiguration>()
            when (patchAccount(db, ctx, req, username, isAdmin)) {
                AccountPatchResult.Success -> call.respond(HttpStatusCode.NoContent)
                AccountPatchResult.UnknownAccount -> throw notFound(
                    "Account '$username' not found",
                    TalerErrorCode.BANK_UNKNOWN_ACCOUNT
                )
                AccountPatchResult.NonAdminName -> throw conflict(
                    "non-admin user cannot change their legal name",
                    TalerErrorCode.BANK_NON_ADMIN_PATCH_LEGAL_NAME
                )
                AccountPatchResult.NonAdminCashout -> throw conflict(
                    "non-admin user cannot change their cashout account",
                    TalerErrorCode.BANK_NON_ADMIN_PATCH_CASHOUT
                )
                AccountPatchResult.NonAdminDebtLimit -> throw conflict(
                    "non-admin user cannot change their debt limit",
                    TalerErrorCode.BANK_NON_ADMIN_PATCH_DEBT_LIMIT
                )
            }
        }
        patch("/accounts/{USERNAME}/auth") {
            val req = call.receive<AccountPasswordChange>()
            if (!isAdmin && req.old_password == null) {
                throw conflict(
                    "non-admin user cannot change password without providing old password",
                    TalerErrorCode.BANK_NON_ADMIN_PATCH_MISSING_OLD_PASSWORD
                )
            }
            when (db.account.reconfigPassword(username, req.new_password, req.old_password)) {
                AccountPatchAuthResult.Success -> call.respond(HttpStatusCode.NoContent)
                AccountPatchAuthResult.UnknownAccount -> throw notFound(
                    "Account '$username' not found",
                    TalerErrorCode.BANK_UNKNOWN_ACCOUNT
                )
                AccountPatchAuthResult.OldPasswordMismatch -> throw conflict(
                    "old password does not match",
                    TalerErrorCode.BANK_PATCH_BAD_OLD_PASSWORD
                )
            }
        }
    }
    get("/public-accounts") {
        val params = AccountParams.extract(call.request.queryParameters)
        val publicAccounts = db.account.pagePublic(params)
        if (publicAccounts.isEmpty()) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(PublicAccountsResponse(publicAccounts))
        }
    }
    authAdmin(db, TokenScope.readonly) {
        get("/accounts") {
            val params = AccountParams.extract(call.request.queryParameters)
            val accounts = db.account.pageAdmin(params)
            if (accounts.isEmpty()) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(ListBankAccountsResponse(accounts))
            }
        }
    }
    auth(db, TokenScope.readonly, allowAdmin = true) {
        get("/accounts/{USERNAME}") {
            val account = db.account.get(username) ?: throw notFound(
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
            val bankAccount = call.bankInfo(db)
    
            val history: List<BankAccountTransactionInfo> =
                    db.transaction.pollHistory(params, bankAccount.bankAccountId)
            if (history.isEmpty()) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(BankAccountTransactionsResponse(history))
            }
        }
        get("/accounts/{USERNAME}/transactions/{T_ID}") {
            val tId = call.longUriComponent("T_ID")
            val tx = db.transaction.get(tId, username) ?: throw notFound(
                    "Bank transaction '$tId' not found",
                    TalerErrorCode.BANK_TRANSACTION_NOT_FOUND
                )
            call.respond(tx)
        }
    }
    auth(db, TokenScope.readwrite) {
        post("/accounts/{USERNAME}/transactions") {
            val tx = call.receive<TransactionCreateRequest>()
            val subject = tx.payto_uri.message ?: throw badRequest("Wire transfer lacks subject")
            val amount =
                    tx.payto_uri.amount ?: tx.amount ?: throw badRequest("Wire transfer lacks amount")
            ctx.checkRegionalCurrency(amount)
            val res = db.transaction.create(
                creditAccountPayto = tx.payto_uri,
                debitAccountUsername = username,
                subject = subject,
                amount = amount,
                timestamp = Instant.now(),
            )
            when (res) {
                is BankTransactionResult.UnknownDebtor -> throw notFound(
                    "Account '$username' not found",
                    TalerErrorCode.BANK_UNKNOWN_ACCOUNT
                )
                is BankTransactionResult.BothPartySame -> throw conflict(
                    "Wire transfer attempted with credit and debit party being the same bank account",
                    TalerErrorCode.BANK_SAME_ACCOUNT 
                )
                is BankTransactionResult.UnknownCreditor -> throw conflict(
                    "Creditor account was not found",
                    TalerErrorCode.BANK_UNKNOWN_CREDITOR
                )
                is BankTransactionResult.BalanceInsufficient -> throw conflict(
                    "Insufficient funds",
                    TalerErrorCode.BANK_UNALLOWED_DEBIT
                )
                is BankTransactionResult.Success -> call.respond(TransactionCreateResponse(res.id))
            }
        }
    }
}

private fun Routing.coreBankWithdrawalApi(db: Database, ctx: BankConfig) {
    auth(db, TokenScope.readwrite) {
        post("/accounts/{USERNAME}/withdrawals") {
            val req = call.receive<BankAccountCreateWithdrawalRequest>()
            ctx.checkRegionalCurrency(req.amount)
            val opId = UUID.randomUUID()
            when (db.withdrawal.create(username, opId, req.amount)) {
                WithdrawalCreationResult.UnknownAccount -> throw notFound(
                    "Account '$username' not found",
                    TalerErrorCode.BANK_UNKNOWN_ACCOUNT
                )
                WithdrawalCreationResult.AccountIsExchange -> throw conflict(
                    "Exchange account cannot perform withdrawal operation",
                    TalerErrorCode.BANK_ACCOUNT_IS_EXCHANGE
                )
                WithdrawalCreationResult.BalanceInsufficient -> throw conflict(
                    "Insufficient funds to withdraw with Taler",
                    TalerErrorCode.BANK_UNALLOWED_DEBIT
                )
                WithdrawalCreationResult.Success -> {
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
        post("/accounts/{USERNAME}/withdrawals/{withdrawal_id}/abort") {
            val opId = call.uuidUriComponent("withdrawal_id")
            when (db.withdrawal.abort(opId)) {
                AbortResult.UnknownOperation -> throw notFound(
                    "Withdrawal operation $opId not found",
                    TalerErrorCode.BANK_TRANSACTION_NOT_FOUND
                )
                AbortResult.AlreadyConfirmed -> throw conflict(
                    "Cannot abort confirmed withdrawal", 
                    TalerErrorCode.BANK_ABORT_CONFIRM_CONFLICT
                )
                AbortResult.Success -> call.respond(HttpStatusCode.NoContent)
            }
        }
        post("/accounts/{USERNAME}/withdrawals/{withdrawal_id}/confirm") {
            val opId = call.uuidUriComponent("withdrawal_id")
            when (db.withdrawal.confirm(opId, Instant.now())) {
                WithdrawalConfirmationResult.UnknownOperation -> throw notFound(
                    "Withdrawal operation $opId not found",
                    TalerErrorCode.BANK_TRANSACTION_NOT_FOUND
                )
                WithdrawalConfirmationResult.AlreadyAborted -> throw conflict(
                    "Cannot confirm an aborted withdrawal",
                    TalerErrorCode.BANK_CONFIRM_ABORT_CONFLICT
                )
                WithdrawalConfirmationResult.NotSelected -> throw conflict(
                    "Cannot confirm an unselected withdrawal",
                    TalerErrorCode.BANK_CONFIRM_INCOMPLETE
                )
                WithdrawalConfirmationResult.BalanceInsufficient -> throw conflict(
                    "Insufficient funds",
                    TalerErrorCode.BANK_UNALLOWED_DEBIT
                )
                WithdrawalConfirmationResult.UnknownExchange -> throw conflict(
                    "Exchange to withdraw from not found",
                    TalerErrorCode.BANK_UNKNOWN_CREDITOR
                )
                WithdrawalConfirmationResult.Success -> call.respond(HttpStatusCode.NoContent)
            }
        }
    }
    get("/withdrawals/{withdrawal_id}") {
        val uuid = call.uuidUriComponent("withdrawal_id")
        val params = StatusParams.extract(call.request.queryParameters)
        val op = db.withdrawal.pollInfo(uuid, params) ?: throw notFound(
            "Withdrawal operation '$uuid' not found", 
            TalerErrorCode.BANK_TRANSACTION_NOT_FOUND
        )
        call.respond(op)
    }
    post("/withdrawals/{withdrawal_id}/abort") {
        val opId = call.uuidUriComponent("withdrawal_id")
        when (db.withdrawal.abort(opId)) {
            AbortResult.UnknownOperation -> throw notFound(
                "Withdrawal operation $opId not found",
                TalerErrorCode.BANK_TRANSACTION_NOT_FOUND
            )
            AbortResult.AlreadyConfirmed -> throw conflict(
                "Cannot abort confirmed withdrawal", 
                TalerErrorCode.BANK_ABORT_CONFIRM_CONFLICT
            )
            AbortResult.Success -> call.respond(HttpStatusCode.NoContent)
        }
    }
    post("/withdrawals/{withdrawal_id}/confirm") {
        val opId = call.uuidUriComponent("withdrawal_id")
        when (db.withdrawal.confirm(opId, Instant.now())) {
            WithdrawalConfirmationResult.UnknownOperation -> throw notFound(
                "Withdrawal operation $opId not found",
                TalerErrorCode.BANK_TRANSACTION_NOT_FOUND
            )
            WithdrawalConfirmationResult.AlreadyAborted -> throw conflict(
                "Cannot confirm an aborted withdrawal",
                TalerErrorCode.BANK_CONFIRM_ABORT_CONFLICT
            )
            WithdrawalConfirmationResult.NotSelected -> throw conflict(
                "Cannot confirm an unselected withdrawal",
                TalerErrorCode.BANK_CONFIRM_INCOMPLETE
            )
            WithdrawalConfirmationResult.BalanceInsufficient -> throw conflict(
                "Insufficient funds",
                TalerErrorCode.BANK_UNALLOWED_DEBIT
            )
            WithdrawalConfirmationResult.UnknownExchange -> throw conflict(
                "Exchange to withdraw from not found",
                TalerErrorCode.BANK_UNKNOWN_CREDITOR
            )
            WithdrawalConfirmationResult.Success -> call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun Routing.coreBankCashoutApi(db: Database, ctx: BankConfig) = conditional(ctx.allowConversion) {
    auth(db, TokenScope.readwrite) {
        post("/accounts/{USERNAME}/cashouts") {
            val req = call.receive<CashoutRequest>()

            ctx.checkRegionalCurrency(req.amount_debit)
            ctx.checkFiatCurrency(req.amount_credit)

            val tanChannel = req.tan_channel ?: TanChannel.sms
            val tanScript = ctx.tanChannels.get(tanChannel) ?: throw libeufinError( 
                HttpStatusCode.NotImplemented,
                "Unsupported tan channel $tanChannel",
                TalerErrorCode.BANK_TAN_CHANNEL_NOT_SUPPORTED
            )

            val res = db.cashout.create(
                login = username, 
                requestUid = req.request_uid,
                amountDebit = req.amount_debit, 
                amountCredit = req.amount_credit, 
                subject = req.subject ?: "", // TODO default subject
                tanChannel = tanChannel, 
                tanCode = UUID.randomUUID().toString(),
                now = Instant.now(), 
                retryCounter = TAN_RETRY_COUNTER,
                validityPeriod = TAN_VALIDITY_PERIOD
            )
            when (res) {
                is CashoutCreationResult.AccountNotFound -> throw notFound(
                    "Account '$username' not found",
                    TalerErrorCode.BANK_UNKNOWN_ACCOUNT
                )
                is CashoutCreationResult.BadConversion -> throw conflict(
                    "Wrong currency conversion",
                    TalerErrorCode.BANK_BAD_CONVERSION
                )
                is CashoutCreationResult.AccountIsExchange -> throw conflict(
                    "Exchange account cannot perform cashout operation",
                    TalerErrorCode.BANK_ACCOUNT_IS_EXCHANGE
                )
                is CashoutCreationResult.BalanceInsufficient -> throw conflict(
                    "Insufficient funds to withdraw with Taler",
                    TalerErrorCode.BANK_UNALLOWED_DEBIT
                )
                is CashoutCreationResult.MissingTanInfo -> throw conflict(
                    "Account '$username' missing info for tan channel ${req.tan_channel}",
                    TalerErrorCode.BANK_MISSING_TAN_INFO
                )
                is CashoutCreationResult.RequestUidReuse -> throw conflict(
                    "request_uid used already",
                    TalerErrorCode.BANK_TRANSFER_REQUEST_UID_REUSED
                )
                is CashoutCreationResult.Success -> {
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
                        db.cashout.markSent(res.id, Instant.now(), TAN_RETRANSMISSION_PERIOD, tanChannel, res.tanInfo)
                    }
                    call.respond(CashoutPending(res.id))
                }
            }
        }
        post("/accounts/{USERNAME}/cashouts/{CASHOUT_ID}/abort") {
            val id = call.longUriComponent("CASHOUT_ID")
            when (db.cashout.abort(id, username)) {
                AbortResult.UnknownOperation -> throw notFound(
                    "Cashout operation $id not found",
                    TalerErrorCode.BANK_TRANSACTION_NOT_FOUND
                )
                AbortResult.AlreadyConfirmed -> throw conflict(
                    "Cannot abort confirmed cashout",
                    TalerErrorCode.BANK_ABORT_CONFIRM_CONFLICT
                )
                AbortResult.Success -> call.respond(HttpStatusCode.NoContent)
            }
        }
        post("/accounts/{USERNAME}/cashouts/{CASHOUT_ID}/confirm") {
            val req = call.receive<CashoutConfirm>()
            val id = call.longUriComponent("CASHOUT_ID")
            when (db.cashout.confirm(
                id = id,
                login = username,
                tanCode = req.tan,
                timestamp = Instant.now()
            )) {
                CashoutConfirmationResult.OP_NOT_FOUND -> throw notFound(
                    "Cashout operation $id not found",
                    TalerErrorCode.BANK_TRANSACTION_NOT_FOUND
                )
                CashoutConfirmationResult.ABORTED -> throw conflict(
                    "Cannot confirm an aborted cashout",
                    TalerErrorCode.BANK_CONFIRM_ABORT_CONFLICT
                )
                CashoutConfirmationResult.BAD_TAN_CODE -> throw conflict(
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
        get("/accounts/{USERNAME}/cashouts/{CASHOUT_ID}") {
            val id = call.longUriComponent("CASHOUT_ID")
            val cashout = db.cashout.get(id, username) ?: throw notFound(
                "Cashout operation $id not found", 
                TalerErrorCode.BANK_TRANSACTION_NOT_FOUND
            )
            call.respond(cashout)
        }
        get("/accounts/{USERNAME}/cashouts") {
            val params = PageParams.extract(call.request.queryParameters)
            val cashouts = db.cashout.pageForUser(params, username)
            if (cashouts.isEmpty()) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(Cashouts(cashouts))
            }
        }
    }
    authAdmin(db, TokenScope.readonly) {
        get("/cashouts") {
            val params = PageParams.extract(call.request.queryParameters)
            val cashouts = db.cashout.pageAll(params)
            if (cashouts.isEmpty()) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(GlobalCashouts(cashouts))
            }
        }
    }
}
