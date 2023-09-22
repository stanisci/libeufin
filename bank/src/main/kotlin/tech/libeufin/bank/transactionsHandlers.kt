package tech.libeufin.bank

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.taler.common.errorcodes.TalerErrorCode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.util.getNowUs
import tech.libeufin.util.parsePayto
import kotlin.math.abs


private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.bank.transactionHandlers")

fun Routing.transactionsHandlers(db: Database) {
    get("/accounts/{USERNAME}/transactions") {
        val c = call.myAuth(db, TokenScope.readonly) ?: throw unauthorized()
        val resourceName = call.expectUriComponent("USERNAME")
        if (c.login != resourceName && c.login != "admin") throw forbidden()
        // Collecting params.
        val historyParams = getHistoryParams(call.request)
        // Making the query.
        val bankAccount = db.bankAccountGetFromOwnerId(c.expectRowId())
            ?: throw internalServerError("Customer '${c.login}' lacks bank account.")
        val bankAccountId = bankAccount.expectRowId()
        val history: List<BankAccountTransaction> = db.bankTransactionGetHistory(
            start = historyParams.start,
            delta = historyParams.delta,
            bankAccountId = bankAccountId
        )
        val res = BankAccountTransactionsResponse(transactions = mutableListOf())
        history.forEach {
            res.transactions.add(BankAccountTransactionInfo(
                debtor_payto_uri = it.debtorPaytoUri,
                creditor_payto_uri = it.creditorPaytoUri,
                subject = it.subject,
                amount = it.amount.toString(),
                direction = it.direction,
                date = it.transactionDate,
                row_id = it.dbRowId ?: throw internalServerError(
                    "Transaction timestamped with '${it.transactionDate}' did not have row ID"
                )
            ))
        }
        call.respond(res)
        return@get
    }
    // Creates a bank transaction.
    post("/accounts/{USERNAME}/transactions") {
        val c = call.myAuth(db, TokenScope.readwrite) ?: throw unauthorized()
        val resourceName = call.expectUriComponent("USERNAME")
        // admin has no rights here.
        if ((c.login != resourceName) && (call.getAuthToken() == null))
            throw forbidden()
        val txData = call.receive<BankAccountTransactionCreate>()
        // FIXME: make payto parser IBAN-agnostic?
        val payto = parsePayto(txData.payto_uri) ?: throw badRequest("Invalid creditor Payto")
        val paytoWithoutParams = "payto://iban/${payto.bic}/${payto.iban}"
        val subject = payto.message ?: throw badRequest("Wire transfer lacks subject")
        val debtorId = c.dbRowId ?: throw internalServerError("Debtor database ID not found")
        // This performs already a SELECT on the bank account,
        // like the wire transfer will do as well later!
        val creditorCustomerData = db.bankAccountGetFromInternalPayto(paytoWithoutParams)
            ?: throw notFound(
                "Creditor account not found",
                TalerErrorCode.TALER_EC_END // FIXME: define this EC.
            )
        val amount = parseTalerAmount(txData.amount)
        if (amount.currency != getBankCurrency(db))
            throw badRequest(
                "Wrong currency: ${amount.currency}",
                talerErrorCode = TalerErrorCode.TALER_EC_GENERIC_CURRENCY_MISMATCH
            )
        val dbInstructions = BankInternalTransaction(
            debtorAccountId = debtorId,
            creditorAccountId = creditorCustomerData.owningCustomerId,
            subject = subject,
            amount = amount,
            transactionDate = getNowUs()
        )
        val res = db.bankTransactionCreate(dbInstructions)
        when(res) {
            Database.BankTransactionResult.CONFLICT ->
                throw conflict(
                    "Insufficient funds",
                    TalerErrorCode.TALER_EC_END // FIXME: need bank 'insufficient funds' EC.
                )
            Database.BankTransactionResult.NO_CREDITOR ->
                throw internalServerError("Creditor not found despite previous checks.")
            Database.BankTransactionResult.NO_DEBTOR ->
                throw internalServerError("Debtor not found despite the request was authenticated.")
            Database.BankTransactionResult.SUCCESS -> call.respond(HttpStatusCode.OK)
        }
        return@post
    }
    get("/accounts/{USERNAME}/transactions/{T_ID}") {
        val c = call.myAuth(db, TokenScope.readonly) ?: throw unauthorized()
        val accountOwner = call.expectUriComponent("USERNAME")
        // auth ok, check rights.
        if (c.login != "admin" && c.login != accountOwner)
            throw forbidden()
        // rights ok, check tx exists.
        val tId = call.expectUriComponent("T_ID")
        val txRowId = try {
            tId.toLong()
        } catch (e: Exception) {
            logger.error(e.message)
            throw badRequest("TRANSACTION_ID is not a number: ${tId}")
        }
        val customerRowId = c.dbRowId ?: throw internalServerError("Authenticated client lacks database entry")
        val tx = db.bankTransactionGetFromInternalId(txRowId)
            ?: throw notFound(
                "Bank transaction '$tId' not found",
                TalerErrorCode.TALER_EC_NONE // FIXME: need def.
            )
        val customerBankAccount = db.bankAccountGetFromOwnerId(customerRowId)
            ?: throw internalServerError("Customer '${c.login}' lacks bank account.")
        if (tx.bankAccountId != customerBankAccount.bankAccountId)
            throw forbidden("Client has no rights over the bank transaction: $tId")
        // auth and rights, respond.
        call.respond(BankAccountTransactionInfo(
            amount = "${tx.amount.currency}:${tx.amount.value}.${tx.amount.frac}",
            creditor_payto_uri = tx.creditorPaytoUri,
            debtor_payto_uri = tx.debtorPaytoUri,
            date = tx.transactionDate,
            direction = tx.direction,
            subject = tx.subject,
            row_id = txRowId
        ))
        return@get
    }
}