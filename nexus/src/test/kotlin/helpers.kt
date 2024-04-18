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
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import tech.libeufin.common.TalerAmount
import tech.libeufin.common.db.dbInit
import tech.libeufin.common.db.pgDataSource
import tech.libeufin.common.fromFile
import tech.libeufin.nexus.*
import tech.libeufin.nexus.db.Database
import tech.libeufin.nexus.db.InitiatedPayment
import java.time.Instant
import kotlin.io.path.Path

fun conf(
    conf: String = "test.conf",
    lambda: suspend (NexusConfig) -> Unit
) = runBlocking {
    val config = NEXUS_CONFIG_SOURCE.fromFile(Path("conf/$conf"))
    val ctx = NexusConfig(config)
    lambda(ctx) 
}

fun setup(
    conf: String = "test.conf",
    lambda: suspend (Database, NexusConfig) -> Unit
) = runBlocking {
    val config = NEXUS_CONFIG_SOURCE.fromFile(Path("conf/$conf"))
    val dbCfg = config.dbConfig()
    val ctx = NexusConfig(config)
    pgDataSource(dbCfg.dbConnStr).dbInit(dbCfg, "libeufin-nexus", true)
    Database(dbCfg).use {
        lambda(it, ctx)
    }
}

fun serverSetup(
    conf: String = "test.conf",
    lambda: suspend ApplicationTestBuilder.(Database) -> Unit
) = setup { db, cfg ->
    testApplication {
        application {
            nexusApi(db, cfg)
        }
        lambda(db)
    }
}

val grothoffPayto = "payto://iban/CH4189144589712575493?receiver-name=Grothoff%20Hans"

val clientKeys = generateNewKeys()

// Gets an HTTP client whose requests are going to be served by 'handler'.
fun getMockedClient(
    handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
): HttpClient = HttpClient(MockEngine) {
    followRedirects = false
    engine {
        addHandler {
                request -> handler(request)
        }
    }
}

// Generates a payment initiation, given its subject.
fun genInitPay(
    subject: String = "init payment",
    requestUid: String = "unique"
) = InitiatedPayment(
        id = -1,
        amount = TalerAmount(44, 0, "KUDOS"),
        creditPaytoUri = "payto://iban/CH4189144589712575493?receiver-name=Test",
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
        creditPaytoUri = "payto://iban/CH4189144589712575493?receiver-name=Test",
        wireTransferSubject = subject,
        executionTime = Instant.now(),
        messageId = messageId
    )