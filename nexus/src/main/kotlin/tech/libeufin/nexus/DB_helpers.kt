package tech.libeufin.nexus

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.nexus.server.GetTransactionsParams
import tech.libeufin.nexus.server.Pain001Data
import tech.libeufin.util.notFound
import java.time.Instant

fun getBankAccount(label: String): NexusBankAccountEntity {
    val maybeBankAccount = transaction {
        NexusBankAccountEntity.findByName(label)
    }
    return maybeBankAccount ?:
    throw NexusError(
        HttpStatusCode.NotFound,
        "Account $label not found"
    )
}

/**
 * Queries the database according to the GET /transactions
 * parameters.
 */
fun getIngestedTransactions(params: GetTransactionsParams): List<JsonNode> =
    transaction {
        val bankAccount = getBankAccount(params.bankAccountId)
        val maybeResult = NexusBankTransactionEntity.find {
            NexusBankTransactionsTable.bankAccount eq bankAccount.id.value and (
                    NexusBankTransactionsTable.id greaterEq params.startIndex
                    )
        }.sortedBy { it.id.value }.take(params.resultSize.toInt()) // Smallest index (= earliest transaction) first
        // Converting the result to the HTTP response type.
        maybeResult.map {
            val element: ObjectNode = jacksonObjectMapper().createObjectNode()
            element.put("index", it.id.value.toString())
            val txObj: JsonNode = jacksonObjectMapper().readTree(it.transactionJson)
            element.set<JsonNode>("camtData", txObj)
            return@map element
        }
    }

// Gets connection or throws.
fun getBankConnection(connId: String): NexusBankConnectionEntity {
    val maybeConn = transaction {
        NexusBankConnectionEntity.find {
            NexusBankConnectionsTable.connectionId eq connId
        }.firstOrNull()
    }
    if (maybeConn == null) throw notFound("Bank connection $connId not found")
    return maybeConn
}

/**
 * Retrieve payment initiation from database, raising exception if not found.
 */
fun getPaymentInitiation(uuid: Long): PaymentInitiationEntity {
    return transaction {
        PaymentInitiationEntity.findById(uuid)
    } ?: throw NexusError(
        HttpStatusCode.NotFound,
        "Payment '$uuid' not found"
    )
}

/**
 * Gets a prepared payment starting from its 'payment information id'.
 * Note: although the terminology comes from CaMt, a 'payment information id'
 * is indeed any UID that identifies the payment.  For this reason, also
 * the x-libeufin-bank logic uses this helper.
 *
 * Returns the prepared payment, or null if that's not found.  Not throwing
 * any exception because the null case is common: not every transaction being
 * processed by Neuxs was prepared/initiated here; incoming transactions are
 * one example.
 */
fun getPaymentInitiation(pmtInfId: String): PaymentInitiationEntity? =
    transaction {
        PaymentInitiationEntity.find(
            PaymentInitiationsTable.paymentInformationId.eq(pmtInfId)
        ).firstOrNull()
    }

/**
 * Insert one row in the database, and leaves it marked as non-submitted.
 * @param debtorAccount the mnemonic id assigned by the bank to one bank
 * account of the subscriber that is creating the pain entity.  In this case,
 * it will be the account whose money will pay the wire transfer being defined
 * by this pain document.
 */
fun addPaymentInitiation(
    paymentData: Pain001Data,
    debtorAccount: NexusBankAccountEntity
): PaymentInitiationEntity {
    return transaction {

        val now = Instant.now().toEpochMilli()
        val nowHex = now.toString(16)
        val painCounter = debtorAccount.pain001Counter++
        val painHex = painCounter.toString(16)
        val acctHex = debtorAccount.id.value.toString(16)

        PaymentInitiationEntity.new {
            currency = paymentData.currency
            bankAccount = debtorAccount
            subject = paymentData.subject
            sum = paymentData.sum
            creditorName = paymentData.creditorName
            creditorBic = paymentData.creditorBic
            creditorIban = paymentData.creditorIban
            preparationDate = now
            endToEndId = paymentData.endToEndId ?: "leuf-e-$nowHex-$painHex-$acctHex"
            messageId = "leuf-mp1-$nowHex-$painHex-$acctHex"
            paymentInformationId = "leuf-p-$nowHex-$painHex-$acctHex"
            instructionId = "leuf-i-$nowHex-$painHex-$acctHex"
        }
    }
}
