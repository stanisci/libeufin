package tech.libeufin.util

import java.math.BigDecimal
import java.math.RoundingMode

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

fun BigDecimal.roundToTwoDigits(): BigDecimal {
    // val twoDigitsRounding = MathContext(2)
    // return this.round(twoDigitsRounding)
    return this.setScale(2, RoundingMode.HALF_UP)
}