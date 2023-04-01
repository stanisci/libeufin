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

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import tech.libeufin.nexus.ebics.*
import tech.libeufin.nexus.server.BankConnectionType
import tech.libeufin.nexus.server.FetchSpecJson
import tech.libeufin.nexus.server.XLibeufinBankTransport
import tech.libeufin.nexus.xlibeufinbank.XlibeufinBankConnectionProtocol

// 'const' allows only primitive types.
val bankConnectionRegistry: Map<BankConnectionType, BankConnectionProtocol> = mapOf(
    BankConnectionType.EBICS to EbicsBankConnectionProtocol(),
    BankConnectionType.X_LIBEUFIN_BANK to XlibeufinBankConnectionProtocol()
)

interface BankConnectionProtocol {
    // Initialize the connection.  Usually uploads keys to the bank.
    suspend fun connect(client: HttpClient, connId: String)

    // Downloads the list of bank accounts managed at the
    // bank under one particular connection.
    suspend fun fetchAccounts(client: HttpClient, connId: String)

    // Create a new connection from backup data.
    fun createConnectionFromBackup(connId: String, user: NexusUserEntity, passphrase: String?, backup: JsonNode)

    // Create a new connection from an HTTP request.
    fun createConnection(connId: String, user: NexusUserEntity, data: JsonNode)

    // Merely a formatter of connection details coming from
    // the database.
    fun getConnectionDetails(conn: NexusBankConnectionEntity): JsonNode

    // Returns the backup data.
    fun exportBackup(bankConnectionId: String, passphrase: String): JsonNode

    // Export a printable format of the connection details.  Useful
    // to provide authentication via the traditional mail system.
    fun exportAnalogDetails(conn: NexusBankConnectionEntity): ByteArray

    // Send to the bank a previously prepared payment instruction.
    suspend fun submitPaymentInitiation(httpClient: HttpClient, paymentInitiationId: Long)

    /**
     * Downloads transactions from the bank, according to the specification
     * given in the arguments.
     *
     * This function returns a possibly empty list of exceptions.
     * That helps not to stop fetching if ONE operation fails.  Notably,
     * C52 _and_ C53 may be asked along one invocation of this function,
     * therefore storing the exception on C52 allows the C53 to still
     * take place.  The caller then decides how to handle the exceptions.
     *
     * More on multi requests: C52 and C53, or more generally 'reports'
     * and 'statements' are tried to be downloaded together when the fetch
     * level is set to ALL.
     */
    suspend fun fetchTransactions(
        fetchSpec: FetchSpecJson,
        client: HttpClient,
        bankConnectionId: String,
        accountId: String
    ): List<Exception>?
}

fun getConnectionPlugin(connType: BankConnectionType): BankConnectionProtocol {
    return bankConnectionRegistry[connType] ?: throw NexusError(
        HttpStatusCode.NotFound,
        "Connection type '${connType}' not available"
    )
}

/**
 * Adaptor helper to keep until all the connection type mentions will
 * be passed as BankConnectionType instead of arbitrary easy-to-break
 * string.
 */
fun getConnectionPlugin(connType: String): BankConnectionProtocol {
    return getConnectionPlugin(BankConnectionType.parseBankConnectionType(connType))
}