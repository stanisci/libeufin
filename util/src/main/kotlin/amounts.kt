import io.ktor.http.*
import tech.libeufin.util.Amount
import tech.libeufin.util.AmountWithCurrency
import tech.libeufin.util.EbicsProtocolError
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

val re = Regex("^([0-9]+(\\.[0-9]+)?)$")

fun validatePlainAmount(plainAmount: String): Boolean {
    return re.matches(plainAmount)
}
