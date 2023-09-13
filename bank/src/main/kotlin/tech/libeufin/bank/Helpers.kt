/*
 * This file is part of LibEuFin.
 * Copyright (C) 2019 Stanisci and Dold.

 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.

 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.

 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

package tech.libeufin.bank

import io.ktor.http.*
import tech.libeufin.util.getIban
import java.lang.NumberFormatException

// HELPERS.  FIXME: make unit tests for them.

fun internalServerError(hint: String): LibeufinBankException =
    LibeufinBankException(
        httpStatus = HttpStatusCode.InternalServerError,
        talerError = TalerError(
            code = GENERIC_INTERNAL_INVARIANT_FAILURE,
            hint = hint
        )
    )
// Generates a new Payto-URI with IBAN scheme.
fun genIbanPaytoUri(): String = "payto://iban/SANDBOXX/${getIban()}"

fun parseTalerAmount(
    amount: String,
    fracDigits: FracDigits = FracDigits.EIGHT
): TalerAmount {
    val format = when (fracDigits) {
        FracDigits.TWO ->
            Pair("^([A-Z]+):([0-9])(\\.[0-9][0-9]?)?$", 100)
        FracDigits.EIGHT ->
            Pair(
                "^([A-Z]+):([0-9])(\\.[0-9][0-9]?[0-9]?[0-9]?[0-9]?[0-9]?[0-9]?[0-9]?)?\$",
                100000000
            )
    }
    val match = Regex(format.first).find(amount) ?: throw LibeufinBankException(
        httpStatus = HttpStatusCode.BadRequest,
        talerError = TalerError(
            code = BANK_BAD_FORMAT_AMOUNT,
            hint = "Invalid amount: $amount"
        ))
    val _value = match.destructured.component2()
    // Fraction is at most 8 digits, so it's always < than MAX_INT.
    val fraction: Int = match.destructured.component3().run {
        var frac = 0
        var power = format.second
        if (this.isNotEmpty())
            // Skips the dot and processes the fractional chars.
            this.substring(1).forEach { chr ->
                power /= 10
                frac += power * chr.digitToInt()
        }
        return@run frac
    }
    val value: Long = try {
        _value.toLong()
    } catch (e: NumberFormatException) {
        throw LibeufinBankException(
            httpStatus = HttpStatusCode.BadRequest,
            talerError = TalerError(
                code = BANK_BAD_FORMAT_AMOUNT,
                hint = "Invalid amount: ${amount}, could not extract the value part."
            )
        )
    }
    return TalerAmount(
        value = value,
        frac = fraction
    )
}