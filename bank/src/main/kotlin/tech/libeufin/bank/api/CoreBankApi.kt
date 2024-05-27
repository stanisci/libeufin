/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023-2024 Taler Systems S.A.

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
package tech.libeufin.bank.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.bank.*
import tech.libeufin.bank.auth.*
import tech.libeufin.bank.db.AbortResult
import tech.libeufin.bank.db.AccountDAO.*
import tech.libeufin.bank.db.CashoutDAO.CashoutCreationResult
import tech.libeufin.bank.db.Database
import tech.libeufin.bank.db.TanDAO.TanSendResult
import tech.libeufin.bank.db.TanDAO.TanSolveResult
import tech.libeufin.bank.db.TransactionDAO.BankTransactionResult
import tech.libeufin.bank.db.WithdrawalDAO.WithdrawalConfirmationResult
import tech.libeufin.bank.db.WithdrawalDAO.WithdrawalCreationResult
import tech.libeufin.common.*
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

private val logger: Logger = LoggerFactory.getLogger("libeufin-bank-api")

fun Routing.coreBankApi(db: Database, ctx: BankConfig) {
    get("/config") {
        call.respond(
            Config(
                bank_name = ctx.name,
                base_url = ctx.baseUrl,
                currency = ctx.regionalCurrency,
                currency_specification = ctx.regionalCurrencySpec,
                allow_conversion = ctx.allowConversion,
                allow_registrations = ctx.allowRegistration,
                allow_deletions = ctx.allowAccountDeletion,
                default_debit_threshold = ctx.defaultDebtLimit,
                supported_tan_channels = ctx.tanChannels.keys,
                allow_edit_name = ctx.allowEditName,
                allow_edit_cashout_payto_uri = ctx.allowEditCashout,
                wire_type = ctx.wireMethod
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
    coreBankTanApi(db, ctx)
}

private fun Routing.coreBankTokenApi(db: Database) {
    val TOKEN_DEFAULT_DURATION: Duration = Duration.ofDays(1L)
    auth(db, TokenScope.refreshable) {
        post("/accounts/{USERNAME}/token") {
            val existingToken = call.authToken
            val req = call.receive<TokenRequest>()
            
            if (existingToken != null) {
                // This block checks permissions ONLY IF the call was authenticated with a token
                val refreshingToken = db.token.get(existingToken) ?: throw internalServerError(
                    "Token used to auth not found in the database!"
                )
                if (refreshingToken.scope == TokenScope.readonly && req.scope == TokenScope.readwrite)
                    throw forbidden(
                        "Cannot generate RW token from RO",
                        TalerErrorCode.GENERIC_TOKEN_PERMISSION_INSUFFICIENT
                    )
            }
            val token = Base32Crockford32B.rand()
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
                content = token.raw,
                creationTime = creationTime,
                expirationTime = expirationTimestamp,
                scope = req.scope,
                isRefreshable = req.refreshable
            )) {
                throw internalServerError("Failed at inserting new token in the database")
            }
            call.respond(
                TokenSuccessResponse(
                    access_token = "$TOKEN_PREFIX$token",
                    expiration = TalerProtocolTimestamp(t_s = expirationTimestamp)
                )
            )
        }
    }
    auth(db, TokenScope.readonly) {
        delete("/accounts/{USERNAME}/token") {
            val token = call.authToken ?: throw badRequest("Basic auth not supported here.")
            db.token.delete(token)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

suspend fun createAccount(
    db: Database, 
    cfg: BankConfig, 
    req: RegisterAccountRequest, 
    isAdmin: Boolean
): AccountCreationResult  {
    // Prohibit reserved usernames:
    if (RESERVED_ACCOUNTS.contains(req.username))
        throw conflict(
            "Username '${req.username}' is reserved",
            TalerErrorCode.BANK_RESERVED_USERNAME_CONFLICT
        )

    if (!isAdmin) {
        if (req.debit_threshold != null)
            throw conflict(
                "only admin account can choose the debit limit",
                TalerErrorCode.BANK_NON_ADMIN_PATCH_DEBT_LIMIT
            )
        
        if (req.min_cashout != null)
            throw conflict(
                "only admin account can choose the minimum cashout amount",
                TalerErrorCode.BANK_NON_ADMIN_SET_MIN_CASHOUT
            )

        if (req.tan_channel != null)
            throw conflict(
                "only admin account can enable 2fa on creation",
                TalerErrorCode.BANK_NON_ADMIN_SET_TAN_CHANNEL
            )

    } else if (req.tan_channel != null) {
        if (cfg.tanChannels.get(req.tan_channel) == null) {
            throw unsupportedTanChannel(req.tan_channel)
        } 
        val missing = when (req.tan_channel) {
            TanChannel.sms ->  req.contact_data?.phone?.get() == null
            TanChannel.email ->  req.contact_data?.email?.get() == null
        }
        if (missing)
            throw conflict(
                "missing info for tan channel ${req.tan_channel}",
                TalerErrorCode.BANK_MISSING_TAN_INFO
            )
    }
   
    if (req.username == "exchange" && !req.is_taler_exchange)
        throw conflict(
            "'exchange' account must be a taler exchange account",
            TalerErrorCode.END
        )

    suspend fun doDb(internalPayto: Payto) = db.account.create(
        login = req.username,
        name = req.name,
        email = req.contact_data?.email?.get(),
        phone = req.contact_data?.phone?.get(),
        cashoutPayto = req.cashout_payto_uri,
        password = req.password,
        internalPayto = internalPayto,
        isPublic = req.is_public,
        isTalerExchange = req.is_taler_exchange,
        maxDebt = req.debit_threshold ?: cfg.defaultDebtLimit,
        bonus = if (!req.is_taler_exchange) cfg.registrationBonus 
                else TalerAmount(0, 0, cfg.regionalCurrency),
        tanChannel = req.tan_channel,
        checkPaytoIdempotent = req.payto_uri != null,
        ctx = cfg.payto,
        minCashout = req.min_cashout
    )

    when (cfg.wireMethod) {
        WireMethod.IBAN -> {
            req.payto_uri?.expectRequestIban()
            var retry = if (req.payto_uri == null) IBAN_ALLOCATION_RETRY_COUNTER else 0

            while (true) {
                val internalPayto = req.payto_uri ?: IbanPayto.rand() as Payto
                val res = doDb(internalPayto)
                // Retry with new IBAN
                if (res == AccountCreationResult.PayToReuse && retry > 0) {
                    retry--
                    continue
                }
                return res
            }
        }
        WireMethod.X_TALER_BANK -> {
            if (req.payto_uri != null) {
                val payto = req.payto_uri.expectRequestXTalerBank()
                if (payto.username != req.username)
                    throw badRequest("Expected a payto uri for '${req.username}' got one for '${payto.username}'")
            }
         
            val internalPayto = XTalerBankPayto.forUsername(req.username)
            return doDb(internalPayto)
        }
    }
}

suspend fun patchAccount(
    db: Database, 
    cfg: BankConfig, 
    req: AccountReconfiguration, 
    username: String, 
    isAdmin: Boolean, 
    is2fa: Boolean, 
    channel: TanChannel? = null, 
    info: String? = null
): AccountPatchResult {
    req.debit_threshold?.run { cfg.checkRegionalCurrency(this) }

    if (username == "admin" && req.is_public == true)
        throw conflict(
            "'admin' account cannot be public",
            TalerErrorCode.END
        )
    
    if (req.tan_channel is Option.Some && req.tan_channel.value != null && !cfg.tanChannels.contains(req.tan_channel.value)) {
        throw unsupportedTanChannel(req.tan_channel.value)
    }

    return db.account.reconfig( 
        login = username,
        name = req.name,
        cashoutPayto = req.cashout_payto_uri, 
        email = req.contact_data?.email ?: Option.None,
        phone = req.contact_data?.phone ?: Option.None,
        tan_channel = req.tan_channel,
        isPublic = req.is_public,
        debtLimit = req.debit_threshold,
        minCashout = req.min_cashout,
        isAdmin = isAdmin,
        is2fa = is2fa,
        faChannel = channel,
        faInfo = info,
        allowEditName = cfg.allowEditName,
        allowEditCashout = cfg.allowEditCashout
    )
}

private fun Routing.coreBankAccountsApi(db: Database, ctx: BankConfig) {
    authAdmin(db, TokenScope.readwrite, !ctx.allowRegistration) {
        post("/accounts") {
            val req = call.receive<RegisterAccountRequest>()
            when (val result = createAccount(db, ctx, req, isAdmin)) {
                AccountCreationResult.BonusBalanceInsufficient -> throw conflict(
                    "Insufficient admin funds to grant bonus",
                    TalerErrorCode.BANK_UNALLOWED_DEBIT
                )
                AccountCreationResult.LoginReuse -> throw conflict(
                    "Account username reuse '${req.username}'",
                    TalerErrorCode.BANK_REGISTER_USERNAME_REUSE
                )
                AccountCreationResult.PayToReuse -> throw conflict(
                    "Bank internalPayToUri reuse",
                    TalerErrorCode.BANK_REGISTER_PAYTO_URI_REUSE
                )
                is AccountCreationResult.Success -> call.respond(RegisterAccountResponse(result.payto))
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
            val challenge = call.checkChallenge(db, Operation.account_delete)

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

            when (db.account.delete(username, isAdmin || challenge != null)) {
                AccountDeletionResult.UnknownAccount -> throw unknownAccount(username)
                AccountDeletionResult.BalanceNotZero -> throw conflict(
                    "Account balance is not zero.",
                    TalerErrorCode.BANK_ACCOUNT_BALANCE_NOT_ZERO
                )
                AccountDeletionResult.TanRequired -> call.respondChallenge(db, Operation.account_delete, Unit)
                AccountDeletionResult.Success -> call.respond(HttpStatusCode.NoContent)
            }
        }
    }
    auth(db, TokenScope.readwrite, allowAdmin = true) {
        patch("/accounts/{USERNAME}") {
            val (req, challenge) = call.receiveChallenge<AccountReconfiguration>(db, Operation.account_reconfig)
            val res = patchAccount(db, ctx, req, username, isAdmin, challenge != null, challenge?.channel, challenge?.info)
            when (res) {
                AccountPatchResult.Success -> call.respond(HttpStatusCode.NoContent)
                is AccountPatchResult.TanRequired -> {
                    call.respondChallenge(db, Operation.account_reconfig, req, res.channel, res.info)
                }
                AccountPatchResult.UnknownAccount -> throw unknownAccount(username)
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
                AccountPatchResult.NonAdminMinCashout -> throw conflict(
                    "non-admin user cannot change their min cashout amount",
                    TalerErrorCode.BANK_NON_ADMIN_SET_MIN_CASHOUT
                )
                AccountPatchResult.MissingTanInfo -> throw conflict(
                    "missing info for tan channel ${req.tan_channel.get()}",
                    TalerErrorCode.BANK_MISSING_TAN_INFO
                )
            }
        }
        patch("/accounts/{USERNAME}/auth") {
            val (req, challenge) = call.receiveChallenge<AccountPasswordChange>(db, Operation.account_auth_reconfig)

            if (!isAdmin && req.old_password == null) {
                throw conflict(
                    "non-admin user cannot change password without providing old password",
                    TalerErrorCode.BANK_NON_ADMIN_PATCH_MISSING_OLD_PASSWORD
                )
            }
            when (db.account.reconfigPassword(username, req.new_password, req.old_password, isAdmin || challenge != null)) {
                AccountPatchAuthResult.Success -> call.respond(HttpStatusCode.NoContent)
                AccountPatchAuthResult.TanRequired -> call.respondChallenge(db, Operation.account_auth_reconfig, req)
                AccountPatchAuthResult.UnknownAccount -> throw unknownAccount(username)
                AccountPatchAuthResult.OldPasswordMismatch -> throw conflict(
                    "old password does not match",
                    TalerErrorCode.BANK_PATCH_BAD_OLD_PASSWORD
                )
            }
        }
    }
    get("/public-accounts") {
        val params = AccountParams.extract(call.request.queryParameters)
        val publicAccounts = db.account.pagePublic(params, ctx.payto)
        if (publicAccounts.isEmpty()) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(PublicAccountsResponse(publicAccounts))
        }
    }
    authAdmin(db, TokenScope.readonly) {
        get("/accounts") {
            val params = AccountParams.extract(call.request.queryParameters)
            val accounts = db.account.pageAdmin(params, ctx.payto)
            if (accounts.isEmpty()) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(ListBankAccountsResponse(accounts))
            }
        }
    }
    auth(db, TokenScope.readonly, allowAdmin = true) {
        get("/accounts/{USERNAME}") {
            val account = db.account.get(username, ctx.payto) ?: throw unknownAccount(username)
            call.respond(account)
        }
    }
}

private fun Routing.coreBankTransactionsApi(db: Database, ctx: BankConfig) {
    auth(db, TokenScope.readonly, allowAdmin = true) {
        get("/accounts/{USERNAME}/transactions") {
            val params = HistoryParams.extract(call.request.queryParameters)
            val bankAccount = call.bankInfo(db, ctx.payto)
    
            val history: List<BankAccountTransactionInfo> =
                    db.transaction.pollHistory(params, bankAccount.bankAccountId, ctx.payto)
            if (history.isEmpty()) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(BankAccountTransactionsResponse(history))
            }
        }
        get("/accounts/{USERNAME}/transactions/{T_ID}") {
            val tId = call.longPath("T_ID")
            val tx = db.transaction.get(tId, username, ctx.payto) ?: throw notFound(
                    "Bank transaction '$tId' not found",
                    TalerErrorCode.BANK_TRANSACTION_NOT_FOUND
                )
            call.respond(tx)
        }
    }
    auth(db, TokenScope.readwrite) {
        post("/accounts/{USERNAME}/transactions") {
            val (req, challenge) = call.receiveChallenge<TransactionCreateRequest>(db, Operation.bank_transaction)

            val subject = req.payto_uri.message ?: throw badRequest("Wire transfer lacks subject")
            val amount = req.payto_uri.amount ?: req.amount ?: throw badRequest("Wire transfer lacks amount")

            ctx.checkRegionalCurrency(amount)

            val res = db.transaction.create(
                creditAccountPayto = req.payto_uri,
                debitAccountUsername = username,
                subject = subject,
                amount = amount,
                timestamp = Instant.now(),
                requestUid = req.request_uid,
                is2fa = challenge != null
            )
            when (res) {
                BankTransactionResult.UnknownDebtor -> throw unknownAccount(username)
                BankTransactionResult.TanRequired -> {
                    call.respondChallenge(db, Operation.bank_transaction, req)
                }
                BankTransactionResult.BothPartySame -> throw conflict(
                    "Wire transfer attempted with credit and debit party being the same bank account",
                    TalerErrorCode.BANK_SAME_ACCOUNT 
                )
                BankTransactionResult.UnknownCreditor -> throw unknownCreditorAccount(req.payto_uri.canonical)
                BankTransactionResult.AdminCreditor -> throw conflict(
                    "Cannot transfer money to admin account",
                    TalerErrorCode.BANK_ADMIN_CREDITOR
                )
                BankTransactionResult.BalanceInsufficient -> throw conflict(
                    "Insufficient funds",
                    TalerErrorCode.BANK_UNALLOWED_DEBIT
                )
                BankTransactionResult.RequestUidReuse -> throw conflict(
                    "request_uid used already",
                    TalerErrorCode.BANK_TRANSFER_REQUEST_UID_REUSED
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
            when (db.withdrawal.create(username, opId, req.amount, Instant.now())) {
                WithdrawalCreationResult.UnknownAccount -> throw unknownAccount(username)
                WithdrawalCreationResult.AccountIsExchange -> throw conflict(
                    "Exchange account cannot perform withdrawal operation",
                    TalerErrorCode.BANK_ACCOUNT_IS_EXCHANGE
                )
                WithdrawalCreationResult.BalanceInsufficient -> throw conflict(
                    "Insufficient funds to withdraw with Taler",
                    TalerErrorCode.BANK_UNALLOWED_DEBIT
                )
                WithdrawalCreationResult.Success -> {
                    call.respond(
                        BankAccountCreateWithdrawalResponse(
                            withdrawal_id = opId.toString(),
                            taler_withdraw_uri = call.request.talerWithdrawUri(opId)
                        )
                    )
                }
            }
        }
        post("/accounts/{USERNAME}/withdrawals/{withdrawal_id}/confirm") {
            val id = call.uuidPath("withdrawal_id")
            val challenge = call.checkChallenge(db, Operation.withdrawal)
            when (db.withdrawal.confirm(username, id, Instant.now(), challenge != null)) {
                WithdrawalConfirmationResult.UnknownOperation -> throw notFound(
                    "Withdrawal operation $id not found",
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
                WithdrawalConfirmationResult.TanRequired -> {
                    call.respondChallenge(db, Operation.withdrawal, StoredUUID(id))
                }
                WithdrawalConfirmationResult.Success -> call.respond(HttpStatusCode.NoContent)
            }
        }
        post("/accounts/{USERNAME}/withdrawals/{withdrawal_id}/abort") {
            val opId = call.uuidPath("withdrawal_id")
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
    }
    get("/withdrawals/{withdrawal_id}") {
        val uuid = call.uuidPath("withdrawal_id")
        val params = StatusParams.extract(call.request.queryParameters)
        val op = db.withdrawal.pollInfo(uuid, params) ?: throw notFound(
            "Withdrawal operation '$uuid' not found", 
            TalerErrorCode.BANK_TRANSACTION_NOT_FOUND
        )
        call.respond(op)
    }
}

private fun Routing.coreBankCashoutApi(db: Database, ctx: BankConfig) = conditional(ctx.allowConversion) {
    auth(db, TokenScope.readwrite) {
        post("/accounts/{USERNAME}/cashouts") {
            val (req, challenge) = call.receiveChallenge<CashoutRequest>(db, Operation.cashout)

            ctx.checkRegionalCurrency(req.amount_debit)
            ctx.checkFiatCurrency(req.amount_credit)
        
            val res = db.cashout.create(
                login = username, 
                requestUid = req.request_uid,
                amountDebit = req.amount_debit, 
                amountCredit = req.amount_credit, 
                subject = req.subject ?: "", // TODO default subject
                now = Instant.now(),
                is2fa = challenge != null
            )
            when (res) {
                CashoutCreationResult.AccountNotFound -> throw unknownAccount(username)
                CashoutCreationResult.BadConversion -> throw conflict(
                    "Wrong currency conversion",
                    TalerErrorCode.BANK_BAD_CONVERSION
                )
                CashoutCreationResult.UnderMin -> throw conflict(
                    "Amount of currency conversion it less than the minimum allowed",
                    TalerErrorCode.BANK_CONVERSION_AMOUNT_TO_SMALL
                )
                CashoutCreationResult.AccountIsExchange -> throw conflict(
                    "Exchange account cannot perform cashout operation",
                    TalerErrorCode.BANK_ACCOUNT_IS_EXCHANGE
                )
                CashoutCreationResult.BalanceInsufficient -> throw conflict(
                    "Insufficient funds to withdraw with Taler",
                    TalerErrorCode.BANK_UNALLOWED_DEBIT
                )
                CashoutCreationResult.RequestUidReuse -> throw conflict(
                    "request_uid used already",
                    TalerErrorCode.BANK_TRANSFER_REQUEST_UID_REUSED
                )
                CashoutCreationResult.NoCashoutPayto -> throw conflict(
                    "Missing cashout payto uri",
                    TalerErrorCode.BANK_CONFIRM_INCOMPLETE
                )
                CashoutCreationResult.TanRequired -> {
                    call.respondChallenge(db, Operation.cashout, req)
                }
                is CashoutCreationResult.Success -> call.respond(CashoutResponse(res.id))
            }
        }
    }
    auth(db, TokenScope.readonly, allowAdmin = true) {
        get("/accounts/{USERNAME}/cashouts/{CASHOUT_ID}") {
            val id = call.longPath("CASHOUT_ID")
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

private fun Routing.coreBankTanApi(db: Database, ctx: BankConfig) {
    auth(db, TokenScope.readwrite) {
        post("/accounts/{USERNAME}/challenge/{CHALLENGE_ID}") {
            val id = call.longPath("CHALLENGE_ID")
            val res = db.tan.send(
                id = id,
                login = username,
                code = Tan.genCode(),
                now = Instant.now(), 
                retryCounter = TAN_RETRY_COUNTER,
                validityPeriod = TAN_VALIDITY_PERIOD
            )
            when (res) {
                TanSendResult.NotFound -> throw notFound(
                    "Challenge $id not found",
                    TalerErrorCode.BANK_TRANSACTION_NOT_FOUND
                )
                is TanSendResult.Success -> {
                    res.tanCode?.run {
                        val (tanScript, tanEnv) = ctx.tanChannels.get(res.tanChannel) 
                            ?: throw unsupportedTanChannel(res.tanChannel)
                        val msg = "T-${res.tanCode} is your ${ctx.name} verification code"
                        val exitValue = withContext(Dispatchers.IO) {
                            val builder = ProcessBuilder(tanScript.toString(), res.tanInfo)
                            builder.redirectErrorStream(true)
                            for ((name, value) in tanEnv) {
                                builder.environment()[name] = value
                            }
                            val process = builder.start()
                            try {
                                process.outputWriter().use { it.write(msg) }
                                process.onExit().await()
                            } catch (e: Exception) {
                                process.destroy()
                            }
                            val exitValue = process.exitValue()
                            if (exitValue != 0) {
                                val out = runCatching {
                                    process.getInputStream().use {
                                        reader().readText()
                                    }
                                }.getOrDefault("")
                                if (out.isNotEmpty()) {
                                    logger.error("TAN ${res.tanChannel} - ${tanScript}: $out")
                                }
                            }
                            exitValue
                        }
                        if (exitValue != 0) {
                            throw apiError(
                                HttpStatusCode.BadGateway,
                                "Tan channel script failure with exit value $exitValue",
                                TalerErrorCode.BANK_TAN_CHANNEL_SCRIPT_FAILED
                            )
                        }
                        db.tan.markSent(id, Instant.now(), TAN_RETRANSMISSION_PERIOD)
                    }
                    call.respond(TanTransmission(
                        tan_info = res.tanInfo,
                        tan_channel = res.tanChannel
                    ))
                }
            }
        }
        post("/accounts/{USERNAME}/challenge/{CHALLENGE_ID}/confirm") {
            val id = call.longPath("CHALLENGE_ID")
            val req = call.receive<ChallengeSolve>()
            val code = req.tan.removePrefix("T-")
            val res = db.tan.solve(
                id = id,
                login = username,
                code = code,
                now = Instant.now()
            )
            when (res) {
                TanSolveResult.NotFound -> throw notFound(
                    "Challenge $id not found",
                    TalerErrorCode.BANK_CHALLENGE_NOT_FOUND
                )
                TanSolveResult.BadCode -> throw conflict(
                    "Incorrect TAN code",
                    TalerErrorCode.BANK_TAN_CHALLENGE_FAILED
                )
                TanSolveResult.NoRetry -> throw apiError(
                    HttpStatusCode.TooManyRequests,
                    "Too many failed confirmation attempt",
                    TalerErrorCode.BANK_TAN_RATE_LIMITED
                )
                TanSolveResult.Expired -> throw conflict(
                    "Challenge expired",
                    TalerErrorCode.BANK_TAN_CHALLENGE_EXPIRED
                )
                is TanSolveResult.Success -> call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
