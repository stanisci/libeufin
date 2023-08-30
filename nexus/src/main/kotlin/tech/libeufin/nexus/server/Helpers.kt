package tech.libeufin.nexus.server

import io.ktor.http.*
import tech.libeufin.nexus.*
import tech.libeufin.util.internalServerError

// Type holding parameters of GET /transactions.
data class GetTransactionsParams(
    val bankAccountId: String,
    val startIndex: Long,
    val resultSize: Long
)

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
enum class PaymentUidQualifiers {
    BANK_GIVEN,
    USER_GIVEN
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