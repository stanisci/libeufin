/*
 * This file is part of LibEuFin.
 * Copyright (C) 2024 Taler Systems S.A.
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

package tech.libeufin.common.db

import tech.libeufin.common.BankPaytoCtx
import tech.libeufin.common.Payto
import tech.libeufin.common.TalerAmount
import tech.libeufin.common.TalerProtocolTimestamp
import tech.libeufin.common.asInstant
import java.sql.ResultSet

fun ResultSet.getAmount(name: String, currency: String): TalerAmount {
    return TalerAmount(
        getLong("${name}_val"),
        getInt("${name}_frac"),
        currency
    )
}

fun ResultSet.getTalerTimestamp(name: String): TalerProtocolTimestamp{
    return TalerProtocolTimestamp(getLong(name).asInstant())
}

fun ResultSet.getBankPayto(payto: String, name: String, ctx: BankPaytoCtx): String {
    return Payto.parse(getString(payto)).bank(getString(name), ctx)
}