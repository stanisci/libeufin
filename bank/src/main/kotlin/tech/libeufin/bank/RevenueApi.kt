/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023 Taler Systems S.A.

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
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import tech.libeufin.bank.auth.auth
import tech.libeufin.bank.db.Database

fun Routing.revenueApi(db: Database, ctx: BankConfig) { 
    auth(db, TokenScope.readonly) {
        get("/accounts/{USERNAME}/taler-revenue/config") {
            call.respond(RevenueConfig(
                currency = ctx.regionalCurrency
            ))
        }
        get("/accounts/{USERNAME}/taler-revenue/history") {
            val params = HistoryParams.extract(context.request.queryParameters)
            val bankAccount = call.bankInfo(db, ctx.payto)
            val items = db.transaction.revenueHistory(params, bankAccount.bankAccountId, ctx.payto)

            if (items.isEmpty()) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(RevenueIncomingHistory(items, bankAccount.payto))
            }
        }
    }
}