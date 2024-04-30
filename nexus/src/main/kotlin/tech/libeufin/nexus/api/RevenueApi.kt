/*
 * This file is part of LibEuFin.
 * Copyright (C) 2024 Taler Systems S.A.

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
package tech.libeufin.nexus.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import tech.libeufin.nexus.*
import tech.libeufin.nexus.db.*
import tech.libeufin.common.*

fun Routing.revenueApi(db: Database, cfg: NexusConfig) = authApi(cfg.revenueApiCfg) {
    get("/taler-revenue/config") {
        call.respond(RevenueConfig(
            currency = cfg.currency
        ))
    }
    get("/taler-revenue/history") {
        val params = HistoryParams.extract(context.request.queryParameters)
        val items = db.payment.revenueHistory(params)

        if (items.isEmpty()) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(RevenueIncomingHistory(items, cfg.payto))
        }
    }
}