package tech.libeufin.util

import java.net.URI

/**
 * Helper data structures.
 */
data class Payto(
    val name: String,
    val iban: String,
    val bic: String?
)
class InvalidPaytoError(msg: String) : Exception(msg)

fun parsePayto(paytoLine: String): Payto {
    if (!"^payto://".toRegex().containsMatchIn(paytoLine)) throw InvalidPaytoError("Invalid payto line: $paytoLine")
    val javaParsedUri = try {
        URI(paytoLine)
    } catch (e: java.lang.Exception) {
        throw InvalidPaytoError("'${paytoLine}' is not a valid URI")
    }
    if (javaParsedUri.scheme != "payto") {
        throw InvalidPaytoError("'${paytoLine}' is not payto")
    }
    val queryStringAsList = javaParsedUri.query.split("&")
    // admit only ONE parameter: receiver-name.
    if (queryStringAsList.size != 1) {
        throw InvalidPaytoError("'${paytoLine}' has unsupported query string")
    }
    val splitParameter = queryStringAsList.first().split("=")
    if (splitParameter.first() != "receiver-name" && splitParameter.first() != "sender-name") {
        throw InvalidPaytoError("'${paytoLine}' has unsupported query string")
    }
    val receiverName = splitParameter.last()
    val splitPath = javaParsedUri.path.split("/").filter { it.isNotEmpty() }
    if (splitPath.size > 2) {
        throw InvalidPaytoError("too many path segments in iban payto URI")
    }
    if (splitPath.size < 2) {
        return Payto(iban = splitPath[0], name = receiverName, bic = null)
    }
    return Payto(iban = splitPath[1], bic = splitPath[0], name = receiverName)
}