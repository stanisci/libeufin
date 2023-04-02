package tech.libeufin.nexus.server

import io.ktor.http.*
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.nexus.NexusBankConnectionEntity
import tech.libeufin.nexus.NexusBankConnectionsTable
import tech.libeufin.nexus.NexusError
import tech.libeufin.util.internalServerError
import tech.libeufin.util.notFound

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

// Valid connection types.
enum class BankConnectionType(val typeName: String) {
    EBICS("ebics"),
    X_LIBEUFIN_BANK("x-taler-bank");
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