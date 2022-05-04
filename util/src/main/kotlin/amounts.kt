package tech.libeufin.util

import UtilError
import io.ktor.http.*

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

val re = Regex("^([0-9]+(\\.[0-9]+)?)$")

fun validatePlainAmount(plainAmount: String): Boolean {
    return re.matches(plainAmount)
}

/**
 * Parse an "amount" where the currency is optional.  It returns
 * a pair where the first item is always the amount, and the second
 * is the currency or null (when this one wasn't given in the input)
 */
fun parseAmountAsString(amount: String): Pair<String, String?> {
    val match = Regex("^([A-Z]+:)?([0-9]+(\\.[0-9]+)?)$").find(amount) ?: throw
    UtilError(HttpStatusCode.BadRequest, "invalid amount: $amount")
    var (currency, number) = match.destructured
    // Currency given, need to strip the ":".
    if (currency.isNotEmpty())
        currency = currency.dropLast(1)
    return Pair(number, if (currency.isEmpty()) null else currency)
}

fun parseAmount(amount: String): AmountWithCurrency {
    val match = Regex("([A-Z]+):([0-9]+(\\.[0-9]+)?)").find(amount) ?: throw
    UtilError(HttpStatusCode.BadRequest, "invalid amount: $amount")
    val (currency, number) = match.destructured
    return AmountWithCurrency(currency, Amount(number))
}
