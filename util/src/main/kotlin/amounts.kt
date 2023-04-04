package tech.libeufin.util

import UtilError
import io.ktor.http.*
import java.math.BigDecimal

/*
 * This file is part of LibEuFin.
 * Copyright (C) 2021 Taler Systems S.A.
 *
 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.
 *
 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

const val plainAmountRe = "^([0-9]+(\\.[0-9][0-9]?)?)$"
const val plainAmountReWithSign = "^-?([0-9]+(\\.[0-9][0-9]?)?)$"
const val amountWithCurrencyRe = "^([A-Z]+):([0-9]+(\\.[0-9][0-9]?)?)$"

fun validatePlainAmount(plainAmount: String, withSign: Boolean = false): Boolean {
    if (withSign) return Regex(plainAmountReWithSign).matches(plainAmount)
    return Regex(plainAmountRe).matches(plainAmount)
}

fun parseAmount(amount: String): AmountWithCurrency {
    val match = Regex(amountWithCurrencyRe).find(amount) ?:
        throw UtilError(HttpStatusCode.BadRequest, "invalid amount: $amount")
    val (currency, number) = match.destructured
    return AmountWithCurrency(currency = currency, amount = number)
}

fun isAmountZero(a: BigDecimal): Boolean {
    a.abs().toPlainString().forEach {
        if (it != '0' && it != '.')
            return false
    }
    return true
}