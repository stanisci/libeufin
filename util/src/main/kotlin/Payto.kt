package tech.libeufin.util

import java.net.URI
import java.net.URLDecoder

/**
 * Payto information.
 */
data class Payto(
    // represent query param "sender-name" or "receiver-name".
    val name: String?,
    val iban: String,
    val bic: String?,
    // Typically, a wire transfer's subject.
    val message: String?,
    val amount: String?
)
class InvalidPaytoError(msg: String) : Exception(msg)

// Return the value of query string parameter 'name', or null if not found.
// 'params' is the a list of key-value elements of all the query parameters found in the URI.
private fun getQueryParamOrNull(name: String, params: List<Pair<String, String>>?): String? {
    if (params == null) return null
    return params.firstNotNullOfOrNull { pair ->
        URLDecoder.decode(pair.second, Charsets.UTF_8).takeIf { pair.first == name }
    }
}

fun parsePayto(paytoLine: String): Payto {
    /**
     * This check is due because URIs having a "payto:" prefix without
     * slashes are correctly parsed by the Java 'URI' class.  'mailto'
     * for example lacks the double-slash part.
     */
    if (!paytoLine.startsWith("payto://"))
        throw InvalidPaytoError("Invalid payto URI: $paytoLine")

    val javaParsedUri = try {
        URI(paytoLine)
    } catch (e: java.lang.Exception) {
        throw InvalidPaytoError("'${paytoLine}' is not a valid URI")
    }
    if (javaParsedUri.scheme != "payto") {
        throw InvalidPaytoError("'${paytoLine}' is not payto")
    }
    val wireMethod = javaParsedUri.host
    if (wireMethod != "iban") {
        throw InvalidPaytoError("Only 'iban' is supported, not '$wireMethod'")
    }
    val splitPath = javaParsedUri.path.split("/").filter { it.isNotEmpty() }
    if (splitPath.size > 2) {
        throw InvalidPaytoError("too many path segments in iban payto URI")
    }
    val (iban, bic) = if (splitPath.size == 1) {
        Pair(splitPath[0], null)
    } else Pair(splitPath[1], splitPath[0])

    val params: List<Pair<String, String>>? = if (javaParsedUri.query != null) {
        val queryString: List<String> = javaParsedUri.query.split("&")
        queryString.map {
            val split = it.split("="); Pair(split[0], split[1])
        }
    } else null

    val receiverName = getQueryParamOrNull("receiver-name", params)
    val senderName = getQueryParamOrNull("sender-name", params)
    if (receiverName != null  && senderName != null) throw InvalidPaytoError("URI had both sender and receiver")

    return Payto(
        iban = iban,
        bic = bic,
        amount = getQueryParamOrNull("amount", params),
        message = getQueryParamOrNull("message", params),
        name = listOf(receiverName, senderName).firstNotNullOfOrNull { it }
    )
}