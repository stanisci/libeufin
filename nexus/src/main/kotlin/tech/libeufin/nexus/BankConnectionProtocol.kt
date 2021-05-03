/*
 * This file is part of LibEuFin.
 * Copyright (C) 2020 Taler Systems S.A.
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

package tech.libeufin.nexus

import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import tech.libeufin.nexus.ebics.*

// 'const' allows only primitive types.
val bankConnectionRegistry: Map<String, BankConnectionProtocol> = mapOf(
    "ebics" to EbicsBankConnectionProtocol()
)

interface BankConnectionProtocol {
    suspend fun connect(client: HttpClient, connId: String)
}

fun getConnectionPlugin(connId: String): BankConnectionProtocol {
    return bankConnectionRegistry.get(connId) ?: throw NexusError(
        HttpStatusCode.NotFound,
        "Connection type '${connId}' not available"
    )
}