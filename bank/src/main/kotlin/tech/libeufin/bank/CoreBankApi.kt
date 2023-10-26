/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023 Stanisci and Dold.

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
import net.taler.common.errorcodes.TalerErrorCode
import net.taler.wallet.crypto.Base32Crockford
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.util.*

private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.bank.accountsMgmtHandlers")

fun Routing.coreBankApi(db: Database, ctx: BankConfig) {
    get("/config") {
        call.respond(
                Config(
                        currency = ctx.currencySpecification,
                        have_cashout = ctx.haveCashout,
                        fiat_currency = ctx.fiatCurrency,
                        conversion_info = ctx.conversionInfo
                )
        )
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
    coreBankCashoutApi(db, ctx)
}

private fun Routing.coreBankTokenApi(db: Database) {
    post("/accounts/{USERNAME}/token") {
        val (login, _) = call.authCheck(db, TokenScope.refreshable)
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
                            TalerErrorCode.TALER_EC_GENERIC_TOKEN_PERMISSION_INSUFFICIENT
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
            login = login,
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
    delete("/accounts/{USERNAME}/token") {
        call.authCheck(db, TokenScope.readonly)
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

private fun Routing.coreBankAccountsMgmtApi(db: Database, ctx: BankConfig) {
    post("/accounts") {
        // check if only admin is allowed to create new accounts
        if (ctx.restrictRegistration) {
            call.authAdmin(db, TokenScope.readwrite)
        } // auth passed, proceed with activity.
        val req = call.receive<RegisterAccountRequest>()
        // Prohibit reserved usernames:
        if (reservedAccounts.contains(req.username))
            throw forbidden(
                "Username '${req.username}' is reserved.",
                TalerErrorCode.TALER_EC_BANK_RESERVED_USERNAME_CONFLICT
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
                "Insufficient funds",
                TalerErrorCode.TALER_EC_BANK_UNALLOWED_DEBIT
            )
            CustomerCreationResult.CONFLICT_LOGIN -> throw conflict(
                "Customer username reuse '${req.username}'",
                TalerErrorCode.TALER_EC_END // FIXME: provide appropriate EC.
            )
            CustomerCreationResult.CONFLICT_PAY_TO -> throw conflict(
                "Bank internalPayToUri reuse '${internalPayto.canonical}'",
                TalerErrorCode.TALER_EC_END // FIXME: provide appropriate EC.
            )
            CustomerCreationResult.SUCCESS -> call.respond(HttpStatusCode.Created)
        }
    }
    delete("/accounts/{USERNAME}") {
        val (login, _) =
                call.authCheck(
                        db,
                        TokenScope.readwrite,
                        withAdmin = true,
                        requireAdmin = ctx.restrictAccountDeletion
                )
        // Not deleting reserved names.
        if (reservedAccounts.contains(login))
                throw forbidden(
                        "Cannot delete reserved accounts",
                        TalerErrorCode.TALER_EC_BANK_RESERVED_USERNAME_CONFLICT
                )

        when (db.customerDeleteIfBalanceIsZero(login)) {
            CustomerDeletionResult.CUSTOMER_NOT_FOUND ->
                    throw notFound(
                            "Customer '$login' not found",
                            TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT
                    )
            CustomerDeletionResult.BALANCE_NOT_ZERO ->
                    throw conflict(
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
        
        val req = call.receive<AccountReconfiguration>()
        val res = db.accountReconfig(
            login = login,
            name = req.name,
            cashoutPayto = req.cashout_address,
            emailAddress = req.challenge_contact_data?.email,
            isTalerExchange = req.is_exchange,
            phoneNumber = req.challenge_contact_data?.phone,
            isAdmin = isAdmin
        )
        when (res) {
            CustomerPatchResult.SUCCESS -> call.respond(HttpStatusCode.NoContent)
            CustomerPatchResult.ACCOUNT_NOT_FOUND -> throw notFound(
                "Customer '$login' not found",
                TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT
            )
            CustomerPatchResult.CONFLICT_LEGAL_NAME -> 
                throw forbidden("non-admin user cannot change their legal name")
        }
    }
    patch("/accounts/{USERNAME}/auth") {
        val (login, _) = call.authCheck(db, TokenScope.readwrite)
        val req = call.receive<AccountPasswordChange>()
        val hashedPassword = CryptoUtil.hashpw(req.new_password)
        if (!db.customerChangePassword(login, hashedPassword))
                throw notFound(
                        "Account '$login' not found (despite it being authenticated by this call)",
                        talerEc =
                                TalerErrorCode
                                        .TALER_EC_END // FIXME: need at least GENERIC_NOT_FOUND.
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
    get("/accounts/{USERNAME}") {
        val (login, _) = call.authCheck(db, TokenScope.readonly, withAdmin = true)
        val account = db.accountDataFromLogin(login) ?: throw notFound(
            "Customer '$login' not found in the database.",
            talerEc = TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT
        )
        call.respond(account)
    }
}

private fun Routing.coreBankTransactionsApi(db: Database, ctx: BankConfig) {
    get("/accounts/{USERNAME}/transactions") {
        call.authCheck(db, TokenScope.readonly)
        val params = HistoryParams.extract(call.request.queryParameters)
        val bankAccount = call.bankAccount(db)

        val history: List<BankAccountTransactionInfo> =
                db.bankPoolHistory(params, bankAccount.bankAccountId)
        call.respond(BankAccountTransactionsResponse(history))
    }
    get("/accounts/{USERNAME}/transactions/{T_ID}") {
        call.authCheck(db, TokenScope.readonly)
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
        val (login, _) = call.authCheck(db, TokenScope.readwrite)
        val tx = call.receive<BankAccountTransactionCreate>()

        val subject = tx.payto_uri.message ?: throw badRequest("Wire transfer lacks subject")
        val amount =
                tx.payto_uri.amount ?: tx.amount ?: throw badRequest("Wire transfer lacks amount")
        ctx.checkInternalCurrency(amount)
        val result =
                db.bankTransaction(
                        creditAccountPayto = tx.payto_uri,
                        debitAccountUsername = login,
                        subject = subject,
                        amount = amount,
                        timestamp = Instant.now(),
                )
        when (result) {
            BankTransactionResult.BALANCE_INSUFFICIENT ->
                    throw conflict(
                            "Insufficient funds",
                            TalerErrorCode.TALER_EC_BANK_UNALLOWED_DEBIT
                    )
            BankTransactionResult.SAME_ACCOUNT ->
                    throw conflict(
                            "Wire transfer attempted with credit and debit party being the same bank account",
                            TalerErrorCode.TALER_EC_BANK_SAME_ACCOUNT
                    )
            BankTransactionResult.NO_DEBTOR ->
                    throw notFound(
                            "Customer $login not found",
                            TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT
                    )
            BankTransactionResult.NO_CREDITOR ->
                    throw notFound(
                            "Creditor account was not found",
                            TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT
                    )
            BankTransactionResult.SUCCESS -> call.respond(HttpStatusCode.NoContent)
        }
    }
}

fun Routing.coreBankWithdrawalApi(db: Database, ctx: BankConfig) {
    post("/accounts/{USERNAME}/withdrawals") {
        val (login, _) = call.authCheck(db, TokenScope.readwrite)
        val req =
                call.receive<
                        BankAccountCreateWithdrawalRequest>() // Checking that the user has enough
        // funds.

        ctx.checkInternalCurrency(req.amount)

        val opId = UUID.randomUUID()
        when (db.talerWithdrawalCreate(login, opId, req.amount)) {
            WithdrawalCreationResult.ACCOUNT_NOT_FOUND ->
                    throw notFound(
                            "Customer $login not found",
                            TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT
                    )
            WithdrawalCreationResult.ACCOUNT_IS_EXCHANGE ->
                    throw conflict(
                            "Exchange account cannot perform withdrawal operation",
                            TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT
                    )
            WithdrawalCreationResult.BALANCE_INSUFFICIENT ->
                    throw conflict(
                            "Insufficient funds to withdraw with Taler",
                            TalerErrorCode.TALER_EC_BANK_UNALLOWED_DEBIT
                    )
            WithdrawalCreationResult.SUCCESS -> {
                val bankBaseUrl =
                        call.request.getBaseUrl()
                                ?: throw internalServerError("Bank could not find its own base URL")
                call.respond(
                        BankAccountCreateWithdrawalResponse(
                                withdrawal_id = opId.toString(),
                                taler_withdraw_uri =
                                        getTalerWithdrawUri(bankBaseUrl, opId.toString())
                        )
                )
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
        when (db.talerWithdrawalAbort(opId)) {
            WithdrawalAbortResult.NOT_FOUND ->
                    throw notFound(
                            "Withdrawal operation $opId not found",
                            TalerErrorCode.TALER_EC_END
                    )
            WithdrawalAbortResult.CONFIRMED ->
                    throw conflict("Cannot abort confirmed withdrawal", TalerErrorCode.TALER_EC_END)
            WithdrawalAbortResult.SUCCESS -> call.respond(HttpStatusCode.NoContent)
        }
    }
    post("/withdrawals/{withdrawal_id}/confirm") {
        val opId = call.uuidUriComponent("withdrawal_id")
        when (db.talerWithdrawalConfirm(opId, Instant.now())) {
            WithdrawalConfirmationResult.OP_NOT_FOUND ->
                    throw notFound(
                            "Withdrawal operation $opId not found",
                            TalerErrorCode.TALER_EC_END
                    )
            WithdrawalConfirmationResult.ABORTED ->
                    throw conflict(
                            "Cannot confirm an aborted withdrawal",
                            TalerErrorCode.TALER_EC_BANK_CONFIRM_ABORT_CONFLICT
                    )
            WithdrawalConfirmationResult.NOT_SELECTED ->
                    throw LibeufinBankException(
                            httpStatus = HttpStatusCode.UnprocessableEntity,
                            talerError =
                                    TalerError(
                                            hint = "Cannot confirm an unselected withdrawal",
                                            code = TalerErrorCode.TALER_EC_END.code
                                    )
                    )
            WithdrawalConfirmationResult.BALANCE_INSUFFICIENT ->
                    throw conflict(
                            "Insufficient funds",
                            TalerErrorCode.TALER_EC_BANK_UNALLOWED_DEBIT
                    )
            WithdrawalConfirmationResult.EXCHANGE_NOT_FOUND ->
                    throw conflict(
                            "Exchange to withdraw from not found",
                            TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT
                    )
            WithdrawalConfirmationResult.SUCCESS -> call.respond(HttpStatusCode.NoContent)
        }
    }
}

fun Routing.coreBankCashoutApi(db: Database, ctx: BankConfig) {
    post("/accounts/{USERNAME}/cashouts") {
        val (login, _) = call.authCheck(db, TokenScope.readwrite)
        val req = call.receive<CashoutRequest>()

        ctx.checkInternalCurrency(req.amount_debit)
        ctx.checkFiatCurrency(req.amount_credit)

        // TODO
    }
    post("/accounts/{USERNAME}/cashouts/{CASHOUT_ID}/abort") {
        val (login, _) = call.authCheck(db, TokenScope.readwrite)
        // TODO
    }
    post("/accounts/{USERNAME}/cashouts/{CASHOUT_ID}/confirm") {
        val (login, _) = call.authCheck(db, TokenScope.readwrite)
        // TODO
    }
    get("/accounts/{USERNAME}/cashouts") {
        val (login, _) = call.authCheck(db, TokenScope.readonly)
        // TODO
    }
    get("/accounts/{USERNAME}/cashouts/{CASHOUT_ID}") {
        val (login, _) = call.authCheck(db, TokenScope.readonly)
        // TODO
    }
    get("/cashouts") {
        call.authAdmin(db, TokenScope.readonly)
        // TODO
    }
    get("/cashout-rate") {
        val params = CashoutRateParams.extract(call.request.queryParameters)

        params.debit?.let { ctx.checkInternalCurrency(it) }
        params.credit?.let { ctx.checkFiatCurrency(it) }

        if (params.debit != null) {
            val credit = db.conversionInternalToFiat(params.debit)
            if (params.credit != null && params.credit != credit) {
                throw badRequest("Bad conversion expected $credit got $params.credit")
            }
            call.respond(CashoutConversionResponse(params.debit, credit))
        } else {
            // TODO
        }
    }
}
