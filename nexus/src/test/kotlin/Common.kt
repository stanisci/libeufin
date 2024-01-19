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
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import tech.libeufin.nexus.*
import tech.libeufin.util.*
import java.security.interfaces.RSAPrivateCrtKey
import java.time.Instant

val j = Json {
    this.serializersModule = SerializersModule {
        contextual(RSAPrivateCrtKey::class) { RSAPrivateCrtKeySerializer }
    }
}

val config: EbicsSetupConfig = run {
    val handle = TalerConfig(NEXUS_CONFIG_SOURCE)
    handle.load()
    EbicsSetupConfig(handle)
}

fun prepDb(cfg: TalerConfig): Database {
    cfg.loadDefaults()
    val dbCfg = DatabaseConfig(
        dbConnStr = "postgresql:///libeufincheck",
        sqlDir = cfg.requirePath("paths", "datadir") + "sql"
    )
    pgDataSource(dbCfg.dbConnStr).pgConnection().use { conn ->
        println("SQL dir for testing: ${dbCfg.sqlDir}")
        try {
            resetDatabaseTables(conn, dbCfg, "libeufin-nexus")
        } catch (e: Exception) {
            logger.warn("Resetting an empty database throws, tolerating this...")
            logger.warn(e.message)
        }
        initializeDatabaseTables(conn, dbCfg, "libeufin-nexus")
    }
   
    return Database(dbCfg.dbConnStr)
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

// Partial config to talk to PostFinance.
fun getPofiConfig(
    userId: String,
    partnerId: String,
    accountOwner: String? = "NotGiven"
    ) = """
    [nexus-ebics]
    CURRENCY = KUDOS
    HOST_BASE_URL = https://isotest.postfinance.ch/ebicsweb/ebicsweb
    HOST_ID = PFEBICS
    USER_ID = $userId
    PARTNER_ID = $partnerId
    SYSTEM_ID = not-used
    IBAN = CH9789144829733648596
    BIC = POFICHBE
    NAME = LibEuFin
    BANK_PUBLIC_KEYS_FILE = /tmp/pofi-testplatform-bank-keys.json
    CLIENT_PRIVATE_KEYS_FILE = /tmp/pofi-testplatform-subscriber-keys.json
    BANK_DIALECT = postfinance
""".trimIndent()

// Generates a payment initiation, given its subject.
fun genInitPay(
    subject: String = "init payment",
    requestUid: String = "unique"
) =
    InitiatedPayment(
        amount = TalerAmount(44, 0, "KUDOS"),
        creditPaytoUri = "payto://iban/TEST-IBAN?receiver-name=Test",
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
        creditPaytoUri = "payto://iban/TEST-IBAN?receiver-name=Test",
        wireTransferSubject = subject,
        executionTime = Instant.now(),
        messageId = messageId
    )