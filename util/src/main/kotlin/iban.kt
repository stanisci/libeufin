package tech.libeufin.util

import java.math.BigInteger

fun getIban(): String {
    val ccNoCheck = "131400" // DE00
    val bban = (0..3).map {
        (0..9).random()
    }.joinToString("") // 4 digits BBAN.
    var checkDigits = "98".toBigInteger().minus("$bban$ccNoCheck".toBigInteger().mod("97".toBigInteger())).toString()
    if (checkDigits.length == 1) {
        checkDigits = "0${checkDigits}"
    }
    return "DE$checkDigits$bban"
}