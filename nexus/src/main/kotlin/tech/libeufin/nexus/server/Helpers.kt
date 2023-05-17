package tech.libeufin.nexus.server

import CamtBankAccountEntry
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.nexus.*
import tech.libeufin.nexus.bankaccount.getBankAccount
import tech.libeufin.util.internalServerError
import tech.libeufin.util.notFound

// Type holding parameters of GET /transactions.
data class GetTransactionsParams(
    val bankAccountId: String,
    val startIndex: Long,
    val resultSize: Long
)

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

fun unknownBankAccount(bankAccountLabel: String): NexusError {
    return NexusError(
        HttpStatusCode.NotFound,
        "Bank account $bankAccountLabel was not found"
    )
}

/**
 * FIXME:
 * enum type names were introduced after 0.9.2 and need to
 * be employed wherever now type names are passed as plain
 * strings.
 */

enum class EbicsDialects(val dialectName: String) {
    POSTFINANCE("pf")
}

/**
 * Nexus needs to uniquely identify a payment, in order
 * to spot the same payment to be ingested more than once.
 * For example, payment X may have been already ingested
 * (and possibly led to a Taler withdrawal) via a EBICS C52
 * order, and might be later again downloaded via another
 * EBICS order (e.g. C53).  The second time this payment
 * reaches Nexus, it must NOT be considered new, therefore
 * Nexus needs a UID to check its database for the presence
 * of known payments.  Every bank assigns UIDs in a different
 * fashion, sometimes even differentiating between incoming and
 * outgoing payments; Nexus therefore classifies those UIDs
 * by assigning them one of the names defined in the following
 * enum class.  This way, Nexus has more control when it tries
 * to locally reconcile payments.
 */
enum class PaymentUidQualifiers(qualifierName: String) {
    BANK_GIVEN("bank_given"),
    NEXUS_GIVEN("nexus_given")
}

// Valid connection types.
enum class BankConnectionType(val typeName: String) {
    EBICS("ebics"),
    X_LIBEUFIN_BANK("x-libeufin-bank");
    companion object {
        /**
         * This method takes legacy bank connection type names as input
         * and _tries_ to return the correspondent enum type.  This
         * fixes the cases where bank connection types are passed as
         * easy-to-break arbitrary strings; eventually this method should
         * be discarded and only enum types be passed as connection type names.
         */
        fun parseBankConnectionType(typeName: String): BankConnectionType {
            return when(typeName) {
                "ebics" -> EBICS
                "x-libeufin-bank" -> X_LIBEUFIN_BANK
                else -> throw internalServerError(
                    "Cannot extract ${this::class.java.typeName}' instance from name: $typeName'"
                )
            }
        }
    }
}
// Valid facade types
enum class NexusFacadeType(val facadeType: String) {
    TALER("taler-wire-gateway"),
    ANASTASIS("anastasis")
}

/**
 * These types point at the _content_ brought by bank connections.
 * The following stack depicts the layering of banking communication
 * as modeled here in Nexus.  On top the most inner layer.
 *
 * --------------------
 * Banking data type
 * --------------------
 * Bank connection type
 * --------------------
 * HTTP
 * --------------------
 *
 * Once the banking data type arrives to the local database, facades
 * types MAY apply further processing to it.
 *
 * For example, a Taler facade WILL look for Taler-meaningful wire
 * subjects and act accordingly.  Even without a facade, the Nexus
 * native HTTP API picks instances of banking data and extracts its
 * details to serve to the client.
 *
 * NOTE: this type MAY help but is NOT essential, as each connection
 * is USUALLY tied with the same banking data type.  For example, EBICS
 * brings CaMt, and x-libeufin-bank bring its own (same-named x-libeufin-bank)
 * banking data type.
 */
enum class BankingDataType {
    X_LIBEUFIN_BANK,
    CAMT
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