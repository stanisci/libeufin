package tech.libeufin.util

fun getIban(): String {
    val bankCode = "00000000" // 8 digits
    val accountCodeChars = ('0'..'9')
    // 10 digits
    val accountCode = (0..9).map {
        accountCodeChars.random()
    }.joinToString("")
    return "EU00" + bankCode + accountCode
}