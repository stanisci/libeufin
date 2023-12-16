package tech.libeufin.util

fun getIban(): String {
    val ccNoCheck = "131400" // DE00
    val bban = (0..10).map {
        (0..9).random()
    }.joinToString("") // 10 digits account number
    var checkDigits: String = "98".toBigInteger().minus("$bban$ccNoCheck".toBigInteger().mod("97".toBigInteger())).toString()
    if (checkDigits.length == 1) {
        checkDigits = "0${checkDigits}"
    }
    return "DE$checkDigits$bban"
}

// Taken from the ISO20022 XSD schema
private val bicRegex = Regex("^[A-Z]{6}[A-Z2-9][A-NP-Z0-9]([A-Z0-9]{3})?$")

fun validateBic(bic: String): Boolean {
    return bicRegex.matches(bic)
}

// Taken from the ISO20022 XSD schema
private val ibanRegex = Regex("^[A-Z]{2}[0-9]{2}[a-zA-Z0-9]{1,30}$")

fun validateIban(iban: String): Boolean {
    return ibanRegex.matches(iban)
}