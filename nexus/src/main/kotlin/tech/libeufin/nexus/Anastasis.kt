package tech.libeufin.nexus

import io.ktor.application.*
import io.ktor.client.*
import io.ktor.http.*
import io.ktor.response.*
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.nexus.iso20022.TransactionDetails
import tech.libeufin.nexus.server.PermissionQuery
import tech.libeufin.nexus.server.expectNonNull
import tech.libeufin.nexus.server.expectUrlParameter
import tech.libeufin.util.EbicsProtocolError
import kotlin.math.abs
import kotlin.math.min
import io.ktor.content.TextContent
import io.ktor.routing.*

data class AnastasisIncomingBankTransaction(
    val row_id: Long,
    val date: GnunetTimestamp, // timestamp
    val amount: String,
    val credit_account: String, // payto form,
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
    if (debtorAgent.bic == null) {
        logger.warn("Not allowing transactions missing the BIC.  IBAN and name: ${debtorIban}, $debtorName")
        return
    }
    AnastasisIncomingPaymentEntity.new {
        this.payment = payment
        subject = txDtls.unstructuredRemittanceInformation
        timestampMs = System.currentTimeMillis()
        debtorPaytoUri = buildIbanPaytoUri(
            debtorIban, debtorAgent.bic, debtorName,
        )
    }
}

/**
 * Handle a /taler-wire-gateway/history/incoming request.
 */
private suspend fun historyIncoming(call: ApplicationCall) {
    val facadeId = expectNonNull(call.parameters["fcid"])
    call.request.requirePermission(PermissionQuery("facade", facadeId, "facade.anastasis.history"))
    val param = call.expectUrlParameter("delta")
    val delta: Int = try {
        param.toInt()
    } catch (e: Exception) {
        throw EbicsProtocolError(HttpStatusCode.BadRequest, "'${param}' is not Int")
    }
    val start: Long = handleStartArgument(call.request.queryParameters["start"], delta)
    val history = object {
        val incoming_transactions: MutableList<AnastasisIncomingBankTransaction> = mutableListOf()
    }
    val startCmpOp = getComparisonOperator(delta, start, AnastasisIncomingPaymentsTable)
    transaction {
        val orderedPayments = AnastasisIncomingPaymentEntity.find {
            startCmpOp
        }.orderTaler(delta) // Taler and Anastasis have same ordering policy.  Fixme: find better function's name?
        if (orderedPayments.isNotEmpty()) {
            orderedPayments.subList(0, min(abs(delta), orderedPayments.size)).forEach {
                history.incoming_transactions.add(
                    AnastasisIncomingBankTransaction(
                        // Rounded timestamp
                        date = GnunetTimestamp((it.timestampMs / 1000) * 1000),
                        row_id = it.id.value,
                        amount = "${it.payment.currency}:${it.payment.amount}",
                        subject = it.subject,
                        credit_account = buildIbanPaytoUri(
                            it.payment.bankAccount.iban,
                            it.payment.bankAccount.bankCode,
                            it.payment.bankAccount.accountHolder,
                        ),
                        debit_account = it.debtorPaytoUri
                    )
                )
            }
        }
    }
    return call.respond(TextContent(customConverter(history), ContentType.Application.Json))
}

fun anastasisFacadeRoutes(route: Route, httpClient: HttpClient) {
    route.get("/history/incoming") {
        historyIncoming(call)
        return@get
    }
}