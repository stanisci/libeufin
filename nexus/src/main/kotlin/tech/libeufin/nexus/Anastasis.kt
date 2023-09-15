package tech.libeufin.nexus

import TransactionDetails
import io.ktor.http.*
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.nexus.server.PermissionQuery
import tech.libeufin.nexus.server.expectNonNull
import tech.libeufin.nexus.server.expectUrlParameter
import tech.libeufin.util.EbicsProtocolError
import kotlin.math.abs
import kotlin.math.min
import io.ktor.content.TextContent
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import tech.libeufin.util.buildIbanPaytoUri
import tech.libeufin.util.internalServerError

data class AnastasisIncomingBankTransaction(
    val row_id: Long,
    val date: GnunetTimestamp, // timestamp
    val amount: String,
    val debit_account: String,
    val subject: String
)

fun anastasisFilter(payment: NexusBankTransactionEntity, txDtls: TransactionDetails) {
    val debtorName = txDtls.debtor?.name
    if (debtorName == null) {
        logger.warn("empty debtor name")
        return
    }
    val debtorAcct = txDtls.debtorAccount
    if (debtorAcct == null) {
        // FIXME: Report payment, we can't even send it back
        logger.warn("empty debtor account")
        return
    }
    val debtorIban = debtorAcct.iban
    if (debtorIban == null) {
        // FIXME: Report payment, we can't even send it back
        logger.warn("non-iban debtor account")
        return
    }
    val debtorAgent = txDtls.debtorAgent
    if (debtorAgent == null) {
        // FIXME: Report payment, we can't even send it back
        logger.warn("missing debtor agent")
        return
    }
    /**
     * This block either assigns a non-null BIC to the 'bic'
     * variable, or causes this function (anastasisFilter())
     * to return.  This last action ensures that the payment
     * being processed won't show up in the Anastasis facade.
     */
    val bic: String = debtorAgent.bic ?: run {
        logger.warn("Not allowing transactions missing the BIC.  IBAN and name: ${debtorIban}, $debtorName")
        return
    }
    val paymentSubject = txDtls.unstructuredRemittanceInformation
    if (paymentSubject == null) {
        throw internalServerError("Nexus payment '${payment.accountTransactionId}' has no subject.")
    }
    AnastasisIncomingPaymentEntity.new {
        this.payment = payment
        subject = paymentSubject
        timestampMs = System.currentTimeMillis()
        debtorPaytoUri = buildIbanPaytoUri(
            debtorIban,
            bic,
            debtorName,
        )
    }
}

data class AnastasisIncomingTransactions(
    val credit_account: String,
    val incoming_transactions: MutableList<AnastasisIncomingBankTransaction>
)

// Handle a /taler-wire-gateway/history/incoming request.
private suspend fun historyIncoming(call: ApplicationCall) {
    val facadeId = expectNonNull(call.parameters["fcid"])
    call.request.requirePermission(
        PermissionQuery(
            "facade",
            facadeId,
            "facade.anastasis.history"
        )
    )
    val param = call.expectUrlParameter("delta")
    val delta: Int = try { param.toInt() } catch (e: Exception) {
        throw EbicsProtocolError(HttpStatusCode.BadRequest, "'${param}' is not Int")
    }
    val start: Long = handleStartArgument(
        call.request.queryParameters["start"],
        delta
    )
    val history = object {
        val incoming_transactions: MutableList<AnastasisIncomingBankTransaction> = mutableListOf()

    }
    val startCmpOp = getComparisonOperator(delta, start, AnastasisIncomingPaymentsTable)
    val incomingTransactionsResp = transaction {
        val orderedPayments = AnastasisIncomingPaymentEntity.find {
            startCmpOp
        }.orderTaler(delta) // Taler and Anastasis have same ordering policy.  Fixme: find better function's name?
        if (orderedPayments.isNotEmpty()) {
            val creditBankAccountObj = orderedPayments[0]
            val ret = AnastasisIncomingTransactions(
                credit_account = buildIbanPaytoUri(
                    creditBankAccountObj.payment.bankAccount.iban,
                    creditBankAccountObj.payment.bankAccount.bankCode,
                    creditBankAccountObj.payment.bankAccount.accountHolder,
                ),
                incoming_transactions = mutableListOf()
            )
            orderedPayments.subList(0, min(abs(delta), orderedPayments.size)).forEach {
                history.incoming_transactions.add(
                    AnastasisIncomingBankTransaction(
                        // Rounded timestamp
                        date = GnunetTimestamp(it.timestampMs / 1000L),
                        row_id = it.id.value,
                        amount = "${it.payment.currency}:${it.payment.amount}",
                        subject = it.subject,
                        debit_account = it.debtorPaytoUri
                    )
                )
            }
            return@transaction ret
        } else null
    }
    if (incomingTransactionsResp == null) {
        call.respond(HttpStatusCode.NoContent)
        return
    }
    return call.respond(
        TextContent(
            customConverter(incomingTransactionsResp),
            ContentType.Application.Json
        )
    )
}

fun anastasisFacadeRoutes(route: Route) {
    route.get("/history/incoming") {
        historyIncoming(call)
        return@get
    }
}