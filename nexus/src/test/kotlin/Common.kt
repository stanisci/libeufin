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

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import kotlinx.coroutines.runBlocking
import tech.libeufin.common.TalerAmount
import tech.libeufin.common.fromFile
import tech.libeufin.common.initializeDatabaseTables
import tech.libeufin.common.resetDatabaseTables
import tech.libeufin.nexus.*
import java.time.Instant
import kotlin.io.path.Path

fun conf(
    conf: String = "test.conf",
    lambda: suspend (EbicsSetupConfig) -> Unit
) {
    val config = NEXUS_CONFIG_SOURCE.fromFile(Path("conf/$conf"))
    val ctx = EbicsSetupConfig(config)
    runBlocking {
        lambda(ctx)
    }
}

fun setup(
    conf: String = "test.conf",
    lambda: suspend (Database, EbicsSetupConfig) -> Unit
) {
    val config = NEXUS_CONFIG_SOURCE.fromFile(Path("conf/$conf"))
    val dbCfg = config.dbConfig()
    val ctx = EbicsSetupConfig(config)
    Database(dbCfg.dbConnStr).use {
        runBlocking {
            it.conn { conn ->
                resetDatabaseTables(conn, dbCfg, "libeufin-nexus")
                initializeDatabaseTables(conn, dbCfg, "libeufin-nexus")
            }
            lambda(it, ctx)
        }
    }
}

val clientKeys = generateNewKeys()

// Gets an HTTP client whose requests are going to be served by 'handler'.
fun getMockedClient(
    handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
): HttpClient {
    return HttpClient(MockEngine) {
        followRedirects = false
        engine {
            addHandler {
                    request -> handler(request)
            }
        }
    }
}

// Generates a payment initiation, given its subject.
fun genInitPay(
    subject: String = "init payment",
    requestUid: String = "unique"
) =
    InitiatedPayment(
        id = -1,
        amount = TalerAmount(44, 0, "KUDOS"),
        creditPaytoUri = "payto://iban/CH9300762011623852957?receiver-name=Test",
        wireTransferSubject = subject,
        initiationTime = Instant.now(),
        requestUid = requestUid
    )

// Generates an incoming payment, given its subject.
fun genInPay(subject: String) =
    IncomingPayment(
        amount = TalerAmount(44, 0, "KUDOS"),
        debitPaytoUri = "payto://iban/not-used",
        wireTransferSubject = subject,
        executionTime = Instant.now(),
        bankId = "entropic"
    )

// Generates an outgoing payment, given its subject and messageId
fun genOutPay(subject: String, messageId: String) =
    OutgoingPayment(
        amount = TalerAmount(44, 0, "KUDOS"),
        creditPaytoUri = "payto://iban/CH9300762011623852957?receiver-name=Test",
        wireTransferSubject = subject,
        executionTime = Instant.now(),
        messageId = messageId
    )