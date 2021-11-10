package tech.libeufin.util

import java.math.BigInteger

fun getIban(): String {
    val ccNoCheck = "131400" // DE00
    val bban = (0..3).map {
        (0..9).random()
    }.joinToString("") // 4 digits BBAN.
    val checkDigits = "98".toBigInteger().minus("$bban$ccNoCheck".toBigInteger().mod("97".toBigInteger()))
    return "DE$checkDigits$bban"
}