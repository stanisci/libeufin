package tech.libeufin.util

import UtilError
import io.ktor.http.*
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Payto information.
 */
data class Payto(
    // represent query param "sender-name" or "receiver-name".
    val receiverName: String?,
    val iban: String,
    val bic: String?,
    // Typically, a wire transfer's subject.
    val message: String?,
    val amount: String?
)
class InvalidPaytoError(msg: String) : UtilError(HttpStatusCode.BadRequest, msg)

// Return the value of query string parameter 'name', or null if not found.
// 'params' is the a list of key-value elements of all the query parameters found in the URI.
private fun getQueryParamOrNull(name: String, params: List<Pair<String, String>>?): String? {
    if (params == null) return null
    return params.firstNotNullOfOrNull { pair ->
        URLDecoder.decode(pair.second, Charsets.UTF_8).takeIf { pair.first == name }
    }
}

fun parsePayto(paytoInput: String): Payto {
    val payto = URLDecoder.decode(paytoInput, Charsets.UTF_8)
    /**
     * This check is due because URIs having a "payto:" prefix without
     * slashes are correctly parsed by the Java 'URI' class.  'mailto'
     * for example lacks the double-slash part.
     */
    if (!payto.startsWith("payto://"))
        throw InvalidPaytoError("Invalid payto URI: $payto")

    val javaParsedUri = try {
        URI(payto)
    } catch (e: java.lang.Exception) {
        throw InvalidPaytoError("'${payto}' is not a valid URI")
    }
    if (javaParsedUri.scheme != "payto") {
        throw InvalidPaytoError("'${payto}' is not payto")
    }
    val wireMethod = javaParsedUri.host
    if (wireMethod != "iban") {
        throw InvalidPaytoError("Only 'iban' is supported, not '$wireMethod'")
    }
    val splitPath = javaParsedUri.path.split("/").filter { it.isNotEmpty() }
    if (splitPath.size > 2) {
        throw InvalidPaytoError("too many path segments in iban payto URI: $payto")
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

    return Payto(
        iban = iban,
        bic = bic,
        amount = getQueryParamOrNull("amount", params),
        message = getQueryParamOrNull("message", params),
        receiverName = getQueryParamOrNull("receiver-name", params)
    )
}

fun buildIbanPaytoUri(
    iban: String,
    bic: String?,
    receiverName: String,
): String {
    val nameUrlEnc = URLEncoder.encode(receiverName, "utf-8")
    return "payto://iban/${if (bic != null) "$bic/" else ""}$iban?receiver-name=$nameUrlEnc"
}