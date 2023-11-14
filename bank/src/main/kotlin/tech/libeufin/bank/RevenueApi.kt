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
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.TimeUnit
import java.io.File
import kotlin.random.Random
import net.taler.common.errorcodes.TalerErrorCode
import net.taler.wallet.crypto.Base32Crockford
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.future.await

fun Routing.revenueApi(db: Database) { 
    auth(db, TokenScope.readonly) {
        get("/accounts/{USERNAME}/taler-revenue/history") {
            val params = HistoryParams.extract(context.request.queryParameters)
            val bankAccount = call.bankAccount(db)
            val items = db.exchange.revenueHistory(params, bankAccount.bankAccountId);
        
            if (items.isEmpty()) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(MerchantIncomingHistory(items, bankAccount.internalPaytoUri))
            }
        }
    }
}