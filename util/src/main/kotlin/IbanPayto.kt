package tech.libeufin.util

import logger
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

// Payto information.
data class IbanPayto(
    // represent query param "sender-name" or "receiver-name".
    val receiverName: String?,
    val iban: String,
    val bic: String?,
    // Typically, a wire transfer's subject.
    val message: String?,
    val amount: String?
)

// Return the value of query string parameter 'name', or null if not found.
// 'params' is the list of key-value elements of all the query parameters found in the URI.
private fun getQueryParamOrNull(name: String, params: List<Pair<String, String>>?): String? {
    if (params == null) return null
    return params.firstNotNullOfOrNull { pair ->
        URLDecoder.decode(pair.second, Charsets.UTF_8).takeIf { pair.first == name }
    }
}

// Parses a Payto URI, returning null if the input is invalid.
fun parsePayto(payto: String): IbanPayto? {
    /**
     * This check is due because URIs having a "payto:" prefix without
     * slashes are correctly parsed by the Java 'URI' class.  'mailto'
     * for example lacks the double-slash part.
     */
    if (!payto.startsWith("payto://")) {
        logger.error("Invalid payto URI: $payto")
        return null
    }

    val javaParsedUri = try {
        URI(payto)
    } catch (e: java.lang.Exception) {
        logger.error("'${payto}' is not a valid URI")
        return null
    }
    if (javaParsedUri.scheme != "payto") {
        logger.error("'${payto}' is not payto")
        return null
    }
    val wireMethod = javaParsedUri.host
    if (wireMethod != "iban") {
        logger.error("Only 'iban' is supported, not '$wireMethod'")
        return null
    }
    val splitPath = javaParsedUri.path.split("/").filter { it.isNotEmpty() }
    if (splitPath.size > 2) {
        logger.error("too many path segments in iban payto URI: $payto")
        return null
    }
    val (iban, bic) = if (splitPath.size == 1) {
        Pair(splitPath[0], null)
    } else Pair(splitPath[1], splitPath[0])

    val params: List<Pair<String, String>>? = if (javaParsedUri.query != null) {
        val queryString: List<String> = javaParsedUri.query.split("&")
        queryString.map {
            val split = it.split("=");
            if (split.size != 2) {
                logger.error("parameter '$it' was malformed")
                return null
            }
            Pair(split[0], split[1])
        }
    } else null

    return IbanPayto(
        iban = iban,
        bic = bic,
        amount = getQueryParamOrNull("amount", params),
        message = getQueryParamOrNull("message", params),
        receiverName = getQueryParamOrNull("receiver-name", params)
    )
}

fun buildIbanPaytoUri(
    iban: String,
    bic: String,
    receiverName: String,
    message: String? = null
): String {
    val nameUrlEnc = URLEncoder.encode(receiverName, "utf-8")
    val ret = "payto://iban/$bic/$iban?receiver-name=$nameUrlEnc"
    if (message != null) {
        val messageUrlEnc = URLEncoder.encode(message, "utf-8")
        return "$ret&message=$messageUrlEnc"
    }
    return ret
}

/**
 * Strip a payto://iban URI of everything
 * except the IBAN.
 */
fun stripIbanPayto(paytoUri: String): String {
    val parsedPayto = parsePayto(paytoUri)
    if (parsedPayto == null) {
        throw Error("invalid payto://iban URI")
    }
    val canonIban = parsedPayto.iban.lowercase()
    return "payto://iban/${canonIban}"
}
