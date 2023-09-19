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

/* This file contains all the Taler handlers that do NOT
 * communicate with wallets, therefore any handler that serves
 * to SPAs or CLI HTTP clients.
 */

package tech.libeufin.bank

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*

fun Routing.talerWebHandlers() {
    post("/accounts/{USERNAME}/withdrawals") {
        val c = call.myAuth(TokenScope.readwrite) ?: throw unauthorized()
        // Admin not allowed to withdraw in the name of customers:
        val accountName = call.expectUriComponent("USERNAME")
        if (c.login != accountName)
            throw unauthorized("User ${c.login} not allowed to withdraw for account '${accountName}'")
        val req = call.receive<BankAccountCreateWithdrawalRequest>()
        // Checking that the user has enough funds.
        val b = db.bankAccountGetFromOwnerId(c.expectRowId())
            ?: throw internalServerError("Customer '${c.login}' lacks bank account.")

        throw NotImplementedError()
    }
    get("/accounts/{USERNAME}/withdrawals/{W_ID}") {
        throw NotImplementedError()
    }
    post("/accounts/{USERNAME}/withdrawals/abort") {
        throw NotImplementedError()
    }
    post("/accounts/{USERNAME}/withdrawals/confirm") {
        throw NotImplementedError()
    }
}

